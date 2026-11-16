"""평면도 초안(결과6 venueDraft, S15P31A202-298) 테스트. 모델 호출 없음(GMS는 가짜 응답)."""

import copy
import hashlib
import json
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx

import contract
import evaluate
import flow
from floor_plan import RESERVE_SECONDS, FloorPlanError, GmsFloorPlanReader, fetch, render, to_layout, with_grid

HERE = Path(__file__).parent
NOW = datetime(2026, 11, 24, 8, 0, tzinfo=timezone.utc)
PDF, TRUTH = evaluate.standin_pdf()
NAMES = ["A구역 무대 앞", "B구역 객석 뒤쪽"]


def demo(case="floorplan"):
    return json.loads((HERE / "examples" / "demo-pdf" / f"request-{case}.json").read_text(encoding="utf-8"))


class FakeGms:
    GmsError = RuntimeError

    def __init__(self, reply):
        self.reply = reply
        self.messages = None

    def chat_json(self, messages, **kwargs):
        self.messages = messages
        self.kwargs = kwargs
        if isinstance(self.reply, Exception):
            raise self.reply
        return self.reply, "gpt-4o-test", {"total_tokens": 1500}


def truth_reply():
    return {"zones": [{"name": n, **r} for n, r in TRUTH.items()], "notFound": []}


def reader(reply, data=PDF, clock=None):
    return GmsFloorPlanReader(fetcher=lambda req: data, gms=FakeGms(reply), clock=clock or (lambda: NOW))


class RenderTest(unittest.TestCase):
    def test_renders_requested_pdf_page_at_long_side(self):
        image = render(PDF, "application/pdf", 2)
        self.assertEqual(max(image.size), 1600)
        w, h = evaluate.STANDIN_PAGE
        self.assertAlmostEqual(image.width / image.height, w / h, places=2)
        # 2쪽은 평면도(무대 보라색)가 있고 1쪽은 거의 흰 쪽이다
        self.assertLess(min(render(PDF, "application/pdf", 2).convert("L").getdata()), 120)

    def test_missing_page_and_broken_pdf(self):
        with self.assertRaises(FloorPlanError):
            render(PDF, "application/pdf", 3)
        with self.assertRaises(FloorPlanError):
            render(b"not a pdf", "application/pdf", 1)

    def test_image_file_is_page_one_and_downscaled(self):
        data = evaluate.FLOOR_PLAN_PNG.read_bytes()
        self.assertEqual(render(data, "image/png", 1).size, (1600, 1120))
        with self.assertRaises(FloorPlanError):
            render(data, "image/png", 2)

    def test_grid_keeps_size(self):
        image = render(PDF, "application/pdf", 2)
        self.assertEqual(with_grid(image).size, image.size)


class LayoutTest(unittest.TestCase):
    SOURCE = {"documentId": "29800000-0000-4000-8000-000000000401", "pageNumber": 2}

    def test_matches_names_ignoring_spaces_and_clamps(self):
        data = {"zones": [{"name": "A구역  무대앞", "x": 0.6, "y": 0.3, "width": 0.5, "height": 0.1},
                          {"name": "로비", "x": 0.3, "y": 0.1, "width": 0.2, "height": 0.5},
                          {"name": "A구역 무대 앞", "x": 0.1, "y": 0.1, "width": 0.1, "height": 0.1},
                          {"name": "B구역 객석 뒤쪽", "x": 0.6, "y": 0.5, "width": 0.001, "height": 0.1}]}
        layout, missing, dropped = to_layout(data, NAMES, self.SOURCE, (1600, 1131))
        self.assertEqual(layout["zones"], [{"name": "A구역 무대 앞", "x": 0.6, "y": 0.3, "width": 0.4, "height": 0.1}])
        self.assertEqual(missing, ["B구역 객석 뒤쪽"])
        self.assertEqual(dropped, 3)  # 계획에 없는 로비, 같은 구역 두 번째, 너무 좁은 사각형
        self.assertEqual((layout["width"], layout["height"]), (1600, 1131))

    def test_nothing_found_is_none(self):
        self.assertEqual(to_layout({"zones": [], "notFound": NAMES}, NAMES, self.SOURCE, (10, 10))[0], None)
        self.assertEqual(to_layout({"zones": [{"name": "A구역 무대 앞"}]}, NAMES, self.SOURCE, (10, 10))[0], None)


class FetchTest(unittest.TestCase):
    def request(self, **floor):
        raw = demo()
        raw["payload"]["floorPlan"].update(floor)
        return contract.parse_request(raw)

    def client(self, status=200, body=PDF, seen=None):
        def handle(request):
            if seen is not None:
                seen.append(request)
            return httpx.Response(status, content=body)
        return httpx.Client(transport=httpx.MockTransport(handle))

    def test_sends_service_token_and_job_headers(self):
        req, seen = self.request(), []
        prefixes = ["https://service.p.example.test/internal/intake-documents/"]
        self.assertEqual(fetch(req, "tok", prefixes, self.client(seen=seen)), PDF)
        self.assertEqual(seen[0].headers["authorization"], "Bearer tok")
        self.assertEqual(seen[0].headers["x-scenecrew-job-id"], req.jobId)
        self.assertEqual(seen[0].headers["x-scenecrew-job-attempt"], "1")

    def test_rejects_other_url_hash_mismatch_and_http_error(self):
        prefixes = ["https://service.p.example.test/internal/intake-documents/"]
        with self.assertRaisesRegex(FloorPlanError, "주소"):
            fetch(self.request(url="https://evil.example/x"), "tok", prefixes, self.client())
        with self.assertRaisesRegex(FloorPlanError, "sha256"):
            fetch(self.request(), "tok", prefixes, self.client(body=b"other"))
        with self.assertRaisesRegex(FloorPlanError, "404"):
            fetch(self.request(), "tok", prefixes, self.client(status=404))


class FlowVenueTest(unittest.TestCase):
    def run_flow(self, raw, venue_reader=None):
        response = flow.run(raw, NOW, venue_reader=venue_reader)
        self.assertEqual(contract.validate_response(raw, response)[1], [])
        return response

    def test_reads_floor_plan_into_result6(self):
        fake = reader(truth_reply())
        response = self.run_flow(demo(), fake)
        self.assertEqual(response["resultSchemaVersion"], "6")
        layout = response["result"]["venueDraft"]["layout"]
        self.assertEqual(layout["source"], {"documentId": "29800000-0000-4000-8000-000000000401", "pageNumber": 2})
        self.assertEqual([z["name"] for z in layout["zones"]], NAMES)
        self.assertEqual(max(layout["width"], layout["height"]), 1600)
        self.assertEqual(fake.calls, 1)
        # 모델에는 구역 이름과 격자 그림만 보낸다(행사 식별·문서 주소 없음)
        content = fake._gms.messages[1]["content"]
        self.assertEqual(json.loads(content[0]["text"]), {"zoneNames": NAMES})
        self.assertTrue(content[1]["image_url"]["url"].startswith("data:image/png;base64,"))
        self.assertNotIn(response["jobId"], json.dumps(fake._gms.messages, ensure_ascii=False))

    def test_zone_not_found_is_unresolved(self):
        reply = {"zones": [{"name": "A구역 무대 앞", **TRUTH["A구역 무대 앞"]}], "notFound": ["B구역 객석 뒤쪽"]}
        result = self.run_flow(demo(), reader(reply))["result"]
        self.assertEqual(len(result["venueDraft"]["layout"]["zones"]), 1)
        missing = [u for u in result["unresolved"] if u["key"] == "floor-plan-zones"]
        self.assertEqual(missing[0]["refs"], ["29800000-0000-4000-8000-000000000102"])

    def test_failures_keep_plan_ready_with_null_venue(self):
        for name, fake in (("model", reader(RuntimeError("GMS HTTP 500"))),
                           ("nothing", reader({"zones": [], "notFound": NAMES})),
                           ("bad pdf", reader(truth_reply(), data=b"broken"))):
            with self.subTest(name):
                result = self.run_flow(demo(), fake)["result"]
                self.assertEqual(result["status"], "READY")
                self.assertIsNone(result["venueDraft"])
                self.assertIn("floor-plan", [u["key"] for u in result["unresolved"]])

    def test_floor_plan_leaves_time_for_plan_before_deadline(self):
        deadline = contract.parse_request(demo()).deadlineAt
        # 마감 40초 전: 모델 호출은 마감 RESERVE_SECONDS 전까지만 기다린다
        fake = reader(truth_reply(), clock=lambda: deadline - timedelta(seconds=40))
        self.run_flow(demo(), fake)
        self.assertEqual(fake._gms.kwargs["timeout"], 40 - RESERVE_SECONDS)
        # 마감이 RESERVE_SECONDS 안으로 들어왔으면 읽지 않고, 계획은 AI_TIMEOUT이 아니라 READY로 나간다
        late = reader(truth_reply(), clock=lambda: deadline - timedelta(seconds=RESERVE_SECONDS))
        result = self.run_flow(demo(), late)["result"]
        self.assertEqual(result["status"], "READY")
        self.assertIsNone(result["venueDraft"])
        self.assertIsNone(late._gms.messages)
        self.assertIn("마감 임박", next(u for u in result["unresolved"] if u["key"] == "floor-plan")["description"])

    def test_without_floor_plan_reader_venue_is_null(self):
        result = self.run_flow(demo())["result"]
        self.assertIsNone(result["venueDraft"])
        self.assertIn("floor-plan", [u["key"] for u in result["unresolved"]])

    def test_null_floor_plan_is_result5_without_reading(self):
        """평면도 없음·원본 만료로 BE가 floorPlan: null을 보내면 키가 없을 때와 같이 결과5다(성하빈 합의)."""
        fake = reader(truth_reply())
        response = self.run_flow(demo("text"), fake)
        self.assertEqual(response["resultSchemaVersion"], "5")
        self.assertNotIn("venueDraft", response["result"])
        self.assertEqual(fake.calls, 0)
        bad = copy.deepcopy(response)
        bad["result"]["venueDraft"], bad["resultSchemaVersion"] = None, "6"
        self.assertEqual(contract.validate_response(demo("text"), bad)[1][0]["code"], "VENUE_MISMATCH")

    def test_request_without_key_stays_result5(self):
        raw = demo("text")
        del raw["payload"]["floorPlan"]
        response = self.run_flow(raw, reader(truth_reply()))
        self.assertEqual(response["resultSchemaVersion"], "5")
        self.assertNotIn("venueDraft", response["result"])

    def test_needs_info_carries_null_venue(self):
        raw = demo()
        raw["payload"]["event"]["prepStartAt"] = "2026-11-24T08:45:00Z"  # 개장 15분 전: 선검사 질문
        fake = reader(truth_reply())
        response = self.run_flow(raw, fake)
        self.assertEqual(response["result"]["status"], "NEEDS_INFO")
        self.assertIsNone(response["result"]["venueDraft"])
        self.assertEqual(fake.calls, 0)

    def test_reads_once_even_with_staffing_revision(self):
        class Revising(flow.RuleGenerator):
            revises = True
            rounds = 0

            def draft(self, req, feedback=None):
                self.rounds += 1
                return super().draft(req, feedback)

        raw = demo()
        raw["payload"]["event"]["totalCapacity"] = 4  # 규칙 후보 묶음 4개 > 가능 3명 → 보완 회차
        gen, fake = Revising(), reader(truth_reply())
        response = flow.run(raw, NOW, gen, fake)
        self.assertEqual(gen.rounds, 2)
        self.assertEqual(fake.calls, 0)  # 보완 뒤에도 초과라 NEEDS_INFO: 평면도를 읽지 않는다
        self.assertIsNone(response["result"]["venueDraft"])


class ContractVenueTest(unittest.TestCase):
    def setUp(self):
        self.raw = demo()
        self.response = flow.run(self.raw, NOW, venue_reader=reader(truth_reply()))

    def check(self, response, raw=None):
        return contract.validate_response(raw or self.raw, response)

    def test_rejects_zone_name_not_in_result(self):
        bad = copy.deepcopy(self.response)
        bad["result"]["venueDraft"]["layout"]["zones"][0]["name"] = "로비"
        branch, issues = self.check(bad)
        self.assertEqual(branch, "INVALID")
        self.assertEqual(issues[0]["path"], "result.venueDraft.layout.zones[0].name")

    def test_rejects_other_source_and_outside_rect(self):
        bad = copy.deepcopy(self.response)
        bad["result"]["venueDraft"]["layout"]["source"]["pageNumber"] = 1
        self.assertEqual(self.check(bad)[1][0]["path"], "result.venueDraft.layout.source")
        bad = copy.deepcopy(self.response)
        bad["result"]["venueDraft"]["layout"]["zones"][0].update(x=0.9, width=0.2)
        self.assertEqual(self.check(bad)[0], "INVALID")

    def test_version_and_key_must_follow_request(self):
        bad = copy.deepcopy(self.response)
        bad["resultSchemaVersion"] = "5"
        self.assertEqual(self.check(bad)[0], "INVALID")
        raw5 = copy.deepcopy(self.raw)
        del raw5["payload"]["floorPlan"]
        self.assertEqual(self.check(self.response, raw5)[1][0]["code"], "VENUE_MISMATCH")
        bad = copy.deepcopy(self.response)
        del bad["result"]["venueDraft"]
        bad["resultSchemaVersion"] = "5"
        self.assertEqual(self.check(bad)[1][0]["code"], "VENUE_MISMATCH")

    def test_floor_plan_request_shape(self):
        for field, value in (("mime", "image/gif"), ("pageNumber", 0), ("sha256", "x"), ("extra", 1)):
            with self.subTest(field):
                raw = copy.deepcopy(self.raw)
                raw["payload"]["floorPlan"][field] = value
                with self.assertRaises(contract.ContractViolation):
                    contract.parse_request(raw)


class RefineTest(unittest.TestCase):
    def test_recorded_coarse_boxes_snap_to_drawn_zones(self):
        """2026-11-11 GMS 1회차 원본(0.1 격자 근처의 큰 사각형, IoU 0.31·0.22)을 그림의 구역 상자로 보정한다."""
        raw = json.loads((evaluate.DEMO_PDF / "gms-runs" / "1-floorplan-raw.json").read_text(encoding="utf-8"))
        result = flow.run(demo(), NOW, venue_reader=reader(raw["floorPlan"][0]))["result"]
        for name, value in evaluate.venue_metrics(result["venueDraft"], TRUTH)["iou"].items():
            self.assertGreater(value, 0.9, name)

    def test_keeps_model_box_without_matching_region(self):
        from floor_plan import color_regions, refine
        regions = color_regions(render(PDF, "application/pdf", 2))
        far = {"name": "A구역 무대 앞", "x": 0.05, "y": 0.05, "width": 0.1, "height": 0.05}  # 제목 글자 부근
        self.assertIsNone(refine(far, regions))

    def test_shifted_box_snaps_to_nearest_colored_zone(self):
        """2026-11-12 운영 GMS 3회 원본: A는 겹침으로 맞고 B는 실제 상자에서 아래·오른쪽으로 밀려(겹침 0~40%) 보정되지 않았다.
        가까운 색칠 영역으로 B를 맞추고, 복도·로비 같은 회색 면이나 A가 쓴 영역은 고르지 않는다."""
        from floor_plan import color_regions, snap_zones
        pdf = Path(__file__).resolve().parents[2] / "docs" / "output" / "demo-pdf-20261111" /             "씬크루_쇼케이스_라이브_운영계획서_평면도포함_v2.pdf"
        if not pdf.exists():
            self.skipTest("시연 PDF v2(docs/output) 없음")
        regions = color_regions(render(pdf.read_bytes(), "application/pdf", 5))
        a = {"name": "A구역 무대 앞", "x": 0.62, "y": 0.3392, "width": 0.1375, "height": 0.0919}
        for b in ({"name": "B구역 객석 뒤쪽", "x": 0.6, "y": 0.6, "width": 0.2, "height": 0.3},
                  {"name": "B구역 객석 뒤쪽", "x": 0.65, "y": 0.5, "width": 0.2, "height": 0.2},
                  {"name": "B구역 객석 뒤쪽", "x": 0.8, "y": 0.3, "width": 0.15, "height": 0.3}):   # A 오른쪽·세로로 긴 사각형
            with self.subTest(b=b):
                zones, fixed = snap_zones([a, b], regions)
                self.assertEqual(fixed, 2)
                self.assertEqual((zones[0]["x"], zones[0]["y"]), (0.62, 0.3392))
                self.assertAlmostEqual(zones[1]["x"], 0.62, places=2)
                self.assertAlmostEqual(zones[1]["y"], 0.456, places=2)
        far = {"name": "B구역 객석 뒤쪽", "x": 0.02, "y": 0.85, "width": 0.1, "height": 0.1}   # 가까운 색칠 구역 없음
        self.assertEqual(snap_zones([far], regions), ([far], 0))

    def test_nearest_skips_thin_strips_gray_and_taken_regions(self):
        from floor_plan import snap_zones
        rect = {"name": "B", "x": 0.6, "y": 0.6, "width": 0.2, "height": 0.2}
        strip = (0.6, 0.55, 0.4, 0.025, 1.0, 120.0)     # 가장 가깝지만 가늘고 긴 띠(가로세로 비 16배)
        gray = (0.62, 0.5, 0.15, 0.1, 0.9, 10.0)        # 회색 면(복도)
        zone = (0.62, 0.45, 0.14, 0.09, 0.8, 29.0)      # 색칠된 구역 상자
        zones, fixed = snap_zones([rect], [strip, gray, zone])
        self.assertEqual((fixed, zones[0]["y"]), (1, 0.45))
        taken_first = {"name": "A", "x": 0.6, "y": 0.43, "width": 0.18, "height": 0.12}   # 겹침으로 zone을 먼저 차지
        zones, fixed = snap_zones([taken_first, rect], [strip, gray, zone])
        self.assertEqual((fixed, zones[1]), (1, rect))


class DemoFilesTest(unittest.TestCase):
    def test_floor_plan_request_matches_standin_pdf(self):
        self.assertEqual(demo()["payload"]["floorPlan"]["sha256"], hashlib.sha256(PDF).hexdigest())

    def test_rule_outputs_are_current(self):
        for case in evaluate.DEMO_CASES:
            with self.subTest(case):
                saved = json.loads((evaluate.DEMO_PDF / f"{case}-rule.json").read_text(encoding="utf-8"))
                self.assertEqual(flow.run(demo(case), evaluate.demo_now(demo(case))), saved,
                                 "uv run python evaluate.py demo-rule")

    def test_demo_input_hash_follows_be_rule(self):
        """시연 요청의 inputHash는 BE AiJobInputs 규칙(키 재귀 정렬·공백 없는 JSON의 SHA-256)으로 payload에서 나온 값이다."""
        for path in sorted((evaluate.HERE / "examples").glob("demo*/request-*.json")):
            with self.subTest(path.name):
                raw = json.loads(path.read_text(encoding="utf-8"))
                self.assertEqual(raw["inputHash"], evaluate.input_hash(raw["payload"]))

    def test_saved_run_requests_echo_into_responses(self):
        """회차별로 저장한 실제 요청과 응답의 식별 필드가 같다(실행 근거 연결)."""
        runs = evaluate.DEMO_PDF / "gms-runs"
        for path in sorted(runs.glob("*-request.json")):
            with self.subTest(path.name):
                raw = json.loads(path.read_text(encoding="utf-8"))
                res = json.loads((runs / path.name.replace("-request", "")).read_text(encoding="utf-8"))
                self.assertEqual(raw["inputHash"], evaluate.input_hash(raw["payload"]))
                for key in contract.IDENTITY_FIELDS:
                    self.assertEqual(res[key], raw[key], key)

    def test_demo_now_keeps_previous_cases(self):
        for case in ("floorplan", "text"):
            self.assertEqual(evaluate.demo_now(demo(case)), evaluate.DEMO_NOW)


class DesignPdfTest(unittest.TestCase):
    """대표 디자인 PDF v2 평면도 포함판 5쪽(S15P31A202-407)."""

    def setUp(self):
        if not evaluate.DESIGN_PDF.exists():
            self.skipTest("시연 PDF v2(docs/output) 없음")
        self.raw = demo("design")
        self.pdf, self.truth = evaluate.floor_plan_source(self.raw)

    def test_request_points_to_design_pdf_page_5(self):
        fp = self.raw["payload"]["floorPlan"]
        self.assertEqual((fp["pageNumber"], fp["sha256"]), (5, hashlib.sha256(self.pdf).hexdigest()))
        contract.parse_request(self.raw)

    def test_truth_comes_from_embedded_image_position(self):
        """그림 개체 위치(155.2~687.8pt × 98.3~470.9pt, 쪽 842×595pt)에 SVG 구역을 옮긴 값."""
        a, b = self.truth["A구역 무대 앞"], self.truth["B구역 객석 뒤쪽"]
        self.assertAlmostEqual(a["x"], 0.6177, places=3)
        self.assertAlmostEqual(a["y"], 0.3352, places=3)
        self.assertAlmostEqual(b["y"], 0.4515, places=3)
        self.assertAlmostEqual(a["width"], 0.1423, places=3)

    def test_coarse_or_shifted_boxes_refine_but_swapped_names_do_not(self):
        rows = evaluate.run_design_offline()
        for case in ("grid0.1", "pad0.05", "shift0.05"):
            for value in rows[case]["after"].values():
                self.assertGreater(value, 0.9, case)
        self.assertEqual(set(rows["swapped"]["after"].values()), {0.0})   # 이름 뒤바뀜은 보정으로 고치지 못한다

    def test_model_iou_matches_names_ignoring_spaces(self):
        raw = [{"zones": [{"name": "A구역무대 앞", **self.truth["A구역 무대 앞"]}, {"name": "B구역", "x": 0, "y": 0}]}]
        self.assertEqual(evaluate.model_iou(raw, self.truth), {"A구역 무대 앞": 1.0, "B구역 객석 뒤쪽": 0.0})
        self.assertIsNone(evaluate.model_iou(["형식 오류"], self.truth))


class DemoGmsFailureRecordTest(unittest.TestCase):
    """모델 출력이 변환에 실패해도(INVALID_ARTIFACT) 실행 기록에 원본·실패 필드·변환 단계가 남는다(모델 호출 없음)."""

    def test_invalid_artifact_keeps_raw_output_and_failure_stage(self):
        import tempfile
        from unittest import mock

        import gms_generator

        bad = {"workGroups": [], "tasks": [{"tempId": "tmp-task-1", "title": "시각 없음"}], "precedences": [],
               "instructions": [], "assumptions": [], "unresolved": [], "questions": []}

        class FakeGms:
            GmsError = RuntimeError
            TIMEOUT_SECONDS = 0

            @staticmethod
            def chat_json(messages, **kwargs):
                return copy.deepcopy(bad), "fake-model", {"total_tokens": 10}

        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(gms_generator, "_load_gms", return_value=FakeGms):
            out = Path(tmp)
            row, = evaluate.run_demo_gms(["text"], 1, out_dir=out)
            saved = json.loads((out / "1-text-raw.json").read_text(encoding="utf-8"))
            response = json.loads((out / "1-text.json").read_text(encoding="utf-8"))
            request = json.loads((out / "1-text-request.json").read_text(encoding="utf-8"))
            history = json.loads((out / "runs.json").read_text(encoding="utf-8"))
        self.assertEqual(row["branch"], "FAILED")
        self.assertEqual(row["error"]["code"], "AI_REVIEW_FAILED")
        self.assertEqual(row["error"]["details"], {"reason": "INVALID_ARTIFACT", "stage": "PLANNING",
                                                   "unresolvedRefs": ["model.tasks[0].workGroupId"]})
        self.assertEqual(saved["plan"], [bad])                       # 모델 원본 보존
        self.assertEqual(len(saved["modelInputs"]), 1)               # 실제 조건 본문·프롬프트 해시 보존
        self.assertEqual(saved["modelInputs"][0]["conditions"]["event"]["name"], request["payload"]["event"]["name"])
        self.assertEqual(len(saved["modelInputs"][0]["systemPromptSha256"]), 64)
        self.assertIn("변환 실패", row["convertErrors"][0])           # 실패 필드가 예외 요약에 나온다
        self.assertEqual(row["convertErrors"], ["1회차 변환 실패: tasks[0].workGroupId (missing)"])
        self.assertNotIn("시각 없음", json.dumps(response, ensure_ascii=False))   # 실패 응답에 모델 원문이 없다
        self.assertEqual(saved["errors"], row["convertErrors"])
        self.assertEqual(response["inputHash"], request["inputHash"])
        self.assertEqual(history[0]["run"], "1-text")


class PhasesMissingTest(unittest.TestCase):
    def test_reports_zone_and_group_phases_without_tasks(self):
        raw = demo("design")
        req = contract.parse_request(raw)
        a, b = (z.id for z in req.payload.zones)
        sound, fix, safety = (g.id for g in req.payload.workGroups)
        task = lambda zone, group, start, end: {"zoneId": zone, "workGroupId": group,
                                                "startAt": f"2026-11-18T{start}:00Z", "endAt": f"2026-11-18T{end}:00Z"}
        tasks = [task(a, sound, "08:30", "09:00"), task(a, sound, "09:30", "09:35"), task(a, sound, "09:35", "09:50"),
                 task(b, safety, "08:50", "09:30"), task(a, fix, "09:35", "09:50")]
        self.assertEqual(evaluate.phases_missing(req, tasks), [
            "zone:B구역 객석 뒤쪽:teardown", "group:장비 장애 대응:prep", "group:장비 장애 대응:operation",
            "group:관객 안전:teardown"])

    def test_truth_iou_is_one(self):
        venue = flow.run(demo(), NOW, venue_reader=reader(truth_reply()))["result"]["venueDraft"]
        for value in evaluate.venue_metrics(venue, TRUTH)["iou"].values():
            self.assertGreater(value, 0.9)  # 보정이 테두리 안쪽 면으로 맞춘다


if __name__ == "__main__":
    unittest.main()

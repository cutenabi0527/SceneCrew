"""보고 분석 계약·구역 후보·상황 추천·긴 입력 병합·내부 API 테스트.

작은 토크나이저·모델(test_report)과 가짜 분석기로 실행한다. ONNX INT8 묶음(report-v2-onnx-int8)이 있으면
예시 응답을 배포 모델로 다시 만들어 저장된 예시와 같은지와 HTTP 호출을 확인한다. 학습한 묶음(ai/checkpoints/report-v2)이
있으면 rollback용 PyTorch로 같은 예시를 실행해 계약과 라벨·구역 후보를 확인한다.
실행: cd ai/report-api && uv run python -m unittest -v
"""

import asyncio
import copy
import threading
import time
import unittest
import unittest.mock
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
from fastapi.testclient import TestClient

import contract
import server
import situation
import zones
from inference import Analyzer, TorchRunner, merge, run  # ai/report를 import 경로에 넣으므로 아래 두 줄보다 먼저 둔다
import data
from test_report import tiny_model, tiny_tokenizer

HERE = Path(__file__).resolve().parent
EXAMPLES = HERE / "examples"
CHECKPOINT = HERE.parent / "checkpoints" / "report-v2"
ONNX_BUNDLE = HERE.parent / "checkpoints" / "report-v2-onnx-int8"
TOKEN = "test-token"
SUCCESS_PAIRS = 5  # PAIRS 앞쪽의 성공 예시 수
PAIRS = [("request.json", "success.json"), ("request-program.json", "success-program.json"),
         ("request-unclassified.json", "success-unclassified.json"), ("request-long.json", "success-long.json"),
         ("request-equipment.json", "success-equipment.json"),
         ("request.json", "failure-timeout.json"), ("request.json", "failure-unavailable.json"),
         ("request-invalid-reference.json", "failure-invalid-request.json"),
         ("request-malformed.json", "failure-malformed.json")]


def example(name):
    return contract.load_json(EXAMPLES / name)


def analysis(category=("STAFF_GAP", 0.9), state=("OCCURRED", 0.9), entities=()):
    return {"scores": {"category": {"label": category[0], "score": category[1]},
                       "reportedState": {"label": state[0], "score": state[1]}},
            "entities": list(entities)}


def entity(text, sentence, kind):
    start = sentence.index(text)
    return {"type": kind, "start": start, "end": start + len(text), "text": text}


class ContractTest(unittest.TestCase):
    def test_examples_match_contract(self):
        for req, res in PAIRS:
            with self.subTest(res):
                branch, issues = contract.validate_response(example(req), example(res))
                self.assertNotEqual(branch, "INVALID", issues)

    def test_label_lists_match_training(self):
        self.assertEqual(list(contract.CATEGORIES), data.CATEGORIES)
        self.assertEqual(list(contract.STATES), data.STATES)
        self.assertEqual(list(contract.ENTITY_TYPES), data.ENTITY_TYPES)

    def test_scores_below_threshold_become_null(self):
        req = contract.parse_request(example("request.json"))
        res = contract.success(req, analysis(("STAFF_GAP", 0.6499), ("OCCURRED", 0.65)), "m")
        self.assertIsNone(res["result"]["category"])
        self.assertEqual(res["result"]["scores"]["category"], {"label": "STAFF_GAP", "score": 0.6499})
        self.assertEqual(res["result"]["reportedState"], "OCCURRED")

    def test_rejects_value_that_ignores_threshold(self):
        res = example("success.json")
        res["result"]["scores"]["category"]["score"] = 0.5
        branch, issues = contract.validate_response(example("request.json"), res)
        self.assertEqual((branch, issues[0]["code"]), ("INVALID", "THRESHOLD"))
        res = example("success-unclassified.json")
        res["result"]["category"] = "OTHER"
        branch, issues = contract.validate_response(example("request-unclassified.json"), res)
        self.assertEqual((branch, issues[0]["code"]), ("INVALID", "THRESHOLD"))

    def test_rejects_span_mismatch_and_overlap(self):
        res = example("success.json")
        res["result"]["entities"][0]["text"] = "B구역"
        self.assertEqual(contract.validate_response(example("request.json"), res)[1][0]["code"], "SPAN")
        res = example("success.json")
        res["result"]["entities"][1]["start"] = 3
        res["result"]["entities"][1]["text"] = example("request.json")["payload"]["normalizedText"][3:22]
        codes = [i["code"] for i in contract.validate_response(example("request.json"), res)[1]]
        self.assertIn("SPAN", codes)

    def test_rejects_candidates_not_derived_from_entities(self):
        res = example("success.json")
        res["result"]["zoneCandidates"].pop()
        branch, issues = contract.validate_response(example("request.json"), res)
        self.assertEqual((branch, issues[0]["code"]), ("INVALID", "ZONE_CANDIDATES"))

    def test_analysis_validity_is_always_valid(self):
        res = example("success.json")
        res["result"]["analysisValidity"] = "PARTIAL_STALE"
        self.assertEqual(contract.validate_response(example("request.json"), res)[0], "INVALID")

    def test_failure_identity(self):
        res = example("failure-timeout.json")
        res["contextRevision"] = 13
        self.assertEqual(contract.validate_response(example("request.json"), res)[1][0]["code"], "ECHO_MISMATCH")
        res = example("failure-malformed.json")
        res["error"] = example("failure-timeout.json")["error"]
        self.assertEqual(contract.validate_response(example("request.json"), res)[0], "INVALID")  # 접수 뒤 실패는 식별 필수

    def test_valid_request_rejects_invalid_request_failure(self):
        res = example("failure-invalid-request.json")
        for f in contract.IDENTITY_FIELDS:
            res[f] = example("request.json")[f]
        branch, issues = contract.validate_response(example("request.json"), res)
        self.assertEqual((branch, [i["code"] for i in issues]), ("INVALID", ["UNEXPECTED_INVALID_REQUEST"]))

    def test_result_schema_version_is_4_with_situation(self):
        req = contract.parse_request(example("request.json"))
        res = contract.success(req, analysis(), "m")
        self.assertEqual(res["resultSchemaVersion"], "4")
        # '사람이 몰렸는데'가 관객 안전 단서라 연관 상황으로 함께 나온다
        self.assertEqual(res["result"]["situation"], {"primary": "STAFF_GAP", "related": ["AUDIENCE_SAFETY"]})
        old = example("success.json")
        old["resultSchemaVersion"] = "3"
        del old["result"]["situation"]
        self.assertEqual(contract.validate_response(example("request.json"), old)[0], "INVALID")

    def test_unclassified_situation_is_null(self):
        res = example("success-unclassified.json")
        self.assertEqual(res["result"]["situation"], {"primary": None, "related": []})
        self.assertEqual(contract.validate_response(example("request-unclassified.json"), res), ("SUCCEEDED", []))

    def test_rejects_situation_shape(self):
        bad = [{"primary": "UNCLASSIFIED", "related": []},
               {"primary": "STAFF_GAP", "related": ["STAFF_GAP"]},
               {"primary": "STAFF_GAP", "related": ["AUDIENCE_SAFETY", "AUDIENCE_SAFETY"]},
               {"primary": None, "related": ["STAFF_GAP"]},
               {"primary": "STAFF_GAP"}]
        for value in bad:
            with self.subTest(value):
                res = example("success.json")
                res["result"]["situation"] = value
                self.assertEqual(contract.validate_response(example("request.json"), res)[1][0]["code"], "SCHEMA")

    def test_rejects_situation_not_derived_from_text(self):
        res = example("success.json")
        res["result"]["situation"] = {"primary": "EQUIPMENT_ANOMALY", "related": []}
        branch, issues = contract.validate_response(example("request.json"), res)
        self.assertEqual((branch, issues[0]["code"]), ("INVALID", "SITUATION"))

    def test_request_issues(self):
        raw = example("request.json")
        raw["payload"]["normalizedText"] = "B구역  통로"
        raw["payload"]["allowedZones"][1]["name"] = "A 구역"  # 공백만 다른 같은 이름
        raw["payload"]["allowedWorkGroups"][0]["zoneIds"].append("33333333-3333-4333-8333-333333333399")
        with self.assertRaises(contract.ContractViolation) as e:
            contract.parse_request(raw)
        self.assertEqual([i["code"] for i in e.exception.issues], ["NOT_NORMALIZED", "DUPLICATE", "INVALID_REFERENCE"])
        self.assertIsNotNone(e.exception.request)

    def test_long_program_and_work_group_names_are_accepted(self):
        raw = example("request.json")
        long_name = "장비 및 시스템 오류 대응 그룹(무대 우측)" * 3
        raw["payload"]["allowedPrograms"][0]["name"] = long_name
        raw["payload"]["allowedWorkGroups"][0]["name"] = long_name + " 2"
        self.assertEqual(contract.parse_request(raw).payload.allowedPrograms[0].name, long_name)  # 위반이면 예외


class SituationTest(unittest.TestCase):
    def test_category_maps_to_situation(self):
        for category, want in [("DELAY", "SCHEDULE_DELAY"), ("STAFF_GAP", "STAFF_GAP"),
                               ("EQUIPMENT", "EQUIPMENT_ANOMALY"), ("CONGESTION", "AUDIENCE_SAFETY")]:
            self.assertEqual(situation.recommend("확인 부탁드립니다.", category), {"primary": want, "related": []})

    def test_unclassified_is_null_not_code(self):
        for text, category in [("분실물 문의가 들어왔습니다.", None), ("물품이 떨어졌어요.", "SUPPLY"),
                               ("관객 문의가 있습니다.", "OTHER"), ("공연 잘 진행되고 있습니다. 특이사항 없습니다.", "DELAY")]:
            with self.subTest(text):
                self.assertEqual(situation.recommend(text, category), {"primary": None, "related": []})

    def test_negated_cue_does_not_count(self):
        self.assertEqual(situation.recommend("둘째 곡 지연 없습니다.", "DELAY"), {"primary": None, "related": []})
        self.assertEqual(situation.recommend("3번 마이크는 문제 없고 5번 마이크만 안 됩니다.", None),
                         {"primary": "EQUIPMENT_ANOMALY", "related": []})

    def test_negation_covers_the_whole_clause(self):
        # 리뷰에서 실제 ONNX 응답으로 재현한 오분류. category는 그때 모델이 낸 값이다.
        for text, category in [("인력은 부족하지 않습니다.", "STAFF_GAP"), ("지연은 현재 없습니다.", "DELAY"),
                               ("두 업무 모두 문제 없이 진행 중입니다.", None), ("펜스 상태는 정상입니다.", "EQUIPMENT"),
                               ("믹서 문제는 아니고 확인 중입니다.", None)]:
            with self.subTest(text):
                self.assertEqual(situation.recommend(text, category), {"primary": None, "related": []})
        # 다른 절의 부정은 앞 절의 단서를 지우지 않는다
        self.assertEqual(situation.recommend("지연은 없고 인원이 부족합니다.", "DELAY"),
                         {"primary": "STAFF_GAP", "related": []})

    def test_recovery_report_keeps_mapped_situation(self):
        # 복구·조치 뒤의 `정상입니다`는 해결 보고라 해결된 문제의 상황을 낸다(#357 시연 문장).
        for text in ["메인 무선 마이크 전원 다시 켜고 재시험했는데 정상입니다.", "믹서 재부팅하고 나서 정상입니다.",
                     "스피커 케이블 교체했고 지금 정상입니다.", "스피커 교체 완료되어 현재 이상 없습니다.",
                     "믹서 복구 완료 후 현재 정상입니다.", "케이블을 교체한 뒤 정상입니다.",
                     "스피커 교체를 했고 지금 정상입니다.", "케이블 교체 후 현재 정상입니다."]:
            with self.subTest(text):
                self.assertEqual(situation.recommend(text, "EQUIPMENT"),
                                 {"primary": "EQUIPMENT_ANOMALY", "related": []})
        # 복구 표현이 없는 상태 확인은 그대로 null이다
        self.assertEqual(situation.recommend("마이크 상태는 정상입니다.", "EQUIPMENT"), {"primary": None, "related": []})
        # 조치를 부정·예정·의도로만 말한 전체 이상 없음 보고도 null이다
        for text in ["재시험은 내일 할 예정이고 지금은 정상입니다.", "다시 연결할 필요 없고 정상입니다.",
                     "고친 건 없고 이상 없습니다.", "교체하고 싶은데 현재 특이사항 없습니다.",
                     "재시험은 안 했고 지금은 정상입니다.", "다시 연결은 안 했지만 현재 정상입니다.",
                     "재부팅 안 하고 정상입니다.", "케이블은 안 고쳤고 현재 정상입니다.",
                     "교체는 못 했지만 이상 없습니다.", "교체 완료 예정이고 지금은 정상입니다."]:
            with self.subTest(text):
                self.assertEqual(situation.recommend(text, "EQUIPMENT"), {"primary": None, "related": []})

    def test_neutral_cues_need_context(self):
        for text in ["두 업무 모두 진행 중입니다.", "같은 시간에 입장합니다.", "공연 순서대로 먼저 진행합니다.",
                     "펜스 점검했습니다."]:
            with self.subTest(text):
                self.assertEqual(situation.recommend(text, None), {"primary": None, "related": []})
        self.assertEqual(situation.recommend("두 업무가 같은 시간에 배정돼 있어요.", None)["primary"], "TASK_CONFLICT")
        self.assertEqual(situation.recommend("VCR 상영을 먼저 틀어 주세요.", None)["primary"], "PERFORMANCE_ORDER_CHANGE")
        self.assertEqual(situation.recommend("펜스가 밀려 넘어질 것 같습니다.", None),
                         {"primary": "AUDIENCE_SAFETY", "related": []})  # 관객·펜스가 밀리는 것은 지연이 아니다
        self.assertEqual(situation.recommend("둘째 곡이 5분 밀렸습니다.", None)["primary"], "SCHEDULE_DELAY")

    def test_unmapped_situations_take_primary(self):
        self.assertEqual(situation.recommend("음향 담당 한 명이 반입이랑 부스 근무가 겹쳐 있어요.", "STAFF_GAP"),
                         {"primary": "TASK_CONFLICT", "related": []})
        self.assertEqual(situation.recommend("게스트가 늦어서 게스트 무대 순서를 바꿔야 합니다.", "DELAY"),
                         {"primary": "PERFORMANCE_ORDER_CHANGE", "related": ["SCHEDULE_DELAY"]})

    def test_related_follows_text_order_without_primary(self):
        got = situation.recommend("스캐너가 안 돼서 입장 줄이 길어지고 공연 시작도 늦어질 것 같아요.", None)
        self.assertEqual(got, {"primary": "EQUIPMENT_ANOMALY", "related": ["AUDIENCE_SAFETY", "SCHEDULE_DELAY"]})
        # 모델 유형만으로 연관 상황을 더하지 않는다
        self.assertEqual(situation.recommend("무대 감독님 일정이 리허설이랑 같은 시간이에요.", "DELAY"),
                         {"primary": "TASK_CONFLICT", "related": []})

    def test_situation_codes_match_backend_and_eval(self):
        self.assertEqual(len(situation.SITUATIONS), 6)
        java = (HERE.parents[1] / "backend/src/main/java/com/ssafy/scenecrew/issue/analysis/AnalyzeReportKind.java")
        if java.exists():
            text = java.read_text(encoding="utf-8")
            for code in situation.SITUATIONS:
                self.assertIn(f'"{code}"', text)


class ZoneCandidateTest(unittest.TestCase):
    def setUp(self):
        self.payload = contract.parse_request(example("request.json")).payload
        self.ids = {z.name: z.id for z in self.payload.allowedZones}

    def zones_for(self, sentence, *spans):
        ents = [entity(text, sentence, kind) for kind, text in spans]
        return [(c["zoneId"], c["source"]) for c in zones.candidates(self.payload, ents, 12)]

    def test_name_match_ignores_spaces_then_uses_longest_contained_name(self):
        s = "메인무대와 B 체험존 입구 쪽"
        self.assertEqual(self.zones_for(s, ("ZONE", "메인무대"), ("ZONE", "B 체험존 입구")),
                         [(self.ids["메인 무대"], "ZONE"), (self.ids["B 체험존"], "ZONE")])

    def test_unmatched_and_undecided_zone_make_no_candidate(self):
        s = "통로 경품 추첨"
        self.assertEqual(self.zones_for(s, ("ZONE", "통로"), ("PROGRAM", "경품 추첨")), [])

    def test_priority_zone_program_role_and_dedupe(self):
        s = "체험 진행 인력이 체험 프로그램 하는 A구역에 없습니다"
        got = self.zones_for(s, ("ROLE", "체험 진행"), ("PROGRAM", "체험 프로그램"), ("ZONE", "A구역"))
        self.assertEqual(got, [(self.ids["A구역"], "ZONE"), (self.ids["B 체험존"], "PROGRAM")])

    def test_role_maps_to_all_work_group_zones(self):
        s = "동선 안내 인력 부족"
        self.assertEqual(self.zones_for(s, ("ROLE", "동선 안내")),
                         [(self.ids[n], "ROLE") for n in ("A구역", "B구역", "C구역")])

    def test_same_program_name_keeps_both_zones(self):
        raw = example("request.json")
        raw["payload"]["allowedPrograms"].append(
            {"id": "44444444-4444-4444-8444-444444444405", "name": "개막식", "zoneId": self.ids["입구"]})
        self.payload = contract.parse_request(raw).payload
        self.assertEqual(self.zones_for("개막식 지연", ("PROGRAM", "개막식")),
                         [(self.ids["메인 무대"], "PROGRAM"), (self.ids["입구"], "PROGRAM")])


class MergeTest(unittest.TestCase):
    TEXT = "가나다라마"

    def window(self, chars, tags, category):
        offsets = [(0, 0)] + [(i, i + 1) for i in chars] + [(0, 0)]
        return {"offsets": offsets, "content": list(range(1, len(chars) + 1)), "category": category,
                "state": [1, 0, 0, 0], "tags": [-100] + tags + [-100]}

    def test_overlap_uses_window_farther_from_edge_and_weighted_average(self):
        b, i, o = (data.ENTITY_TAGS.index(t) for t in ("B-ITEM", "I-ITEM", "O"))
        w1 = self.window([0, 1, 2, 3], [o, o, b, o], [1, 0, 0, 0, 0, 0])  # 2는 w1 안쪽, 3은 w1 끝
        w2 = self.window([2, 3, 4], [o, b, i], [0, 1, 0, 0, 0, 0])  # 2는 w2 끝, 3은 w2 안쪽
        out = merge(self.TEXT, [w1, w2])
        self.assertEqual(out["entities"], [{"type": "ITEM", "start": 2, "end": 3, "text": "다"},
                                           {"type": "ITEM", "start": 3, "end": 5, "text": "라마"}])
        self.assertEqual(out["scores"]["category"], {"label": "EQUIPMENT", "score": 0.5714})

    def test_scores_are_floored_to_keep_threshold(self):
        req = contract.parse_request(example("request.json"))
        for prob, want in ((0.64996, None), (0.65, "EQUIPMENT"), (0.65004, "EQUIPMENT")):
            with self.subTest(prob):
                out = merge(self.TEXT, [self.window([0], [data.ENTITY_TAGS.index("O")], [prob, 1 - prob, 0, 0, 0, 0])])
                res = contract.success(req, out, "m")
                self.assertEqual(res["result"]["category"], want)
                self.assertEqual(res["result"]["scores"]["category"]["score"], 0.6499 if want is None else 0.65)
                self.assertEqual(contract.validate_response(example("request.json"), res)[0], "SUCCEEDED")

    def test_long_input_is_split_with_overlap(self):
        text = contract.normalize("B 체험존 프로젝터가 꺼졌습니다. " * 6)
        tok = tiny_tokenizer([text])
        analyzer = Analyzer(TorchRunner(tiny_model(tok)), tok, "tiny", max_length=24, stride=4)
        windows = analyzer.windows(text)
        self.assertGreater(len(windows), 1)
        for a, b in zip(windows, windows[1:]):
            tail = [a["offsets"][k] for k in a["content"]][-4:]
            self.assertEqual(tail, [b["offsets"][k] for k in b["content"]][:4])
        covered = {w["offsets"][k] for w in windows for k in w["content"]}
        self.assertEqual(covered, {o for o in tok(text, return_offsets_mapping=True)["offset_mapping"] if o[0] != o[1]})
        raw = example("request.json")
        raw["payload"]["normalizedText"] = text
        self.assertEqual(contract.validate_response(raw, run(analyzer, raw))[0], "SUCCEEDED")


class FakeAnalyzer:
    model_version = "fake"

    def __init__(self, delay=0, error=None):
        self.delay, self.error, self.calls = delay, error, 0

    def analyze(self, text):
        self.calls += 1
        time.sleep(self.delay)
        if self.error:
            raise self.error
        return analysis(entities=[entity("B구역 통로", text, "ZONE")] if "B구역 통로" in text else [])


class ServerTest(unittest.TestCase):
    def client(self, analyzer, timeout=15, model_dir="missing"):
        settings = server.Settings(service_token=TOKEN, model_dir=model_dir, timeout_sec=timeout)
        c = TestClient(server.create_app(settings, analyzer))
        c.headers["Authorization"] = f"Bearer {TOKEN}"
        return c

    def request(self, deadline_sec=15):
        raw = example("request.json")
        raw["deadlineAt"] = (datetime.now(timezone.utc) + timedelta(seconds=deadline_sec)).isoformat()
        return raw

    def post(self, c, raw):
        res = c.post("/internal/analyze-report", json=raw)
        self.assertEqual(contract.validate_response(raw, res.json())[0] != "INVALID", True, res.json())
        return res

    def test_success(self):
        with self.client(FakeAnalyzer()) as c:
            res = self.post(c, self.request())
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["result"]["zoneCandidates"][0]["source"], "ZONE")

    def test_requires_service_token(self):
        with self.client(FakeAnalyzer()) as c:
            res = c.post("/internal/analyze-report", json=self.request(), headers={"Authorization": "Bearer x"})
        self.assertEqual(res.status_code, 401)

    def test_invalid_requests(self):
        with self.client(FakeAnalyzer()) as c:
            res = c.post("/internal/analyze-report", content=b"{", headers={"Content-Type": "application/json"})
            self.assertEqual((res.status_code, res.json()["jobId"]), (400, None))
            raw = self.request()
            raw["payload"]["allowedPrograms"][0]["zoneId"] = "33333333-3333-4333-8333-333333333399"
            res = self.post(c, raw)
        self.assertEqual((res.status_code, res.json()["jobId"]), (400, raw["jobId"]))

    def test_deadline_passed(self):
        with self.client(FakeAnalyzer()) as c:
            res = self.post(c, self.request(deadline_sec=-1))
        self.assertEqual((res.status_code, res.json()["error"]["code"]), (504, "AI_TIMEOUT"))

    def test_model_not_loaded(self):
        with self.assertLogs("server", "ERROR"), self.client(None) as c:
            self.assertEqual(c.get("/health").json()["status"], "unavailable")
            res = self.post(c, self.request())
        self.assertEqual((res.status_code, res.json()["error"]["details"]["reason"]), (503, "MODEL_NOT_LOADED"))
        self.assertTrue(res.json()["error"]["retryable"])

    def test_inference_error(self):
        with self.assertLogs("server", "ERROR"), self.client(FakeAnalyzer(error=RuntimeError("boom"))) as c:
            res = self.post(c, self.request())
        self.assertEqual((res.status_code, res.json()["error"]["details"]["reason"]), (503, "INFERENCE_ERROR"))

    def test_timeout_keeps_slot_until_inference_ends(self):
        slow = FakeAnalyzer(delay=0.5)
        with self.client(slow, timeout=0.2) as c:
            res = self.post(c, self.request())
            self.assertEqual(res.status_code, 504)
            res = self.post(c, self.request())  # 앞 추론이 끝날 때까지 기다리다 마감
            self.assertEqual(res.status_code, 504)
            self.assertEqual(slow.calls, 1)
            time.sleep(0.4)
            slow.delay = 0
            self.assertEqual(self.post(c, self.request()).status_code, 200)

    def test_cancelled_request_returns_slot(self):
        slow = FakeAnalyzer(delay=0.3)
        app = server.create_app(server.Settings(service_token=TOKEN, model_dir="missing", timeout_sec=15), slow)

        async def scenario():
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(transport=transport, base_url="http://test",
                                         headers={"Authorization": f"Bearer {TOKEN}"}) as c:
                with self.assertRaises(TimeoutError):  # 추론 대기 중 요청 취소
                    await asyncio.wait_for(c.post("/internal/analyze-report", json=self.request()), 0.1)
                await asyncio.sleep(0.4)
                slow.delay = 0
                return await c.post("/internal/analyze-report", json=self.request(deadline_sec=2))

        self.assertEqual(asyncio.run(scenario()).status_code, 200)
        self.assertEqual(slow.calls, 2)

    def test_one_inference_at_a_time(self):
        class Probe(FakeAnalyzer):
            active = peak = 0
            guard = threading.Lock()

            def analyze(self, text):
                with self.guard:
                    Probe.active += 1
                    Probe.peak = max(Probe.peak, Probe.active)
                time.sleep(0.05)
                with self.guard:
                    Probe.active -= 1
                return super().analyze(text)

        with self.client(Probe()) as c:
            threads = [threading.Thread(target=self.post, args=(c, self.request())) for _ in range(4)]
            for t in threads:
                t.start()
            for t in threads:
                t.join()
        self.assertEqual(Probe.peak, 1)


class SettingsTest(unittest.TestCase):
    def from_env(self, **env):
        with unittest.mock.patch.dict("os.environ", {"AI_SERVICE_TOKEN": TOKEN, **env}, clear=True):
            return server.Settings.from_env()

    def test_runtime_selects_default_bundle(self):
        onnx_settings, torch_settings = self.from_env(), self.from_env(REPORT_RUNTIME="torch")
        self.assertEqual((onnx_settings.runtime, Path(onnx_settings.model_dir).name), ("onnx", "report-v2-onnx-int8"))
        self.assertEqual((torch_settings.runtime, Path(torch_settings.model_dir).name), ("torch", "report-v2"))
        self.assertEqual(self.from_env(REPORT_RUNTIME="torch", REPORT_MODEL_DIR="x").model_dir, "x")
        self.assertEqual(Path(server.Settings(service_token=TOKEN, runtime="torch").model_dir).name, "report-v2")
        with self.assertRaises(RuntimeError):
            self.from_env(REPORT_RUNTIME="tensorrt")

    def test_unknown_runtime_is_rejected_by_loader(self):
        with self.assertRaises(ValueError):
            Analyzer.load(HERE, runtime="tensorrt")


@unittest.skipUnless((CHECKPOINT / "model.safetensors").exists(), "학습한 묶음(report-v2)이 없음")
class RealModelTest(unittest.TestCase):
    """rollback용 PyTorch 응답이 계약을 지키고 ONNX 예시와 라벨(미분류 포함)·구역 후보가 같은지 확인한다.

    점수는 양자화로 달라진다(2026-10-28 기준 최대 0.06). 개체는 긴 예시만 43개 중 41개가 같아 90% 이상을 요구한다.
    """

    @classmethod
    def setUpClass(cls):
        cls.analyzer = Analyzer.load(CHECKPOINT, runtime="torch")

    def test_examples_keep_labels_and_contract(self):
        self.assertEqual(self.analyzer.model_version, "report-v2")
        for req, res in PAIRS[:SUCCESS_PAIRS]:
            with self.subTest(res):
                got, want = run(self.analyzer, example(req)), example(res)
                self.assertEqual(contract.validate_response(example(req), got), ("SUCCEEDED", []))
                self.assertEqual(got["modelVersion"], "report-v2")
                g, w = got["result"], want["result"]
                for field in ("category", "reportedState"):
                    self.assertEqual(g[field], w[field])
                    # 0.65 미만(null)이면 점수 라벨은 판정에 쓰이지 않아 양자화로 바뀔 수 있다(report-v2 긴 예시 상태)
                    if w[field] is not None:
                        self.assertEqual(g["scores"][field]["label"], w["scores"][field]["label"])
                self.assertEqual(g["zoneCandidates"], w["zoneCandidates"])
                self.assertEqual(g["situation"], w["situation"])
                spans = lambda r: {(e["type"], e["start"], e["end"]) for e in r["entities"]}  # noqa: E731
                self.assertGreaterEqual(len(spans(g) & spans(w)) / len(spans(w)), 0.9)

    def test_split_windows_agree_with_single_window(self):
        text = example("request-long.json")["payload"]["normalizedText"]
        whole = self.analyzer.analyze(text)
        split = copy.copy(self.analyzer)
        split.max_length = 128
        self.assertGreater(len(split.windows(text)), 3)
        parts = split.analyze(text)
        same = {(e["type"], e["start"], e["end"]) for e in whole["entities"]} & \
               {(e["type"], e["start"], e["end"]) for e in parts["entities"]}
        # 관계없는 문장을 이은 980자 예시의 개체 일치율은 모델마다 0.88~0.93이다
        # (report-v1 0.930·report-v1-onnx-int8 0.881·report-v2 0.881·report-v2-onnx-int8 0.902, #357)
        self.assertGreaterEqual(len(same) / len(whole["entities"]), 0.85)


@unittest.skipUnless((ONNX_BUNDLE / "model.onnx").exists(), "ONNX INT8 묶음(report-v2-onnx-int8)이 없음")
class OnnxModelTest(unittest.TestCase):
    """배포 ONNX INT8 추론 응답이 저장된 연동 예시와 같은지 확인한다(점수는 CPU 차이로 1e-3까지 허용)."""

    @classmethod
    def setUpClass(cls):
        cls.analyzer = Analyzer.load(ONNX_BUNDLE, runtime="onnx")

    def test_examples_reproduce(self):
        self.assertEqual(self.analyzer.model_version, "report-v2-onnx-int8")
        for req, res in PAIRS[:SUCCESS_PAIRS]:
            with self.subTest(res):
                got, want = run(self.analyzer, example(req)), example(res)
                self.assertEqual(contract.validate_response(example(req), got), ("SUCCEEDED", []))
                for field in ("category", "reportedState"):
                    self.assertAlmostEqual(got["result"]["scores"][field].pop("score"),
                                           want["result"]["scores"][field].pop("score"), delta=1e-3)
                self.assertEqual(got, want)

    def test_split_windows_run_in_one_batch(self):
        raw = example("request-long.json")
        split = copy.copy(self.analyzer)
        split.max_length = 128
        self.assertGreater(len(split.windows(raw["payload"]["normalizedText"])), 3)
        self.assertEqual(contract.validate_response(raw, run(split, raw))[0], "SUCCEEDED")

    def test_http_analyze_report(self):
        settings = server.Settings(service_token=TOKEN, runtime="onnx", model_dir=str(ONNX_BUNDLE))
        with TestClient(server.create_app(settings)) as c:
            self.assertEqual(c.get("/health").json(), {"status": "ok", "modelVersion": "report-v2-onnx-int8"})
            raw = example("request.json")
            raw["deadlineAt"] = (datetime.now(timezone.utc) + timedelta(seconds=15)).isoformat()
            res = c.post("/internal/analyze-report", json=raw, headers={"Authorization": f"Bearer {TOKEN}"})
        self.assertEqual(res.status_code, 200)
        self.assertEqual(contract.validate_response(raw, res.json()), ("SUCCEEDED", []))
        self.assertEqual(res.json()["modelVersion"], "report-v2-onnx-int8")


if __name__ == "__main__":
    unittest.main()

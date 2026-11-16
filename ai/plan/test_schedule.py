"""문서 일정표 입력 어댑터 시험(S15P31A202-408). 모델 호출 없이 가짜 GMS 응답으로 원문 시각 보존을 확인한다."""

import copy
import json
import re
import unittest
from datetime import datetime
from pathlib import Path
from unittest import mock

import contract
import evaluate
import flow
import gms_generator
import schedule

HERE = Path(__file__).parent
DEMO = HERE / "examples" / "demo-pdf"
FIXTURE = DEMO / "schedule-p7.json"
SOUND = "40700000-0000-4000-8000-000000000301"
ZONE_A = "40700000-0000-4000-8000-000000000101"
# 대표 PDF 7쪽 음향 장비 운영 일정(UTC). 사운드 체크(08:50~09:10Z = 17:50~18:10 KST)는 개장(09:00Z)을 넘는다.
P7_UTC = {
    "p7-1": ("2026-11-18T08:30:00Z", "2026-11-18T08:50:00Z"),
    "p7-2": ("2026-11-18T08:50:00Z", "2026-11-18T09:10:00Z"),
    "p7-3": ("2026-11-18T09:20:00Z", "2026-11-18T09:30:00Z"),
    "p7-4": ("2026-11-18T09:30:00Z", "2026-11-18T09:35:00Z"),
    "p7-5": ("2026-11-18T09:35:00Z", "2026-11-18T09:50:00Z"),
}


def design_request():
    raw = json.loads((DEMO / "request-design.json").read_text(encoding="utf-8"))
    raw["payload"].pop("floorPlan")  # 평면도 읽기와 분리해 결과5로 본다(평면도는 이 시험 범위가 아니다)
    return raw


def run11_plan():
    """2026-11-12 실제 GMS 11회차 계획 원본. 일정을 17:30~18:00 한 업무로 줄이고 사운드 체크를 두지 않았다."""
    raw = json.loads((DEMO / "gms-runs" / "11-design-raw.json").read_text(encoding="utf-8"))
    return copy.deepcopy(raw["plan"][0])


class FakeGms:
    class GmsError(RuntimeError):
        pass

    TIMEOUT_SECONDS = 120

    def __init__(self, reply):
        self.reply = reply
        self.sent = []

    def chat_json(self, messages, **kwargs):
        self.sent.append(messages)
        return copy.deepcopy(self.reply), "gpt-test", {"total_tokens": 1}


def run(reply, items, raw=None):
    raw = raw or design_request()
    raw["deadlineAt"] = "2099-01-01T00:00:00Z"
    fake = FakeGms(reply)
    gen = gms_generator.GmsGenerator(schedule=items)
    with mock.patch.object(gms_generator, "_load_gms", return_value=fake):
        out = flow.run(raw, datetime.fromisoformat("2026-11-18T08:00:00+00:00"), gen)
    return raw, out, fake, gen


def times(out):
    return {(t["startAt"], t["endAt"]): t for t in out["result"]["tasks"]}


class FixtureTest(unittest.TestCase):
    def test_fixture_is_page_7_sound_schedule(self):
        items = schedule.load(FIXTURE)
        self.assertEqual([i.key for i in items], list(P7_UTC))
        for i in items:
            self.assertEqual((schedule._z(i.startAt), schedule._z(i.endAt)), P7_UTC[i.key])
            self.assertEqual(i.timeText, f"{i.startAt:%H:%M}~{i.endAt:%H:%M}")   # 원문 표기와 KST 시각이 같다
            self.assertEqual((i.source.pageNumber, i.workGroupName, i.bundle), (7, "음향 장비 운영", "묶음 1"))
        valid, unclear = schedule.review(items, contract.parse_request(design_request()))
        self.assertEqual((len(valid), unclear), (5, []))

    def test_fixture_matches_pdf_text(self):
        if not evaluate.DESIGN_PDF.exists():
            self.skipTest("시연 PDF v2(docs/output) 없음")
        import pypdfium2 as pdfium
        text = pdfium.PdfDocument(str(evaluate.DESIGN_PDF))[6].get_textpage().get_text_range()
        flat = re.sub(r"\s+", "", text)
        for i in schedule.load(FIXTURE):
            self.assertIn(i.timeText, text)
            self.assertIn(re.sub(r"\s+", "", i.title), flat)

    def test_duplicate_keys_rejected(self):
        raw = json.loads(FIXTURE.read_text(encoding="utf-8"))["scheduleItems"]
        with self.assertRaises(ValueError):
            schedule.parse(raw + raw[:1])


class ModelInputTest(unittest.TestCase):
    def test_schedule_items_sent_in_request_time_format(self):
        _, _, fake, gen = run(run11_plan(), schedule.load(FIXTURE))
        system, user = fake.sent[0][0]["content"], json.loads(fake.sent[0][1]["content"])
        self.assertIn("scheduleKey", system)
        self.assertEqual({i["key"]: (i["startAt"], i["endAt"]) for i in user["scheduleItems"]}, P7_UTC)
        self.assertNotIn("source", user["scheduleItems"][0])
        self.assertEqual(gen.model_inputs[0]["conditions"], user)
        self.assertEqual(len(gen.model_inputs[0]["systemPromptSha256"]), 64)
        self.assertEqual(len(gen.model_inputs[0]["userContentSha256"]), 64)

    def test_without_schedule_prompt_and_input_unchanged(self):
        req = contract.parse_request(design_request())
        self.assertEqual(gms_generator.system_prompt([]), gms_generator.SYSTEM)
        self.assertNotIn("scheduleItems", gms_generator.conditions(req))


class PreserveTest(unittest.TestCase):
    def test_wrong_schedule_key_does_not_claim_other_task(self):
        """다른 직무·제목의 업무가 scheduleKey를 잘못 붙여도 일정 반영 근거로 인정하지 않는다."""
        plan = run11_plan()
        wrong = plan["tasks"][1]   # 장비 장애 대응 업무
        wrong["scheduleKey"] = "p7-1"
        raw, out, _, gen = run(plan, schedule.load(FIXTURE))
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        evidence = out["result"]["coordination"]["evidenceRefs"]
        self.assertIn("schedule:p7-1>task:tmp-sched-p7-1", evidence)
        self.assertNotIn(f"schedule:p7-1>task:{wrong['tempId']}", evidence)
        task = next(t for t in out["result"]["tasks"] if t["tempId"] == "tmp-sched-p7-1")
        item = schedule.load(FIXTURE)[0]
        self.assertEqual((task["title"], task["workGroupId"], task["zoneId"]),
                         (item.title, SOUND, ZONE_A))
        self.assertIn("p7-1", gen.schedule_results[0]["added"])
        self.assertEqual(schedule.kept(schedule.load(FIXTURE), contract.parse_request(raw), out["result"]["tasks"]),
                         {"kept": 5, "total": 5, "missing": []})

    def test_model_changed_times_are_restored(self):
        """모델이 scheduleKey를 붙이고도 사운드 체크를 개장 시각에서 자르면 원문 시각으로 되돌린다."""
        plan = run11_plan()
        plan["tasks"] = [t for t in plan["tasks"] if t["workGroupId"] != SOUND]
        fixture_items = {i.key: i for i in schedule.load(FIXTURE)}
        for key, (start, end) in P7_UTC.items():
            plan["tasks"].append({"tempId": f"tmp-task-{key}", "workGroupId": SOUND, "bundleKey": "sound-1",
                                  "zoneId": ZONE_A, "programId": None, "title": fixture_items[key].title,
                                  "startAt": start, "endAt": end,
                                  "steps": ["원문 절차"], "checklist": [], "conditionRefs": [], "scheduleKey": key})
        plan["tasks"][-4]["endAt"] = "2026-11-18T09:00:00Z"   # p7-2 사운드 체크를 18:00에서 자름
        plan["precedences"] = []
        raw, out, _, gen = run(plan, schedule.load(FIXTURE))
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        by_time = times(out)
        for key, span in P7_UTC.items():
            self.assertEqual(by_time[span]["tempId"], f"tmp-task-{key}")
        self.assertEqual(gen.schedule_results[0]["restored"], ["p7-2"])
        self.assertIn("schedule:p7-2>task:tmp-task-p7-2", out["result"]["coordination"]["evidenceRefs"])
        self.assertNotIn("scheduleKey", json.dumps(out))

    def test_model_ignoring_schedule_gets_source_tasks(self):
        """실제 11회차 출력(일정표 없음)에 일정 규칙을 적용하면 7쪽 5건이 원문 시각으로 들어가고 겹치는 모델 업무는 빠진다.

        모델의 '음향 장비 설치 및 점검'(17:30~18:00)과 '음향 장비 철수'(18:35~18:50)는 제목이 일정 항목과 달라 반영으로
        인정하지 않고, 같은 담당 묶음에서 일정 항목과 겹쳐 둘 다 빠진다.
        """
        raw, out, _, gen = run(run11_plan(), schedule.load(FIXTURE))
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        result = out["result"]
        req = contract.parse_request(raw)
        self.assertEqual(schedule.kept(schedule.load(FIXTURE), req, result["tasks"]),
                         {"kept": 5, "total": 5, "missing": []})
        sound = sorted((t["startAt"], t["endAt"], t["title"]) for t in result["tasks"] if t["workGroupId"] == SOUND)
        self.assertEqual([s[:2] for s in sound], list(P7_UTC.values()))
        self.assertIn(("2026-11-18T08:50:00Z", "2026-11-18T09:10:00Z", "사운드 체크와 리허설"), sound)
        self.assertEqual(gen.schedule_results[0]["replaced"], ["tmp-task-1", "tmp-task-7"])
        self.assertEqual(gen.schedule_results[0]["added"], list(P7_UTC))
        self.assertTrue(any(a["key"].startswith("schedule-replaced") for a in result["assumptions"]))
        self.assertLessEqual(len(result["bundles"]), 4)
        self.assertEqual(len({t["bundleKey"] for t in result["tasks"] if t["workGroupId"] == SOUND}), 1)   # 한 사람

    def test_kept_metric_on_saved_run_without_schedule(self):
        """11회차는 철수 시각만 같고 제목이 달라 이름·직무·구역·시각을 모두 보존한 항목은 없다."""
        raw = json.loads((DEMO / "gms-runs" / "11-design-request.json").read_text(encoding="utf-8"))
        res = json.loads((DEMO / "gms-runs" / "11-design.json").read_text(encoding="utf-8"))
        kept = schedule.kept(schedule.load(FIXTURE), contract.parse_request(raw), res["result"]["tasks"])
        self.assertEqual(kept, {"kept": 0, "total": 5, "missing": list(P7_UTC)})

    def test_schedule_split_across_model_bundles_stays_one_person(self):
        """모델이 한 사람의 일정을 단계별 bundleKey로 나눠도 연결을 유지하고 한 묶음으로 모은다(중복 추가 없음, READY)."""
        plan = run11_plan()
        plan["tasks"] = [t for t in plan["tasks"] if t["workGroupId"] != SOUND]
        plan["precedences"] = []
        for n, item in enumerate(schedule.load(FIXTURE)):
            start, end = P7_UTC[item.key]
            plan["tasks"].append({"tempId": f"tmp-task-{item.key}", "workGroupId": SOUND,
                                  "bundleKey": "sound-1" if n < 2 else "sound-2", "zoneId": ZONE_A, "programId": None,
                                  "title": item.title, "startAt": start, "endAt": end, "steps": ["원문 절차"],
                                  "checklist": [], "conditionRefs": [], "scheduleKey": item.key})
        raw, out, fake, gen = run(plan, schedule.load(FIXTURE))
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        self.assertEqual(len(fake.sent), 1)   # 묶음 보완 호출 없음
        self.assertEqual(gen.schedule_results[0]["added"], [])
        sound = [t for t in out["result"]["tasks"] if t["workGroupId"] == SOUND]
        self.assertEqual(len(sound), 5)
        self.assertEqual({t["bundleKey"] for t in sound}, {"sound-1"})

    def test_added_task_id_avoids_model_temp_ids(self):
        """모델이 이미 tmp-sched-<key>와 첫 접미사 후보를 다른 업무에 썼어도 추가 업무는 겹치지 않는 tempId를 받아 READY다."""
        plan = run11_plan()
        renamed = {}
        others = [t for t in plan["tasks"] if t["workGroupId"] != SOUND][:2]
        for task, new in zip(others, ("tmp-sched-p7-1", "tmp-sched-p7-1-2")):
            renamed[task["tempId"]] = new
            task["tempId"] = new
        plan["precedences"] = [[renamed.get(a, a), renamed.get(b, b)] for a, b in plan["precedences"]]
        raw, out, _, gen = run(plan, schedule.load(FIXTURE))
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        ids = [t["tempId"] for t in out["result"]["tasks"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue({"tmp-sched-p7-1", "tmp-sched-p7-1-2", "tmp-sched-p7-1-3"} <= set(ids))
        self.assertIn("schedule:p7-1>task:tmp-sched-p7-1-3", out["result"]["coordination"]["evidenceRefs"])

    def test_other_bundle_overlap_is_not_removed(self):
        """같은 직무라도 다른 담당 묶음의 동시 업무는 일정표 중복으로 추측해 삭제하지 않는다."""
        plan = run11_plan()
        other = copy.deepcopy(plan["tasks"][0])
        other.update(tempId="tmp-task-other-bundle", bundleKey="sound-2", title="별도 송출 로그 기록",
                     startAt=P7_UTC["p7-1"][0], endAt=P7_UTC["p7-1"][1])
        plan["tasks"].append(other)
        draft = gms_generator.to_draft(plan, contract.parse_request(design_request()), schedule.load(FIXTURE))
        self.assertIn("tmp-task-other-bundle", {t["tempId"] for t in draft.tasks})
        self.assertEqual(draft.scheduleSummary["replaced"], [])


class UnclearTest(unittest.TestCase):
    def items(self):
        raw = json.loads(FIXTURE.read_text(encoding="utf-8"))["scheduleItems"]
        raw[2].update(startAt=None, endAt=None, timeText="미정")            # p7-3 시각 불명
        raw[3]["startAt"] = "2026-11-18T18:28:00+09:00"                      # p7-4를 18:28~18:35로
        raw[4]["startAt"] = "2026-11-18T18:33:00+09:00"                      # p7-5가 p7-4(18:28~18:35)와 겹침
        raw.append({**raw[0], "key": "p7-x", "title": "행사 뒤 반납 확인", "startAt": "2026-11-18T19:00:00+09:00",
                    "endAt": "2026-11-18T19:10:00+09:00", "timeText": "19:00~19:10"})   # 철수 완료(18:50) 밖
        raw.append({**raw[0], "key": "p7-y", "title": "거꾸로 적힌 행", "startAt": "2026-11-18T18:15:00+09:00",
                    "endAt": "2026-11-18T18:12:00+09:00", "timeText": "18:15~18:12", "bundle": "묶음 2"})
        return schedule.parse(raw)

    def test_unclear_and_conflicting_items_become_unresolved_without_times(self):
        items = self.items()
        raw, out, fake, _ = run(run11_plan(), items)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        sent = {i["key"] for i in json.loads(fake.sent[0][1]["content"])["scheduleItems"]}
        self.assertEqual(sent, {"p7-1", "p7-2"})   # 확정 항목만 모델에 간다
        keys = {u["key"] for u in out["result"]["unresolved"]}
        self.assertTrue({"schedule-unclear-p7-3", "schedule-conflict-p7-4", "schedule-conflict-p7-5",
                         "schedule-outside-p7-x", "schedule-invalid-p7-y"} <= keys)
        titles = {t["title"] for t in out["result"]["tasks"]}
        for title in ("공연 직전 마이크 최종 점검", "공연 중 마이크·수신기 상태 확인", "마이크·수신기 철수와 반납",
                      "행사 뒤 반납 확인", "거꾸로 적힌 행"):
            self.assertNotIn(title, titles)   # 임의 시각으로 업무를 만들지 않는다
        conflict = next(u for u in out["result"]["unresolved"] if u["key"] == "schedule-conflict-p7-4")
        self.assertIn("schedule:p7-5", conflict["refs"])

    def test_unknown_zone_is_left_unresolved(self):
        raw = json.loads(FIXTURE.read_text(encoding="utf-8"))["scheduleItems"]
        raw[0]["zoneName"] = "무대"   # 요청 구역이 아니다
        req_raw, out, _, _ = run(run11_plan(), schedule.parse(raw))
        self.assertEqual(contract.validate_response(req_raw, out), ("READY", []))
        self.assertIn("schedule-missing-p7-1", {u["key"] for u in out["result"]["unresolved"]})
        self.assertNotIn(P7_UTC["p7-1"], times(out))




class RequestScheduleTest(unittest.TestCase):
    """V9-D66: 요청4 payload.scheduleItems로 온 일정표도 시험 경로(GmsGenerator(schedule=...))와 같게 반영한다."""

    def raw_with_items(self):
        raw = design_request()
        raw["payload"]["scheduleItems"] = json.loads(FIXTURE.read_text(encoding="utf-8"))["scheduleItems"]
        return raw

    def test_request_items_restore_source_tasks(self):
        raw = self.raw_with_items()
        raw["deadlineAt"] = "2099-01-01T00:00:00Z"
        fake = FakeGms(run11_plan())
        gen = gms_generator.GmsGenerator()
        with mock.patch.object(gms_generator, "_load_gms", return_value=fake):
            out = flow.run(raw, datetime.fromisoformat("2026-11-18T08:00:00+00:00"), gen)
        self.assertEqual(out["result"]["status"], "READY")
        spans = set(times(out))
        for start, end in P7_UTC.values():
            self.assertIn((start, end), spans)
        self.assertIn("scheduleItems", json.loads(fake.sent[0][1]["content"]))
        self.assertEqual(len(gen.schedule_results), 1)

    def test_request_without_items_keeps_existing_prompt(self):
        raw = design_request()
        raw["deadlineAt"] = "2099-01-01T00:00:00Z"
        fake = FakeGms(run11_plan())
        with mock.patch.object(gms_generator, "_load_gms", return_value=fake):
            flow.run(raw, datetime.fromisoformat("2026-11-18T08:00:00+00:00"), gms_generator.GmsGenerator())
        self.assertNotIn("scheduleItems", json.loads(fake.sent[0][1]["content"]))
        self.assertEqual(fake.sent[0][0]["content"], gms_generator.SYSTEM)

    def test_contract_limits_request_items(self):
        raw = self.raw_with_items()
        item = raw["payload"]["scheduleItems"][0]
        raw["payload"]["scheduleItems"] = [{**item, "key": f"k{n}"} for n in range(contract.MAX_SCHEDULE_ITEMS + 1)]
        with self.assertRaises(Exception):
            contract.parse_request(raw)
        raw["payload"]["scheduleItems"] = [{**item, "extra": 1}]
        with self.assertRaises(Exception):
            contract.parse_request(raw)
        raw["payload"]["scheduleItems"] = None
        self.assertIsNone(contract.parse_request(raw).payload.scheduleItems)

    def test_item_steps_and_checklist_become_task_steps(self):
        raw = self.raw_with_items()
        raw["deadlineAt"] = "2099-01-01T00:00:00Z"
        first = raw["payload"]["scheduleItems"][0]
        first["steps"] = ["수신기 전원 확인", "마이크 짝 맞춤"]
        first["checklist"] = ["메인 소리 확인"]
        with mock.patch.object(gms_generator, "_load_gms", return_value=FakeGms(run11_plan())):
            out = flow.run(raw, datetime.fromisoformat("2026-11-18T08:00:00+00:00"), gms_generator.GmsGenerator())
        task = times(out)[P7_UTC[first["key"]]]
        self.assertEqual(task["steps"], ["수신기 전원 확인", "마이크 짝 맞춤"])
        self.assertEqual(task["checklist"], [{"key": "check-1", "text": "메인 소리 확인", "required": True}])
        other = times(out)[P7_UTC["p7-2"]]
        self.assertEqual(len(other["steps"]), 1)   # 절차가 없는 행은 일정표 안내 한 줄이다
        first["steps"] = ["x" * 121]
        with self.assertRaises(Exception):
            contract.parse_request(raw)

    def test_document_steps_win_over_model_steps_for_bound_tasks(self):
        """모델이 scheduleKey로 일정 업무를 만들어도 문서의 절차·점검 항목이 있으면 그것을 쓴다(#410 리뷰)."""
        plan = run11_plan()
        plan["tasks"] = [t for t in plan["tasks"] if t["workGroupId"] != SOUND]
        items = json.loads(FIXTURE.read_text(encoding="utf-8"))["scheduleItems"]
        items[0]["steps"] = ["문서 절차 1", "문서 절차 2"]
        items[0]["checklist"] = ["문서 점검"]
        for item in items:
            start, end = P7_UTC[item["key"]]
            plan["tasks"].append({"tempId": f"tmp-task-{item['key']}", "workGroupId": SOUND, "bundleKey": "sound-1",
                                  "zoneId": ZONE_A, "programId": None, "title": item["title"],
                                  "startAt": start, "endAt": end, "steps": ["모델 절차"], "checklist": [],
                                  "conditionRefs": [], "scheduleKey": item["key"]})
        plan["precedences"] = []
        raw, out, _, _ = run(plan, schedule.parse(items))
        by_time = times(out)
        self.assertEqual(by_time[P7_UTC["p7-1"]]["steps"], ["문서 절차 1", "문서 절차 2"])
        self.assertEqual(by_time[P7_UTC["p7-1"]]["checklist"][0]["text"], "문서 점검")
        self.assertEqual(by_time[P7_UTC["p7-2"]]["steps"], ["모델 절차"])   # 문서에 절차가 없으면 모델 것

if __name__ == "__main__":
    unittest.main()

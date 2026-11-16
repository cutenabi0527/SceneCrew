"""최초 계획 계약 테스트 (모델 호출 없이 실행된다).

실행: cd ai/plan && uv run python -m unittest -v
"""

import copy
import json
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import contract
from contract import ContractViolation, Question

HERE = Path(__file__).parent
EXAMPLES = HERE / "examples"


def load(name):
    return json.loads((EXAMPLES / name).read_text(encoding="utf-8"))


REQUEST = load("request.json")
READY = load("success-ready.json")


def tasks_of(res):
    return res["result"]["tasks"]


class ExampleTest(unittest.TestCase):
    """README의 분기별 예시가 계약대로 분류되는지 확인한다."""

    def test_branches(self):
        cases = {
            "success-ready.json": "READY",
            "success-needs-info.json": "NEEDS_INFO",
            "failure-review-budget.json": "FAILED",
            "failure-timeout.json": "FAILED",
            "failure-context-changed.json": "FAILED",
            "failure-unavailable.json": "FAILED",
        }
        for name, expected in cases.items():
            with self.subTest(name):
                self.assertEqual(contract.validate_response(REQUEST, load(name)), (expected, []))

    def test_precheck_branch(self):
        req = load("request-missing-zones.json")
        self.assertEqual(contract.validate_response(req, load("success-needs-info-precheck.json")), ("NEEDS_INFO", []))

    def test_invalid_request_branches(self):
        self.assertEqual(contract.validate_response(load("request-malformed.json"), load("failure-pre-acceptance.json")),
                         ("FAILED", []))
        self.assertEqual(contract.validate_response(load("request-invalid-reserve.json"),
                                                    load("failure-invalid-request.json")), ("FAILED", []))

    def test_schema_files_are_current(self):
        for name, schema in contract.json_schemas().items():
            with self.subTest(name):
                saved = json.loads((HERE / "schema" / name).read_text(encoding="utf-8"))
                self.assertEqual(saved, schema, "uv run python contract.py schema schema 로 다시 생성하세요.")


class RequestTest(unittest.TestCase):
    def assertInvalid(self, mutate, code=None):
        raw = copy.deepcopy(REQUEST)
        mutate(raw)
        with self.assertRaises(ContractViolation) as cm:
            contract.parse_request(raw)
        if code:
            self.assertIn(code, [i["code"] for i in cm.exception.issues])
        return cm.exception.issues

    def test_valid(self):
        req = contract.parse_request(REQUEST)
        self.assertEqual(contract.precheck_questions(req), [])

    def test_envelope(self):
        self.assertInvalid(lambda r: r.update(kind="GENERATE_CHANGE"), "SCHEMA")
        self.assertInvalid(lambda r: r.update(requestSchemaVersion="3"), "SCHEMA")
        self.assertInvalid(lambda r: r.update(inputHash="sha256"), "SCHEMA")
        self.assertInvalid(lambda r: r.update(attempt=0), "SCHEMA")
        self.assertInvalid(lambda r: r.update(deadlineAt="2026-11-01T03:10:00"), "SCHEMA")
        self.assertInvalid(lambda r: r.pop("deadlineAt"), "SCHEMA")
        self.assertInvalid(lambda r: r.update(contextVersions={}), "SCHEMA")

    def test_forbidden_payload_fields(self):
        # 행사 입력에 실제 담당자나 임의 필드를 섞어 보내지 않는다.
        self.assertInvalid(lambda r: r["payload"].update(members=[]), "SCHEMA")

    def test_event_times(self):
        self.assertInvalid(lambda r: r["payload"]["event"].update(openAt="2026-11-14T09:00:00Z"), "TIME_OUT_OF_RANGE")

    def test_event_date_must_match_korean_local_date(self):
        issues = self.assertInvalid(lambda r: r["payload"]["event"].update(date="2030-01-01"), "DATE_MISMATCH")
        self.assertEqual({i["path"] for i in issues if i["code"] == "DATE_MISMATCH"},
                         {"payload.event.prepStartAt", "payload.event.teardownEndAt"})

    def test_prep_and_teardown_cannot_cross_local_date(self):
        for field, value in (("prepStartAt", "2026-11-13T14:59:59Z"),
                             ("teardownEndAt", "2026-11-14T15:00:00Z")):
            with self.subTest(field=field):
                issues = self.assertInvalid(lambda r: r["payload"]["event"].update({field: value}), "DATE_MISMATCH")
                self.assertIn(f"payload.event.{field}", [i["path"] for i in issues])

    def test_event_date_uses_service_timezone_not_request_offset(self):
        # 기본 예시의 준비 시작은 UTC 전날이지만 한국 현지 날짜는 행사 당일이다.
        for offset in (-7, 0, 9):
            with self.subTest(offset=offset):
                raw = copy.deepcopy(REQUEST)
                for field in ("prepStartAt", "openAt", "closeAt", "teardownEndAt"):
                    instant = datetime.fromisoformat(raw["payload"]["event"][field])
                    raw["payload"]["event"][field] = instant.astimezone(timezone(timedelta(hours=offset))).isoformat()
                contract.parse_request(raw)

    def test_reserve_below_capacity(self):
        self.assertInvalid(lambda r: r["payload"]["event"].update(desiredReserveCount=12), "INVALID_REQUEST")

    def test_capacity_limit(self):
        self.assertInvalid(lambda r: r["payload"]["event"].update(totalCapacity=32), "SCHEMA")

    def test_program_zone_reference(self):
        self.assertInvalid(lambda r: r["payload"]["programs"][0].update(
            zoneId="33333333-3333-4333-8333-333333333399"), "INVALID_REFERENCE")

    def test_program_outside_operation(self):
        self.assertInvalid(lambda r: r["payload"]["programs"][0].update(startAt="2026-11-14T00:00:00Z"),
                           "TIME_OUT_OF_RANGE")

    def test_zone_limit(self):
        def many(r):
            r["payload"]["zones"] = [{"id": f"33333333-3333-4333-8333-3333333334{i:02d}", "name": f"구역{i}"}
                                     for i in range(11)]
            r["payload"]["programs"] = []
        self.assertInvalid(many, "SCHEMA")

    def test_duplicate_zone_name(self):
        self.assertInvalid(lambda r: r["payload"]["zones"][1].update(name="메인홀"), "DUPLICATE")


class ResultTest(unittest.TestCase):
    def check(self, mutate):
        res = copy.deepcopy(READY)
        mutate(res)
        return contract.validate_response(REQUEST, res)

    def assertInvalid(self, mutate, code):
        branch, issues = self.check(mutate)
        self.assertEqual(branch, "INVALID")
        self.assertIn(code, [i["code"] for i in issues], issues)

    def test_ready_cannot_carry_questions(self):
        self.assertInvalid(lambda r: r["result"]["questions"].append(
            {"key": "q", "field": "payload.event", "question": "확인이 필요합니다."}), "SCHEMA")

    def test_ready_needs_tasks(self):
        def empty(r):
            r["result"].update(tasks=[], bundles=[])
        self.assertInvalid(empty, "SCHEMA")

    def test_needs_info_cannot_carry_partial_plan(self):
        def partial(r):
            r["result"]["status"] = "NEEDS_INFO"
            r["result"]["questions"] = [{"key": "q", "field": "payload.event", "question": "확인이 필요합니다."}]
        self.assertInvalid(partial, "SCHEMA")

    def test_needs_info_requires_question(self):
        res = load("success-needs-info.json")
        res["result"]["questions"] = []
        branch, issues = contract.validate_response(REQUEST, res)
        self.assertEqual(branch, "INVALID")

    def test_no_assignee_in_draft(self):
        self.assertInvalid(lambda r: tasks_of(r)[0].update(assigneeId="66666666-6666-4666-8666-666666666666"),
                           "SCHEMA")

    def test_one_of_id_or_temp_id(self):
        self.assertInvalid(lambda r: r["result"]["workGroups"][0].update(id="77777777-7777-4777-8777-777777777777"),
                           "SCHEMA")

    def test_existing_id_must_come_from_request(self):
        self.assertInvalid(lambda r: r["result"]["zones"][0].update(id="33333333-3333-4333-8333-333333333399"),
                           "INVALID_REFERENCE")

    def test_task_references(self):
        self.assertInvalid(lambda r: tasks_of(r)[0].update(zoneId="tmp-zone-x"), "INVALID_REFERENCE")
        self.assertInvalid(lambda r: tasks_of(r)[0].update(programId="tmp-prog-x"), "INVALID_REFERENCE")

    def test_task_in_program_zone(self):
        # 메인홀 개회식을 참조하면서 로비에 배치할 수 없다.
        self.assertInvalid(lambda r: tasks_of(r)[4].update(zoneId="33333333-3333-4333-8333-333333333302"),
                           "INVALID_REFERENCE")

    def test_task_overlaps_program_time(self):
        # 개회식(01:00~01:30)이 끝난 뒤에 실행하는 업무는 개회식을 참조할 수 없다.
        def after(r):
            tasks_of(r)[6]["programId"] = "44444444-4444-4444-8444-444444444401"
        self.assertInvalid(after, "TIME_OUT_OF_RANGE")
        # 경계에서 끝나 겹치지 않는 업무(무대 장비 점검 23:30~01:00)도 마찬가지다.
        self.assertInvalid(lambda r: tasks_of(r)[3].update(programId="44444444-4444-4444-8444-444444444401"),
                           "TIME_OUT_OF_RANGE")

    def test_program_without_zone_allows_any_zone(self):
        def free(r):
            r["result"]["programs"][0]["zoneId"] = None
            tasks_of(r)[4]["zoneId"] = "33333333-3333-4333-8333-333333333302"
        self.assertEqual(self.check(free), ("READY", []))

    def test_task_inside_event_window(self):
        def early(r):
            tasks_of(r)[0]["startAt"] = "2026-11-13T22:00:00Z"
            r["result"]["bundles"][0]["startAt"] = "2026-11-13T22:00:00Z"
        self.assertInvalid(early, "TIME_OUT_OF_RANGE")

    def test_bundle_overlap(self):
        # 오전 접수(reception-a, 01:00~04:00)를 오후 접수와 겹치게 늘리면 한 사람이 동시에 맡게 된다.
        def stretch(r):
            tasks_of(r)[2]["bundleKey"] = "reception-a"
            tasks_of(r)[1]["endAt"] = "2026-11-14T05:00:00Z"
        self.assertInvalid(stretch, "TIME_OVERLAP")

    def test_bundle_single_group(self):
        def cross(r):
            tasks_of(r)[2].update(bundleKey="stage-b")
        self.assertInvalid(cross, "BUNDLE_MISMATCH")

    def test_bundle_summary_matches_tasks(self):
        self.assertInvalid(lambda r: r["result"]["bundles"][0].update(taskCount=3), "BUNDLE_MISMATCH")
        self.assertInvalid(lambda r: r["result"]["bundles"].pop(), "BUNDLE_MISMATCH")

    def test_text_limits(self):
        self.assertInvalid(lambda r: tasks_of(r)[0].update(title="가" * 101), "SCHEMA")
        self.assertInvalid(lambda r: tasks_of(r)[0].update(steps=["단계"] * 7), "SCHEMA")
        self.assertInvalid(lambda r: tasks_of(r)[0].update(steps=[]), "SCHEMA")
        self.assertInvalid(lambda r: tasks_of(r)[0]["checklist"][0].update(text="가" * 81), "SCHEMA")
        self.assertInvalid(lambda r: r["result"]["workGroups"][0].update(name="   "), "SCHEMA")

    def test_checklist_key_unique(self):
        self.assertInvalid(lambda r: tasks_of(r)[3]["checklist"][1].update(key="projector-ok"), "DUPLICATE")

    def test_duplicate_group_name(self):
        self.assertInvalid(lambda r: r["result"]["workGroups"][1].update(name="접수"), "DUPLICATE")

    def test_instructions(self):
        self.assertInvalid(lambda r: r["result"]["instructions"][0].update(targetId="tmp-wg-stage"), "SCHEMA")
        self.assertInvalid(lambda r: r["result"]["instructions"][1].update(targetId="tmp-wg-none"), "INVALID_REFERENCE")
        self.assertInvalid(lambda r: r["result"]["instructions"].append(
            {"scope": "EVENT", "targetId": None, "body": "중복 안내"}), "DUPLICATE")

    def test_coordination_has_no_raw_fields(self):
        self.assertInvalid(lambda r: r["result"]["coordination"].update(prompt="원시 프롬프트"), "SCHEMA")

    def test_echo(self):
        self.assertInvalid(lambda r: r.update(attempt=2), "ECHO_MISMATCH")
        self.assertInvalid(lambda r: r.update(contextRevision=13), "ECHO_MISMATCH")

    def test_result_version(self):
        self.assertInvalid(lambda r: r.update(resultSchemaVersion="4"), "SCHEMA")


class FailureTest(unittest.TestCase):
    def check(self, mutate, name="failure-timeout.json"):
        res = load(name)
        mutate(res)
        return contract.validate_response(REQUEST, res)

    def test_retryable_fixed_by_code(self):
        self.assertEqual(self.check(lambda r: r["error"].update(retryable=True))[0], "INVALID")
        self.assertEqual(self.check(lambda r: r["error"].update(retryable=False), "failure-unavailable.json")[0],
                         "INVALID")

    def test_review_failed_reason(self):
        self.assertEqual(self.check(lambda r: r["error"]["details"].pop("reason"), "failure-review-budget.json")[0],
                         "INVALID")

    def test_unknown_code(self):
        self.assertEqual(self.check(lambda r: r["error"].update(code="PLAN_NEEDS_INPUT"))[0], "INVALID")

    def test_identity_after_acceptance(self):
        self.assertEqual(self.check(lambda r: r.update(inputHash=None))[0], "INVALID")
        self.assertEqual(self.check(lambda r: r.update(attempt=2))[0], "INVALID")

    def test_failure_is_not_result(self):
        self.assertEqual(self.check(lambda r: r.update(result={}))[0], "INVALID")

    def test_non_object_response(self):
        for res in (None, 0, 1.5, True, "error", ["error"]):
            with self.subTest(res):
                self.assertEqual(contract.validate_response(REQUEST, res)[0], "INVALID")


class RejectedRequestTest(unittest.TestCase):
    """유효하지 않은 요청에는 INVALID_REQUEST 실패만 맞는 응답이다."""

    MALFORMED = load("request-malformed.json")
    LOGIC = load("request-invalid-reserve.json")
    PRE = load("failure-pre-acceptance.json")
    ECHOED = load("failure-invalid-request.json")

    def codes(self, raw_request, raw_response):
        branch, issues = contract.validate_response(raw_request, raw_response)
        return branch, [i["code"] for i in issues]

    def test_logic_invalid_request_is_not_ready(self):
        # 희망 예비 == 총인원은 parse_request가 거부한다. 같은 요청에 READY를 돌려주면 안 된다.
        res = copy.deepcopy(READY)
        res.update(jobId=self.LOGIC["jobId"], inputHash=self.LOGIC["inputHash"])
        branch, codes = self.codes(self.LOGIC, res)
        self.assertEqual(branch, "INVALID")
        self.assertIn("EXPECTED_INVALID_REQUEST", codes)

    def test_logic_invalid_request_echoes_identity(self):
        self.assertEqual(self.codes(self.LOGIC, self.PRE), ("INVALID", ["ECHO_MISMATCH"] * 5))
        other = copy.deepcopy(self.ECHOED)
        other["error"] = {"code": "AI_TIMEOUT", "message": "마감", "retryable": False, "details": {}}
        self.assertIn("EXPECTED_INVALID_REQUEST", self.codes(self.LOGIC, other)[1])

    def test_valid_request_rejects_null_identity(self):
        self.assertEqual(self.codes(REQUEST, self.PRE), ("INVALID", ["ECHO_MISMATCH"] * 5))

    def test_malformed_request(self):
        self.assertEqual(self.codes(self.MALFORMED, READY)[0], "INVALID")
        known = copy.deepcopy(self.PRE)
        known["jobId"] = self.MALFORMED["jobId"]  # 해석 가능한 원래 값은 반환해도 된다
        self.assertEqual(self.codes(self.MALFORMED, known), ("FAILED", []))
        known["jobId"] = REQUEST["jobId"]
        self.assertEqual(self.codes(self.MALFORMED, known), ("INVALID", ["ECHO_MISMATCH"]))
        self.assertEqual(self.codes("요청이 아님", self.PRE), ("FAILED", []))

    def test_parse_request_keeps_parsed_request(self):
        with self.assertRaises(ContractViolation) as cm:
            contract.parse_request(self.LOGIC)
        self.assertEqual(contract.failure(cm.exception.request, "INVALID_REQUEST", self.ECHOED["error"]["message"],
                                          {"issues": ["payload.event.desiredReserveCount"]}), self.ECHOED)
        with self.assertRaises(ContractViolation) as cm:
            contract.parse_request(self.MALFORMED)
        self.assertIsNone(cm.exception.request)


class BuilderTest(unittest.TestCase):
    def setUp(self):
        self.req = contract.parse_request(REQUEST)

    def test_ready_roundtrip(self):
        out = contract.ready(self.req, READY["result"], "plan-workflow-dev")
        self.assertEqual(out, READY)

    def test_ready_rejects_invalid_artifact(self):
        result = copy.deepcopy(READY["result"])
        result["tasks"][5]["bundleKey"] = "stage-a"
        result["tasks"][5]["startAt"] = "2026-11-14T00:30:00Z"  # 무대 장비 점검(23:30~01:00)과 겹침
        with self.assertRaises(ContractViolation) as cm:
            contract.ready(self.req, result, "plan-workflow-dev")
        self.assertIn("TIME_OVERLAP", [i["code"] for i in cm.exception.issues])

    def test_needs_info_requires_questions(self):
        with self.assertRaises(ValueError):
            contract.needs_info(self.req, [], "plan-workflow-dev")
        out = contract.needs_info(self.req, [Question(key="q", field="payload.event", question="확인이 필요합니다.")],
                                  "plan-workflow-dev")
        self.assertEqual(contract.validate_response(REQUEST, out), ("NEEDS_INFO", []))

    def test_failure_retryable_and_identity(self):
        out = contract.failure(self.req, "AI_UNAVAILABLE", "연결 실패")
        self.assertTrue(out["error"]["retryable"])
        self.assertEqual(out["jobId"], REQUEST["jobId"])
        pre = contract.failure(None, "INVALID_REQUEST", "해석 불가")
        self.assertTrue(all(pre[f] is None for f in contract.IDENTITY_FIELDS))
        with self.assertRaises(ValueError):
            contract.failure(None, "AI_TIMEOUT", "마감")

    def test_deadline(self):
        self.assertFalse(contract.deadline_passed(self.req, datetime(2026, 11, 1, 3, 9, tzinfo=timezone.utc)))
        self.assertTrue(contract.deadline_passed(self.req, datetime(2026, 11, 1, 3, 10, tzinfo=timezone.utc)))


if __name__ == "__main__":
    unittest.main()

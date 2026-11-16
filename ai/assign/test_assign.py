"""최초 배정 계약·실행 흐름·내부 API 테스트 (모델 호출 없이 실행된다).

실행: cd ai/assign && uv run python -m unittest -v
"""

import copy
import json
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi.testclient import TestClient

import assign
import contract
import server

EXAMPLES = Path(__file__).parent / "examples"
NOW = datetime(2026, 11, 18, 4, 0, tzinfo=timezone.utc)
M1, M2 = "d1000000-0000-4000-8000-000000000001", "d1000000-0000-4000-8000-000000000002"
TOKEN = "test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


def request(**payload):
    raw = json.loads((EXAMPLES / "request.json").read_text(encoding="utf-8"))
    raw["payload"].update(payload)
    return raw


def live(raw):
    """서버 테스트용. 마감을 지금부터 10분 뒤로 옮긴다."""
    raw = copy.deepcopy(raw)
    raw["deadlineAt"] = (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat().replace("+00:00", "Z")
    return raw


class FakeGms:
    """gms.py와 같은 모양의 모의 모듈. output이 예외면 chat_json이 그 예외를 낸다."""

    class GmsError(RuntimeError):
        pass

    def __init__(self, output):
        self.output = output
        self.TIMEOUT_SECONDS = 120
        self.calls = 0

    def chat_json(self, messages, **kwargs):
        self.calls += 1
        self.messages = messages
        self.kwargs = kwargs
        if isinstance(self.output, Exception):
            raise self.output
        return self.output, "gpt-test", {}


def result(envelope):
    return envelope["result"]


class RuleTest(unittest.TestCase):
    def test_saved_example_matches(self):
        saved = json.loads((EXAMPLES / "success-rule.json").read_text(encoding="utf-8"))
        self.assertEqual(assign.run(request(), now=NOW), saved)

    def test_busy_member_skips_overlapping_bundle_and_load_is_balanced(self):
        r = result(assign.run(request(), now=NOW))
        self.assertEqual({a["bundleKey"]: a["memberId"] for a in r["assignments"]},
                         {"entry-1": M1, "entry-2": M2, "exit-1": M2})
        self.assertEqual(r["generator"], "RULE")

    def test_no_free_member_is_unassigned(self):
        busy = [{"startAt": "2026-11-18T00:00:00Z", "endAt": "2026-11-18T23:00:00Z"}]
        r = result(assign.run(request(members=[{"memberId": M1, "busy": busy}]), now=NOW))
        self.assertEqual(r["assignments"], [])
        self.assertEqual({u["code"] for u in r["unassigned"]}, {"NO_AVAILABLE_MEMBER"})
        self.assertEqual(len(r["unassigned"]), 3)

    def test_no_member_is_unassigned_without_calling_gms(self):
        gms = FakeGms(AssertionError("호출되면 안 됨"))
        r = result(assign.run(request(members=[], allowExternalAi=True), now=NOW, gms=gms))
        self.assertEqual({u["code"] for u in r["unassigned"]}, {"NO_MEMBER"})
        self.assertEqual(gms.calls, 0)

    def test_touching_times_are_allowed(self):
        busy = [{"startAt": "2026-11-18T08:00:00Z", "endAt": "2026-11-18T09:00:00Z"}]  # 08:00 끝 업무와 맞닿음
        r = result(assign.run(request(members=[{"memberId": M1, "busy": busy}]), now=NOW))
        self.assertIn({"bundleKey": "entry-1", "memberId": M1}, r["assignments"])


    def test_existing_busy_counts_as_load(self):
        # M1은 이미 10시간을 맡았고 M2는 비어 있다. 둘 다 시간이 비면 덜 맡은 M2가 첫 묶음을 받는다.
        busy = [{"startAt": "2026-11-17T08:00:00Z", "endAt": "2026-11-17T18:00:00Z"}]
        r = result(assign.run(request(members=[{"memberId": M1, "busy": busy}, {"memberId": M2, "busy": []}]), now=NOW))
        self.assertEqual(r["assignments"][0], {"bundleKey": "entry-1", "memberId": M2})


class GmsTest(unittest.TestCase):
    def test_gms_leaving_assignable_bundles_unassigned_falls_back_to_rule(self):
        gms = FakeGms({"assignments": [],
                       "unassigned": [{"bundleKey": k, "reason": "없음"} for k in ("entry-1", "entry-2", "exit-1")]})
        r = result(assign.run(request(allowExternalAi=True), now=NOW, gms=gms))
        self.assertEqual((r["generator"], r["coordination"]["fallback"]), ("RULE", "GMS_INVALID_RESULT"))
        self.assertEqual(len(r["assignments"]), 3)

    def test_two_reasons_for_one_bundle_is_rejected(self):
        req = contract.parse_request(request())
        res = assign.rule_result(req)
        res["reasons"].append({"bundleKey": "entry-1", "text": "둘째 사유"})
        with self.assertRaises(contract.ContractViolation) as e:
            contract.success(req, res, "x")
        self.assertIn("DUPLICATE", {i["code"] for i in e.exception.issues})

    def test_gms_success_has_no_coordination_key(self):
        env = assign.run(request(allowExternalAi=True), now=NOW, gms=FakeGms(GmsTest.valid_output()))
        self.assertEqual(result(env)["generator"], "GMS")
        self.assertNotIn("coordination", result(env))

    def test_gms_waits_less_than_deadline_and_leaves_rule_reserve(self):
        gms = FakeGms(GmsTest.valid_output())
        assign.run(live(request(allowExternalAi=True)), gms=gms)
        self.assertLessEqual(gms.kwargs["timeout"], assign.MAX_CALL_SECONDS)

    def test_near_deadline_skips_gms_and_uses_rule(self):
        raw = request(allowExternalAi=True)
        now = datetime.now(timezone.utc)
        raw["deadlineAt"] = (now + timedelta(seconds=assign.RULE_RESERVE_SECONDS - 1)).isoformat().replace("+00:00", "Z")
        gms = FakeGms(AssertionError("호출되면 안 됨"))
        r = result(assign.run(raw, now=now, gms=gms))
        self.assertEqual((r["generator"], r["coordination"]["fallback"]), ("RULE", "GMS_UNAVAILABLE"))
        self.assertEqual(gms.calls, 0)

    def test_external_ai_not_allowed_never_calls_gms(self):
        gms = FakeGms(AssertionError("호출되면 안 됨"))
        r = result(assign.run(request(allowExternalAi=False), now=NOW, gms=gms))
        self.assertEqual((r["generator"], r["coordination"]), ("RULE", {"fallback": "EXTERNAL_AI_NOT_ALLOWED"}))
        self.assertEqual(gms.calls, 0)

    def test_gms_result_is_used_when_valid(self):
        gms = FakeGms({"assignments": [{"bundleKey": "entry-1", "memberId": M1, "reason": "개장 전부터 비어 있음"},
                                       {"bundleKey": "entry-2", "memberId": M2, "reason": "입장 시간대가 비어 있음"},
                                       {"bundleKey": "exit-1", "memberId": M1, "reason": "퇴장 시간대가 비어 있음"}],
                       "unassigned": []})
        env = assign.run(request(allowExternalAi=True), now=NOW, gms=gms)
        self.assertEqual(env["modelVersion"], "assign-gms-gpt-test")
        self.assertEqual(result(env)["generator"], "GMS")
        self.assertEqual(len(result(env)["reasons"]), 3)

    def test_gms_input_has_no_identity_or_group(self):
        gms = FakeGms(GmsTest.valid_output())
        assign.run(request(allowExternalAi=True), now=NOW, gms=gms)
        sent = json.loads(gms.messages[-1]["content"])
        self.assertEqual(set(sent), {"bundles", "members"})
        self.assertNotIn("taskId", sent["bundles"][0]["tasks"][0])

    def test_gms_failure_falls_back_to_rule(self):
        r = result(assign.run(request(allowExternalAi=True), now=NOW, gms=FakeGms(FakeGms.GmsError("503"))))
        self.assertEqual((r["generator"], r["coordination"]["fallback"]), ("RULE", "GMS_UNAVAILABLE"))

    def test_gms_time_conflict_falls_back_to_rule(self):
        # M2는 05:30~06:00에 바빠 entry-1(05:00~08:00)을 맡을 수 없다
        gms = FakeGms({"assignments": [{"bundleKey": "entry-1", "memberId": M2},
                                       {"bundleKey": "entry-2", "memberId": M1},
                                       {"bundleKey": "exit-1", "memberId": M1}], "unassigned": []})
        r = result(assign.run(request(allowExternalAi=True), now=NOW, gms=gms))
        self.assertEqual((r["generator"], r["coordination"]["fallback"]), ("RULE", "GMS_INVALID_RESULT"))

    def test_gms_unknown_member_or_missing_bundle_falls_back(self):
        for output in ({"assignments": [{"bundleKey": "entry-1", "memberId": "00000000-0000-4000-8000-000000000000"}]},
                       {"assignments": [{"bundleKey": "entry-1", "memberId": M1}]},  # 나머지 묶음 누락
                       ["not", "object"]):
            r = result(assign.run(request(allowExternalAi=True), now=NOW, gms=FakeGms(output)))
            self.assertEqual(r["generator"], "RULE", output)

    @staticmethod
    def valid_output():
        return {"assignments": [{"bundleKey": "entry-1", "memberId": M1}, {"bundleKey": "entry-2", "memberId": M2},
                                {"bundleKey": "exit-1", "memberId": M1}], "unassigned": []}


class ContractTest(unittest.TestCase):
    def test_duplicate_bundle_key_is_invalid_request(self):
        raw = request()
        raw["payload"]["bundles"][1]["bundleKey"] = "entry-1"
        env = assign.run(raw, now=NOW)
        self.assertEqual(env["error"]["code"], "INVALID_REQUEST")
        self.assertEqual(env["jobId"], raw["jobId"])  # 구조는 맞아 식별을 그대로 돌려준다

    def test_overlapping_tasks_in_one_bundle_is_invalid(self):
        raw = request()
        raw["payload"]["bundles"][0]["tasks"][1]["startAt"] = "2026-11-18T05:30:00Z"
        self.assertEqual(assign.run(raw, now=NOW)["error"]["code"], "INVALID_REQUEST")

    def test_personal_fields_are_rejected(self):
        raw = request()
        raw["payload"]["members"][0]["name"] = "홍길동"
        env = assign.run(raw, now=NOW)
        self.assertEqual(env["error"]["code"], "INVALID_REQUEST")
        self.assertIsNone(env["jobId"])  # 구조 위반은 식별을 신뢰하지 않는다

    def test_deadline_passed_is_timeout(self):
        env = assign.run(request(), now=datetime(2026, 11, 18, 4, 10, tzinfo=timezone.utc))
        self.assertEqual(env["error"]["code"], "AI_TIMEOUT")

    def test_result_issues_catch_conflict_and_duplicate(self):
        req = contract.parse_request(request())
        bad = contract.AssignResult(assignments=[{"bundleKey": "entry-1", "memberId": M1},
                                                 {"bundleKey": "entry-2", "memberId": M1},
                                                 {"bundleKey": "entry-2", "memberId": M2}],
                                    unassigned=[], reasons=[], generator="RULE")
        codes = {i["code"] for i in contract.result_issues(req, bad)}
        self.assertTrue({"TIME_CONFLICT", "DUPLICATE", "MISSING"} <= codes)


class ServerTest(unittest.TestCase):
    def client(self, generator="rule"):
        return TestClient(server.create_app(server.Settings(service_token=TOKEN, generator=generator)))

    def test_wrong_token_is_401(self):
        res = self.client().post("/internal/assign-initial", json=live(request()), headers={"Authorization": "Bearer x"})
        self.assertEqual(res.status_code, 401)

    def test_rule_success_is_200(self):
        res = self.client().post("/internal/assign-initial", json=live(request()), headers=AUTH)
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["result"]["generator"], "RULE")

    def test_invalid_request_is_400(self):
        raw = live(request())
        raw["payload"]["bundles"] = []
        res = self.client().post("/internal/assign-initial", json=raw, headers=AUTH)
        self.assertEqual((res.status_code, res.json()["error"]["code"]), (400, "INVALID_REQUEST"))

    def test_health_is_ok_without_gms(self):
        self.assertEqual(self.client().get("/health").json(), {"status": "ok", "generator": "rule", "gms": False})


if __name__ == "__main__":
    unittest.main()

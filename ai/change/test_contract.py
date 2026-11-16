"""요청·결과6 계약 시험. 실행: cd ai/change && uv run python -m unittest -v test_contract"""
import unittest

from pydantic import ValidationError

import contract
import test_graph


class RequestTest(unittest.TestCase):
    def test_valid_example(self):
        self.assertEqual(contract.parse_request(test_graph.request()).payload.issue.id, test_graph.ISSUE)

    def check(self, change, path):
        raw = test_graph.request()
        change(raw)
        with self.assertRaises(contract.ContractViolation) as e:
            contract.parse_request(raw)
        self.assertTrue(any(path in i["path"] for i in e.exception.issues), e.exception.issues)

    def test_rejects(self):
        self.check(lambda r: r.update(kind="GENERATE_PLAN"), "kind")
        self.check(lambda r: r["payload"]["members"][0].update(name="홍길동"), "name")      # 개인 정보 칸 없음
        self.check(lambda r: r["payload"]["tasks"][0].update(assigneeId="00000000-0000-4000-8000-0000000000ff"), "assigneeId")
        self.check(lambda r: r["payload"]["tasks"].append(dict(r["payload"]["tasks"][0])), "payload.tasks")
        self.check(lambda r: r["payload"]["issue"].update(reports=[]), "reports")
        self.check(lambda r: r["payload"]["programs"][0].update(endAt=r["payload"]["programs"][0]["startAt"]), "programs[0]")


class OperationTest(unittest.TestCase):
    def test_update_needs_a_change_and_instructions_scope(self):
        with self.assertRaises(ValidationError):
            contract.Operation.model_validate({"type": "UPDATE_TASK", "payload": {"taskId": test_graph.T[1]}})
        with self.assertRaises(ValidationError):
            contract.Operation.model_validate({"type": "UPDATE_INSTRUCTIONS",
                                               "payload": {"scope": "EVENT", "workGroupId": test_graph.G1, "body": "x"}})
        with self.assertRaises(ValidationError):   # 형식과 payload가 다르면 거절
            contract.Operation.model_validate({"type": "CANCEL_TASK", "payload": {"taskId": test_graph.T[1],
                                                                                 "fromMemberId": None, "toMemberId": None}})

    def test_reservation_operations_are_not_produced(self):
        with self.assertRaises(ValidationError):
            contract.Operation.model_validate({"type": "RESERVE_EQUIPMENT", "payload": {}})

    def test_dump_sends_utc_and_only_changed_fields(self):
        op = contract.Operation.model_validate({"type": "UPDATE_TASK", "payload": {
            "taskId": test_graph.T[1], "startAt": "2026-11-18T18:20:00+09:00"}})
        self.assertEqual(op.dump(), {"type": "UPDATE_TASK", "payload": {"taskId": test_graph.T[1], "startAt": "2026-11-18T09:20:00Z"}})


class ResultTest(unittest.TestCase):
    def test_branches(self):
        coord = {"graph": [], "redelegations": [], "resolvedConflicts": [], "contributions": []}
        with self.assertRaises(ValidationError):
            contract.ChangeResult.model_validate({"status": "READY", "operations": [], "rationale": "x", "coordination": coord})
        with self.assertRaises(ValidationError):
            contract.ChangeResult.model_validate({"status": "NEEDS_INFO", "operations": [], "rationale": "", "questions": [],
                                                  "coordination": coord})
        with self.assertRaises(ValidationError):
            contract.ChangeResult.model_validate({"status": "NEEDS_INFO", "operations": [], "rationale": "",
                                                  "questions": [{"key": "a", "field": "b", "question": "c"}],
                                                  "coordination": coord, "simulationRefs": [{"x": 1}]})

    def test_failure_retryable_follows_the_code(self):
        self.assertFalse(contract.failure(None, "INVALID_REQUEST", "x")["error"]["retryable"])
        with self.assertRaises(ValidationError):
            contract.failure(None, "AI_TIMEOUT", "x")   # 접수 뒤 실패는 식별 필드가 있어야 한다

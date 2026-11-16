"""대응안 생성 내부 API 시험(모델 호출 없음). 실행: cd ai/change && uv run python -m unittest -v test_server"""
import time
import unittest
from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

import server
import test_graph

TOKEN = "test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


def body(deadline_in=600):
    raw = test_graph.request()
    raw["deadlineAt"] = (datetime.now(timezone.utc) + timedelta(seconds=deadline_in)).isoformat()
    return raw


def client(run=None):
    return TestClient(server.create_app(server.Settings(service_token=TOKEN, supervisor="rule", agents="rule"), run=run))


class ServerTest(unittest.TestCase):
    def test_wrong_token_is_401(self):
        res = client().post("/internal/generate-change", json=body(), headers={"Authorization": "Bearer x"})
        self.assertEqual(res.status_code, 401)

    def test_health_needs_no_token(self):
        res = client().get("/health")
        self.assertEqual(res.json(), {"status": "ok", "supervisor": "rule", "agents": "rule"})

    def test_rule_supervisor_end_to_end(self):
        res = client().post("/internal/generate-change", json=body(), headers=AUTH)
        self.assertEqual(res.status_code, 200, res.text)
        self.assertEqual(res.json()["result"]["status"], "READY")

    def test_not_json_and_bad_request_are_400(self):
        res = client().post("/internal/generate-change", content=b"x", headers={**AUTH, "Content-Type": "application/json"})
        self.assertEqual((res.status_code, res.json()["error"]["code"]), (400, "INVALID_REQUEST"))
        bad = body()
        bad["kind"] = "GENERATE_PLAN"
        self.assertEqual(client().post("/internal/generate-change", json=bad, headers=AUTH).status_code, 400)

    def test_run_that_outlives_the_deadline_is_504(self):
        server.DEADLINE_GRACE_SEC, old = 0, server.DEADLINE_GRACE_SEC
        try:
            res = client(run=lambda raw: time.sleep(2)).post("/internal/generate-change", json=body(deadline_in=0.5),
                                                              headers=AUTH)
        finally:
            server.DEADLINE_GRACE_SEC = old
        self.assertEqual((res.status_code, res.json()["error"]["code"]), (504, "AI_TIMEOUT"))

    def test_unknown_settings_are_rejected(self):
        with self.assertRaises(ValueError):
            server.Settings(service_token=TOKEN, supervisor="x")

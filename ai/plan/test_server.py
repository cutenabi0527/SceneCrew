"""계획 생성 내부 API 테스트 (모델 호출 없이 실행된다).

실행: cd ai/plan && uv run python -m unittest -v test_server
"""

import json
import threading
import time
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi.testclient import TestClient

import flow
import server

EXAMPLES = Path(__file__).parent / "examples"
TOKEN = "test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


def request_body(deadline_in=600):
    """예시 요청의 마감을 지금 기준으로 다시 정한다. 규칙 생성기는 실제 시각으로 실행된다."""
    raw = json.loads((EXAMPLES / "request.json").read_text(encoding="utf-8"))
    raw["deadlineAt"] = (datetime.now(timezone.utc) + timedelta(seconds=deadline_in)).isoformat().replace("+00:00", "Z")
    return raw


def client(run=None, generator="rule"):
    return TestClient(server.create_app(server.Settings(service_token=TOKEN, generator=generator), run=run))


class AuthTest(unittest.TestCase):
    def test_wrong_token_is_401(self):
        res = client().post("/internal/generate-plan", json=request_body(), headers={"Authorization": "Bearer x"})
        self.assertEqual(res.status_code, 401)
        self.assertEqual(res.json()["error"]["code"], "UNAUTHENTICATED")

    def test_health_needs_no_token(self):
        res = client().get("/health")
        self.assertEqual(res.json(), {"status": "ok", "generator": "rule"})


class GeneratePlanTest(unittest.TestCase):
    def test_rule_generator_returns_ready(self):
        raw = request_body()
        res = client().post("/internal/generate-plan", json=raw, headers=AUTH)
        self.assertEqual(res.status_code, 200)
        body = res.json()
        self.assertEqual(body["result"]["status"], "READY")
        self.assertEqual(body["jobId"], raw["jobId"])  # 식별 필드는 요청 그대로

    def test_floor_plan_request_returns_result6_with_rule(self):
        """결과6 요청(floorPlan)도 규칙 생성기는 평면도를 읽지 않고 venueDraft=null과 이유를 돌려준다."""
        raw = json.loads((EXAMPLES / "demo-pdf" / "request-floorplan.json").read_text(encoding="utf-8"))
        raw["deadlineAt"] = (datetime.now(timezone.utc) + timedelta(seconds=600)).isoformat().replace("+00:00", "Z")
        res = client().post("/internal/generate-plan", json=raw, headers=AUTH)
        self.assertEqual(res.status_code, 200)
        body = res.json()
        self.assertEqual((body["resultSchemaVersion"], body["result"]["venueDraft"]), ("6", None))
        self.assertIn("floor-plan", [u["key"] for u in body["result"]["unresolved"]])

    def test_gms_settings_use_service_token_for_floor_plan(self):
        from floor_plan import GmsFloorPlanReader
        reader = server.Settings(service_token=TOKEN, generator="gms").new_venue_reader()
        self.assertIsInstance(reader, GmsFloorPlanReader)
        self.assertEqual(reader.token, TOKEN)
        self.assertIsInstance(server.Settings(service_token=TOKEN, generator="rule").new_venue_reader(),
                              flow.NoFloorPlanReader)

    def test_not_json_is_invalid_request_without_identity(self):
        res = client().post("/internal/generate-plan", content=b"{", headers={**AUTH, "content-type": "application/json"})
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "INVALID_REQUEST")
        self.assertIsNone(res.json()["jobId"])

    def test_contract_violation_is_400(self):
        raw = json.loads((EXAMPLES / "request-invalid-reserve.json").read_text(encoding="utf-8"))
        res = client().post("/internal/generate-plan", json=raw, headers=AUTH)
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "INVALID_REQUEST")

    def test_generator_unavailable_is_503_retryable(self):
        class Down:
            def draft(self, req, feedback=None):
                raise flow.GeneratorUnavailable("GMS 연결 실패")
        res = client(run=lambda raw: flow.run(raw, generator=Down())).post(
            "/internal/generate-plan", json=request_body(), headers=AUTH)
        self.assertEqual(res.status_code, 503)
        self.assertEqual(res.json()["error"]["code"], "AI_UNAVAILABLE")
        self.assertTrue(res.json()["error"]["retryable"])

    def test_stuck_generator_times_out_after_deadline(self):
        release = threading.Event()

        def stuck(raw):
            release.wait(10)
            return flow.run(raw)
        server.DEADLINE_GRACE_SEC, grace = 0, server.DEADLINE_GRACE_SEC
        # with로 이벤트 루프를 유지해야 응답 뒤에도 남은 실행을 기다리지 않는다(운영 uvicorn과 같다)
        with client(run=stuck) as c:
            try:
                started = time.monotonic()
                res = c.post("/internal/generate-plan", json=request_body(deadline_in=1), headers=AUTH)
                elapsed = time.monotonic() - started
            finally:
                server.DEADLINE_GRACE_SEC = grace
                release.set()
        self.assertEqual(res.status_code, 504)
        self.assertEqual(res.json()["error"]["code"], "AI_TIMEOUT")
        self.assertLess(elapsed, 5)

    def test_unexpected_error_is_503(self):
        def broken(raw):
            raise RuntimeError("bug")
        res = client(run=broken).post("/internal/generate-plan", json=request_body(), headers=AUTH)
        self.assertEqual(res.status_code, 503)
        self.assertEqual(res.json()["error"]["details"]["reason"], "INTERNAL_ERROR")


class SettingsTest(unittest.TestCase):
    def test_unknown_generator_is_rejected(self):
        with self.assertRaises(ValueError):
            server.Settings(service_token=TOKEN, generator="other")

    def test_gms_without_key_is_unavailable(self):
        import gms_generator

        class NoKey:
            class GmsError(RuntimeError):
                pass

            def config(self, model_var="GMS_MODEL"):
                raise self.GmsError("GMS_KEY가 없습니다.")
        original = gms_generator._load_gms
        gms_generator._load_gms = NoKey
        try:
            res = client(generator="gms").get("/health")
        finally:
            gms_generator._load_gms = original
        self.assertEqual(res.json(), {"status": "unavailable", "generator": "gms", "reason": "GmsError"})


if __name__ == "__main__":
    unittest.main()

"""게이트웨이 테스트. 가짜 하위 앱(fake_service.py)을 실제 프로세스로 띄워 전달·상태·재시작을 확인한다."""

import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from dataclasses import replace
from pathlib import Path
from unittest import mock

from fastapi.testclient import TestClient

import gateway

HERE = Path(__file__).resolve().parent


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def fake(name, *routes):
    return gateway.Service(name, HERE, free_port(), routes, app="fake_service:create_app")


def wait_for(client, predicate, timeout=30):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        res = client.get("/health")
        if predicate(res):
            return res
        time.sleep(0.2)
    raise AssertionError(f"제한 시간 안에 상태가 바뀌지 않았습니다: {res.status_code} {res.text}")


class ChildEnvTest(unittest.TestCase):
    def test_model_paths_follow_model_dir(self):
        env = gateway.child_env({"MODEL_DIR": "/models"})
        self.assertEqual(Path(env["REPORT_MODEL_DIR"]), Path("/models/report/report-v2-onnx-int8"))
        self.assertEqual(Path(env["STT_MODEL"]), Path("/models/stt/large-v3"))
        self.assertEqual(Path(env["CROWD_MODEL_DIR"]), Path("/models/crowd/crowd-v1"))

    def test_torch_runtime_uses_fallback_bundle(self):
        env = gateway.child_env({"MODEL_DIR": "/models", "REPORT_RUNTIME": "torch", "REPORT_MODEL_DIR": ""})
        self.assertEqual(Path(env["REPORT_MODEL_DIR"]), Path("/models/report/report-v2"))

    def test_previous_bundle_until_default_is_placed(self):
        # report-v2를 볼륨에 배치하기 전에는 이전 묶음을 쓰고, 배치하면 기본 묶음을 쓴다(#357)
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "report" / "report-v1-onnx-int8").mkdir(parents=True)
            env = gateway.child_env({"MODEL_DIR": d})
            self.assertEqual(Path(env["REPORT_MODEL_DIR"]), Path(d) / "report" / "report-v1-onnx-int8")
            (Path(d) / "report" / "report-v2-onnx-int8").mkdir()
            env = gateway.child_env({"MODEL_DIR": d})
            self.assertEqual(Path(env["REPORT_MODEL_DIR"]), Path(d) / "report" / "report-v2-onnx-int8")

    def test_explicit_paths_win(self):
        env = gateway.child_env({"MODEL_DIR": "/models", "REPORT_MODEL_DIR": "/x", "STT_MODEL": "/y"})
        self.assertEqual((env["REPORT_MODEL_DIR"], env["STT_MODEL"]), ("/x", "/y"))

    def test_without_model_dir_keeps_app_defaults(self):
        env = gateway.child_env({"PATH": "p"})
        self.assertNotIn("REPORT_MODEL_DIR", env)
        self.assertNotIn("STT_MODEL", env)

    def test_unknown_service_is_rejected(self):
        with mock.patch.dict("os.environ", {"AI_SERVICES": "stt,unknown"}):
            with self.assertRaises(RuntimeError):
                gateway.Settings.from_env()

    def test_missing_required_env_is_rejected(self):
        with mock.patch.dict("os.environ", {"AI_SERVICES": "stt,report", "AI_SERVICE_TOKEN": " "}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "AI_SERVICE_TOKEN, STT_AUDIO_URL_PREFIXES"):
                gateway.Settings.from_env()
        with mock.patch.dict("os.environ", {"AI_SERVICES": "report", "AI_SERVICE_TOKEN": "t"}, clear=True):
            self.assertEqual([s.name for s in gateway.Settings.from_env().services], ["report"])

    def test_gateway_import_without_service_environment(self):
        result = subprocess.run(
            [sys.executable, "-B", "-c", "import os; os.environ.clear(); import gateway; gateway.Settings()"],
            cwd=HERE, capture_output=True, text=True, timeout=15,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_default_services_include_plan_assign_change_with_long_timeout(self):
        env = {"AI_SERVICE_TOKEN": "t", "STT_AUDIO_URL_PREFIXES": "https://x/"}
        with mock.patch.dict("os.environ", env, clear=True):
            services = {s.name: s for s in gateway.Settings.from_env().services}
        self.assertEqual(list(services), ["stt", "report", "plan", "assign", "change", "summary"])
        self.assertEqual(services["plan"].routes, ("/internal/generate-plan",))
        self.assertEqual(services["assign"].routes, ("/internal/assign-initial",))
        self.assertEqual(services["change"].routes, ("/internal/generate-change",))
        # BE 마감 600초보다 길고 AI nginx 630초보다 짧다
        self.assertTrue(all(600 < services[n].timeout_sec < 630 for n in ("plan", "assign", "change")))
        self.assertEqual(services["report"].timeout_sec, 170)
        # 종료 요약은 BE가 120초만 기다리는 동기 호출이다
        self.assertEqual(services["summary"].routes, ("/internal/summarize-event",))
        self.assertTrue(120 < services["summary"].timeout_sec < 630)
        self.assertEqual(list(services), [service.name for service in gateway.Settings().services])

    def test_all_registered_services_use_unique_ports(self):
        ports = {name: service.port for name, service in gateway.SERVICES.items()}
        self.assertEqual(len(ports), len(set(ports.values())), ports)

    def test_extract_is_opt_in_and_checks_required_configuration(self):
        env = {"AI_SERVICES": "extract", "AI_SERVICE_TOKEN": "t"}
        with mock.patch.dict("os.environ", env, clear=True):
            with self.assertRaisesRegex(RuntimeError, "DOCUMENT_URL_PREFIXES"):
                gateway.Settings.from_env()
        env.update(AI_SERVICES="change,extract",
                   DOCUMENT_URL_PREFIXES="https://example.invalid/internal/intake-documents/", MODEL_DIR="/models")
        with mock.patch.dict("os.environ", env, clear=True):
            settings = gateway.Settings.from_env()
        change, service = settings.services
        self.assertEqual((change.name, change.port), ("change", 8105))
        self.assertEqual((service.name, service.port, service.timeout_sec), ("extract", 8107, 620))
        self.assertEqual(service.routes, ("/internal/extract-event-input",))
        self.assertEqual(service.app, "document_extraction.server:create_app")
        self.assertEqual(Path(settings.child_env["OCR_DETECTION_MODEL_DIR"]), Path("/models/ocr/detection"))
        self.assertEqual(Path(settings.child_env["OCR_RECOGNITION_MODEL_DIR"]), Path("/models/ocr/recognition"))

    def test_change_extract_and_crowd_can_be_selected_together(self):
        env = {"AI_SERVICES": "change, extract,crowd", "AI_SERVICE_TOKEN": "t",
               "DOCUMENT_URL_PREFIXES": "https://example.invalid/internal/intake-documents/", "MODEL_DIR": "/models"}
        with mock.patch.dict("os.environ", env, clear=True):
            settings = gateway.Settings.from_env()
        self.assertEqual([(s.name, s.port) for s in settings.services],
                         [("change", 8105), ("extract", 8107), ("crowd", 8106)])

    def test_crowd_is_opt_in(self):
        # 모델 묶음이 없는 환경에서 crowd /health 503이 게이트웨이 전체를 unhealthy로 만들지 않도록 직접 켤 때만 띄운다(#360)
        env = {"AI_SERVICE_TOKEN": "t", "AI_SERVICES": "report,crowd"}
        with mock.patch.dict("os.environ", env, clear=True):
            services = {s.name: s for s in gateway.Settings.from_env().services}
        self.assertEqual(list(services), ["report", "crowd"])
        self.assertEqual(services["crowd"].routes, ("/internal/estimate-crowd",))
        # BE 응답 대기 8초보다 조금 길게 둔다
        self.assertEqual(services["crowd"].timeout_sec, 10)


class GatewayTest(unittest.TestCase):
    def start(self, services, **env):
        settings = gateway.Settings(services=services, restart_delay_sec=0.2,
                                    child_env=gateway.child_env({**os.environ, **env}))
        client = TestClient(gateway.create_app(settings))
        client.__enter__()
        self.addCleanup(client.__exit__, None, None, None)
        return client

    def test_forwards_body_auth_and_status(self):
        client = self.start((fake("a", "/internal/echo"),), MODEL_DIR="/models")
        wait_for(client, lambda r: r.status_code == 200)
        res = client.post("/internal/echo", content=b'{"x":1}',
                          headers={"Authorization": "Bearer t", "Content-Type": "application/json", "Cookie": "c"})
        self.assertEqual(res.status_code, 201)
        self.assertEqual(res.json()["authorization"], "Bearer t")
        self.assertEqual(res.json()["body"], '{"x":1}')
        self.assertEqual(Path(res.json()["reportDir"]), Path("/models/report/report-v2-onnx-int8"))

    def test_extract_unavailable_is_reported_and_bearer_is_forwarded(self):
        extract = replace(gateway.SERVICES["extract"], port=free_port())
        client = self.start(
            (extract,), AI_SERVICE_TOKEN="test-token",
            DOCUMENT_URL_PREFIXES="https://example.invalid/internal/intake-documents/",
            OCR_DETECTION_MODEL_DIR="/missing/detection", OCR_RECOGNITION_MODEL_DIR="/missing/recognition",
        )
        health = wait_for(client, lambda r: r.json()["services"]["extract"]["status"] == "unavailable")
        self.assertEqual(health.status_code, 503)
        path, = extract.routes
        self.assertEqual(client.post(path, json={}).status_code, 401)
        self.assertEqual(client.post(path, json={}, headers={"Authorization": "Bearer test-token"}).status_code, 400)

    def test_unknown_internal_path_is_404(self):
        client = self.start((fake("a", "/internal/echo"),))
        self.assertEqual(client.post("/internal/other").status_code, 404)
        self.assertEqual(client.get("/internal/echo").status_code, 405)

    def test_health_is_503_until_every_service_is_ok(self):
        client = self.start((fake("a", "/internal/echo"),), FAKE_STATUS="loading")
        res = wait_for(client, lambda r: r.json()["services"]["a"]["status"] == "loading")
        self.assertEqual(res.status_code, 503)
        self.assertEqual(res.json()["status"], "unavailable")

    def test_health_reports_each_service(self):
        client = self.start((fake("a", "/internal/echo"), fake("b", "/internal/crash")))
        res = wait_for(client, lambda r: r.status_code == 200)
        self.assertEqual(set(res.json()["services"]), {"a", "b"})
        self.assertEqual(res.json()["services"]["a"]["modelVersion"], "fake-1")

    def test_crashed_service_returns_unavailable_and_restarts(self):
        client = self.start((fake("a", "/internal/crash"),))
        wait_for(client, lambda r: r.status_code == 200)
        res = client.post("/internal/crash")
        self.assertEqual(res.status_code, 503)
        self.assertEqual(res.json()["error"]["code"], "AI_UNAVAILABLE")
        self.assertEqual(res.json()["error"]["details"]["service"], "a")
        res = wait_for(client, lambda r: r.status_code == 200 and r.json()["services"]["a"]["restarts"] == 1)
        self.assertEqual(res.json()["services"]["a"]["status"], "ok")


if __name__ == "__main__":
    unittest.main()

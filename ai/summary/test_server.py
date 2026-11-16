"""행사 종료 요약 앱 시험(S15P31A202-269). 실행: cd ai/summary && uv run python -m unittest -v

GMS 대신 가짜 함수로 성공·실패·마감·인증·형식을 확인한다.
"""
import logging
import unittest
from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

import contract
import server
import summarizer

TOKEN = "test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


def request(deadline=60, **payload):
    now = datetime.now(timezone.utc)
    body = {
        "jobId": "00000000-0000-4000-8000-000000000001", "attempt": 1, "requestSchemaVersion": "4",
        "kind": "SUMMARIZE_EVENT", "inputHash": "a" * 64, "contextRevision": 9,
        "deadlineAt": (now + timedelta(seconds=deadline)).isoformat(),
        "payload": {
            "event": {"name": "가을 공연", "date": "2026-11-14", "openAt": "2026-11-14T09:00:00Z",
                      "closeAt": "2026-11-14T12:00:00Z", "endedAt": "2026-11-14T13:10:00Z",
                      "locationName": "합성 시연장", "totalCapacity": 12, "expectedVisitors": 300},
            "workGroups": [{"name": "무대 운영", "tasks": {"total": 5, "done": 4, "inProgress": 0, "waiting": 0, "canceled": 1}}],
            "programs": [{"name": "오프닝", "zone": "무대", "startAt": "2026-11-14T09:00:00Z", "endAt": "2026-11-14T09:20:00Z"}],
            "issues": [{"title": "프로젝터 꺼짐", "status": "CLOSED", "zone": "무대", "situation": "EQUIPMENT_ANOMALY",
                        "reports": 2, "createdAt": "2026-11-14T09:02:00Z", "closedAt": "2026-11-14T09:30:00Z"}],
            "changes": [{"mode": "AI", "rationale": "프로젝터 조치 업무 추가", "operations": 2,
                         "appliedAt": "2026-11-14T09:06:00Z"}],
            **payload}}
    return body


def app(summarize=None):
    calls = []

    def fake(payload, timeout):
        calls.append((payload, timeout))
        return summarize(payload, timeout) if summarize else ("## 개요\n가을 공연을 마쳤습니다.", "MARKDOWN", "summary-gpt-4o")
    client = TestClient(server.create_app(service_token=TOKEN, summarize=fake, ready=lambda: (True, None)))
    client.calls = calls
    return client


class ServerTest(unittest.TestCase):
    def test_success_returns_result2_with_identity(self):
        c = app()
        r = c.post("/internal/summarize-event", json=request(), headers=AUTH)
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["resultSchemaVersion"] == "2" and body["modelVersion"] == "summary-gpt-4o"
        assert body["result"] == {"text": "## 개요\n가을 공연을 마쳤습니다.", "format": "MARKDOWN"}
        assert body["jobId"] == request()["jobId"] and body["contextRevision"] == 9
        assert len(c.calls) == 1 and 0 < c.calls[0][1] <= server.MAX_CALL_SECONDS

    def test_summary_text_is_not_logged(self):
        with self.assertLogs("server", level=logging.INFO) as logs:
            app().post("/internal/summarize-event", json=request(), headers=AUTH)
        assert not any("가을 공연을 마쳤습니다" in line or "프로젝터" in line for line in logs.output)

    def test_model_failure_is_unavailable(self):
        def down(payload, timeout):
            raise summarizer.SummaryUnavailable("GmsError")
        r = app(down).post("/internal/summarize-event", json=request(), headers=AUTH)
        assert r.status_code == 503 and r.json()["error"]["code"] == "AI_UNAVAILABLE"
        assert r.json()["error"]["retryable"] is True and r.json()["jobId"] == request()["jobId"]

    def test_past_deadline_is_timeout_without_calling_the_model(self):
        c = app()
        r = c.post("/internal/summarize-event", json=request(deadline=-1), headers=AUTH)
        assert r.status_code == 504 and r.json()["error"]["code"] == "AI_TIMEOUT"
        assert c.calls == []

    def test_bad_request_and_auth(self):
        c = app()
        bad = request(people=["오하늘"])                     # 계약 밖 필드(개인 정보)는 거절
        r = c.post("/internal/summarize-event", json=bad, headers=AUTH)
        assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST" and r.json()["jobId"] is None
        assert c.post("/internal/summarize-event", json=request()).status_code == 401
        assert c.post("/internal/summarize-event", json=request(), headers={"Authorization": "Bearer x"}).status_code == 401
        assert c.calls == []

    def test_health(self):
        c = TestClient(server.create_app(service_token=TOKEN, summarize=lambda p, t: None, ready=lambda: (False, "GmsError")))
        assert c.get("/health").json() == {"status": "unavailable", "reason": "GmsError"}


class SummarizerTest(unittest.TestCase):
    def test_uses_strict_json_schema_once_without_retry(self):
        calls = []

        def chat(messages, **kw):
            calls.append((messages, kw))
            return {"text": " 요약 ", "format": "TEXT"}, "gpt-4o", {}
        payload = contract.parse_request(request()).payload
        assert summarizer.summarize(payload, 30, chat=chat) == ("요약", "TEXT", "summary-gpt-4o")
        assert len(calls) == 1 and calls[0][1]["retries"] == 0 and calls[0][1]["timeout"] == 30
        assert calls[0][1]["response_format"]["type"] == "json_schema"
        assert "한국어" in calls[0][0][0]["content"]

    def test_model_gets_korean_time_and_korean_labels(self):
        calls = []

        def chat(messages, **kw):
            calls.append(messages)
            return {"text": "요약", "format": "MARKDOWN"}, "gpt-4o", {}
        summarizer.summarize(contract.parse_request(request()).payload, 30, chat=chat)
        sent = calls[0][1]["content"]
        # 09:00Z~12:00Z, 종료 13:10Z → 한국 시간 18:00~21:00, 22:10
        assert "2026-11-14 18:00" in sent and "2026-11-14 21:00" in sent and "2026-11-14 22:10" in sent
        assert "09:00" not in sent and "T09" not in sent and "Z\"" not in sent
        assert "AI 대응안을 총괄이 승인" in sent and "장비 이상" in sent and "종료" in sent
        assert "EQUIPMENT_ANOMALY" not in sent and '"AI"' not in sent and "CLOSED" not in sent
        assert "일반적인 조언" in calls[0][0]["content"]

    def test_empty_or_bad_output_is_unavailable(self):
        payload = contract.parse_request(request()).payload
        for out in ({"text": " ", "format": "TEXT"}, {"text": "x", "format": "HTML"}, ["x"]):
            with self.assertRaises(summarizer.SummaryUnavailable):
                summarizer.summarize(payload, 30, chat=lambda m, **kw: (out, "m", {}))

        def broken(m, **kw):
            raise RuntimeError("GMS HTTP 503")
        with self.assertRaises(summarizer.SummaryUnavailable):
            summarizer.summarize(payload, 30, chat=broken)

    def test_long_text_is_cut(self):
        payload = contract.parse_request(request()).payload
        text, _, _ = summarizer.summarize(payload, 30, chat=lambda m, **kw: ({"text": "가" * 5000, "format": "TEXT"}, "m", {}))
        assert len(text) == contract.MAX_TEXT


if __name__ == "__main__":
    unittest.main()

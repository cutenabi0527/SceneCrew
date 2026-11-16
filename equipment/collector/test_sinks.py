import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

from sinks import HttpBatchSink, JsonlSink, _next_wait
from summarize import summarize


TRUNCATE = "x-test-truncate"


def ack_for(body, duplicates=0):
    """계약 ACK. 받은 묶음 전체를 저장했다고 답한다."""
    runs = {}
    for r in body["records"]:
        runs[r["runId"]] = max(runs.get(r["runId"], 0), r["sequence"])
    return {"collectorId": body["collectorId"], "receivedAt": "2026-11-04T00:00:00Z",
            "accepted": len(body["records"]) - duplicates, "duplicates": duplicates,
            "runs": [{"runId": k, "lastSequence": v} for k, v in runs.items()]}


class _Handler(BaseHTTPRequestHandler):
    bodies: list = []
    fail_first = 0
    # 앞에서부터 하나씩 꺼내 쓰는 응답 목록: (상태, 본문 bytes, 추가 헤더)
    # 헤더에 TRUNCATE를 넣으면 본문 길이를 실제보다 크게 알리고 연결을 끊는다(본문 읽기 중 단절).
    script: list = []
    seen: list = []

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        _Handler.seen.append([r["sequence"] for r in body["records"]])
        if _Handler.script:
            status, payload, headers = _Handler.script.pop(0)
            truncate = TRUNCATE in headers
            # 시험이 정한 응답을 그대로 보낸다(2xx ACK 불일치 시험 포함)
            self.send_response(status)
            for k, v in headers.items():
                if k != TRUNCATE:
                    self.send_header(k, v)
            self.send_header("Content-Length", str(len(payload) + (100 if truncate else 0)))
            self.end_headers()
            self.wfile.write(payload)
            if truncate:
                self.close_connection = True
            return
        if _Handler.fail_first > 0:
            _Handler.fail_first -= 1
            self.send_response(503)
            self.end_headers()
            return
        _Handler.bodies.append((self.headers.get("Authorization"), body))
        payload = json.dumps(ack_for(body)).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


def _server():
    _Handler.bodies = []
    _Handler.script = []
    _Handler.seen = []
    srv = HTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def _rec(i):
    return {"recordType": "READING", "sequence": i, "runId": "r", "sessionId": "s", "param": "RF_ANTENNA",
            "receivedAt": f"2026-11-03T10:00:00.{i:03d}Z", "mode": "SIMULATOR", "valueStatus": "OK"}


def test_http_sink_retries_and_sends_in_order():
    srv = _server()
    _Handler.fail_first = 1

    async def scenario():
        sink = HttpBatchSink(f"http://127.0.0.1:{srv.server_port}/ingest", "c1", "tok",
                             batch_size=3, flush_interval_s=0.05)
        for i in range(1, 8):
            await sink.emit(_rec(i))
        for _ in range(100):
            if sink.sent == 7:
                break
            await asyncio.sleep(0.05)
        await sink.close()
        return sink

    sink = asyncio.run(scenario())
    srv.shutdown()
    assert sink.sent == 7 and sink.failures >= 1
    seqs = [r["sequence"] for _, body in _Handler.bodies for r in body["records"]]
    assert seqs == list(range(1, 8))
    assert _Handler.bodies[0][0] == "Bearer tok"


def _error(code):
    return json.dumps({"code": code, "message": "시험", "requestId": "req-1"}).encode()


def _run_sink(srv, n, *, until, **kw):
    async def scenario():
        sink = HttpBatchSink(f"http://127.0.0.1:{srv.server_port}/ingest", "c1", "tok",
                             batch_size=2, flush_interval_s=0.02, **kw)
        for i in range(1, n + 1):
            await sink.emit(_rec(i))
        for _ in range(200):
            if until(sink):
                break
            await asyncio.sleep(0.02)
        await sink.close()
        return sink
    return asyncio.run(scenario())


def test_http_sink_skips_rejected_batch_and_keeps_sending(tmp_path):
    srv = _server()
    _Handler.script = [(422, _error("COLLECTOR_RECORD_INVALID"), {})]
    warnings = []
    rejected = tmp_path / "rejected.jsonl"
    sink = _run_sink(srv, 5, until=lambda s: s.sent + s.rejected == 5,
                     rejected_path=rejected, warn=warnings.append)
    srv.shutdown()
    # 거절된 첫 묶음(1,2)은 다시 보내지 않고 다음 기록으로 넘어간다
    assert _Handler.seen == [[1, 2], [3, 4], [5]]
    assert sink.rejected == 2 and sink.sent == 3
    line = json.loads(rejected.read_text(encoding="utf-8").splitlines()[0])
    assert line["status"] == 422 and line["code"] == "COLLECTOR_RECORD_INVALID"
    assert line["requestId"] == "req-1"
    assert [r["sequence"] for r in line["batch"]["records"]] == [1, 2]
    assert "거절" in warnings[0]


def test_http_sink_rejects_non_json_proxy_413_by_status():
    srv = _server()
    _Handler.script = [(413, b"<html>Request Entity Too Large</html>", {})]
    sink = _run_sink(srv, 2, until=lambda s: s.rejected == 2, warn=lambda m: None)
    srv.shutdown()
    assert sink.rejected == 2 and sink.last_error == "413"
    assert _Handler.seen == [[1, 2]]


def test_http_sink_keeps_records_on_401_and_retries():
    srv = _server()
    _Handler.script = [(401, _error("COLLECTOR_UNAUTHENTICATED"), {})]
    warnings = []
    sink = _run_sink(srv, 2, until=lambda s: s.sent == 2, auth_backoff_s=0.05, warn=warnings.append)
    srv.shutdown()
    assert sink.sent == 2 and sink.rejected == 0
    assert _Handler.seen == [[1, 2], [1, 2]]
    assert "401" in warnings[0]


def test_http_sink_retries_429_with_retry_after():
    srv = _server()
    _Handler.script = [(429, b"", {"Retry-After": "0"}), (503, _error("COLLECTOR_UNAVAILABLE"), {})]
    sink = _run_sink(srv, 2, until=lambda s: s.sent == 2, warn=lambda m: None)
    srv.shutdown()
    assert sink.sent == 2 and sink.rejected == 0 and sink.failures == 2
    assert _Handler.seen == [[1, 2], [1, 2], [1, 2]]


def test_http_sink_retries_when_error_body_read_fails():
    # 오류 응답 본문을 읽다가 연결이 끊겨도 전송 작업이 끝나지 않고 같은 묶음을 다시 보낸다
    srv = _server()
    _Handler.script = [(422, b'{"code":', {TRUNCATE: "1"})]
    sink = _run_sink(srv, 2, until=lambda s: s.sent == 2, warn=lambda m: None)
    srv.shutdown()
    assert sink.sent == 2 and sink.rejected == 0 and sink.failures == 1
    assert sink.last_error is None
    assert _Handler.seen == [[1, 2], [1, 2]]


def test_http_sink_retries_when_success_body_read_fails():
    srv = _server()
    _Handler.script = [(202, b"{}", {TRUNCATE: "1"})]
    sink = _run_sink(srv, 2, until=lambda s: s.sent == 2, warn=lambda m: None)
    srv.shutdown()
    assert sink.sent == 2 and sink.failures == 1
    assert _Handler.seen == [[1, 2], [1, 2]]


def test_http_sink_keeps_sending_when_rejected_file_cannot_be_written(tmp_path):
    # 거절 기록 경로가 폴더라 저장이 실패해도 경고만 하고 다음 묶음을 계속 보낸다
    srv = _server()
    _Handler.script = [(422, _error("COLLECTOR_RECORD_INVALID"), {})]
    warnings = []
    sink = _run_sink(srv, 5, until=lambda s: s.sent + s.rejected == 5,
                     rejected_path=tmp_path, warn=warnings.append)
    srv.shutdown()
    assert _Handler.seen == [[1, 2], [3, 4], [5]]
    assert sink.rejected == 2 and sink.sent == 3
    assert any("거절 기록을 저장하지 못했습니다" in w for w in warnings)
    assert any("거절 기록도 저장하지 못했습니다" in w for w in warnings)


def test_http_sink_waits_full_retry_after():
    # Retry-After: 120이면 상한(30초·60초)으로 자르지 않고 120초를 기다린다
    srv = _server()
    _Handler.script = [(429, b"", {"Retry-After": "120"})]
    waits = []

    async def scenario():
        sink = HttpBatchSink(f"http://127.0.0.1:{srv.server_port}/ingest", "c1", "tok",
                             batch_size=2, flush_interval_s=0.02, warn=lambda m: None)

        async def fake_sleep(seconds):
            waits.append(seconds)
            await asyncio.sleep(min(seconds, 0.02))

        sink._sleep = fake_sleep
        for i in range(1, 3):
            await sink.emit(_rec(i))
        for _ in range(200):
            if sink.sent == 2:
                break
            await asyncio.sleep(0.02)
        await sink.close()
        return sink

    sink = asyncio.run(scenario())
    srv.shutdown()
    assert sink.sent == 2
    assert 120.0 in waits
    assert _Handler.seen == [[1, 2], [1, 2]]


def test_next_wait_keeps_server_retry_after_and_caps_own_backoff():
    kw = {"max_backoff_s": 30.0, "auth_backoff_s": 60.0}
    assert _next_wait(429, 120.0, 1.0, **kw) == (120.0, 2.0)
    assert _next_wait(503, 3600.0, 16.0, **kw) == (3600.0, 30.0)
    assert _next_wait(None, None, 16.0, **kw) == (16.0, 30.0)
    assert _next_wait(503, None, 30.0, **kw) == (30.0, 30.0)
    assert _next_wait(401, None, 4.0, **kw) == (60.0, 4.0)
    assert _next_wait(401, 120.0, 4.0, **kw) == (120.0, 4.0)


def test_http_sink_drops_oldest_when_full():
    async def scenario():
        sink = HttpBatchSink("http://127.0.0.1:9/ingest", "c1", max_queue=3, flush_interval_s=10)
        for i in range(1, 6):
            await sink.emit(_rec(i))
        queued = [r["sequence"] for r in sink.state.pending(10)]
        sink._task.cancel()
        return sink, queued

    sink, queued = asyncio.run(scenario())
    assert queued == [3, 4, 5] and sink.dropped == 2


def test_jsonl_and_summary(tmp_path):
    path = tmp_path / "run.jsonl"

    async def scenario():
        sink = JsonlSink(path)
        for i in range(1, 5):
            await sink.emit(_rec(i))
        await sink.close()

    asyncio.run(scenario())
    records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    s = summarize(records)
    assert s["samples"] == 4 and s["sequenceOrdered"] is True
    assert s["sampleGapS"]["max"] == 0.001


def test_http_sink_warns_once_when_dropping_and_counts_total():
    """한도를 넘기 시작할 때 한 번 알리고, 서버에 알린 뒤 0이 되는 droppedSinceLast와 별개로 총수를 센다."""
    async def scenario():
        warnings = []
        sink = HttpBatchSink("http://127.0.0.1:9/ingest", "c1", max_queue=3, flush_interval_s=10,
                             warn=warnings.append)
        for i in range(1, 7):
            await sink.emit(_rec(i))
        sink.state.acknowledge([4])  # 서버가 받은 것처럼 상태의 버린 수를 0으로 만든다
        await sink.emit(_rec(7))
        await sink.emit(_rec(8))
        sink._task.cancel()
        return sink, warnings

    sink, warnings = asyncio.run(scenario())
    assert sink.dropped_total == 4
    assert sum("버립니다" in w for w in warnings) == 1


def test_http_sink_reports_recovery_with_total_and_sends_dropped_count():
    srv = _server()
    _Handler.script = [(503, b"", {})]

    async def scenario():
        warnings = []
        sink = HttpBatchSink(f"http://127.0.0.1:{srv.server_port}/ingest", "c1", "tok",
                             batch_size=10, flush_interval_s=0.02, max_queue=3, warn=warnings.append)
        for i in range(1, 6):
            await sink.emit(_rec(i))
        for _ in range(200):
            if sink.sent == 3:
                break
            await asyncio.sleep(0.02)
        await sink.close()
        return sink, warnings

    sink, warnings = asyncio.run(scenario())
    srv.shutdown()
    assert sink.sent == 3 and sink.dropped_total == 2
    assert [r["sequence"] for r in _Handler.bodies[0][1]["records"]] == [3, 4, 5]
    assert _Handler.bodies[0][1]["droppedSinceLast"] == 2
    assert sum("버립니다" in w for w in warnings) == 1
    assert any("다시 보내고 있습니다" in w and "2건" in w for w in warnings)

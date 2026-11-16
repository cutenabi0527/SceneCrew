"""웹 연결 수집(#399) 시험. 가짜 업무 서버와 시뮬레이터 수신기 2대를 쓴다. 실기 검증이 아니다."""

import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

import app
from observation import RecordFactory, Sequence
from simulator import QlxdSimulator
from sinks import HttpBatchSink
from state import MemoryState, SqliteState
from web_link import (Credentials, Discovery, FixedReceivers, LinkApi, Receiver, ServerError, Supervisor,
                      normalize_code, receiver_key)

CODE = "ABCD-EFGH"
TOKEN = "secret-token"


class FakeServer:
    """업무 서버의 수집기 경로 네 개만 흉내 낸다."""

    def __init__(self):
        self.links: set[str] = set()
        self.revoked = False
        self.forbidden = False
        self.pairs: list[dict] = []
        self.reports: list[dict] = []
        self.records: list[dict] = []
        self.unmapped = 0
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _reply(self, status, body):
                data = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def _body(self):
                return json.loads(self.rfile.read(int(self.headers["Content-Length"])) or b"{}")

            def _authed(self):
                if self.headers.get("Authorization") != f"Bearer {TOKEN}" or fake.revoked:
                    self._reply(401, {"code": "COLLECTOR_UNAUTHENTICATED"})
                    return False
                if fake.forbidden:
                    self._reply(403, {"code": "COLLECTOR_LINK_FORBIDDEN"})
                    return False
                return True

            def do_POST(self):
                body = self._body()
                if self.path == "/internal/equipment-collectors/pair":
                    fake.pairs.append(body)
                    if body.get("code") != CODE:
                        return self._reply(401, {"code": "COLLECTOR_UNAUTHENTICATED"})
                    return self._reply(200, {"collectorId": "web-1", "token": TOKEN, "runId": "run-1"})
                if self.path == "/internal/equipment-collectors/web-1/observations":
                    if not self._authed():
                        return
                    records = body["records"]
                    if any(r["sourceRef"] not in fake.links for r in records):
                        fake.unmapped += 1
                        return self._reply(422, {"code": "COLLECTOR_SOURCE_UNMAPPED"})
                    fake.records.extend(records)
                    return self._reply(200, {"collectorId": "web-1", "receivedAt": "2026-11-13T00:00:00Z",
                                             "accepted": len(records), "duplicates": 0,
                                             "runs": [{"runId": "run-1",
                                                       "lastSequence": max(r["sequence"] for r in records)}]})
                self._reply(404, {"code": "NOT_FOUND"})

            def do_PUT(self):
                body = self._body()
                if self.path != "/internal/equipment-collectors/web-1/receivers":
                    return self._reply(404, {"code": "NOT_FOUND"})
                if not self._authed():
                    return
                fake.reports.append(body)
                links = [{"receiverKey": k, "channel": 1} for k in sorted(fake.links)]
                self._reply(200, {"collectorId": "web-1", "receivers": len(body["receivers"]), "links": links})

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()

    def sources(self):
        return {r["sourceRef"] for r in self.records}


@pytest.fixture
def server():
    s = FakeServer()
    yield s
    s.close()


@pytest.fixture
def home(tmp_path, monkeypatch):
    monkeypatch.setenv("SCENECREW_COLLECTOR_HOME", str(tmp_path))
    return tmp_path


def cred(server):
    return Credentials(server.url, "web-1", TOKEN, "run-1")


async def _until(predicate, timeout=5.0):
    end = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < end:
        if predicate():
            return True
        await asyncio.sleep(0.05)
    return False


FAST = dict(meter_rate_ms=100, stale_after_s=0.4, stall_reconnect_s=1.0, status_interval_s=0.2,
            backoff_initial_s=0.05, backoff_max_s=0.2, connect_timeout_s=1.0)


async def _with_two_receivers(server, body, tmp_path):
    sims = [QlxdSimulator(), QlxdSimulator()]
    ports = [await s.start() for s in sims]
    source = FixedReceivers([("127.0.0.1", p) for p in ports])
    state = SqliteState(tmp_path / "state.db", "web-1", "run-1")
    sup = Supervisor(LinkApi(server.url, timeout_s=2), cred(server), source, state, mode="SIMULATOR",
                     report_interval_s=0.2, out=lambda m: None, collector_options=FAST,
                     sink_options={"flush_interval_s": 0.05})
    stop = asyncio.Event()
    task = asyncio.create_task(sup.run(stop))
    try:
        return await body(sup, stop, task, [f"sim-{p}" for p in ports])
    finally:
        stop.set()
        await asyncio.wait_for(task, 10)
        state.close()
        for s in sims:
            await s.stop()


def test_code_exchange_saves_connection_in_user_folder_only(server, home):
    api = LinkApi(server.url)
    answers = iter(["wrong-code", "abcd efgh"])
    said = []
    got = app.ask_code(api, read=lambda _: next(answers), out=said.append)
    assert got == cred(server)
    # 대소문자·공백을 맞춰 보내고, 버전·노트북 이름을 함께 보낸다. IP 칸은 없다
    assert server.pairs[-1]["code"] == CODE
    assert set(server.pairs[-1]) == {"code", "programVersion", "hostLabel"}
    assert any("맞지 않거나 만료" in s for s in said)
    got.save()
    assert Credentials.load() == got
    assert Credentials.path().parent == home
    Credentials.forget()
    assert Credentials.load() is None


def test_receiver_report_sends_no_address_and_only_linked_receivers_are_collected(server, tmp_path):
    async def body(sup, stop, task, keys):
        assert await _until(lambda: server.reports)
        sent = server.reports[-1]["receivers"]
        assert {r["receiverKey"] for r in sent} == set(keys)
        assert all(set(r) == {"receiverKey", "name", "model", "channels"} for r in sent)
        assert not sup.running and not server.records  # 연결 전에는 수집하지 않는다
        server.links = {keys[0]}
        assert await _until(lambda: server.sources() == {keys[0]})
        server.links = set(keys)
        assert await _until(lambda: server.sources() == set(keys))
        # 같은 run에서 수신기 두 대가 sequence를 나눠 쓰므로 겹치지 않는다
        seqs = [r["sequence"] for r in server.records]
        assert len(seqs) == len(set(seqs))
        assert {r["runId"] for r in server.records} == {"run-1"}
        server.links = {keys[1]}
        assert await _until(lambda: keys[0] not in sup.running)
        # 서버에서 막 해제된 수신기 기록이 섞여 422가 와도 묶음을 거절 표로 버리지 않고, 걸러서 다시 보낸다
        before = len([r for r in server.records if r["sourceRef"] == keys[1]])
        assert await _until(lambda: len([r for r in server.records if r["sourceRef"] == keys[1]]) > before + 5)
        assert sup.http.rejected == 0

    asyncio.run(_with_two_receivers(server, body, tmp_path))


def test_revoked_token_stops_collection(server, tmp_path):
    async def body(sup, stop, task, keys):
        server.links = {keys[0]}
        assert await _until(lambda: server.records)
        server.revoked = True
        reason = await asyncio.wait_for(task, 10)
        assert reason is not None and reason.status == 401
        assert not sup.running

    asyncio.run(_with_two_receivers(server, body, tmp_path))


def test_lost_qualification_stops_collection(server, tmp_path):
    async def body(sup, stop, task, keys):
        server.links = {keys[0]}
        assert await _until(lambda: server.records)
        server.forbidden = True
        reason = await asyncio.wait_for(task, 10)
        assert (reason.status, reason.code) == (403, "COLLECTOR_LINK_FORBIDDEN")

    asyncio.run(_with_two_receivers(server, body, tmp_path))


def test_app_stops_with_guidance_and_forgets_connection_when_unlinked(server, home, capsys):
    """실행 파일 흐름: 저장한 연결로 붙고, WEB에서 해제돼 토큰이 폐기되면 안내하고 연결 정보를 지운다(운영 확인에서 찾은 문제)."""
    cred(server).save()

    async def scenario():
        sim = QlxdSimulator()
        port = await sim.start()
        server.links = {f"sim-{port}"}
        args = app.parse_args(["--sim", f"127.0.0.1:{port}"])

        async def revoke_later():
            await _until(lambda: server.records, timeout=15)
            server.revoked = True

        revoker = asyncio.create_task(revoke_later())
        try:
            return await asyncio.wait_for(app.collect(Credentials.load(), args, out=print), 40)
        finally:
            revoker.cancel()
            await sim.stop()

    reason = asyncio.run(scenario())
    assert reason.status == 401
    assert "보낸 기록" in capsys.readouterr().out


def test_main_forgets_connection_after_server_stop(server, home, monkeypatch, capsys):
    cred(server).save()

    async def stopped(cred_, args, out=print):
        return ServerError(401, "COLLECTOR_UNAUTHENTICATED", "x")

    monkeypatch.setattr(app, "collect", stopped)
    assert app.main(["--sim", "127.0.0.1:1"]) == 3
    assert Credentials.load() is None
    assert "다시 연결하세요" in capsys.readouterr().out


def test_sink_drops_unlinked_records_without_resetting_dropped_count(server):
    async def body():
        server.links = {"a"}
        state = MemoryState("web-1", "run-1")
        state.dropped = 3
        sink = HttpBatchSink(server.url + "/internal/equipment-collectors/web-1/observations", "web-1", TOKEN,
                             state=state, allowed=lambda ref: ref in server.links, flush_interval_s=0.05,
                             on_auth_failure=lambda *a: None)
        seq = Sequence()
        for ref in ("a", "b", "a"):
            await sink.emit(RecordFactory("web-1", ref, "SIMULATOR", run_id="run-1", counter=seq).link(None, "OFFLINE"))
        assert await _until(lambda: len(server.records) == 2)
        await sink.close()
        assert sink.unlinked_dropped == 1 and server.unmapped == 0
        assert [r["sequence"] for r in server.records] == [1, 3]

    asyncio.run(body())


def test_server_unreachable_keeps_running_and_reports_later(tmp_path):
    api = LinkApi("http://127.0.0.1:9", timeout_s=0.5)
    with pytest.raises(ServerError) as e:
        api.report(Credentials("x", "web-1", TOKEN, "run-1"), [])
    assert e.value.status is None


def test_discovery_keys_are_opaque_and_ignore_other_devices():
    d = Discovery([])
    d.heard("169.254.10.20", "(acn-fctn=QLXD: Rx),(cid=AB12-CD34)")
    d.heard("169.254.10.21", "(acn-fctn=ULXD: Rx)")  # QLXD가 아니면 무시
    d.heard("169.254.10.22", "(acn-fctn=ANI4IN)")
    found = d.receivers()
    assert [r.key for r in found] == ["qlxd4-ab12cd34"]
    assert "169.254" not in json.dumps([r.payload() for r in found])
    assert receiver_key(None, "169.254.1.1").startswith("qlxd4-") and "169" not in receiver_key(None, "169.254.1.1")


def test_discovery_remembers_adapter_per_receiver_and_names_differ():
    """실기(#399): 수신기 2대를 USB 랜 어댑터 2개에 하나씩 붙이면, 받은 어댑터로 접속해야 한다. 장치 ID 끝자리는 같았다."""
    d = Discovery(["169.254.254.51", "169.254.5.231"])
    d.heard("169.254.19.111", "(acn-fctn=QLXD: Rx),(cid=DD496F12-0000-11DD-A000-000EDDCCCCCC)", "169.254.254.51")
    d.heard("169.254.169.248", "(acn-fctn=QLXD: Rx),(cid=DD45F8A8-0000-11DD-A000-000EDDCCCCCC)", "169.254.5.231")
    by_host = {r.host: r for r in d.receivers()}
    assert by_host["169.254.19.111"].source_ip == "169.254.254.51"
    assert by_host["169.254.169.248"].source_ip == "169.254.5.231"
    assert {r.name for r in by_host.values()} == {"QLXD4 DD496F12", "QLXD4 DD45F8A8"}
    # 어댑터를 모르면(어댑터 구분 없는 소켓) OS 경로에 맡긴다
    d.heard("169.254.1.1", "(acn-fctn=QLXD: Rx),(cid=AA000000-1)")
    assert [r.source_ip for r in d.receivers() if r.host == "169.254.1.1"] == [None]


def test_discovery_reads_adapters_again_when_not_fixed():
    found = [[], ["169.254.9.9"]]
    d = Discovery(None, find_ifaces=lambda: found[0])
    assert d.ifaces == []
    found[0] = found[1]
    assert d.ifaces == ["169.254.9.9"]


def test_normalize_code():
    assert normalize_code(" abcd-efgh ") == CODE
    assert normalize_code("abcdefgh") == CODE


def test_receiver_payload_shape():
    r = Receiver(key="k", name="n", model="QLXD4", host="169.254.1.1")
    assert r.payload() == {"receiverKey": "k", "name": "n", "model": "QLXD4", "channels": 1}

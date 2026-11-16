"""시뮬레이터를 상대로 한 수집기 통합 시험. 실기 검증이 아니다."""

import asyncio

from client import CollectorConfig, QlxdCollector
from observation import RecordFactory
from simulator import QlxdSimulator
from sinks import MemorySink


def _config(port: int, **kw) -> CollectorConfig:
    base = dict(host="127.0.0.1", port=port, meter_rate_ms=100, stale_after_s=0.4,
                stall_reconnect_s=1.0, status_interval_s=0.2, backoff_initial_s=0.05,
                backoff_max_s=0.2, connect_timeout_s=1.0, wait_for_announce=False)
    base.update(kw)
    return CollectorConfig(**base)


async def _wait_for(predicate, timeout=3.0):
    loop = asyncio.get_running_loop()
    end = loop.time() + timeout
    while loop.time() < end:
        if predicate():
            return True
        await asyncio.sleep(0.02)
    return False


class Harness:
    def __init__(self, fragment=False, **cfg):
        self.sim = QlxdSimulator(fragment=fragment)
        self.cfg = cfg
        self.sink = MemorySink()
        self.stop = asyncio.Event()

    async def __aenter__(self):
        port = await self.sim.start()
        factory = RecordFactory(collector_id="test", source_ref="qlxd4-test", mode="SIMULATOR")
        self.collector = QlxdCollector(_config(port, **self.cfg), factory, self.sink)
        self.task = asyncio.create_task(self.collector.run(self.stop))
        return self

    async def __aexit__(self, *exc):
        self.stop.set()
        await asyncio.wait_for(self.task, 3)
        await self.sim.stop()

    def samples(self):
        return [r for r in self.sink.of("READING") if r["param"] == "RF_ANTENNA"]

    def statuses(self):
        return self.sink.of("STATUS")

    def reading(self, param):
        found = [r for r in self.sink.of("READING") if r["param"] == param]
        return found[-1] if found else None


def run(coro):
    return asyncio.run(coro)


def test_normal_collection_and_clean_shutdown():
    async def scenario():
        async with Harness() as h:
            await h.sim.set_speaking(True)
            assert await _wait_for(lambda: len(h.samples()) >= 4)
            assert await _wait_for(lambda: h.statuses()[-1]["metering"] == "FRESH")
            assert h.reading("FW_VER")["valueStatus"] == "OK"
            assert h.reading("TX_TYPE")["value"] == "QLXD1"
            assert h.reading("AUDIO_LEVEL")["value"] >= 25
        # 허용한 명령만 보냈고 종료 때 계측을 껐다.
        sent = [c.decode() for c in h.collector.sent_commands]
        assert all(c.startswith("< GET ") or "METER_RATE" in c for c in sent)
        assert sent[-1] == "< SET 1 METER_RATE 00000 >"
        assert h.sim.refused_writes == []
        assert h.sink.of("LINK")[-1]["linkState"] == "OFFLINE"
        seqs = [r["sequence"] for r in h.sink.records]
        assert seqs == sorted(seqs) and len(set(seqs)) == len(seqs)
        assert h.sink.of("UNPARSED") == []

    run(scenario())


def test_transmitter_off_is_not_zero_battery():
    async def scenario():
        async with Harness() as h:
            assert await _wait_for(lambda: len(h.samples()) >= 2)
            await h.sim.set_tx(False)
            assert await _wait_for(lambda: h.reading("TX_TYPE")["valueStatus"] == "UNKNOWN_VALUE")
            batt = h.reading("BATT_BARS")
            assert (batt["valueStatus"], batt["value"], batt["raw"]) == ("UNAVAILABLE", None, "255")
            assert await _wait_for(lambda: h.samples()[-1]["value"] == "XX")
            # 송신기가 꺼져도 수집 자체는 최신이다. 음향 상태와 수집 상태를 섞지 않는다.
            assert h.statuses()[-1]["metering"] == "FRESH"
            await h.sim.set_tx(True)
            assert await _wait_for(lambda: h.reading("BATT_BARS")["value"] == 4)

    run(scenario())


def test_link_drop_goes_offline_and_reconnects_with_new_session():
    async def scenario():
        async with Harness() as h:
            assert await _wait_for(lambda: len(h.samples()) >= 2)
            first_session = h.samples()[-1]["sessionId"]
            await h.sim.drop_clients()
            assert await _wait_for(lambda: any(r["linkState"] == "OFFLINE" for r in h.sink.of("LINK")))
            offline = [s for s in h.statuses() if s["linkState"] == "OFFLINE"]
            assert offline and offline[0]["metering"] == "OFFLINE"
            assert await _wait_for(lambda: h.samples()[-1]["sessionId"] != first_session)
            assert h.collector.sessions >= 2
            assert h.sim.connections >= 2

    run(scenario())


def test_offline_does_not_refresh_last_sample_time():
    async def scenario():
        sim = QlxdSimulator()
        port = await sim.start()
        sink = MemorySink()
        stop = asyncio.Event()
        collector = QlxdCollector(_config(port), RecordFactory(collector_id="t", source_ref="s", mode="SIMULATOR"), sink)
        task = asyncio.create_task(collector.run(stop))
        assert await _wait_for(lambda: collector.freshness.last_sample_at is not None)
        await sim.stop()  # 서버까지 내려 재연결이 실패하게 한다.
        assert await _wait_for(lambda: collector.freshness.link != "CONNECTED")
        before = collector.freshness.last_sample_at
        mark = len(sink.records)
        await asyncio.sleep(0.6)
        after = [s for s in sink.records[mark:] if s["recordType"] == "STATUS"]
        assert len(after) >= 2
        assert all(s["lastSampleAt"] == before for s in after)
        assert all(s["metering"] == "OFFLINE" for s in after)
        # Windows는 닫힌 포트에 SYN을 재시도해 거절 대신 시간 초과로 끝날 수 있다.
        assert await _wait_for(lambda: any((r["reason"] or "").startswith(("connect failed", "connect timeout"))
                                           for r in sink.of("LINK")))
        assert all(s["lastSampleAt"] == before for s in sink.of("STATUS")[-3:])
        stop.set()
        await asyncio.wait_for(task, 3)

    run(scenario())


def test_stall_turns_stale_then_reconnects():
    async def scenario():
        async with Harness() as h:
            assert await _wait_for(lambda: len(h.samples()) >= 2)
            h.sim.stall(1.6)
            assert await _wait_for(lambda: h.statuses()[-1]["metering"] == "STALE"
                                   and h.statuses()[-1]["linkState"] == "CONNECTED")
            assert await _wait_for(lambda: any(r["reason"] == "no data (stall)" for r in h.sink.of("LINK")))
            assert await _wait_for(lambda: h.statuses()[-1]["metering"] == "FRESH", timeout=4)

    run(scenario())


def test_fragmented_stream_parses_without_loss():
    async def scenario():
        async with Harness(fragment=True) as h:
            assert await _wait_for(lambda: len(h.samples()) >= 5)
            assert h.sink.of("UNPARSED") == []
            assert h.reading("CHAN_NAME")["value"] == "VOCAL1"

    run(scenario())


def test_no_connect_until_receiver_announces(monkeypatch):
    """수신기가 자동 주소를 확인하는 중에 접속하면 주소 충돌로 주소가 바뀌었다(실기 캡처). 알림 전에는 접속하지 않는다."""
    import client

    calls = []

    def fake_wait(host, iface_ip, timeout_s, stop_check):
        calls.append(host)
        time_ok = len(calls) >= 3  # 세 번째 대기에서야 알림을 듣는다
        return time_ok

    monkeypatch.setattr(client, "wait_for_announcement", fake_wait)

    async def scenario():
        sim = QlxdSimulator()
        port = await sim.start()
        sink = MemorySink()
        stop = asyncio.Event()
        collector = QlxdCollector(_config(port, wait_for_announce=True),
                                  RecordFactory(collector_id="t", source_ref="s", mode="SIMULATOR"), sink)
        task = asyncio.create_task(collector.run(stop))
        assert await _wait_for(lambda: collector.freshness.last_sample_at is not None)
        stop.set()
        await asyncio.wait_for(task, 3)
        await sim.stop()
        return sink, sim

    sink, sim = run(scenario())
    links = [(r["linkState"], r["reason"]) for r in sink.of("LINK")]
    assert links[0][0] == "OFFLINE" and links[0][1].startswith("receiver not announcing")
    first_connecting = next(i for i, (s, _) in enumerate(links) if s == "CONNECTING")
    assert len(calls) >= 3 and first_connecting == 1
    assert sim.connections == 1


def test_keep_metering_option_skips_meter_off():
    async def scenario():
        async with Harness(meter_off_on_exit=False) as h:
            assert await _wait_for(lambda: len(h.samples()) >= 1)
        assert b"< SET 1 METER_RATE 00000 >" not in h.collector.sent_commands

    run(scenario())

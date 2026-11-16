import asyncio
import json

import pytest

import collect
from observation import RecordFactory
from sinks import HttpBatchSink, _check_ack
from state import SqliteState, StateError
from test_sinks import _Handler, _server, ack_for

RUN = "11111111-1111-4111-8111-111111111111"
OTHER_RUN = "22222222-2222-4222-8222-222222222222"


def _records(factory: RecordFactory, n: int) -> list[dict]:
    return [factory.link("s", "CONNECTED") for _ in range(n)]


def test_state_keeps_run_sequence_and_pending_across_restart(tmp_path):
    path = tmp_path / "state.sqlite"
    state = SqliteState(path, "c1", RUN)
    factory = RecordFactory("c1", "src", "LIVE", run_id=state.run_id, start_sequence=state.high_water)
    for r in _records(factory, 3):
        state.enqueue(r)
    state.acknowledge([1])
    state.close()

    again = SqliteState(path, "c1")  # run을 다시 지정하지 않아도 저장된 run을 쓴다
    assert again.run_id == RUN and again.high_water == 3
    assert [r["sequence"] for r in again.pending(10)] == [2, 3]
    factory = RecordFactory("c1", "src", "LIVE", run_id=again.run_id, start_sequence=again.high_water)
    # 재시작 뒤 번호는 저장된 상한 다음부터다. 서버 값으로 추정하지 않는다.
    assert factory.link("s", "CONNECTED")["sequence"] == 4
    again.close()


def test_state_run_rules(tmp_path):
    with pytest.raises(StateError, match="runId"):
        SqliteState(tmp_path / "a.sqlite", "c1")  # 실제 전송은 등록된 run이 필요하다
    generated = SqliteState(tmp_path / "b.sqlite", "c1", allow_generated_run=True)  # 시뮬레이터
    assert len(generated.run_id) == 36
    generated.close()
    SqliteState(tmp_path / "c.sqlite", "c1", RUN).close()
    with pytest.raises(StateError, match="run"):
        SqliteState(tmp_path / "c.sqlite", "c1", OTHER_RUN)  # 임의로 run을 바꾸지 않는다
    with pytest.raises(StateError, match="수집기"):
        SqliteState(tmp_path / "c.sqlite", "c2", RUN)


@pytest.mark.parametrize("content", [b"not a sqlite file at all, just bytes", b""])
def test_damaged_state_file_stops_without_new_run(tmp_path, content):
    path = tmp_path / "state.sqlite"
    path.write_bytes(content)
    with pytest.raises(StateError):
        SqliteState(path, "c1", RUN, allow_generated_run=True)


def test_overflow_drops_oldest_and_counts_until_acknowledged(tmp_path):
    state = SqliteState(tmp_path / "s.sqlite", "c1", RUN, max_queue=3)
    factory = RecordFactory("c1", "src", "LIVE", run_id=RUN)
    for r in _records(factory, 5):
        state.enqueue(r)
    assert [r["sequence"] for r in state.pending(10)] == [3, 4, 5]
    assert state.dropped == 2 and state.high_water == 5
    state.acknowledge([3])
    assert state.dropped == 0
    state.close()


def test_quarantine_moves_batch_out_of_pending(tmp_path):
    state = SqliteState(tmp_path / "s.sqlite", "c1", RUN)
    factory = RecordFactory("c1", "src", "LIVE", run_id=RUN)
    for r in _records(factory, 3):
        state.enqueue(r)
    state.quarantine([1, 2], {"rejectedAt": "2026-11-04T00:00:00Z", "status": 422, "code": "X",
                              "requestId": "req", "batch": {"records": []}})
    assert [r["sequence"] for r in state.pending(10)] == [3]
    assert state.rejected_count() == 1
    state.close()


def test_record_from_other_run_is_refused(tmp_path):
    state = SqliteState(tmp_path / "s.sqlite", "c1", RUN)
    with pytest.raises(StateError):
        state.enqueue(RecordFactory("c1", "src", "LIVE", run_id=OTHER_RUN).link("s", "CONNECTED"))
    state.close()


def _sink(srv, state, **kw):
    return HttpBatchSink(f"http://127.0.0.1:{srv.server_port}/ingest", "c1", "tok", batch_size=2,
                         flush_interval_s=0.02, state=state, warn=kw.pop("warn", lambda m: None), **kw)


async def _until(predicate, tries=200):
    for _ in range(tries):
        if predicate():
            return
        await asyncio.sleep(0.02)


def test_restart_replays_pending_records_unchanged(tmp_path):
    path = tmp_path / "s.sqlite"

    async def first_run():
        # 서버가 없는 동안 기록이 쌓인다
        state = SqliteState(path, "c1", RUN)
        sink = HttpBatchSink("http://127.0.0.1:9/ingest", "c1", state=state, flush_interval_s=10, timeout_s=0.2)
        factory = RecordFactory("c1", "src", "LIVE", run_id=RUN, start_sequence=state.high_water)
        for r in _records(factory, 3):
            await sink.emit(r)
        sink._task.cancel()
        stored = state.pending(10)
        state.close()
        return stored

    stored = asyncio.run(first_run())
    srv = _server()

    async def second_run():
        state = SqliteState(path, "c1")
        sink = _sink(srv, state)
        sink.start()  # 새 기록 없이도 남은 기록을 보낸다
        await _until(lambda: state.pending_count() == 0)
        await sink.close()
        state.close()
        return sink

    sink = asyncio.run(second_run())
    srv.shutdown()
    sent = [r for _, body in _Handler.bodies for r in body["records"]]
    assert sent == stored  # 바꾸지 않고 그대로 다시 보낸다
    assert sink.sent == 3


def test_ack_mismatch_keeps_records_until_a_valid_ack(tmp_path):
    srv = _server()
    state = SqliteState(tmp_path / "s.sqlite", "c1", RUN)
    factory = RecordFactory("c1", "src", "LIVE", run_id=RUN)
    batch = _records(factory, 2)
    wrong = json.dumps({**ack_for({"collectorId": "c1", "records": batch}), "accepted": 1}).encode()
    low = ack_for({"collectorId": "c1", "records": batch})
    low["runs"][0]["lastSequence"] = 1
    _Handler.script = [(200, wrong, {}), (200, json.dumps(low).encode(), {}), (200, b"", {})]
    warnings = []

    async def scenario():
        sink = _sink(srv, state, warn=warnings.append, max_backoff_s=0.05)
        sink._sleep = lambda s: asyncio.sleep(min(s, 0.02))
        for r in batch:
            await sink.emit(r)
        await _until(lambda: state.pending_count() == 0)
        await sink.close()
        return sink

    sink = asyncio.run(scenario())
    srv.shutdown()
    # 틀린 ACK 세 번 동안 같은 묶음을 지우지 않고 다시 보냈고, 맞는 ACK 뒤에야 지웠다
    assert _Handler.seen == [[1, 2]] * 4
    assert state.pending_count() == 0 and sink.sent == 2
    assert sum("확인 응답" in w for w in warnings) == 3
    state.close()


def test_check_ack_counts_duplicates_and_rejects_bad_shapes():
    batch = [{"runId": RUN, "sequence": 1}, {"runId": RUN, "sequence": 2}]
    body = {"collectorId": "c1", "records": batch}
    assert _check_ack(json.dumps(ack_for(body, duplicates=1)).encode(), "c1", batch) == (None, 1)
    for bad in [
        {**ack_for(body), "collectorId": "c2"},
        {**ack_for(body), "accepted": True},
        {**ack_for(body), "accepted": "2"},
        {**ack_for(body), "runs": []},
        {**ack_for(body), "runs": [{"runId": OTHER_RUN, "lastSequence": 9}]},
    ]:
        assert _check_ack(json.dumps(bad).encode(), "c1", batch)[0] is not None
    assert _check_ack(b"<html>ok</html>", "c1", batch)[0] == "본문 없음"


def test_cli_requires_state_file_for_real_ingest(tmp_path, capsys):
    with pytest.raises(SystemExit):
        collect.parse_args(["--host", "127.0.0.1", "--ingest-url", "http://x/ingest"])
    assert "--state-db" in capsys.readouterr().err
    # 시뮬레이터는 상태 파일 없이도 되지만 경고한다(open_state가 메모리 저장소를 준다)
    args = collect.parse_args(["--host", "127.0.0.1", "--ingest-url", "http://x/ingest", "--simulated"])
    assert collect.open_state(args).durable is False
    # 실제 전송인데 run을 모르면 시작하지 않는다
    args = collect.parse_args(["--host", "127.0.0.1", "--ingest-url", "http://x/ingest",
                               "--state-db", str(tmp_path / "s.sqlite")])
    with pytest.raises(StateError):
        collect.open_state(args)

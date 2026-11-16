import asyncio
import json

from replay import replay
from sinks import MemorySink


def _line(at, session, data, direction="rx"):
    return json.dumps({"at": at, "sessionId": session, "dir": direction, "bytes": data})


def test_replay_reassembles_split_bytes_per_session():
    lines = [
        _line("2026-11-03T10:00:00.000Z", "s1", "< GET 1 BATT_BARS >", "tx"),
        _line("2026-11-03T10:00:00.010Z", "s1", "< REP 1 BATT_"),
        _line("2026-11-03T10:00:00.020Z", "s2", "< SAMPLE 1 ALL XX 000 000 >"),
        _line("2026-11-03T10:00:00.030Z", "s1", "BARS 255 >< HELLO >"),
    ]
    sink = MemorySink()
    stats = asyncio.run(replay(lines, sink))
    readings = sink.of("READING")
    batt = [r for r in readings if r["param"] == "BATT_BARS"][0]
    assert batt["valueStatus"] == "UNAVAILABLE" and batt["sessionId"] == "s1"
    assert batt["receivedAt"] == "2026-11-03T10:00:00.030Z"
    assert all(r["mode"] == "REPLAY" for r in sink.records)
    assert [r["frame"] for r in sink.of("UNPARSED")] == ["HELLO"]
    assert stats == {"sessions": 2, "discardedBytes": 0, "overflows": 0, "pendingBytes": 0}

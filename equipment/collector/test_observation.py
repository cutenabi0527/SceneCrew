import pytest

from decode import Reading
from observation import (CONNECTED, CONNECTING, FRESH, OFFLINE, STALE, UNKNOWN, Freshness,
                         RecordFactory)


class FakeClock:
    def __init__(self):
        self.t = 100.0

    def __call__(self):
        return self.t


def _factory():
    times = iter(f"2026-11-03T10:00:{i:02d}.000Z" for i in range(60))
    return RecordFactory(collector_id="c1", source_ref="qlxd4-main", mode="SIMULATOR",
                         run_id="run-1", clock=lambda: next(times))


def test_sequence_increases_across_record_types():
    f = _factory()
    a = f.link(None, CONNECTING)
    b = f.reading("s1", "SAMPLE", Reading("RF_LEVEL", "090", 90, "OK", "raw"), "SAMPLE 1 ALL AX 090 010")
    c = f.status("s1", {"linkState": CONNECTED})
    assert [a["sequence"], b["sequence"], c["sequence"]] == [1, 2, 3]
    assert b["observedAt"] is None and b["timeBasis"] == "RECEIVED"
    assert "host" not in b and "ip" not in b


def test_mode_must_be_known():
    with pytest.raises(ValueError):
        RecordFactory(collector_id="c", source_ref="s", mode="FAKE")


def test_freshness_lifecycle():
    clock = FakeClock()
    fr = Freshness(2.0, clock)
    f = _factory()
    assert fr.metering() == UNKNOWN

    fr.on_link(CONNECTED)
    assert fr.metering() == UNKNOWN
    clock.t += 2.5
    assert fr.metering() == STALE  # 연결은 됐지만 계측이 오지 않음

    fr.on_reading(f.reading("s1", "SAMPLE", Reading("RF_ANTENNA", "AX", "AX", "OK"), "x"))
    assert fr.metering() == FRESH
    last_at = fr.last_sample_at
    clock.t += 2.1
    assert fr.metering() == STALE

    fr.on_link(OFFLINE)
    assert fr.metering() == OFFLINE
    clock.t += 30
    snap = fr.snapshot()
    # 끊긴 동안 마지막 값의 시각을 새로 고치지 않는다.
    assert snap["lastSampleAt"] == last_at
    assert snap["metering"] == OFFLINE

    fr.on_link(CONNECTED)  # 새 세션: 이전 세션 계측으로 FRESH가 되지 않는다.
    assert fr.metering() == UNKNOWN


def test_first_sample_wait_counts_from_meter_ack():
    clock = FakeClock()
    fr = Freshness(2.0, clock)
    fr.on_link(CONNECTED)
    clock.t += 1.5  # 초기 GET 응답을 받는 동안
    fr.on_metering_started()
    clock.t += 1.0  # 접속 기준으로는 2.5초지만 계측 시작 기준으로는 1초
    assert fr.metering() == UNKNOWN
    clock.t += 1.1
    assert fr.metering() == STALE


def test_report_values_do_not_refresh_metering():
    clock = FakeClock()
    fr = Freshness(2.0, clock)
    f = _factory()
    fr.on_link(CONNECTED)
    fr.on_reading(f.reading("s1", "REPORT", Reading("BATT_BARS", "255", None, "UNAVAILABLE", "bars"), "x"))
    assert fr.metering() == UNKNOWN
    assert fr.snapshot()["lastValues"]["BATT_BARS"]["valueStatus"] == "UNAVAILABLE"

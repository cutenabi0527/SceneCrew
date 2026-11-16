"""수집 프로그램이 내보내는 관측 기록과 수집 최신성 판정.

기록 형식은 BE 수집 계약이 정해지기 전의 초안(draft-1)이다. 상세는 docs/ingest-contract-draft.md.

- READING: 수신기가 보낸 판독값 하나. 원시값과 해석 상태를 함께 담는다.
- LINK: 수집 프로그램과 수신기 사이 연결의 변화. 음향 경로 상태가 아니다.
- STATUS: 수집 최신성 요약. 변화가 있을 때와 주기적으로 보낸다.

수신기는 시각을 주지 않는다. observedAt은 비우고 timeBasis=RECEIVED로 수신 시각만 기록한다.
과거 값은 새 수신 없이 시각을 갱신하지 않는다. 순서는 (runId, sequence)로 정한다.
"""

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

SCHEMA = "scenecrew.equipment-observation/draft-1"
ADAPTER = "shure-qlxd4"

MODES = ("LIVE", "SIMULATOR", "REPLAY")

# 연결 상태(수집 프로그램 ↔ 수신기)
DISABLED = "DISABLED"
CONNECTING = "CONNECTING"
CONNECTED = "CONNECTED"
OFFLINE = "OFFLINE"

# 수집 최신성. 마이크·음향의 정상 여부가 아니다.
UNKNOWN = "UNKNOWN"
FRESH = "FRESH"
STALE = "STALE"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class Sequence:
    """run 하나의 sequence. 수신기 여러 대의 RecordFactory가 함께 쓴다(같은 이벤트 루프 안에서만 증가)."""

    def __init__(self, start: int = 0):
        if start < 0:
            raise ValueError("start는 0 이상이다")
        self.value = start

    def next(self) -> int:
        self.value += 1
        return self.value


@dataclass
class RecordFactory:
    """같은 실행(run) 안에서 증가하는 sequence를 붙여 기록을 만든다."""

    collector_id: str
    source_ref: str
    mode: str
    channel: int = 1
    run_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    clock: object = utc_now
    # 상태 파일에 남은 sequence 상한. 재시작하면 그 다음 번호부터 이어 쓴다.
    start_sequence: int = 0
    # 한 run에서 수신기 여러 대가 sequence를 나눠 쓸 때 같은 Sequence를 넘긴다(웹 연결, #399).
    counter: "Sequence | None" = None
    _sequence: int = 0

    def __post_init__(self):
        if self.mode not in MODES:
            raise ValueError(f"mode는 {MODES} 중 하나다: {self.mode}")
        if self.start_sequence < 0:
            raise ValueError("start_sequence는 0 이상이다")
        self._sequence = self.start_sequence

    def _next(self) -> int:
        if self.counter is not None:
            return self.counter.next()
        self._sequence += 1
        return self._sequence

    def _base(self, record_type: str, session_id: str | None) -> dict:
        return {
            "schema": SCHEMA,
            "recordType": record_type,
            "collectorId": self.collector_id,
            "runId": self.run_id,
            "sessionId": session_id,
            "sequence": self._next(),
            "adapter": ADAPTER,
            "sourceRef": self.source_ref,
            "mode": self.mode,
            "channel": self.channel,
            "receivedAt": self.clock(),
        }

    def reading(self, session_id: str, origin: str, reading, frame: str) -> dict:
        record = self._base("READING", session_id)
        record.update({
            "origin": origin,  # SAMPLE 또는 REPORT
            "param": reading.param,
            "raw": reading.raw,
            "value": reading.value,
            "valueStatus": reading.status,
            "unit": reading.unit,
            "frame": frame,
            "observedAt": None,
            "timeBasis": "RECEIVED",
        })
        return record

    def unparsed(self, session_id: str, frame: str) -> dict:
        record = self._base("UNPARSED", session_id)
        record["frame"] = frame
        return record

    def link(self, session_id: str | None, state: str, reason: str | None = None) -> dict:
        record = self._base("LINK", session_id)
        record.update({"linkState": state, "reason": reason})
        return record

    def status(self, session_id: str | None, snapshot: dict) -> dict:
        record = self._base("STATUS", session_id)
        record.update(snapshot)
        return record


class Freshness:
    """세션별 계측 최신성을 판정한다. clock은 단조 시계(초)다.

    - 연결 전: UNKNOWN / 연결 끊김: OFFLINE
    - 연결 후 첫 계측 전: UNKNOWN, stale_after가 지나도 없으면 STALE
    - 마지막 계측이 stale_after 안이면 FRESH, 넘으면 STALE
    이전 세션의 계측은 새 세션의 최신성에 쓰지 않는다. 마지막 값과 그 수신 시각은 따로 보존한다.
    """

    def __init__(self, stale_after_s: float, clock, count_reports: bool = False):
        if stale_after_s <= 0:
            raise ValueError("stale_after_s는 양수다")
        self.stale_after_s = stale_after_s
        # 폴링 모드에서는 GET에 대한 REP 응답이 계측 대신 최신성의 근거다.
        self.count_reports = count_reports
        self._clock = clock
        self.link = DISABLED
        self._session_started: float | None = None
        self._last_sample: float | None = None
        self.last_sample_at: str | None = None
        self.last_values: dict[str, dict] = {}

    def on_link(self, state: str) -> None:
        self.link = state
        if state == CONNECTED:
            self._session_started = self._clock()
            self._last_sample = None

    def on_metering_started(self) -> None:
        """수신기가 METER_RATE 설정을 확인한 시점부터 첫 계측 대기 시간을 센다.

        실기(QLXD4 2.2.11.0)는 명령마다 약 0.1초씩 순서대로 답해, 접속 직후 GET이 많으면
        첫 SAMPLE이 접속 2초 뒤에 온다. 접속 시점부터 세면 정상 시작이 STALE로 보인다.
        """
        if self.link == CONNECTED and self._last_sample is None:
            self._session_started = self._clock()

    def on_reading(self, record: dict) -> None:
        if record["origin"] == "SAMPLE" or self.count_reports:
            self._last_sample = self._clock()
            self.last_sample_at = record["receivedAt"]
        self.last_values[record["param"]] = {
            "value": record["value"],
            "valueStatus": record["valueStatus"],
            "raw": record["raw"],
            "receivedAt": record["receivedAt"],
            "sessionId": record["sessionId"],
        }

    def metering(self) -> str:
        if self.link == CONNECTED:
            now = self._clock()
            if self._last_sample is None:
                waited = now - (self._session_started or now)
                return STALE if waited > self.stale_after_s else UNKNOWN
            return FRESH if now - self._last_sample <= self.stale_after_s else STALE
        if self.link in (CONNECTING, OFFLINE) and self.last_sample_at is not None:
            return OFFLINE
        if self.link == OFFLINE:
            return OFFLINE
        return UNKNOWN

    def sample_age_s(self) -> float | None:
        if self._last_sample is None:
            return None
        return round(self._clock() - self._last_sample, 3)

    def snapshot(self) -> dict:
        return {
            "linkState": self.link,
            "metering": self.metering(),
            "staleAfterMs": int(self.stale_after_s * 1000),
            "lastSampleAt": self.last_sample_at,
            "sampleAgeMs": None if self.sample_age_s() is None else int(self.sample_age_s() * 1000),
            "lastValues": dict(self.last_values),
        }

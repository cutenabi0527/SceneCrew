"""Shure QLX-D 수신기 문자열 명령의 프레임 분리·해석·명령 생성.

기준: Shure Technical Bulletin "Command Strings for QLX-D Receivers"(RW 11/2/2023).
TCP 2202, ASCII, 모든 메시지는 `< ... >`로 감싼다. 수신기는 GET/SET에 REP로 답하고,
전면 패널·Wireless Workbench에서 값이 바뀔 때도 REP를 먼저 보낸다. 계측은 SAMPLE로 온다.

- TCP는 메시지 경계를 보장하지 않으므로 Framer가 분할·결합된 바이트를 메시지 단위로 나눈다.
- `{...}` 안의 이름 값은 공백과 패딩을 그대로 보존한다. 괄호 안의 `<`·`>`는 경계로 보지 않는다.
- 이 모듈은 수신기 설정을 바꾸는 명령을 만들지 않는다. 허용한 GET과 METER_RATE만 생성한다.
"""

import re
from dataclasses import dataclass

PORT = 2202
MAX_FRAME_BYTES = 256

KINDS = ("GET", "SET", "REP", "SAMPLE")

# 수집에 필요한 읽기 항목만 허용한다. 장치 전체 항목은 채널 번호가 없다.
DEVICE_READ_PARAMS = frozenset({
    "FW_VER",
    "DEVICE_ID",
    "TX_MUTE_STATUS",
    "TX_MUTE_BUTTON_STATUS",
    "TX_POWER_SOURCE",
})
CHANNEL_READ_PARAMS = frozenset({
    "ALL",
    "CHAN_NAME",
    "AUDIO_GAIN",
    "GROUP_CHAN",
    "FREQUENCY",
    "BATT_TYPE",
    "BATT_BARS",
    "BATT_CHARGE",
    "BATT_RUN_TIME",
    "BATT_CYCLE",
    "BATT_HEALTH",
    "BATT_TEMP_C",
    "TX_TYPE",
    "TX_OFFSET",
    "TX_RF_PWR",
    "TX_PWR_LOCK",
    "TX_MENU_LOCK",
    "ENCRYPTION_WARNING",
    "METER_RATE",
})

METER_RATE_MIN_MS = 100
METER_RATE_MAX_MS = 99999

_TOKEN = re.compile(r"\{[^}]*\}|\S+")


@dataclass(frozen=True)
class Message:
    """해석한 메시지 하나. raw는 `<`·`>`를 뺀 원문이며 해석 실패 시에도 보존한다."""

    kind: str  # GET, SET, REP, SAMPLE, UNKNOWN
    channel: int | None
    param: str | None
    value: str | None
    raw: str


class Framer:
    """바이트 흐름을 `< ... >` 메시지로 나눈다.

    메시지 밖의 공백(CR/LF 포함)은 무시하고, 그 밖의 바이트는 discarded에 센다.
    닫히지 않은 채 MAX_FRAME_BYTES를 넘는 조각은 버리고 overflows에 센다.
    """

    def __init__(self, max_frame_bytes: int = MAX_FRAME_BYTES):
        self._buf = bytearray()
        self._max = max_frame_bytes
        self.discarded = 0
        self.overflows = 0

    @property
    def pending(self) -> int:
        return len(self._buf)

    def feed(self, data: bytes) -> list[str]:
        self._buf += data
        frames: list[str] = []
        while True:
            start = self._buf.find(b"<")
            if start < 0:
                self._discard(len(self._buf))
                return frames
            if start > 0:
                self._discard(start)
            end = self._find_end()
            if end is None:
                if len(self._buf) > self._max:
                    # 닫히지 않는 조각. 다음 '<'부터 다시 맞춘다.
                    self.overflows += 1
                    nxt = self._buf.find(b"<", 1)
                    del self._buf[: nxt if nxt > 0 else len(self._buf)]
                    continue
                return frames
            if end < 0:
                # 닫히기 전에 새 '<'가 나타났다. 앞 조각을 버리고 새 시작으로 맞춘다.
                restart = -end
                self._discard(restart)
                continue
            body = bytes(self._buf[1:end])
            del self._buf[: end + 1]
            frames.append(body.decode("ascii", errors="replace").strip())

    def _find_end(self) -> int | None:
        """닫는 '>'의 위치. 괄호 밖의 새 '<'를 먼저 만나면 -위치, 미완성이면 None."""
        in_brace = False
        for i in range(1, len(self._buf)):
            ch = self._buf[i]
            if in_brace:
                if ch == 0x7D:  # }
                    in_brace = False
            elif ch == 0x7B:  # {
                in_brace = True
            elif ch == 0x3E:  # >
                return i
            elif ch == 0x3C:  # <
                return -i
        return None

    def _discard(self, n: int) -> None:
        chunk = bytes(self._buf[:n])
        del self._buf[:n]
        self.discarded += len(chunk.strip())


def parse(frame: str) -> Message:
    """`<`·`>`를 뺀 메시지 본문을 해석한다. 알 수 없는 형식은 UNKNOWN으로 원문을 보존한다."""
    tokens = [(m.group(0), m.start(), m.end()) for m in _TOKEN.finditer(frame)]
    if not tokens or tokens[0][0] not in KINDS:
        return Message("UNKNOWN", None, None, None, frame)
    kind = tokens[0][0]
    i = 1
    channel = None
    if i < len(tokens) and tokens[i][0].isdigit():
        channel = int(tokens[i][0])
        i += 1
    if i >= len(tokens):
        return Message("UNKNOWN", channel, None, None, frame)
    param = tokens[i][0]
    if param.startswith("{"):
        return Message("UNKNOWN", channel, None, None, frame)
    value = frame[tokens[i][2]:].strip() if i + 1 < len(tokens) else None
    return Message(kind, channel, param, value, frame)


def encode(body: str) -> bytes:
    """명령 본문을 공식 문서의 `< ... >` 형식으로 감싼다. CR/LF를 덧붙이지 않는다."""
    return f"< {body} >".encode("ascii")


def build_get(param: str, channel: int | None = None) -> bytes:
    """허용한 읽기 항목의 GET 명령을 만든다."""
    if channel is None:
        if param not in DEVICE_READ_PARAMS:
            raise ValueError(f"허용하지 않은 장치 읽기 항목: {param}")
        return encode(f"GET {param}")
    if param not in CHANNEL_READ_PARAMS:
        raise ValueError(f"허용하지 않은 채널 읽기 항목: {param}")
    _check_channel(channel)
    return encode(f"GET {channel} {param}")


def build_meter_rate(channel: int, rate_ms: int) -> bytes:
    """계측 전송 간격을 설정한다. 0은 계측 중지다. 수집에 필요한 유일한 SET 명령이다."""
    _check_channel(channel)
    if rate_ms != 0 and not METER_RATE_MIN_MS <= rate_ms <= METER_RATE_MAX_MS:
        raise ValueError(f"METER_RATE는 0 또는 {METER_RATE_MIN_MS}~{METER_RATE_MAX_MS}ms다: {rate_ms}")
    return encode(f"SET {channel} METER_RATE {rate_ms:05d}")


def _check_channel(channel: int) -> None:
    # QLXD4는 단일 채널 수신기라 채널 1만 쓴다. 다른 값은 실기 확인 전 막는다.
    if channel != 1:
        raise ValueError(f"QLXD4 채널은 1이다: {channel}")

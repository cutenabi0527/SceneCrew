"""QLX-D 메시지 값을 원시값을 보존한 판독값으로 바꾼다.

원칙(BE13·장비 원본 6절):
- 원시값(raw)은 항상 남긴다. RF/오디오 레벨은 공식 문서가 dB 단위를 주지 않으므로 변환하지 않는다.
- 255·254·253·252(3자리)와 65535~65532(5자리)는 "장치를 사용할 수 없음" 오류 코드다.
  0%·0분·0칸으로 바꾸지 않고 UNAVAILABLE로 둔다.
- UNKN은 수신기가 모른다고 답한 값이며 UNKNOWN_VALUE로 둔다.
- 범위를 벗어난 값은 OUT_OF_RANGE, 해석 규칙이 없는 항목은 UNPARSED로 두고 값은 비운다.
"""

from dataclasses import dataclass

from protocol import Message

OK = "OK"
UNAVAILABLE = "UNAVAILABLE"
UNKNOWN_VALUE = "UNKNOWN_VALUE"
OUT_OF_RANGE = "OUT_OF_RANGE"
UNPARSED = "UNPARSED"

ERROR_CODES_3 = frozenset({252, 253, 254, 255})
ERROR_CODES_5 = frozenset({65532, 65533, 65534, 65535})

ENUMS = {
    "TX_TYPE": {"QLXD1", "QLXD2", "ULXD1", "ULXD2", "ULXD6", "ULXD8"},
    "BATT_TYPE": {"ALKA", "LION", "LITH", "NIMH"},
    "TX_RF_PWR": {"LOW", "HIGH"},
    "TX_PWR_LOCK": {"ON", "OFF"},
    "TX_MENU_LOCK": {"ON", "OFF"},
    "ENCRYPTION_WARNING": {"ON", "OFF"},
    "TX_MUTE_STATUS": {"ON", "OFF"},
    "TX_MUTE_BUTTON_STATUS": {"PRESSED", "RELEASED"},
    "TX_POWER_SOURCE": {"BATTERY", "EXTERNAL"},
}

# 항목: (자리 수, 최소, 최대, 단위, 표시값 변환)
NUMBERS = {
    "BATT_BARS": (3, 0, 5, "bars", None),
    "BATT_CHARGE": (3, 0, 100, "percent", None),
    "BATT_HEALTH": (3, 0, 100, "percent", None),
    "BATT_TEMP_C": (3, 0, 251, "celsius", lambda v: v - 40),
    "BATT_RUN_TIME": (5, 0, 65531, "minutes", None),
    "BATT_CYCLE": (5, 0, 65531, "count", None),
    "TX_OFFSET": (3, 0, 251, "raw", None),
    # 공식 문서: yyy - 18이 수신기 화면 표시값이다. 화면 표시 단위로만 둔다.
    "AUDIO_GAIN": (3, 0, 60, "display_db", lambda v: v - 18),
    "METER_RATE": (5, 0, 99999, "ms", None),
}

NAMES = {"CHAN_NAME", "DEVICE_ID", "FW_VER", "TX_DEVICE_ID"}

ANTENNA = {"AX", "XB", "XX"}
RF_LEVEL_MAX = 115
AUDIO_LEVEL_MAX = 50


@dataclass(frozen=True)
class Reading:
    param: str
    raw: str | None
    value: object
    status: str
    unit: str | None = None


def decode(msg: Message) -> list[Reading]:
    """REP·SAMPLE 메시지를 판독값 목록으로 바꾼다. 그 밖의 메시지는 빈 목록이다."""
    if msg.kind == "SAMPLE" and msg.param == "ALL":
        return _sample(msg.value)
    if msg.kind != "REP" or msg.param is None:
        return []
    return [_report(msg.param, msg.value)]


def _report(param: str, raw: str | None) -> Reading:
    if raw is None:
        return Reading(param, raw, None, UNPARSED)
    if param in NUMBERS:
        return _number(param, raw)
    if param in ENUMS:
        if raw == "UNKN":
            return Reading(param, raw, None, UNKNOWN_VALUE)
        if raw in ENUMS[param]:
            return Reading(param, raw, raw, OK)
        return Reading(param, raw, None, UNPARSED)
    if param in NAMES:
        if raw.startswith("{") and raw.endswith("}"):
            return Reading(param, raw, raw[1:-1].rstrip(), OK)
        return Reading(param, raw, None, UNPARSED)
    if param == "FREQUENCY":
        if len(raw) == 6 and raw.isdigit():
            return Reading(param, raw, f"{raw[:3]}.{raw[3:]}", OK, "MHz")
        return Reading(param, raw, None, UNPARSED)
    if param == "GROUP_CHAN":
        if raw == "--,--":
            return Reading(param, raw, None, UNAVAILABLE)
        group, _, chan = raw.partition(",")
        if group.isdigit() and chan.isdigit():
            # 수집 계약(#330)의 value는 단일 값만 받는다. 그룹·채널은 원문 "gg,cc" 문자열로 보낸다.
            return Reading(param, raw, raw, OK)
        return Reading(param, raw, None, UNPARSED)
    if param == "ENCRYPTION":
        if raw in {"ON", "OFF"}:
            return Reading(param, raw, raw, OK)
        return Reading(param, raw, None, UNPARSED)
    return Reading(param, raw, None, UNPARSED)


def _number(param: str, raw: str) -> Reading:
    digits, low, high, unit, transform = NUMBERS[param]
    if not raw.isdigit():
        return Reading(param, raw, None, UNPARSED, unit)
    v = int(raw)
    codes = ERROR_CODES_3 if digits == 3 else ERROR_CODES_5
    if v in codes and param != "METER_RATE":
        return Reading(param, raw, None, UNAVAILABLE, unit)
    if not low <= v <= high:
        return Reading(param, raw, None, OUT_OF_RANGE, unit)
    return Reading(param, raw, transform(v) if transform else v, OK, unit)


def _sample(raw: str | None) -> list[Reading]:
    """`SAMPLE 1 ALL nn aaa eee`: 안테나(squelch) 표시, RF 레벨 원시값, 오디오 레벨 원시값."""
    parts = (raw or "").split()
    if len(parts) != 3:
        return [Reading("SAMPLE", raw, None, UNPARSED)]
    antenna, rf, audio = parts
    return [
        Reading("RF_ANTENNA", antenna, antenna if antenna in ANTENNA else None,
                OK if antenna in ANTENNA else UNPARSED),
        _level("RF_LEVEL", rf, RF_LEVEL_MAX),
        _level("AUDIO_LEVEL", audio, AUDIO_LEVEL_MAX),
    ]


def _level(param: str, raw: str, high: int) -> Reading:
    if not raw.isdigit():
        return Reading(param, raw, None, UNPARSED, "raw")
    v = int(raw)
    if v > high:
        return Reading(param, raw, None, OUT_OF_RANGE, "raw")
    return Reading(param, raw, v, OK, "raw")

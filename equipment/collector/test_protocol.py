import pytest

import protocol
from decode import OK, OUT_OF_RANGE, UNAVAILABLE, UNKNOWN_VALUE, UNPARSED, decode


def test_split_and_merged_frames():
    f = protocol.Framer()
    assert f.feed(b"< REP 1 BATT_") == []
    assert f.feed(b"BARS 004 >< SAMPLE 1 ALL AX 090 012 >\r\n< REP") == [
        "REP 1 BATT_BARS 004",
        "SAMPLE 1 ALL AX 090 012",
    ]
    assert f.feed(b" 1 TX_TYPE QLXD1 >") == ["REP 1 TX_TYPE QLXD1"]
    assert f.discarded == 0


def test_byte_by_byte_feed():
    data = b"< REP 1 CHAN_NAME {VOCAL1  } >< SAMPLE 1 ALL XB 101 033 >"
    f = protocol.Framer()
    frames = []
    for b in data:
        frames += f.feed(bytes([b]))
    assert frames == ["REP 1 CHAN_NAME {VOCAL1  }", "SAMPLE 1 ALL XB 101 033"]


def test_garbage_and_resync():
    f = protocol.Framer()
    frames = f.feed(b"xx< REP 1 BATT< REP 1 BATT_BARS 003 >yy")
    assert frames == ["REP 1 BATT_BARS 003"]
    assert f.discarded == len(b"xx") + len(b"< REP 1 BATT") + len(b"yy")


def test_brace_value_may_contain_angle_brackets():
    f = protocol.Framer()
    assert f.feed(b"< REP 1 CHAN_NAME {A>B<C   } >") == ["REP 1 CHAN_NAME {A>B<C   }"]


def test_overflow_drops_unterminated_fragment():
    f = protocol.Framer(max_frame_bytes=16)
    assert f.feed(b"< REP " + b"X" * 40) == []
    assert f.overflows == 1
    assert f.feed(b"< REP 1 BATT_BARS 001 >") == ["REP 1 BATT_BARS 001"]


def test_parse_shapes():
    m = protocol.parse("REP 1 CHAN_NAME {MC 1    }")
    assert (m.kind, m.channel, m.param, m.value) == ("REP", 1, "CHAN_NAME", "{MC 1    }")
    m = protocol.parse("REP FW_VER {2.7.13.0          }")
    assert (m.kind, m.channel, m.param) == ("REP", None, "FW_VER")
    m = protocol.parse("SAMPLE 1 ALL AX 087 032")
    assert (m.kind, m.param, m.value) == ("SAMPLE", "ALL", "AX 087 032")
    assert protocol.parse("HELLO").kind == "UNKNOWN"
    assert protocol.parse("REP 1").kind == "UNKNOWN"


def test_commands_follow_official_format():
    assert protocol.build_get("FW_VER") == b"< GET FW_VER >"
    assert protocol.build_get("BATT_BARS", 1) == b"< GET 1 BATT_BARS >"
    assert protocol.build_meter_rate(1, 500) == b"< SET 1 METER_RATE 00500 >"
    assert protocol.build_meter_rate(1, 0) == b"< SET 1 METER_RATE 00000 >"


@pytest.mark.parametrize("call", [
    lambda: protocol.build_get("AUDIO_GAIN"),          # 채널 항목을 장치 항목으로
    lambda: protocol.build_get("ENCRYPTION", 1),       # 허용 목록 밖
    lambda: protocol.build_get("BATT_BARS", 2),        # QLXD4는 채널 1
    lambda: protocol.build_meter_rate(1, 50),          # 최소 100ms
    lambda: protocol.build_meter_rate(1, 100000),
])
def test_disallowed_commands_raise(call):
    with pytest.raises(ValueError):
        call()


def test_no_write_command_builder_exists():
    # 수집기는 설정 변경 명령을 만들 수단이 없어야 한다.
    public = {name for name in dir(protocol) if name.startswith("build_")}
    assert public == {"build_get", "build_meter_rate"}


def _one(frame):
    (reading,) = decode(protocol.parse(frame))
    return reading


def test_battery_error_codes_are_not_zero():
    r = _one("REP 1 BATT_BARS 255")
    assert (r.status, r.value, r.raw) == (UNAVAILABLE, None, "255")
    r = _one("REP 1 BATT_RUN_TIME 65535")
    assert (r.status, r.value) == (UNAVAILABLE, None)
    r = _one("REP 1 BATT_CHARGE 255")
    assert r.status == UNAVAILABLE
    r = _one("REP 1 BATT_BARS 000")
    assert (r.status, r.value, r.unit) == (OK, 0, "bars")
    assert _one("REP 1 BATT_BARS 007").status == OUT_OF_RANGE


def test_enums_and_unknown():
    assert _one("REP 1 TX_TYPE QLXD1").value == "QLXD1"
    r = _one("REP 1 TX_TYPE UNKN")
    assert (r.status, r.value) == (UNKNOWN_VALUE, None)
    assert _one("REP 1 TX_TYPE ZZZ").status == UNPARSED


def test_names_frequency_group_gain():
    assert _one("REP 1 CHAN_NAME {VOCAL1  }").value == "VOCAL1"
    r = _one("REP 1 FREQUENCY 554125")
    assert (r.value, r.unit) == ("554.125", "MHz")
    assert _one("REP 1 GROUP_CHAN 01,05").value == "01,05"
    assert _one("REP 1 GROUP_CHAN 1,x").status == UNPARSED
    assert _one("REP 1 GROUP_CHAN --,--").status == UNAVAILABLE
    r = _one("REP 1 AUDIO_GAIN 030")
    assert (r.value, r.unit, r.raw) == (12, "display_db", "030")
    assert _one("REP 1 BATT_TEMP_C 040").value == 0


def test_sample_keeps_raw_levels():
    antenna, rf, audio = decode(protocol.parse("SAMPLE 1 ALL AX 087 032"))
    assert (antenna.value, rf.value, audio.value) == ("AX", 87, 32)
    assert rf.unit == audio.unit == "raw"
    _, rf, audio = decode(protocol.parse("SAMPLE 1 ALL XX 000 000"))
    assert (rf.status, rf.value, audio.value) == (OK, 0, 0)
    _, rf, audio = decode(protocol.parse("SAMPLE 1 ALL AX 116 051"))
    assert rf.status == audio.status == OUT_OF_RANGE
    assert decode(protocol.parse("SAMPLE 1 ALL AX 087"))[0].status == UNPARSED


def test_unknown_report_param_is_kept_unparsed():
    r = _one("REP 1 SOMETHING_NEW 123")
    assert (r.status, r.value, r.raw) == (UNPARSED, None, "123")
    assert decode(protocol.parse("GET 1 BATT_BARS")) == []

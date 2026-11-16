"""QLXD4 수신기에 TCP로 붙어 상태를 반복 수집한다.

한 번의 TCP 연결이 한 세션(sessionId)이다. 연결 후 허용한 GET으로 현재 값을 읽고,
METER_RATE로 계측을 켠 뒤 REP·SAMPLE을 기록한다. 끊기면 OFFLINE을 남기고 다시 연결한다.
데이터가 stall_reconnect_s 동안 전혀 오지 않으면 반쯤 끊긴 연결로 보고 다시 연결한다.
종료할 때는 계측을 끄고(METER_RATE 0) 연결을 닫는다. 그 밖의 설정 변경 명령은 보내지 않는다.

접속할 때마다 수신기의 SLP 알림을 먼저 듣고(wait_for_announce) 들은 뒤에만 접속한다. 실기 패킷 캡처에서
수신기가 링크 복귀 후 자동 주소를 확인하는 동안 우리가 주소 확인(ARP)을 보내자, 수신기가 충돌로 보고
주소를 바꿨다. 알림은 주소 확인이 끝난 뒤에 나오므로 이 충돌을 피한다.

알려진 한계(docs/qlxd4-protocol.md): 연결이 열린 채 노트북 쪽 케이블이 빠지면, 수신기는 링크 복귀 약
40초 뒤 밀린 데이터를 옛 연결로 보낸 직후 네트워크가 멈추고 전원 재시작으로만 복구된다. 우리 쪽 재접속
방식으로는 막지 못했으므로 공연 중에는 노트북 쪽 케이블을 고정한다.
"""

import asyncio
import time
import uuid
from dataclasses import dataclass

import protocol
from announce import wait_for_announcement
from decode import decode
from observation import CONNECTED, CONNECTING, OFFLINE, Freshness, RecordFactory

INITIAL_DEVICE_READS = ("FW_VER", "DEVICE_ID")
INITIAL_CHANNEL_READS = (
    "CHAN_NAME", "TX_TYPE", "BATT_TYPE", "BATT_BARS", "BATT_CHARGE", "BATT_RUN_TIME",
    "AUDIO_GAIN", "FREQUENCY", "GROUP_CHAN", "TX_RF_PWR", "ENCRYPTION_WARNING",
)
# 폴링 모드에서 주기적으로 묻는 항목. 송신기 연결·배터리 변화를 확인한다.
POLL_PARAMS = ("TX_TYPE", "BATT_BARS")


@dataclass
class CollectorConfig:
    host: str
    port: int = protocol.PORT
    channel: int = 1
    # 같은 169.254 대역 어댑터가 여럿이면 OS가 다른 어댑터로 보낼 수 있다. 수신기가 붙은 어댑터 주소를 준다.
    source_ip: str | None = None
    meter_rate_ms: int = 500
    connect_timeout_s: float = 3.0
    stale_after_s: float | None = None  # 기본값: max(2초, 계측 간격 4배). 실기 측정 후 조정한다.
    stall_reconnect_s: float | None = None  # 기본값: max(10초, stale_after 3배)
    status_interval_s: float = 5.0
    backoff_initial_s: float = 0.5
    backoff_max_s: float = 10.0
    meter_off_on_exit: bool = True
    # meter_rate_ms=0이면 계측 스트림을 켜지 않고 poll_interval_s마다 POLL_PARAMS를 GET으로 묻는다.
    poll_interval_s: float = 1.0
    # 접속 전에 수신기 SLP 알림을 기다린다. 알림이 없는 시뮬레이터 대상에서만 끈다.
    wait_for_announce: bool = True
    announce_wait_s: float = 30.0  # 한 번 기다리는 시간. 못 들으면 접속하지 않고 다시 기다린다.

    def __post_init__(self):
        protocol.build_meter_rate(self.channel, self.meter_rate_ms)  # 범위 검사
        if self.stale_after_s is None:
            period_s = self.meter_rate_ms / 1000 if self.meter_rate_ms else self.poll_interval_s
            self.stale_after_s = max(2.0, period_s * 4)
        if self.stall_reconnect_s is None:
            self.stall_reconnect_s = max(10.0, self.stale_after_s * 3)


class QlxdCollector:
    def __init__(self, config: CollectorConfig, factory: RecordFactory, sink, *, raw_capture=None,
                 clock=time.monotonic):
        self.config = config
        self.factory = factory
        self.sink = sink
        self.raw_capture = raw_capture
        self.clock = clock
        self.freshness = Freshness(config.stale_after_s, clock, count_reports=config.meter_rate_ms == 0)
        self.session_id: str | None = None
        self.sessions = 0
        self.sent_commands: list[bytes] = []
        self._writer: asyncio.StreamWriter | None = None
        self._last_status_key = None
        self._last_status_at = 0.0

    async def run(self, stop: asyncio.Event) -> None:
        ticker = asyncio.create_task(self._tick(stop))
        backoff = self.config.backoff_initial_s
        try:
            while not stop.is_set():
                if self.config.wait_for_announce:
                    heard = await asyncio.to_thread(
                        wait_for_announcement, self.config.host, self.config.source_ip,
                        self.config.announce_wait_s, stop.is_set)
                    if stop.is_set():
                        break
                    if not heard:
                        # 케이블·전원을 확인하고, 케이블이 빠졌다 돌아온 뒤라면 수신기 재시작이 필요할 수 있다.
                        await self._set_link(OFFLINE, "receiver not announcing (check cable/power; "
                                                      "may need receiver restart after a cable pull)")
                        continue
                await self._set_link(CONNECTING, None)
                started = self.clock()
                reason = await self._session(stop)
                if stop.is_set():
                    break
                await self._set_link(OFFLINE, reason)
                if self.clock() - started > self.config.stall_reconnect_s:
                    backoff = self.config.backoff_initial_s
                if await _wait(stop, backoff):
                    break
                backoff = min(backoff * 2, self.config.backoff_max_s)
        finally:
            stop.set()
            await ticker
            await self._set_link(OFFLINE, "collector stopped")
            await self._emit_status(force=True)

    async def _session(self, stop: asyncio.Event) -> str:
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(
                    self.config.host, self.config.port,
                    local_addr=(self.config.source_ip, 0) if self.config.source_ip else None),
                timeout=self.config.connect_timeout_s)
        except asyncio.TimeoutError:
            return "connect timeout"
        except OSError as exc:
            return f"connect failed: {type(exc).__name__}"

        self.session_id = str(uuid.uuid4())
        self.sessions += 1
        self._writer = writer
        framer = protocol.Framer()
        await self._set_link(CONNECTED, None)
        poller = None
        try:
            for param in INITIAL_DEVICE_READS:
                await self._send(protocol.build_get(param))
            for param in INITIAL_CHANNEL_READS:
                await self._send(protocol.build_get(param, self.config.channel))
            if self.config.meter_rate_ms:
                await self._send(protocol.build_meter_rate(self.config.channel, self.config.meter_rate_ms))
            else:
                poller = asyncio.create_task(self._poll())

            while not stop.is_set():
                read = asyncio.create_task(reader.read(4096))
                stopper = asyncio.create_task(stop.wait())
                done, _ = await asyncio.wait({read, stopper}, timeout=self.config.stall_reconnect_s,
                                             return_when=asyncio.FIRST_COMPLETED)
                stopper.cancel()
                if read not in done:
                    read.cancel()
                    if stop.is_set():
                        break
                    return "no data (stall)"
                data = read.result()
                if not data:
                    return "closed by receiver"
                if self.raw_capture:
                    self.raw_capture.write(self.session_id, "rx", data)
                for frame in framer.feed(data):
                    await self._handle(frame)
            # 정상 종료: 계측을 끄고 닫는다. METER_RATE는 수신기 설정이라 다른 제어 장치와 공유될 수 있다.
            # 시설 담당자가 다른 장치의 계측 사용을 확인하면 meter_off_on_exit=False로 둔다.
            if self.config.meter_rate_ms and self.config.meter_off_on_exit:
                await self._send(protocol.build_meter_rate(self.config.channel, 0))
            return "collector stopped"
        except (ConnectionError, OSError) as exc:
            return f"connection error: {type(exc).__name__}"
        finally:
            if poller:
                poller.cancel()
            self._writer = None
            writer.close()
            try:
                await asyncio.wait_for(writer.wait_closed(), timeout=1.0)
            except (asyncio.TimeoutError, ConnectionError, OSError):
                pass

    async def _poll(self) -> None:
        """계측 스트림 대신 주기적으로 GET을 보낸다. 수신기는 요청받을 때만 답한다."""
        try:
            while self._writer is not None:
                await asyncio.sleep(self.config.poll_interval_s)
                for param in POLL_PARAMS:
                    if self._writer is None:
                        return
                    await self._send(protocol.build_get(param, self.config.channel))
        except (asyncio.CancelledError, ConnectionError, OSError):
            pass

    async def _send(self, command: bytes) -> None:
        self.sent_commands.append(command)
        if self.raw_capture:
            self.raw_capture.write(self.session_id, "tx", command)
        self._writer.write(command)
        await self._writer.drain()

    async def _handle(self, frame: str) -> None:
        msg = protocol.parse(frame)
        readings = decode(msg)
        if msg.kind == "UNKNOWN" or (not readings and msg.kind in ("REP", "SAMPLE")):
            await self.sink.emit(self.factory.unparsed(self.session_id, frame))
            return
        origin = "SAMPLE" if msg.kind == "SAMPLE" else "REPORT"
        for reading in readings:
            record = self.factory.reading(self.session_id, origin, reading, frame)
            self.freshness.on_reading(record)
            if reading.param == "METER_RATE" and reading.value:
                self.freshness.on_metering_started()
            await self.sink.emit(record)

    async def _set_link(self, state: str, reason: str | None) -> None:
        if state == self.freshness.link and state != CONNECTED:
            return
        self.freshness.on_link(state)
        session = self.session_id if state in (CONNECTED, OFFLINE) else None
        await self.sink.emit(self.factory.link(session, state, reason))
        await self._emit_status(force=True)

    async def _tick(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            await self._emit_status()
            await _wait(stop, min(0.25, self.config.stale_after_s / 4))

    async def _emit_status(self, force: bool = False) -> None:
        snapshot = self.freshness.snapshot()
        key = (snapshot["linkState"], snapshot["metering"])
        now = self.clock()
        if force or key != self._last_status_key or now - self._last_status_at >= self.config.status_interval_s:
            self._last_status_key = key
            self._last_status_at = now
            await self.sink.emit(self.factory.status(self.session_id, snapshot))


async def _wait(stop: asyncio.Event, seconds: float) -> bool:
    """seconds만큼 기다린다. 그 사이 stop되면 True."""
    try:
        await asyncio.wait_for(stop.wait(), timeout=seconds)
        return True
    except asyncio.TimeoutError:
        return False

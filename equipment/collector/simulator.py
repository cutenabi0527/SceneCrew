"""개발·시험용 가짜 QLXD4 수신기. 실기 결과가 아니다.

공식 문서의 메시지 형식을 흉내 내 GET에 REP로 답하고, METER_RATE에 따라 SAMPLE을 보낸다.
송신기 전원·발화·배터리 변화는 REP로 먼저 알린다. 시험을 위해 다음 상황을 만든다.

- fragment=True: 메시지를 무작위로 쪼개거나 여러 개를 합쳐 보낸다(TCP 경계 시험).
- drop_clients(): 연결을 끊는다(수집 경로 단절).
- stall(seconds): 연결은 유지한 채 아무것도 보내지 않는다(반쯤 끊긴 연결).
- METER_RATE 이외의 SET은 거절하고 refused_writes에 남긴다. 수집기가 설정을 바꾸지 않는지 확인하는 용도다.
"""

import asyncio
import random
from dataclasses import dataclass, field

import protocol


@dataclass
class ChannelState:
    tx_on: bool = True
    speaking: bool = False
    batt_bars: int = 4
    batt_type: str = "ALKA"
    tx_type: str = "QLXD1"
    chan_name: str = "VOCAL1"
    audio_gain: int = 18
    frequency: str = "554125"
    group_chan: str = "01,01"
    meter_rate_ms: int = 0


@dataclass
class _Client:
    writer: asyncio.StreamWriter
    meter_task: asyncio.Task | None = None


@dataclass
class QlxdSimulator:
    host: str = "127.0.0.1"
    port: int = 0
    fragment: bool = False
    seed: int = 7
    device_id: str = "SIMRX01"
    fw_ver: str = "2.2.11.0"
    state: ChannelState = field(default_factory=ChannelState)
    received: list[str] = field(default_factory=list)
    refused_writes: list[str] = field(default_factory=list)

    def __post_init__(self):
        self._rng = random.Random(self.seed)
        self._clients: list[_Client] = []
        self._server: asyncio.base_events.Server | None = None
        self._stalled_until = 0.0
        self.connections = 0

    async def start(self) -> int:
        self._server = await asyncio.start_server(self._accept, self.host, self.port)
        self.port = self._server.sockets[0].getsockname()[1]
        return self.port

    async def stop(self) -> None:
        await self.drop_clients()
        if self._server:
            self._server.close()
            await self._server.wait_closed()

    # ---- 시험 조작 ----

    async def set_tx(self, on: bool) -> None:
        """송신기 전원 변화. 꺼지면 배터리·송신기 정보가 오류 코드/UNKN으로 바뀐다."""
        self.state.tx_on = on
        await self._broadcast([
            f"REP 1 TX_TYPE {self.state.tx_type if on else 'UNKN'}",
            f"REP 1 BATT_BARS {self.state.batt_bars if on else 255:03d}",
            f"REP 1 BATT_TYPE {self.state.batt_type if on else 'UNKN'}",
            f"REP 1 BATT_RUN_TIME {65535:05d}",
        ])

    async def set_speaking(self, speaking: bool) -> None:
        self.state.speaking = speaking

    async def set_battery(self, bars: int) -> None:
        self.state.batt_bars = bars
        if self.state.tx_on:
            await self._broadcast([f"REP 1 BATT_BARS {bars:03d}"])

    async def drop_clients(self) -> None:
        for client in list(self._clients):
            self._close(client)

    def stall(self, seconds: float) -> None:
        self._stalled_until = asyncio.get_running_loop().time() + seconds

    # ---- 프로토콜 ----

    async def _accept(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        client = _Client(writer)
        self._clients.append(client)
        self.connections += 1
        framer = protocol.Framer()
        try:
            while True:
                data = await reader.read(1024)
                if not data:
                    break
                for frame in framer.feed(data):
                    self.received.append(frame)
                    await self._command(client, protocol.parse(frame))
        except (ConnectionError, OSError):
            pass
        finally:
            self._close(client)

    async def _command(self, client: _Client, msg: protocol.Message) -> None:
        if msg.kind == "GET":
            if msg.param == "ALL" and msg.channel == 1:
                params = ["CHAN_NAME", "AUDIO_GAIN", "GROUP_CHAN", "FREQUENCY", "TX_TYPE", "BATT_BARS"]
                await self._send(client, [self._report(p) for p in params])
            else:
                await self._send(client, [self._report(msg.param, msg.channel)])
        elif msg.kind == "SET" and msg.param == "METER_RATE" and msg.channel == 1 and (msg.value or "").isdigit():
            rate = int(msg.value)
            self.state.meter_rate_ms = rate
            await self._send(client, [f"REP 1 METER_RATE {rate:05d}"])
            if client.meter_task:
                client.meter_task.cancel()
                client.meter_task = None
            if rate:
                client.meter_task = asyncio.create_task(self._meter(client, rate / 1000))
        elif msg.kind == "SET":
            self.refused_writes.append(msg.raw)
            await self._send(client, ["REP ERR"])
        else:
            await self._send(client, ["REP ERR"])

    def _report(self, param: str | None, channel: int | None = 1) -> str:
        s = self.state
        on = s.tx_on
        values = {
            "FW_VER": "{" + self.fw_ver.ljust(23) + "}",
            "DEVICE_ID": "{" + self.device_id.ljust(8) + "}",
            "CHAN_NAME": "{" + s.chan_name.ljust(8) + "}",
            "AUDIO_GAIN": f"{s.audio_gain:03d}",
            "GROUP_CHAN": s.group_chan,
            "FREQUENCY": s.frequency,
            "TX_TYPE": s.tx_type if on else "UNKN",
            "BATT_TYPE": s.batt_type if on else "UNKN",
            "BATT_BARS": f"{s.batt_bars if on else 255:03d}",
            "BATT_CHARGE": "255",  # 알카라인: 충전식 전용 값은 오류 코드
            "BATT_RUN_TIME": "65535",
            "TX_RF_PWR": "LOW" if on else "UNKN",
            "ENCRYPTION_WARNING": "OFF",
            "METER_RATE": f"{s.meter_rate_ms:05d}",
            "TX_MUTE_STATUS": "OFF" if on else "UNKN",
        }
        if param not in values:
            return "REP ERR"
        prefix = "REP" if channel is None else f"REP {channel}"
        return f"{prefix} {param} {values[param]}"

    def _sample(self) -> str:
        if not self.state.tx_on:
            # 실기(2026-11-03, QLXD4 2.2.11.0): 송신기가 없어도 RF 원시값은 바닥 잡음으로 28~32가 온다.
            return f"SAMPLE 1 ALL XX {self._rng.randint(28, 32):03d} 000"
        antenna = self._rng.choice(["AX", "XB"])
        rf = self._rng.randint(85, 100)
        audio = self._rng.randint(25, 40) if self.state.speaking else self._rng.randint(0, 3)
        return f"SAMPLE 1 ALL {antenna} {rf:03d} {audio:03d}"

    async def _meter(self, client: _Client, period: float) -> None:
        try:
            while True:
                await asyncio.sleep(period)
                await self._send(client, [self._sample()])
        except (asyncio.CancelledError, ConnectionError, OSError):
            pass

    async def _broadcast(self, bodies: list[str]) -> None:
        for client in list(self._clients):
            await self._send(client, bodies)

    async def _send(self, client: _Client, bodies: list[str]) -> None:
        loop = asyncio.get_running_loop()
        if loop.time() < self._stalled_until or client.writer.is_closing():
            return
        data = b"".join(protocol.encode(b) for b in bodies)
        try:
            if not self.fragment:
                client.writer.write(data)
            else:
                pos = 0
                while pos < len(data):
                    size = self._rng.randint(1, 9)
                    client.writer.write(data[pos:pos + size])
                    await client.writer.drain()
                    pos += size
            await client.writer.drain()
        except (ConnectionError, OSError):
            self._close(client)

    def _close(self, client: _Client) -> None:
        if client.meter_task:
            client.meter_task.cancel()
        if client in self._clients:
            self._clients.remove(client)
        client.writer.close()

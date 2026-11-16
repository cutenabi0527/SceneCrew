"""웹 연결 수집(#399, V9-D59). 연결 코드로 수집기를 등록하고, 찾은 수신기를 서버에 알리고,
서버가 장비 칸에 연결한 수신기만 한 프로세스에서 함께 수집한다.

흐름(backend/docs/equipment-collector.md "웹 연결"):
1. 코드 교환: POST /internal/equipment-collectors/pair {code, programVersion, hostLabel} → {collectorId, token, runId}
   받은 값은 노트북 사용자 폴더에만 저장한다(저장소·실행 폴더에 쓰지 않는다).
2. 약 10초마다 PUT /internal/equipment-collectors/{c}/receivers {receivers:[...]} → links. IP는 보내지 않는다.
3. links에 있는 수신기만 수집해 POST .../observations로 보낸다. sourceRef = receiverKey, runId = 교환 때 받은 값.
4. 401(토큰 폐기: 해제·넘겨받기)·403 COLLECTOR_LINK_FORBIDDEN(자격 상실)이면 멈추고 WEB에서 다시 연결하라고 알린다.

지키는 것: 수신기에 설정 명령을 보내지 않는다(client.py가 읽기·계측만 한다), 수신기 IP·토큰을 기록·화면에 남기지 않는다,
네트워크를 탐색하지 않고 수신기 알림만 듣는다(announce.py), 미수신을 0·정상으로 바꾸지 않는다.
"""

import asyncio
import hashlib
import http.client
import json
import os
import re
import select
import socket
import struct
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from announce import SLP_GROUP, SLP_PORT, open_listener
from client import CollectorConfig, QlxdCollector
from observation import RecordFactory, Sequence
from sinks import HttpBatchSink
from state import SqliteState

PROGRAM_VERSION = "0.1.1"
DEFAULT_SERVER = "http://127.0.0.1:8080"
REPORT_INTERVAL_S = 10.0
# 이 시간 동안 알림이 없던 수신기는 목록에서 뺀다(수신기는 약 4초마다 알린다).
RECEIVER_FORGET_S = 30.0
MAX_RECEIVERS = 16
RELINK_HINT = "WEB의 '내 연결'에서 새 연결 코드를 받아 다시 연결하세요."


class ServerError(RuntimeError):
    def __init__(self, status: int | None, code: str | None, message: str):
        super().__init__(message)
        self.status = status
        self.code = code


def data_dir() -> Path:
    """연결 정보와 상태 파일을 두는 노트북 사용자 폴더. 시험에서는 SCENECREW_COLLECTOR_HOME으로 바꾼다."""
    override = os.environ.get("SCENECREW_COLLECTOR_HOME")
    if override:
        return Path(override)
    base = os.environ.get("APPDATA")
    if base:
        return Path(base) / "SceneCrew" / "equipment-collector"
    return Path.home() / ".scenecrew" / "equipment-collector"


@dataclass
class Credentials:
    server: str
    collector_id: str
    token: str
    run_id: str

    @staticmethod
    def path(home: Path | None = None) -> Path:
        return (home or data_dir()) / "connection.json"

    @classmethod
    def load(cls, home: Path | None = None) -> "Credentials | None":
        p = cls.path(home)
        try:
            raw = json.loads(p.read_text(encoding="utf-8"))
            return cls(raw["server"], raw["collectorId"], raw["token"], raw["runId"])
        except FileNotFoundError:
            return None
        except (OSError, ValueError, KeyError, TypeError):
            # 깨진 파일은 쓰지 않는다. 새 코드로 다시 연결한다.
            return None

    def save(self, home: Path | None = None) -> None:
        p = self.path(home)
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_suffix(".tmp")
        tmp.write_text(json.dumps({"server": self.server, "collectorId": self.collector_id,
                                   "token": self.token, "runId": self.run_id}), encoding="utf-8")
        os.replace(tmp, p)

    @classmethod
    def forget(cls, home: Path | None = None) -> None:
        try:
            cls.path(home).unlink()
        except FileNotFoundError:
            pass

    def state_path(self, home: Path | None = None) -> Path:
        return (home or data_dir()) / f"state-{self.collector_id}.db"


def normalize_code(text: str) -> str:
    """대소문자·하이픈·공백은 서버가 무시하지만, 보이는 값은 XXXX-XXXX로 맞춰 보낸다."""
    letters = re.sub(r"[\s-]", "", text or "").upper()
    return f"{letters[:4]}-{letters[4:]}" if len(letters) == 8 else letters


class LinkApi:
    """업무 서버의 수집기 경로. 표준 라이브러리 urllib만 쓴다."""

    def __init__(self, server: str, timeout_s: float = 10.0):
        self.server = server.rstrip("/")
        self.timeout_s = timeout_s

    def observations_url(self, collector_id: str) -> str:
        return f"{self.server}/internal/equipment-collectors/{collector_id}/observations"

    def pair(self, code: str, host_label: str | None) -> Credentials:
        body = {"code": normalize_code(code), "programVersion": PROGRAM_VERSION}
        if host_label:
            body["hostLabel"] = host_label[:60]
        data = self._request("POST", "/internal/equipment-collectors/pair", body)
        try:
            return Credentials(self.server, str(data["collectorId"]), str(data["token"]), str(data["runId"]))
        except (KeyError, TypeError) as e:
            raise ServerError(200, None, "서버 응답 형식이 다릅니다.") from e

    def report(self, cred: Credentials, receivers: list["Receiver"]) -> set[str]:
        body = {"receivers": [r.payload() for r in receivers[:MAX_RECEIVERS]]}
        data = self._request("PUT", f"/internal/equipment-collectors/{cred.collector_id}/receivers", body,
                             token=cred.token)
        links = data.get("links") if isinstance(data, dict) else None
        if not isinstance(links, list):
            raise ServerError(200, None, "서버 응답에 연결 목록(links)이 없습니다. 서버 버전을 확인하세요.")
        return {str(link["receiverKey"]) for link in links if isinstance(link, dict) and "receiverKey" in link}

    def _request(self, method: str, path: str, body: dict, token: str | None = None) -> dict:
        req = urllib.request.Request(self.server + path, data=json.dumps(body).encode("utf-8"), method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("User-Agent", f"scenecrew-equipment-collector/{PROGRAM_VERSION}")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_s) as resp:
                payload = resp.read()
        except urllib.error.HTTPError as e:
            try:
                payload = e.read() if e.fp else b""
            except (http.client.HTTPException, OSError):
                payload = b""
            code = _code(payload)
            raise ServerError(e.code, code, f"서버가 요청을 거절했습니다({e.code}{' ' + code if code else ''}).") from e
        except (urllib.error.URLError, http.client.HTTPException, OSError) as e:
            raise ServerError(None, None, f"서버에 연결하지 못했습니다({type(e).__name__}).") from e
        try:
            return json.loads(payload or b"{}")
        except ValueError as e:
            raise ServerError(200, None, "서버 응답을 읽지 못했습니다.") from e


def _code(payload: bytes) -> str | None:
    try:
        value = json.loads(payload).get("code")
        return value if isinstance(value, str) else None
    except (ValueError, AttributeError):
        return None


def ends_link(error: ServerError) -> bool:
    return error.status == 401 or (error.status == 403 and error.code == "COLLECTOR_LINK_FORBIDDEN")


@dataclass(frozen=True)
class Receiver:
    """찾은 수신기. host·port·source_ip는 접속에만 쓰고 서버·기록에는 보내지 않는다."""

    key: str
    name: str
    model: str
    host: str
    port: int = 2202
    channels: int = 1
    simulated: bool = False
    source_ip: str | None = None

    def payload(self) -> dict:
        return {"receiverKey": self.key, "name": self.name, "model": self.model, "channels": self.channels}


def receiver_key(cid: str | None, addr: str) -> str:
    """불투명 키. 장치 ID(cid)가 있으면 그것으로, 없으면 주소의 해시로 만든다(주소 자체는 넣지 않는다)."""
    if cid:
        return "qlxd4-" + re.sub(r"[^A-Za-z0-9]", "", cid).lower()[:60]
    return "qlxd4-" + hashlib.sha256(addr.encode()).hexdigest()[:12]


class Discovery:
    """수신기 SLP 알림을 계속 듣는다. 아무것도 보내지 않는다.

    직결 어댑터마다 소켓을 따로 열어(그 어댑터 주소에 bind) 어느 수신기가 어느 어댑터에 붙었는지 안다.
    수신기 여러 대를 USB 랜 어댑터 여러 개로 붙이면 OS 경로로는 엉뚱한 어댑터로 접속할 수 있어서다(#399 실기).
    어댑터 목록은 30초마다 다시 읽어, 실행 뒤 꽂은 어댑터도 잡는다.
    """

    def __init__(self, ifaces: list[str] | None = None, *, find_ifaces=None, clock=time.monotonic):
        self._fixed = list(ifaces) if ifaces else None
        self._find = find_ifaces or (lambda: [])
        self.clock = clock
        self._seen: dict[str, tuple[Receiver, float]] = {}
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    @property
    def ifaces(self) -> list[str]:
        return self._fixed if self._fixed is not None else self._find()

    def start(self) -> None:
        self._thread = threading.Thread(target=self._listen, name="slp-discovery", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def heard(self, addr: str, text: str, iface: str | None = None) -> None:
        """iface: 알림을 받은 어댑터 주소. 그 어댑터로 수신기에 접속한다(모르면 OS 경로)."""
        if "QLXD" not in text:
            return  # 다른 Shure 장비(믹서 등)는 수집 대상이 아니다
        cid = re.search(r"cid=([0-9A-Fa-f-]+)", text)
        cid_value = cid.group(1) if cid else None
        key = receiver_key(cid_value, addr)
        tail = (cid_value or key)[:8].upper() if cid_value else key[-4:].upper()
        receiver = Receiver(key=key, name=f"QLXD4 {tail}", model="QLXD4", host=addr, source_ip=iface)
        with self._lock:
            self._seen[key] = (receiver, self.clock())

    def receivers(self) -> list[Receiver]:
        now = self.clock()
        with self._lock:
            for key in [k for k, (_, at) in self._seen.items() if now - at > RECEIVER_FORGET_S]:
                del self._seen[key]
            return [r for r, _ in self._seen.values()]

    def _open(self, ifaces: list[str]) -> dict:
        """어댑터마다 소켓 하나. 어댑터를 모르면 모든 어댑터에서 듣는 소켓 하나(어댑터 구분 없음)."""
        if not ifaces:
            return {open_listener([]): None}
        socks = {}
        for ip in ifaces:
            try:
                socks[open_iface_listener(ip)] = ip
            except OSError:
                continue  # 막 빠진 어댑터 등. 다음 창에서 다시 연다
        return socks

    def _listen(self) -> None:
        while not self._stop.is_set():
            try:
                socks = self._open(self.ifaces)
            except OSError:
                socks = {}
            if not socks:
                time.sleep(2.0)
                continue
            try:
                # 어댑터가 다시 인식되거나 새로 꽂힐 수 있어 30초마다 목록과 소켓을 새로 연다
                window_end = time.monotonic() + 30.0
                while time.monotonic() < window_end and not self._stop.is_set():
                    ready, _, _ = select.select(list(socks), [], [], 1.0)
                    for sock in ready:
                        data, (addr, _) = sock.recvfrom(4096)
                        self.heard(addr, data.decode("latin-1"), socks[sock])
            except OSError:
                time.sleep(1.0)
            finally:
                for sock in socks:
                    sock.close()


def open_iface_listener(ip: str) -> socket.socket:
    """그 어댑터로 들어온 SLP 알림만 받는 소켓. Windows는 어댑터 주소에 bind하면 그 어댑터의 멀티캐스트만 받는다."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((ip, SLP_PORT))
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                        struct.pack("4s4s", socket.inet_aton(SLP_GROUP), socket.inet_aton(ip)))
    except OSError:
        sock.close()
        raise
    return sock


class FixedReceivers:
    """시뮬레이터 수신기(--sim host:port). 알림 없이 바로 접속한다."""

    def __init__(self, targets: list[tuple[str, int]]):
        self._list = [Receiver(key=f"sim-{port}", name=f"시뮬레이터 {port}", model="QLXD4", host=host, port=port,
                               simulated=True) for host, port in targets]

    def start(self) -> None:
        pass

    def stop(self) -> None:
        pass

    def receivers(self) -> list[Receiver]:
        return list(self._list)


class Supervisor:
    """수신기 보고를 주기적으로 보내고, 서버가 연결한 수신기만 수집 작업을 돌린다."""

    def __init__(self, api: LinkApi, cred: Credentials, source, state, *, mode: str = "LIVE",
                 report_interval_s: float = REPORT_INTERVAL_S, out=None, collector_options: dict | None = None,
                 sink_options: dict | None = None):
        self.api = api
        self.cred = cred
        self.source = source
        self.state = state
        self.mode = mode
        self.report_interval_s = report_interval_s
        self.out = out or (lambda msg: print(msg, flush=True))
        self.collector_options = collector_options or {}
        self.links: set[str] = set()
        self.running: dict[str, tuple[asyncio.Event, asyncio.Task]] = {}
        self.stop_reason: ServerError | None = None
        self.sequence = Sequence(state.high_water)
        self.http = HttpBatchSink(api.observations_url(cred.collector_id), cred.collector_id, cred.token,
                                  state=state, allowed=lambda ref: ref in self.links,
                                  on_auth_failure=self._ingest_stopped, on_unmapped=self._refresh_soon,
                                  warn=self.out, **(sink_options or {}))
        self._stop: asyncio.Event | None = None
        self._refresh: asyncio.Event | None = None
        self._names: dict[str, str] = {}

    def _ingest_stopped(self, status, code) -> None:
        self.stop_reason = ServerError(status, code, "서버가 관측 기록을 더 받지 않습니다.")
        if self._stop is not None:
            self._stop.set()

    def _refresh_soon(self) -> None:
        # 서버에서 막 해제된 수신기가 있다. 다음 보고를 기다리지 않고 연결 목록을 다시 받는다.
        if self._refresh is not None:
            self._refresh.set()

    async def run(self, stop: asyncio.Event) -> ServerError | None:
        self._stop = stop
        self._refresh = asyncio.Event()
        self.source.start()
        self.http.start()
        try:
            while not stop.is_set():
                await self._report_once()
                if stop.is_set():
                    break
                await _first(stop, self._refresh, self.report_interval_s)
                self._refresh.clear()
        finally:
            self.source.stop()
            for key in list(self.running):
                await self._stop_receiver(key)
            await self.http.close()
        return self.stop_reason

    async def _report_once(self) -> None:
        found = self.source.receivers()
        by_key = {r.key: r for r in found}
        self._names.update({r.key: r.name for r in found})
        try:
            links = await asyncio.to_thread(self.api.report, self.cred, found)
        except ServerError as e:
            if ends_link(e):
                self.stop_reason = e
                self._stop.set()
                return
            # 잠깐 서버에 닿지 않으면 지금 연결대로 계속 수집한다(기록은 상태 파일에 쌓인다)
            self.out(f"수신기 보고 실패: {e}. 10초 뒤 다시 보냅니다.")
            return
        for key in sorted(links - self.links):
            self.out(f"장비 칸에 연결됨: {self._names.get(key, key)}")
        for key in sorted(self.links - links):
            self.out(f"연결 해제됨: {self._names.get(key, key)}")
        self.links = links
        for key in list(self.running):
            if key not in links:
                await self._stop_receiver(key)
        for key in sorted(links):
            if key not in self.running and key in by_key:
                self._start_receiver(by_key[key])

    def _start_receiver(self, receiver: Receiver) -> None:
        config = CollectorConfig(host=receiver.host, port=receiver.port, source_ip=receiver.source_ip,
                                 wait_for_announce=not receiver.simulated, **self.collector_options)
        factory = RecordFactory(collector_id=self.cred.collector_id, source_ref=receiver.key, mode=self.mode,
                                run_id=self.cred.run_id, counter=self.sequence)
        stop = asyncio.Event()
        collector = QlxdCollector(config, factory, self.http)
        self.running[receiver.key] = (stop, asyncio.create_task(collector.run(stop)))
        self.out(f"수집 시작: {receiver.name}")

    async def _stop_receiver(self, key: str) -> None:
        stop, task = self.running.pop(key)
        stop.set()
        try:
            await asyncio.wait_for(task, 5)
        except (asyncio.TimeoutError, Exception):  # noqa: BLE001 - 멈추는 중의 오류는 다른 수신기를 막지 않는다
            task.cancel()
        self.out(f"수집 멈춤: {self._names.get(key, key)}")


async def _first(a: asyncio.Event, b: asyncio.Event, timeout_s: float) -> None:
    waits = [asyncio.create_task(a.wait()), asyncio.create_task(b.wait())]
    try:
        await asyncio.wait(waits, timeout=timeout_s, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for w in waits:
            w.cancel()


def open_state(cred: Credentials, home: Path | None = None) -> SqliteState:
    return SqliteState(cred.state_path(home), cred.collector_id, cred.run_id)


def host_label() -> str:
    return socket.gethostname()[:60]


def link_local_ifaces() -> list[str]:
    from discover import link_local_ifaces as find
    return find() if sys.platform == "win32" else []

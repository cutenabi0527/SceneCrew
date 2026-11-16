"""관측 기록을 내보내는 곳. 수집 루프는 sink.emit(record)만 호출한다.

- JsonlSink: 실기 증거용 로컬 파일. 한 줄에 기록 하나.
- ConsoleSink: 현장에서 사람이 보는 요약 출력.
- HttpBatchSink: BE 수집 경로가 정해지면 쓸 일괄 전송. 계약은 초안이며 BE 구현 전에는 시험 서버로만 확인한다.
- RawCapture: 수신 바이트 원문을 수신 시각과 함께 남긴다(#302 원시 응답 증거).

현장 IP·토큰은 기록에 넣지 않는다.
"""

import asyncio
import http.client
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

from observation import SCHEMA, utc_now
from state import MemoryState


class MultiSink:
    def __init__(self, *sinks):
        self.sinks = [s for s in sinks if s is not None]

    async def emit(self, record: dict) -> None:
        for sink in self.sinks:
            await sink.emit(record)

    async def close(self) -> None:
        for sink in self.sinks:
            await sink.close()


class MemorySink:
    """시험용. 받은 기록을 그대로 쌓는다."""

    def __init__(self):
        self.records: list[dict] = []

    async def emit(self, record: dict) -> None:
        self.records.append(record)

    async def close(self) -> None:
        pass

    def of(self, record_type: str) -> list[dict]:
        return [r for r in self.records if r["recordType"] == record_type]


class JsonlSink:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._fh = self.path.open("a", encoding="utf-8")

    async def emit(self, record: dict) -> None:
        self._fh.write(json.dumps(record, ensure_ascii=False) + "\n")
        self._fh.flush()

    async def close(self) -> None:
        self._fh.close()


class ConsoleSink:
    """LINK·STATUS 변화와 REPORT는 항상, SAMPLE은 every번째마다 한 줄로 출력한다."""

    def __init__(self, sample_every: int = 10, stream=None):
        self.sample_every = max(1, sample_every)
        self.stream = stream or sys.stdout
        self._samples = 0
        self._last_status = None

    async def emit(self, record: dict) -> None:
        kind = record["recordType"]
        line = None
        if kind == "LINK":
            reason = f" ({record['reason']})" if record.get("reason") else ""
            line = f"[LINK] {record['linkState']}{reason}"
        elif kind == "STATUS":
            key = (record["linkState"], record["metering"])
            if key != self._last_status:
                self._last_status = key
                age = "-" if record["sampleAgeMs"] is None else f"{record['sampleAgeMs']}ms"
                line = f"[STATUS] link={key[0]} metering={key[1]} sampleAge={age}"
        elif kind == "UNPARSED":
            line = f"[UNPARSED] {record['frame']}"
        elif record.get("origin") == "REPORT":
            line = f"[REP] {record['param']}={record['value']!r} ({record['valueStatus']}, raw={record['raw']!r})"
        elif record.get("param") == "RF_ANTENNA":
            self._samples += 1
            if self._samples % self.sample_every == 1 or self.sample_every == 1:
                line = f"[SAMPLE] {record['frame']}"
        if line:
            print(f"{record['receivedAt']} #{record['sequence']} {line}", file=self.stream, flush=True)

    async def close(self) -> None:
        pass


class HttpBatchSink:
    """기록을 묶어 POST한다. 기록은 상태 저장소(state.py)에 먼저 넣고, 서버 ACK를 확인한 뒤에만 지운다.

    전송 실패가 수집을 멈추지 않도록 별도 작업으로 돈다. 토큰은 Authorization 헤더로만 보낸다.
    실패는 수집 내부 계약(#330, !128 internal-collector-proposed.yaml)의 재시도 구분을 따른다.
    - 네트워크 오류·429·5xx: 같은 묶음을 그대로 다시 보낸다(Retry-After가 있으면 따른다).
    - 401: 토큰 문제다. 기록을 버리지 않고 경고한 뒤 천천히 다시 보낸다.
    - 그 밖의 4xx(400·403·409·413·422 등): 다시 보내도 결과가 같다. 그 묶음을 저장소의 거절 표로 옮기고
      (지정했으면 거절 기록 파일에도 남기고) 다음 기록으로 넘어간다. 새 run으로 우회하지 않는다.
    - 2xx라도 ACK가 보낸 묶음과 맞지 않으면 지우지 않고 경고한 뒤 같은 묶음으로 다시 확인한다.
    """

    def __init__(self, url: str, collector_id: str, token: str | None = None, *, batch_size: int = 100,
                 flush_interval_s: float = 1.0, max_queue: int = 5000, timeout_s: float = 5.0,
                 rejected_path: str | Path | None = None, max_backoff_s: float = 30.0,
                 auth_backoff_s: float = 60.0, warn=None, state=None, allowed=None, on_auth_failure=None,
                 on_unmapped=None):
        self.url = url
        # 웹 연결(#399): allowed(sourceRef)가 False인 기록은 서버에 보내지 않고 버린다(연결 해제된 수신기).
        # 섞여 보내면 묶음 전체가 422 COLLECTOR_SOURCE_UNMAPPED로 거절되기 때문이다.
        self.allowed = allowed
        self.unlinked_dropped = 0
        # 웹 연결(#399): 401(토큰 폐기)·403 COLLECTOR_LINK_FORBIDDEN(자격 상실)이면 보내기를 멈추고 알린다.
        self.on_auth_failure = on_auth_failure
        self.stopped_by_server: tuple[int, str | None] | None = None
        # 웹 연결(#399): 서버에서 막 해제된 수신기 기록이 섞여 422 COLLECTOR_SOURCE_UNMAPPED가 오면 묶음을 버리지 않고
        # 연결 목록을 바로 다시 받게 한 뒤(on_unmapped) 같은 기록을 걸러 다시 보낸다.
        self.on_unmapped = on_unmapped
        self.collector_id = collector_id
        self.token = token
        self.batch_size = batch_size
        self.flush_interval_s = flush_interval_s
        self.timeout_s = timeout_s
        self.max_backoff_s = max_backoff_s
        self.auth_backoff_s = auth_backoff_s
        # 상태 파일이 없으면 메모리 저장소를 쓴다(재시작하면 잃는다. 시험·시뮬레이터 전용).
        self.state = state if state is not None else MemoryState(collector_id, max_queue=max_queue)
        self.sent = 0
        self.duplicates = 0
        self.failures = 0
        self.rejected = 0
        # 이 실행에서 한도를 넘어 버린 기록 수. state.dropped는 서버에 알린 뒤 0이 되므로 따로 센다.
        self.dropped_total = 0
        self._dropping = False
        self.last_error: str | None = None
        self.rejected_path = Path(rejected_path) if rejected_path else None
        self._warn = warn or (lambda msg: print(msg, file=sys.stderr, flush=True))
        self._task: asyncio.Task | None = None
        self._closing = False
        # 시험에서 실제 대기 시간을 확인하려고 바꿔 끼울 수 있게 둔다
        self._sleep = asyncio.sleep

    @property
    def dropped(self) -> int:
        return self.state.dropped

    @property
    def queued(self) -> int:
        return self.state.pending_count()

    def start(self) -> None:
        """재시작 뒤 남은 미전송 기록이 있으면 새 기록을 기다리지 않고 바로 보낸다."""
        if self.state.dropped:
            self._warn(f"이전 실행에서 미전송 기록 {self.state.dropped}건을 한도 초과로 버렸습니다. "
                       "다음 전송 때 서버에 알립니다.")
        if self._task is None and self.state.pending_count():
            self._task = asyncio.create_task(self._run())

    async def emit(self, record: dict) -> None:
        before = self.state.dropped
        self.state.enqueue(record)
        newly = self.state.dropped - before
        if newly > 0:
            self.dropped_total += newly
            if not self._dropping:
                # 넘치기 시작할 때 한 번 알린다. 서버가 다시 받으면 풀려서 다음에 또 넘치면 다시 알린다.
                self._dropping = True
                self._warn(f"미전송 기록이 한도({self.state.max_queue}건)를 넘어 가장 오래된 기록부터 버립니다. "
                           f"서버 연결을 확인하세요(마지막 오류: {self.last_error}).")
        if self._task is None:
            self._task = asyncio.create_task(self._run())

    async def _run(self) -> None:
        backoff = self.flush_interval_s
        while True:
            batch = self.state.pending(self.batch_size)
            if not batch:
                if self._closing:
                    return
                await self._sleep(self.flush_interval_s)
                continue
            if self.allowed is not None:
                dropped = [r["sequence"] for r in batch if not self.allowed(r.get("sourceRef"))]
                if dropped:
                    self.state.discard(dropped)
                    self.unlinked_dropped += len(dropped)
                    continue
            sequences = [r["sequence"] for r in batch]
            body = {
                "schema": SCHEMA,
                "collectorId": self.collector_id,
                "sentAt": utc_now(),
                "droppedSinceLast": self.state.dropped,
                "records": batch,
            }
            status, payload, retry_after = await asyncio.to_thread(self._post, body)
            if status is not None and 200 <= status < 300:
                problem, duplicates = _check_ack(payload, self.collector_id, batch)
                if problem is None:
                    self.state.acknowledge(sequences)
                    if self._dropping:
                        self._warn(f"수집 서버에 다시 보내고 있습니다. 지금까지 버린 기록은 {self.dropped_total}건입니다.")
                        self._dropping = False
                    self.sent += len(batch) - duplicates
                    self.duplicates += duplicates
                    self.last_error = None
                    backoff = self.flush_interval_s
                    continue
                # 서버는 받았을 수 있지만 확인할 수 없다. 지우지 않고 같은 묶음으로 다시 확인한다.
                self.failures += 1
                self.last_error = f"ack {problem}"
                self._warn(f"수집 서버의 확인 응답이 보낸 묶음과 맞지 않습니다({problem}). "
                           "기록은 지우지 않고 같은 묶음으로 다시 확인합니다.")
                if self._closing:
                    return
                wait, backoff = _next_wait(None, retry_after, backoff, max_backoff_s=self.max_backoff_s,
                                           auth_backoff_s=self.auth_backoff_s)
                await self._sleep(wait)
                continue
            self.failures += 1
            code = _error_code(payload)
            self.last_error = f"{status or 'network'}{' ' + code if code else ''}"
            if self.on_auth_failure is not None and _ends_web_link(status, code):
                # 기록은 지우지 않고 멈춘다. 다시 연결하면 새 수집기·run이라 이 기록은 보내지 않는다.
                self.stopped_by_server = (status, code)
                self.on_auth_failure(status, code)
                return
            if status == 422 and code == "COLLECTOR_SOURCE_UNMAPPED" and self.on_unmapped is not None:
                self.on_unmapped()
                await self._sleep(self.flush_interval_s)
                continue
            if status is not None and _rejects_batch(status):
                # 다시 보내도 같은 결과다. 묶음을 격리하고 다음 기록으로 넘어간다.
                entry = {"rejectedAt": utc_now(), "status": status, "code": code,
                         "requestId": _request_id(payload), "batch": body}
                self.state.quarantine(sequences, entry)
                self.rejected += len(batch)
                saved = self._record_rejected(entry)
                if self.state.durable:
                    where = "상태 파일의 거절 표에 남겼습니다."
                elif saved:
                    where = "거절 기록을 확인하세요."
                elif self.rejected_path is None:
                    where = "거절 기록 파일을 지정하지 않아 내용은 남기지 않았습니다."
                else:
                    where = "거절 기록도 저장하지 못했습니다."
                self._warn(f"수집 서버가 기록 {len(batch)}건을 거절했습니다({self.last_error}). "
                           f"{where} 같은 기록은 다시 보내지 않습니다.")
                backoff = self.flush_interval_s
                continue
            if self._closing:
                return
            if status == 401:
                self._warn("수집 서버가 토큰을 거절했습니다(401). 토큰을 확인하세요. 기록은 보관하고 다시 보냅니다.")
            wait, backoff = _next_wait(status, retry_after, backoff,
                                       max_backoff_s=self.max_backoff_s, auth_backoff_s=self.auth_backoff_s)
            await self._sleep(wait)

    def _record_rejected(self, entry: dict) -> bool:
        """거절 묶음을 파일에도 남긴다. 저장하지 못해도 전송은 멈추지 않고 경고만 한다."""
        if self.rejected_path is None:
            return False
        try:
            self.rejected_path.parent.mkdir(parents=True, exist_ok=True)
            with self.rejected_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        except OSError as e:
            self._warn(f"거절 기록을 저장하지 못했습니다({type(e).__name__}: {e}). 다음 기록은 계속 보냅니다.")
            return False
        return True

    def _post(self, body: dict) -> tuple[int | None, bytes, float | None]:
        """(HTTP 상태, 응답 본문, Retry-After 초)를 돌려준다. 연결 자체가 실패하면 상태는 None이다."""
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        # 응답 본문을 읽다가 연결이 끊기면 연결 실패로 보고 같은 묶음을 다시 보낸다.
        # 2xx 본문이 끊긴 경우 서버가 이미 저장했을 수 있다. 중복은 서버가 runId·sequence로 가린다.
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_s) as resp:
                return resp.status, resp.read(), None
        except urllib.error.HTTPError as e:
            # 이 블록 안에서 난 예외는 아래 except가 잡지 않으므로 본문 읽기를 따로 감싼다
            try:
                payload = e.read() if e.fp else b""
            except (http.client.HTTPException, OSError):
                return None, b"", None
            return e.code, payload, _retry_after(e.headers.get("Retry-After") if e.headers else None)
        except (urllib.error.URLError, http.client.HTTPException, OSError):
            return None, b"", None

    async def close(self) -> None:
        self._closing = True
        if self._task is not None:
            try:
                await asyncio.wait_for(self._task, timeout=self.timeout_s * 2)
            except asyncio.TimeoutError:
                self._task.cancel()


def _ends_web_link(status: int | None, code: str | None) -> bool:
    """웹 연결 수집기를 멈춰야 하는 응답인가. 401은 토큰 폐기(해제·넘겨받기), 403은 연결 자격 상실이다."""
    return status == 401 or (status == 403 and code == "COLLECTOR_LINK_FORBIDDEN")


def _rejects_batch(status: int) -> bool:
    """다시 보내도 결과가 같은 상태인가. 401·429는 재시도 대상이고 5xx도 재시도한다."""
    return 400 <= status < 500 and status not in (401, 408, 429)


def _next_wait(status: int | None, retry_after: float | None, backoff: float, *,
               max_backoff_s: float, auth_backoff_s: float) -> tuple[float, float]:
    """(이번 대기 초, 다음 기본 간격)을 돌려준다.

    서버가 Retry-After를 주면 자르지 않고 그 시간을 그대로 기다린다. 30초 상한은 수집기가 스스로
    늘리는 간격에만 적용한다. 401은 토큰을 고칠 때까지 천천히 다시 보낸다.
    """
    if status == 401:
        return max(auth_backoff_s, retry_after or 0.0), backoff
    wait = retry_after if retry_after is not None else backoff
    return wait, min(backoff * 2, max_backoff_s)


def _is_count(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _check_ack(payload: bytes, collector_id: str, batch: list[dict]) -> tuple[str | None, int]:
    """2xx ACK가 보낸 묶음을 모두 처리했다고 말하는지 본다. (문제, 중복 수)를 돌려준다.

    계약 ACK: collectorId·receivedAt·accepted·duplicates·runs[{runId, lastSequence}].
    accepted+duplicates가 보낸 건수와 같고, 보낸 run마다 lastSequence가 보낸 최대 sequence 이상이어야 한다.
    lastSequence는 서버가 저장한 최대값이지 연속 수신의 증거가 아니므로 다음 번호 계산에 쓰지 않는다.
    """
    ack = _json(payload)
    if not ack:
        return "본문 없음", 0
    if ack.get("collectorId") != collector_id:
        return "collectorId 다름", 0
    accepted, duplicates = ack.get("accepted"), ack.get("duplicates")
    if not _is_count(accepted) or not _is_count(duplicates):
        return "건수 형식", 0
    if accepted + duplicates != len(batch):
        return f"건수 {accepted}+{duplicates}≠{len(batch)}", 0
    runs = ack.get("runs")
    if not isinstance(runs, list):
        return "runs 형식", 0
    last = {r.get("runId"): r.get("lastSequence") for r in runs if isinstance(r, dict)}
    for run_id in {r["runId"] for r in batch}:
        top = max(r["sequence"] for r in batch if r["runId"] == run_id)
        seen = last.get(run_id)
        if not _is_count(seen) or seen < top:
            return f"run {run_id} lastSequence", 0
    return None, duplicates


def _json(payload: bytes) -> dict:
    # 프록시의 413·429는 JSON이 아닐 수 있다. 상태 코드로만 판단하고 본문은 참고만 한다.
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def _error_code(payload: bytes) -> str | None:
    code = _json(payload).get("code")
    return code if isinstance(code, str) else None


def _request_id(payload: bytes) -> str | None:
    rid = _json(payload).get("requestId")
    return rid if isinstance(rid, str) else None


def _retry_after(value: str | None) -> float | None:
    if not value:
        return None
    try:
        seconds = float(value)
    except ValueError:
        return None
    return seconds if seconds >= 0 else None


class RawCapture:
    """수신 바이트를 가공 없이 남긴다. 각 줄은 수신 시각, 세션, 바이트(이스케이프)다."""

    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._fh = self.path.open("a", encoding="utf-8")

    def write(self, session_id: str | None, direction: str, data: bytes) -> None:
        entry = {"at": utc_now(), "sessionId": session_id, "dir": direction,
                 "bytes": data.decode("latin-1")}
        self._fh.write(json.dumps(entry, ensure_ascii=False) + "\n")
        self._fh.flush()

    def close(self) -> None:
        self._fh.close()

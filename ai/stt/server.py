"""현장 보고 음성 전사 내부 API (공통 요청4 · 전사 결과2).

BE가 POST /internal/transcribe로 요청4(kind=TRANSCRIBE)를 보내면, AI 서버가 음성을 받아 전사하고 결과2를 반환한다.
요청3/결과1은 받지 않는다(동시 운영 없음, STT 명세 Q2~Q4). 계약 모델은 contract.py, 기준은
docs/planning/records/STT-CONTRACT-TRANSITION.md와 docs/planning/ai/07-contracts.md다.

실행 (ai/stt에서):
    uv run uvicorn server:create_app --factory --host 0.0.0.0 --port 8000

환경변수는 Settings.from_env를 참고한다. AI_SERVICE_TOKEN과 STT_AUDIO_URL_PREFIXES는 필수다.
"""

import asyncio
import hmac
import logging
import os
import tempfile
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from functools import partial
from pathlib import Path

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from contract import RESULT_SCHEMA_VERSION, UNKNOWN_IDENTITY, TranscribeRequest, known_identity
from transcribe import Transcriber
from worker import AudioRejected, TranscriberWorker, WorkerFailed, WorkerTimeout, runner_factory

DOWNLOAD_CHUNK = 64 * 1024

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Settings:
    service_token: str
    audio_url_prefixes: tuple
    model: str = "large-v3"
    device: str = "cpu"
    compute_type: str = "int8"
    cpu_threads: int = 2
    timeout_sec: float = 90
    max_seconds: float = 30
    max_bytes: int = 10 * 1024 * 1024
    download_timeout_sec: float = 5
    # 다운로드 뒤 남은 시간이 이보다 짧으면 추론을 시작하지 않는다. 끝낼 수 없는 추론을 보내면 작업 프로세스를
    # 강제 종료하고 모델을 다시 불러와야 해서, 그동안 다른 요청까지 AI_BUSY가 된다.
    min_run_sec: float = 1

    @classmethod
    def from_env(cls):
        """기본값은 06-voice-model.md의 추론 기준(large-v3, CPU int8, 2 threads, 90초, 30초·10MB)이다."""
        token = os.environ.get("AI_SERVICE_TOKEN", "")
        prefixes = tuple(p.strip() for p in os.environ.get("STT_AUDIO_URL_PREFIXES", "").split(",") if p.strip())
        if not token:
            raise RuntimeError("AI_SERVICE_TOKEN이 설정되지 않았습니다.")
        if not prefixes:
            raise RuntimeError("STT_AUDIO_URL_PREFIXES가 설정되지 않았습니다.")
        if not all(p.endswith("/") for p in prefixes):
            raise RuntimeError("STT_AUDIO_URL_PREFIXES의 각 값은 /로 끝나야 합니다.")
        return cls(
            service_token=token,
            audio_url_prefixes=prefixes,
            model=os.environ.get("STT_MODEL", cls.model),
            device=os.environ.get("STT_DEVICE", cls.device),
            compute_type=os.environ.get("STT_COMPUTE_TYPE", cls.compute_type),
            cpu_threads=int(os.environ.get("STT_CPU_THREADS", cls.cpu_threads)),
            timeout_sec=float(os.environ.get("STT_TIMEOUT_SEC", cls.timeout_sec)),
            max_seconds=float(os.environ.get("STT_MAX_SECONDS", cls.max_seconds)),
            max_bytes=int(float(os.environ.get("STT_MAX_UPLOAD_MB", 10)) * 1024 * 1024),
            download_timeout_sec=float(os.environ.get("STT_DOWNLOAD_TIMEOUT_SEC", cls.download_timeout_sec)),
            min_run_sec=float(os.environ.get("STT_MIN_RUN_SEC", cls.min_run_sec)),
        )

    @property
    def model_version(self):
        return f"faster-whisper-{Path(self.model).name}-{self.compute_type}"

    def runner_factory(self):
        return runner_factory(partial(Transcriber, self.model, self.device, self.compute_type, self.cpu_threads))


class AiError(Exception):
    def __init__(self, status, code, message, retryable=False, details=None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.retryable = retryable
        self.details = details or {}


def error_response(status, code, message, retryable=False, details=None, identity=None):
    """실패 결과. 식별 5개는 키를 두고, 모르거나 형식이 틀린 값은 null이다(Q12)."""
    error = {"code": code, "message": message, "retryable": retryable}
    if details:
        error["details"] = details
    return JSONResponse(status_code=status, content={**(identity or UNKNOWN_IDENTITY), "error": error})


def audio_invalid(reason, message, **details):
    return AiError(422, "AUDIO_INVALID", message, details={"reason": reason, **details})


def fetch_unavailable(**details):
    """다운로드 5xx·연결 실패·응답 지연. 마감 전이면 BE가 남은 기한 안에서 다시 보낸다(Q12)."""
    return AiError(503, "AI_UNAVAILABLE", "음성 파일을 가져오지 못했습니다.", True,
                   {"reason": "FETCH_FAILED", **details})


def timed_out():
    return AiError(504, "AI_TIMEOUT", "전사 마감을 넘겼습니다.")


def invalid_field(field, message):
    return AiError(400, "INVALID_REQUEST", message, details={"field": field})


def check_audio_url(url, audio_id, settings):
    """허용한 prefix 뒤에 audioId만 붙은 URL(`{prefix}{audioId}`)만 받는다(명세 Q9의 `/internal/audio-assets/{audioId}`).

    문자열 전체를 그대로 비교하므로 앞뒤 경로 구간·쿼리·조각·퍼센트 인코딩·대소문자 변형이 붙으면 모두 거절한다.
    다른 주소로 요청을 보내게 하거나(SSRF) 다른 음성을 읽게 하는 입력을 막는다.
    서비스 토큰·jobId/eventId 연결·Job 상태와 마감·보관 수명은 다운로드 엔드포인트(BE)가 검증한다(Q9).
    """
    if url not in {prefix + audio_id for prefix in settings.audio_url_prefixes}:
        raise invalid_field("payload.audioUrl", "허용되지 않은 음성 주소입니다.")


async def download(client, url, dst, settings):
    """음성을 dst에 받는다. 4xx·3xx(리다이렉트 미추종)는 AUDIO_INVALID, 5xx·연결 실패·지연은 AI_UNAVAILABLE이다.

    연결·읽기 한 번의 대기는 download_timeout_sec, 전체 시간은 호출하는 쪽이 남은 마감으로 끊는다.
    """
    try:
        async with client.stream("GET", url, headers={"Authorization": f"Bearer {settings.service_token}"}) as res:
            if res.status_code >= 500:
                raise fetch_unavailable(status=res.status_code)
            if res.status_code != 200:
                raise audio_invalid("FETCH_FAILED", "음성 파일을 가져오지 못했습니다.", status=res.status_code)
            size = 0
            with open(dst, "wb") as f:
                async for chunk in res.aiter_bytes(DOWNLOAD_CHUNK):
                    size += len(chunk)
                    if size > settings.max_bytes:
                        raise audio_invalid("TOO_LARGE", f"음성 파일 용량이 최대 {settings.max_bytes}바이트를 넘습니다.",
                                            maxBytes=settings.max_bytes)
                    f.write(chunk)
    except httpx.HTTPError:
        raise fetch_unavailable()
    if size == 0:
        raise audio_invalid("EMPTY", "빈 음성 파일입니다.")


def rejected(e, settings):
    if e.reason == "TOO_LONG":
        return audio_invalid("TOO_LONG", f"음성 길이가 최대 {settings.max_seconds:g}초를 넘습니다.",
                             maxSeconds=settings.max_seconds)
    return audio_invalid("DECODE_FAILED", "음성 파일을 읽을 수 없습니다.")


def to_result(raw, audio_id):
    """전사 결과를 결과2의 result로 바꾼다. 힌트 반복·빈 구간은 segments에서도 뺀다."""
    return {
        "audioId": audio_id,
        "text": raw["text"],
        "segments": [
            {"startSec": s["start"], "endSec": s["end"], "text": s["text"]}
            for s in raw["segments"]
            if s["text"] and not s["prompt_echo"]
        ],
        "durationSec": raw["duration"],
    }


def execution_meta(processing_ms):
    """실제 실행 경로(§6.1). 기본 경로가 원음 전사라(V9-D41) 모든 Job이 소음 처리 없이 원음으로 전사된다.

    소음 처리를 시도하지 않았으므로 실패 사유(fallbackReason)도 없다.
    """
    return {"pipeline": "RAW", "denoiseModel": None, "processingMs": processing_ms, "fallbackReason": None}


def create_app(settings=None, worker=None, transport=None):
    """settings·worker를 주지 않으면 환경변수로 만든다. transport는 테스트에서 음성 서버를 대신한다."""
    settings = settings or Settings.from_env()
    worker = worker or TranscriberWorker(settings.runner_factory())
    # 전사는 동시 1건만 처리한다. 처리 중이면 기다리지 않고 AI_BUSY로 돌려준다.
    lock = asyncio.Lock()
    background = set()

    @asynccontextmanager
    async def lifespan(app):
        async with lock:
            await asyncio.to_thread(worker.start)
        async with httpx.AsyncClient(transport=transport, follow_redirects=False,
                                     timeout=settings.download_timeout_sec) as client:
            app.state.http = client
            yield
        worker.stop()

    app = FastAPI(title="Report STT", lifespan=lifespan)
    app.state.worker = worker

    @app.middleware("http")
    async def authenticate(request: Request, call_next):
        if request.url.path.startswith("/internal/"):
            header = request.headers.get("authorization", "")
            expected = f"Bearer {settings.service_token}"
            if not hmac.compare_digest(header.encode(), expected.encode()):
                return error_response(401, "UNAUTHENTICATED", "서비스 인증에 실패했습니다.")
        return await call_next(request)

    @app.exception_handler(RequestValidationError)
    async def handle_validation(request, exc):
        fields = [{"path": ".".join(str(p) for p in e["loc"][1:]), "reason": e["type"]} for e in exc.errors()]
        identity = known_identity(exc.body)
        log_outcome(identity, "INVALID_REQUEST", None, None)
        return error_response(400, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다.",
                              details={"fieldErrors": fields}, identity=identity)

    def log_outcome(identity, code, reason, started):
        """jobId·attempt·코드·사유·소요 시간만 남긴다. 전사문·음성 주소·토큰은 남기지 않는다(BE09)."""
        elapsed = None if started is None else round((time.monotonic() - started) * 1000)
        logger.info("transcribe jobId=%s attempt=%s code=%s reason=%s ms=%s",
                    identity.get("jobId"), identity.get("attempt"), code or "OK", reason, elapsed)

    async def restart_worker():
        try:
            await asyncio.to_thread(worker.start)
        except WorkerFailed:
            logger.exception("작업 프로세스를 다시 띄우지 못했습니다.")
        finally:
            lock.release()

    def schedule_restart():
        """종료된 작업 프로세스를 백그라운드에서 다시 띄운다. 잠금을 쥔 채 호출하고, 끝나면 풀린다.

        모델을 다시 불러오는 동안 들어온 요청은 AI_BUSY를 받는다.
        """
        task = asyncio.create_task(restart_worker())
        background.add(task)
        task.add_done_callback(background.discard)

    @app.get("/health")
    def health():
        return {"status": "ok" if worker.ready else "loading", "modelVersion": settings.model_version}

    @app.post("/internal/transcribe")
    async def transcribe(req: TranscribeRequest):
        started = time.monotonic()
        identity = req.identity

        def fail(e):
            log_outcome(identity, e.code, e.details.get("reason"), started)
            return error_response(e.status, e.code, e.message, e.retryable, e.details, identity)

        # 마감은 BE 접수 시각 기준 deadlineAt이다(Q8). 재시도·재전달로 늘리지 않고, 서버 상한(90초)보다 길게 쓰지 않는다.
        budget = min(settings.timeout_sec, (req.deadlineAt - datetime.now(timezone.utc)).total_seconds())
        deadline = started + budget
        try:
            check_audio_url(req.payload.audioUrl, req.payload.audioId, settings)
            if budget <= 0:
                raise timed_out()
        except AiError as e:
            return fail(e)

        if lock.locked():
            return fail(AiError(503, "AI_BUSY", "다른 전사 작업을 처리 중입니다.", retryable=True))
        await lock.acquire()
        if not worker.ready:
            # 이전 재시작이 실패한 경우다. 다시 띄우고 이번 요청은 돌려보낸다.
            schedule_restart()
            return fail(AiError(503, "AI_UNAVAILABLE", "전사 모델을 준비 중입니다.", retryable=True))
        release = True
        try:
            # 받은 음성과 디코딩 결과는 이 임시 폴더에만 두고 Job이 끝나면(성공·실패·마감) 지운다.
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "audio"
                try:
                    await asyncio.wait_for(download(app.state.http, req.payload.audioUrl, path, settings),
                                           deadline - time.monotonic())
                except asyncio.TimeoutError:
                    raise timed_out()
                except AiError as e:
                    if e.code == "AI_UNAVAILABLE" and time.monotonic() >= deadline:
                        raise timed_out()
                    raise
                # 남은 시간으로는 끝낼 수 없으면 작업 프로세스에 보내지 않고 마감으로 끝낸다(모델 재적재 방지).
                if deadline - time.monotonic() < settings.min_run_sec:
                    raise timed_out()
                # 디코딩도 작업 프로세스에서 해서 남은 시간 안에 끊을 수 있게 한다.
                hints = req.payload.hints.model_dump()
                try:
                    raw = await asyncio.to_thread(worker.run, str(path), settings.max_seconds, hints,
                                                  timeout=deadline - time.monotonic())
                except WorkerTimeout:
                    release = False
                    schedule_restart()
                    raise timed_out()
                except AudioRejected as e:
                    raise rejected(e, settings)
                except Exception:
                    # 예외 원문은 서버 로그에만 남기고 응답에는 넣지 않는다.
                    logger.exception("전사 중 오류 jobId=%s attempt=%s", req.jobId, req.attempt)
                    if not worker.ready:
                        release = False
                        schedule_restart()
                    raise AiError(503, "AI_UNAVAILABLE", "전사 중 오류가 발생했습니다.", retryable=True)

            result = to_result(raw, req.payload.audioId)
            # 추론이 마감 직전에 끝났어도 결과를 만드는 사이 마감을 넘겼으면 성공으로 돌려주지 않는다.
            if time.monotonic() >= deadline:
                raise timed_out()
            if not result["text"]:
                raise AiError(422, "NO_SPEECH", "음성에서 발화를 찾지 못했습니다.")
            log_outcome(identity, None, None, started)
            return {
                **identity,
                "resultSchemaVersion": RESULT_SCHEMA_VERSION,
                "modelVersion": settings.model_version,
                "result": result,
                "executionMeta": execution_meta(round((time.monotonic() - started) * 1000)),
            }
        except AiError as e:
            return fail(e)
        finally:
            if release:
                lock.release()

    return app


"""보고 분석 내부 API (ANALYZE_REPORT, 요청4/결과4).

BE가 POST /internal/analyze-report로 공통 요청 envelope를 보내면 보고 문장을 분석해 성공 또는 실패 envelope를 반환한다.
기준: docs/planning/ai/07-contracts.md, 10-dev-link.md(1슬롯·동시1·1 thread·접수부터 15초)

실행 (ai/report-api에서):
    uv run uvicorn server:create_app --factory --host 0.0.0.0 --port 8001

환경변수는 Settings.from_env를 참고한다. AI_SERVICE_TOKEN은 필수다.
"""

import asyncio
import hmac
import logging
import os
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

import contract

CHECKPOINTS = Path(__file__).resolve().parents[1] / "checkpoints"
# 실행 방식별 기본 묶음. onnx가 배포 기본값이고 torch는 rollback용이다(inference.py).
DEFAULT_MODEL_DIRS = {"onnx": CHECKPOINTS / "report-v2-onnx-int8", "torch": CHECKPOINTS / "report-v2"}
STATUS = {"INVALID_REQUEST": 400, "AI_TIMEOUT": 504, "AI_UNAVAILABLE": 503}

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Settings:
    service_token: str
    runtime: str = "onnx"
    model_dir: str = ""  # 비우면 실행 방식의 기본 묶음
    cpu_threads: int = 1
    timeout_sec: float = 15

    def __post_init__(self):
        if self.runtime not in DEFAULT_MODEL_DIRS:
            raise ValueError(f"실행 방식은 {', '.join(DEFAULT_MODEL_DIRS)} 중 하나입니다: {self.runtime}")
        if not self.model_dir:
            object.__setattr__(self, "model_dir", str(DEFAULT_MODEL_DIRS[self.runtime]))

    @classmethod
    def from_env(cls):
        token = os.environ.get("AI_SERVICE_TOKEN", "")
        if not token:
            raise RuntimeError("AI_SERVICE_TOKEN이 설정되지 않았습니다.")
        runtime = os.environ.get("REPORT_RUNTIME", cls.runtime)
        if runtime not in DEFAULT_MODEL_DIRS:
            raise RuntimeError(f"REPORT_RUNTIME은 {', '.join(DEFAULT_MODEL_DIRS)} 중 하나입니다: {runtime}")
        return cls(
            service_token=token,
            runtime=runtime,
            model_dir=os.environ.get("REPORT_MODEL_DIR", ""),
            cpu_threads=int(os.environ.get("REPORT_CPU_THREADS", cls.cpu_threads)),
            timeout_sec=float(os.environ.get("REPORT_TIMEOUT_SEC", cls.timeout_sec)),
        )

    def load_analyzer(self):
        from inference import Analyzer  # torch를 불러오는 데 시간이 걸려 앱 시작 때만 읽는다
        return Analyzer.load(self.model_dir, threads=self.cpu_threads, runtime=self.runtime)


def respond(envelope):
    error = envelope.get("error")
    return JSONResponse(status_code=STATUS[error["code"]] if error else 200, content=envelope)


def create_app(settings=None, analyzer=None):
    """settings를 주지 않으면 환경변수로 만든다. analyzer를 주면 모델을 불러오지 않는다(테스트용)."""
    settings = settings or Settings.from_env()
    # 분석은 동시 1건만 실행한다. 처리 중이면 요청의 마감 안에서 기다린다.
    lock = asyncio.Lock()

    @asynccontextmanager
    async def lifespan(app):
        if app.state.analyzer is None:
            try:
                app.state.analyzer = await asyncio.to_thread(settings.load_analyzer)
            except Exception:
                logger.exception("보고 모델을 불러오지 못했습니다: %s (%s)", settings.model_dir, settings.runtime)
        yield

    app = FastAPI(title="Report Analysis", lifespan=lifespan)
    app.state.analyzer = analyzer

    @app.middleware("http")
    async def authenticate(request: Request, call_next):
        if request.url.path.startswith("/internal/"):
            header = request.headers.get("authorization", "")
            expected = f"Bearer {settings.service_token}"
            if not hmac.compare_digest(header.encode(), expected.encode()):
                return JSONResponse(status_code=401, content={
                    "error": {"code": "UNAUTHENTICATED", "message": "서비스 인증에 실패했습니다."}})
        return await call_next(request)

    @app.get("/health")
    def health():
        a = app.state.analyzer
        return {"status": "ok" if a else "unavailable", "modelVersion": a.model_version if a else None}

    @app.post("/internal/analyze-report")
    async def analyze_report(request: Request):
        try:
            raw = await request.json()
        except ValueError:
            return respond(contract.failure(None, "INVALID_REQUEST", "요청 본문이 JSON이 아닙니다."))
        try:
            req = contract.parse_request(raw)
        except contract.ContractViolation as e:
            return respond(contract.failure(e.request, "INVALID_REQUEST", "요청이 계약과 맞지 않습니다.",
                                            {"issues": e.issues}))

        loop = asyncio.get_running_loop()
        budget = min(settings.timeout_sec, (req.deadlineAt - datetime.now(timezone.utc)).total_seconds())
        deadline = loop.time() + budget
        timeout = contract.failure(req, "AI_TIMEOUT", "분석이 마감 안에 끝나지 않았습니다.")
        analyzer = app.state.analyzer
        if budget <= 0:
            return respond(timeout)
        if analyzer is None:
            return respond(contract.failure(req, "AI_UNAVAILABLE", "보고 모델을 사용할 수 없습니다.",
                                            {"reason": "MODEL_NOT_LOADED"}))
        try:
            await asyncio.wait_for(lock.acquire(), deadline - loop.time())
        except TimeoutError:
            return respond(timeout)

        task = asyncio.ensure_future(asyncio.to_thread(analyzer.analyze, req.payload.normalizedText))
        try:
            analysis = await asyncio.wait_for(asyncio.shield(task), deadline - loop.time())
        except TimeoutError:
            return respond(timeout)
        except Exception as e:
            logger.exception("보고 분석 중 오류")
            return respond(contract.failure(req, "AI_UNAVAILABLE", "분석 중 오류가 발생했습니다.",
                                            {"reason": "INFERENCE_ERROR", "error": type(e).__name__}))
        finally:
            # 마감 초과·요청 취소로 기다림을 멈춰도 실행 중인 추론은 멈출 수 없어, 끝날 때 슬롯을 돌려준다.
            # 늦은 결과는 버린다. 이미 끝난 추론이면 바로 돌려준다.
            task.add_done_callback(lambda _: lock.release())
        try:
            return respond(contract.success(req, analysis, analyzer.model_version))
        except contract.ContractViolation as e:
            logger.error("분석 결과가 계약과 맞지 않습니다: %s", e.issues)
            return respond(contract.failure(req, "AI_UNAVAILABLE", "분석 결과를 만들지 못했습니다.",
                                            {"reason": "MODEL_OUTPUT_INVALID"}))

    return app

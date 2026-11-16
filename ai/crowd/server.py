"""고정 카메라 밀집도 내부 API (POST /internal/estimate-crowd, #360).

업무 서버가 카메라마다 일정 간격(기본 10초)으로 최신 프레임 1장을 보내면 구역별 추정 인원·품질 사유를 돌려준다(V9-D53).
AI Job이 아니라 직접 호출이며 BE의 응답 대기 시간(8초)이 마감을 대신한다. 형식은 contract.py.

실행 (ai/crowd에서):
    uv run uvicorn server:create_app --factory --host 0.0.0.0 --port 8006

환경변수: AI_SERVICE_TOKEN(필수), CROWD_MODEL_DIR(모델 묶음, 기본 ai/checkpoints/crowd-v1),
CROWD_CPU_THREADS(기본 2), CROWD_WAIT_SEC(앞선 추정을 기다리는 최대 시간, 기본 3)
"""

import asyncio
import hmac
import logging
import os
import time
from dataclasses import dataclass
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

import contract
import estimator

DEFAULT_MODEL_DIR = Path(__file__).resolve().parents[1] / "checkpoints" / "crowd-v1"
STATUS = {"INVALID_REQUEST": 400, "AI_UNAVAILABLE": 503, "AI_BUSY": 503}

logger = logging.getLogger("uvicorn.error.crowd")


@dataclass(frozen=True)
class Settings:
    service_token: str
    model_dir: str = str(DEFAULT_MODEL_DIR)
    threads: int = 2
    wait_sec: float = 3.0

    @classmethod
    def from_env(cls):
        token = os.environ.get("AI_SERVICE_TOKEN", "")
        if not token:
            raise RuntimeError("AI_SERVICE_TOKEN이 설정되지 않았습니다.")
        return cls(service_token=token,
                   model_dir=os.environ.get("CROWD_MODEL_DIR") or cls.model_dir,
                   threads=int(os.environ.get("CROWD_CPU_THREADS", cls.threads)),
                   wait_sec=float(os.environ.get("CROWD_WAIT_SEC", cls.wait_sec)))


def fail(code, message):
    return JSONResponse(status_code=STATUS[code], content=contract.failure(code, message))


def create_app(settings=None, model=None):
    """model을 주면 묶음을 불러오지 않고 쓴다(테스트용). estimate(jpeg, zones)와 model_version이 있어야 한다."""
    settings = settings or Settings.from_env()
    app = FastAPI(title="Crowd Estimate")
    state = {"model": model, "error": None}
    if model is None:
        try:
            state["model"] = estimator.Estimator(settings.model_dir, settings.threads)
        except Exception as e:  # 모델이 없어도 앱은 떠서 /health로 원인을 알린다
            state["error"] = type(e).__name__
            logger.error("밀집도 모델을 불러오지 못했습니다(%s): %s", settings.model_dir, e)
    # 추정은 한 번에 하나만 돈다. CPU 슬롯을 STT·보고와 나눠 쓰므로 밀린 요청은 오래 기다리지 않고 거절한다.
    slot = asyncio.Semaphore(1)

    @app.middleware("http")
    async def authenticate(request: Request, call_next):
        if request.url.path.startswith("/internal/"):
            header = request.headers.get("authorization", "")
            if not hmac.compare_digest(header.encode(), f"Bearer {settings.service_token}".encode()):
                return JSONResponse(status_code=401, content={
                    "error": {"code": "UNAUTHENTICATED", "message": "서비스 인증에 실패했습니다.", "retryable": False}})
        return await call_next(request)

    @app.get("/health")
    def health():
        m = state["model"]
        if m is None:
            return JSONResponse(status_code=503, content={"status": "unavailable", "reason": state["error"]})
        return {"status": "ok", "modelVersion": m.model_version}

    @app.post("/internal/estimate-crowd")
    async def estimate_crowd(request: Request):
        m = state["model"]
        if m is None:
            return fail("AI_UNAVAILABLE", "밀집도 모델이 준비되지 않았습니다.")
        try:
            raw = await request.json()
        except ValueError:
            return fail("INVALID_REQUEST", "요청 본문이 JSON이 아닙니다.")
        try:
            req, jpeg = contract.parse_request(raw)
        except contract.ContractViolation as e:
            return fail("INVALID_REQUEST", str(e))

        try:
            await asyncio.wait_for(slot.acquire(), settings.wait_sec)
        except TimeoutError:
            logger.warning("카메라 %s: 앞선 추정이 %g초 안에 끝나지 않아 이번 프레임을 건너뜁니다.", req.cameraId, settings.wait_sec)
            return fail("AI_BUSY", "앞선 추정이 아직 실행 중입니다.")
        started = time.perf_counter()
        try:
            zones = await asyncio.to_thread(m.estimate, jpeg, req.zones)
        except estimator.FrameError as e:
            return fail("INVALID_REQUEST", str(e))
        except Exception as e:
            logger.exception("카메라 %s 밀집도 추정 중 오류", req.cameraId)
            return fail("AI_UNAVAILABLE", f"밀집도 추정 중 오류가 발생했습니다: {type(e).__name__}")
        finally:
            slot.release()
            del jpeg  # 프레임은 응답과 함께 버린다(V9-D09)
        # 구역 수·처리 시간만 남긴다. 인원·이미지·좌표는 기록하지 않는다.
        logger.info("카메라 %s 순번 %s: 구역 %d개, %.2f초", req.cameraId, req.sequence, len(zones),
                    time.perf_counter() - started)
        return {"modelVersion": m.model_version, "zones": zones}

    return app

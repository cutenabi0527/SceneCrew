"""행사 종료 요약 내부 API (SUMMARIZE_EVENT, 요청4/결과2, S15P31A202-269).

BE(080)가 POST /internal/summarize-event로 종료된 행사의 사실을 보내면 GMS 한 번으로 요약문을 만들어 돌려준다.
요약문은 응답에만 담고 로그·파일에 남기지 않는다. 요청 기록도 jobId·결과 코드만 남긴다.

실행 (ai/summary에서):
    uv run uvicorn server:create_app --factory --host 0.0.0.0 --port 8007

환경변수
- AI_SERVICE_TOKEN(필수): BE와 같은 서비스 토큰
- GMS_KEY·GMS_SUMMARY_MODEL(선택): 요약 모델. 공통 클라이언트가 ai/.env에서도 읽는다
"""
import asyncio
import hmac
import logging
import os
from datetime import datetime, timezone

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

import contract
import summarizer

STATUS = {"INVALID_REQUEST": 400, "AI_UNAVAILABLE": 503, "AI_TIMEOUT": 504}
MAX_CALL_SECONDS = 100          # BE 마감 120초 안에서 GMS 한 번
logger = logging.getLogger(__name__)


def respond(envelope):
    error = envelope.get("error")
    return JSONResponse(status_code=STATUS.get(error["code"], 500) if error else 200, content=envelope)


def create_app(service_token=None, summarize=None, ready=None):
    """인자를 주지 않으면 환경변수·GMS를 쓴다. summarize·ready를 주면 대신 쓴다(테스트용)."""
    token = service_token if service_token is not None else os.environ.get("AI_SERVICE_TOKEN", "")
    if not token:
        raise RuntimeError("AI_SERVICE_TOKEN이 설정되지 않았습니다.")
    summarize = summarize or summarizer.summarize
    ready = ready or summarizer.ready
    app = FastAPI(title="Event Summary")

    @app.middleware("http")
    async def authenticate(request: Request, call_next):
        if request.url.path.startswith("/internal/"):
            header = request.headers.get("authorization", "")
            if not hmac.compare_digest(header.encode(), f"Bearer {token}".encode()):
                return JSONResponse(status_code=401, content={
                    "error": {"code": "UNAUTHENTICATED", "message": "서비스 인증에 실패했습니다."}})
        return await call_next(request)

    @app.get("/health")
    def health():
        ok, reason = ready()
        return {"status": "ok" if ok else "unavailable", **({"reason": reason} if reason else {})}

    @app.post("/internal/summarize-event")
    async def summarize_event(request: Request):
        try:
            raw = await request.json()
        except ValueError:
            return respond(contract.failure(None, "INVALID_REQUEST", "요청 본문이 JSON이 아닙니다."))
        try:
            req = contract.parse_request(raw)
        except contract.ContractViolation as e:
            return respond(contract.failure(None, "INVALID_REQUEST", "요청 형식이 계약과 다릅니다.",
                                            {"issues": e.issues}))
        left = (req.deadlineAt - datetime.now(timezone.utc)).total_seconds()
        if left <= 1:
            return respond(contract.failure(req, "AI_TIMEOUT", "마감이 지나 요약을 만들지 않았습니다."))
        try:
            text, fmt, model = await asyncio.to_thread(summarize, req.payload, min(MAX_CALL_SECONDS, left - 1))
        except summarizer.SummaryUnavailable as e:
            logger.warning("요약 생성 실패: job %s (%s)", req.jobId, e)
            return respond(contract.failure(req, "AI_UNAVAILABLE", "요약 모델을 쓸 수 없습니다. 잠시 뒤 다시 시도해 주세요.",
                                            {"reason": str(e)}))
        except Exception as e:  # 버그가 서버를 죽이지 않게 한다. 본문·요약문은 기록하지 않는다
            logger.error("요약 생성 중 오류: job %s (%s)", req.jobId, type(e).__name__)
            return respond(contract.failure(req, "AI_UNAVAILABLE", "요약 생성 중 오류가 발생했습니다.",
                                            {"reason": "INTERNAL_ERROR"}))
        logger.info("요약 생성: job %s, %d자", req.jobId, len(text))
        return respond(contract.success(req, text, fmt, model))

    return app

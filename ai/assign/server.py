"""최초 배정 내부 API (ASSIGN_INITIAL, 요청4/결과1).

BE가 POST /internal/assign-initial로 공통 요청 envelope를 보내면 assign.run으로 bundleKey→memberId 제안을 만들어
성공 또는 실패 envelope를 반환한다. GMS가 안 되면 규칙으로 대신하므로(V9-D47) GMS_KEY가 없어도 앱은 동작한다.

실행 (ai/assign에서):
    uv run uvicorn server:create_app --factory --host 0.0.0.0 --port 8004

환경변수: AI_SERVICE_TOKEN(필수), ASSIGN_GENERATOR(gms 기본 | rule: GMS를 부르지 않음, 점검용)
"""

import asyncio
import hmac
import logging
import os
from dataclasses import dataclass
from datetime import datetime, timezone

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

import assign
import contract

GENERATORS = ("gms", "rule")
STATUS = {"INVALID_REQUEST": 400, "AI_UNAVAILABLE": 503, "AI_TIMEOUT": 504}
# GMS 호출 하나(최대 120초) 안에서는 멈추지 못한다. 마감 뒤 이만큼 더 기다려도 결과가 없으면 AI_TIMEOUT으로 끝낸다.
DEADLINE_GRACE_SEC = 5

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Settings:
    service_token: str
    generator: str = "gms"

    def __post_init__(self):
        if self.generator not in GENERATORS:
            raise ValueError(f"생성기는 {', '.join(GENERATORS)} 중 하나입니다: {self.generator}")

    @classmethod
    def from_env(cls):
        token = os.environ.get("AI_SERVICE_TOKEN", "")
        if not token:
            raise RuntimeError("AI_SERVICE_TOKEN이 설정되지 않았습니다.")
        return cls(service_token=token, generator=os.environ.get("ASSIGN_GENERATOR", cls.generator))

    def gms_ready(self):
        if self.generator == "rule":
            return False
        try:
            assign._load_gms().config("GMS_ASSIGN_MODEL")
        except Exception:  # GmsError: GMS_KEY 없음
            return False
        return True


def respond(envelope):
    error = envelope.get("error")
    return JSONResponse(status_code=STATUS.get(error["code"], 500) if error else 200, content=envelope)


def create_app(settings=None, run=None):
    """settings를 주지 않으면 환경변수로 만든다. run을 주면 assign.run 대신 쓴다(테스트용)."""
    settings = settings or Settings.from_env()
    run = run or (lambda raw: assign.run(raw, use_gms=settings.generator == "gms"))

    app = FastAPI(title="Initial Assignment")

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
        # 규칙 대신 배정이 있어 GMS가 없어도 ok다. gms는 지금 GMS로 만들 수 있는지만 알려 준다.
        return {"status": "ok", "generator": settings.generator, "gms": settings.gms_ready()}

    @app.post("/internal/assign-initial")
    async def assign_initial(request: Request):
        try:
            raw = await request.json()
        except ValueError:
            return respond(contract.failure(None, "INVALID_REQUEST", "요청 본문이 JSON이 아닙니다."))
        try:
            req = contract.parse_request(raw)
        except contract.ContractViolation:
            req = None  # assign.run이 같은 검사로 INVALID_REQUEST 응답을 만든다

        task = asyncio.ensure_future(asyncio.to_thread(run, raw))
        if req is None:
            return respond(await task)
        budget = (req.deadlineAt - datetime.now(timezone.utc)).total_seconds() + DEADLINE_GRACE_SEC
        try:
            return respond(await asyncio.wait_for(asyncio.shield(task), max(budget, 0)))
        except TimeoutError:
            logger.error("최초 배정이 마감 뒤 %s초 안에 끝나지 않았습니다: job %s", DEADLINE_GRACE_SEC, req.jobId)
            return respond(contract.failure(req, "AI_TIMEOUT", "실행 마감이 지났습니다."))
        except Exception as e:
            logger.exception("최초 배정 중 오류")
            return respond(contract.failure(req, "AI_UNAVAILABLE", "최초 배정 중 오류가 발생했습니다.",
                                            {"reason": "INTERNAL_ERROR", "error": type(e).__name__}))

    return app

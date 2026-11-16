"""AI 대응안 생성 내부 API (GENERATE_CHANGE, 요청4/결과6, S15P31A202-374).

BE가 POST /internal/generate-change로 공통 요청 envelope를 보내면 graph.run(Multi-agent)으로 대응안을 만들어
성공(READY/NEEDS_INFO) 또는 실패 envelope를 반환한다. 계약·검사·마감 판단은 graph가 맡고, 이 앱은 HTTP 입구만 맡는다.

실행 (ai/change에서):
    uv run uvicorn server:create_app --factory --host 0.0.0.0 --port 8005

환경변수
- AI_SERVICE_TOKEN(필수): BE와 같은 서비스 토큰
- CHANGE_SUPERVISOR: gms(기본, LLM Supervisor) | rule(모델 없이 규칙 Supervisor)
- CHANGE_AGENTS: gms(기본, 일정·인력·안내·검토 GMS Agent, #375) | rule(모델 없이 규칙 Agent)
- GMS_KEY·GMS_CHANGE_MODEL(선택): Supervisor 모델. 공통 클라이언트가 ai/.env에서도 읽는다
"""
import asyncio
import hmac
import logging
import os
from dataclasses import dataclass
from datetime import datetime, timezone

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

import contract
import graph
from agents import RuleSupervisor, rule_agents

SUPERVISORS = ("gms", "rule")
AGENT_SETS = ("gms", "rule")
STATUS = {"INVALID_REQUEST": 400, "READ_CONTEXT_CHANGED": 409, "AI_REVIEW_FAILED": 422,
          "AI_UNAVAILABLE": 503, "AI_TIMEOUT": 504}
# graph는 단계마다 마감을 확인하지만 GMS 호출 하나(최대 60초) 안에서는 멈추지 못한다.
DEADLINE_GRACE_SEC = 5
logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Settings:
    service_token: str
    supervisor: str = "gms"
    agents: str = "gms"

    def __post_init__(self):
        if self.supervisor not in SUPERVISORS:
            raise ValueError(f"Supervisor는 {', '.join(SUPERVISORS)} 중 하나입니다: {self.supervisor}")
        if self.agents not in AGENT_SETS:
            raise ValueError(f"전문 Agent는 {', '.join(AGENT_SETS)} 중 하나입니다: {self.agents}")

    @classmethod
    def from_env(cls):
        token = os.environ.get("AI_SERVICE_TOKEN", "")
        if not token:
            raise RuntimeError("AI_SERVICE_TOKEN이 설정되지 않았습니다.")
        return cls(service_token=token, supervisor=os.environ.get("CHANGE_SUPERVISOR", cls.supervisor),
                   agents=os.environ.get("CHANGE_AGENTS", cls.agents))

    def new_supervisor(self):
        """요청마다 새로 만든다. GMS Supervisor는 실행 하나의 모델 이름·사용량을 들고 있다."""
        if self.supervisor == "rule":
            return RuleSupervisor()
        from supervisor import GmsSupervisor
        return GmsSupervisor()

    def new_agents(self):
        """요청마다 새로 만든다. GMS Agent는 실행 하나의 모델 이름·사용량을 들고 있다."""
        if self.agents == "rule":
            return rule_agents()
        from specialists import gms_agents
        return gms_agents()

    def ready(self):
        """모델을 부르지 않고 실행에 필요한 설정만 본다."""
        if self.supervisor == "rule" and self.agents == "rule":
            return True, None
        from supervisor import _load_gms
        try:
            _load_gms().config("GMS_CHANGE_MODEL")
        except Exception as e:  # GmsError: GMS_KEY 없음
            return False, type(e).__name__
        return True, None


def respond(envelope):
    error = envelope.get("error")
    return JSONResponse(status_code=STATUS.get(error["code"], 500) if error else 200, content=envelope)


def create_app(settings=None, run=None):
    """settings를 주지 않으면 환경변수로 만든다. run을 주면 graph.run 대신 쓴다(테스트용)."""
    settings = settings or Settings.from_env()
    run = run or (lambda raw: graph.run(raw, supervisor=settings.new_supervisor(), agents=settings.new_agents()))
    app = FastAPI(title="Change Generation")

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
        ok, reason = settings.ready()
        return {"status": "ok" if ok else "unavailable", "supervisor": settings.supervisor, "agents": settings.agents,
                **({"reason": reason} if reason else {})}

    @app.post("/internal/generate-change")
    async def generate_change(request: Request):
        try:
            raw = await request.json()
        except ValueError:
            return respond(contract.failure(None, "INVALID_REQUEST", "요청 본문이 JSON이 아닙니다."))
        try:
            req = contract.parse_request(raw)
        except contract.ContractViolation:
            req = None  # graph.run이 같은 검사로 INVALID_REQUEST 응답을 만든다

        task = asyncio.ensure_future(asyncio.to_thread(run, raw))
        if req is None:
            return respond(await task)
        budget = (req.deadlineAt - datetime.now(timezone.utc)).total_seconds() + DEADLINE_GRACE_SEC
        try:
            return respond(await asyncio.wait_for(asyncio.shield(task), max(budget, 0)))
        except (TimeoutError, asyncio.TimeoutError):
            logger.error("대응안 생성이 마감 뒤 %s초 안에 끝나지 않았습니다: job %s", DEADLINE_GRACE_SEC, req.jobId)
            return respond(contract.failure(req, "AI_TIMEOUT", "실행 마감이 지났습니다. 다시 생성해 주세요."))
        except Exception as e:  # graph.run은 예외를 내지 않지만 버그가 서버를 죽이지 않게 한다
            logger.exception("대응안 생성 중 오류")
            return respond(contract.failure(req, "AI_UNAVAILABLE", "대응안 생성 중 오류가 발생했습니다.",
                                            {"reason": "INTERNAL_ERROR", "error": type(e).__name__}))

    return app

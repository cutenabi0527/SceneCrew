"""최초 계획 생성 내부 API (GENERATE_PLAN, 요청4/결과5).

BE가 POST /internal/generate-plan으로 공통 요청 envelope를 보내면 flow.run으로 계획을 만들어
성공(READY/NEEDS_INFO) 또는 실패 envelope를 반환한다. 계약·검사·마감 판단은 모두 flow가 맡고, 이 앱은 HTTP 입구만 맡는다.
기준: README의 결과 분기, docs/planning/ai/07-contracts.md, backend/docs/ai-job.md(접수부터 600초, GMS 동시 2)

실행 (ai/plan에서):
    uv run uvicorn server:create_app --factory --host 0.0.0.0 --port 8003

환경변수는 Settings.from_env를 참고한다. AI_SERVICE_TOKEN은 필수이고, gms 생성기(기본)는 GMS_KEY도 필요하다.
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
import flow

GENERATORS = ("gms", "rule")
# 실패 코드별 HTTP 상태. BE는 JSON이면 상태와 관계없이 envelope를 읽는다(backend/docs/ai-job.md 응답 판정).
STATUS = {"INVALID_REQUEST": 400, "READ_CONTEXT_CHANGED": 409, "AI_REVIEW_FAILED": 422,
          "AI_UNAVAILABLE": 503, "AI_TIMEOUT": 504}
# flow는 단계마다 마감을 확인하지만 GMS 호출 하나(최대 120초) 안에서는 멈추지 못한다.
# 마감이 지나도 결과가 오지 않으면 이만큼 더 기다린 뒤 AI_TIMEOUT으로 끝낸다. 게이트웨이 제한보다 짧아야 한다.
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
        return cls(service_token=token, generator=os.environ.get("PLAN_GENERATOR", cls.generator))

    def new_generator(self):
        """요청마다 새로 만든다. GMS 생성기는 실행 하나의 보완 출력·사용량을 들고 있다."""
        if self.generator == "rule":
            return flow.RuleGenerator()
        from gms_generator import GmsGenerator
        return GmsGenerator()

    def new_venue_reader(self):
        """결과6 요청(floorPlan)의 평면도 읽기. gms 생성기일 때만 GMS 이미지 입력으로 읽는다.

        rule이면 읽지 않고 venueDraft=null과 이유만 남긴다. 문서 주소 허용 목록은 AI_DOCUMENT_URL_PREFIXES다.
        """
        if self.generator == "rule":
            return flow.NoFloorPlanReader()
        from floor_plan import GmsFloorPlanReader
        return GmsFloorPlanReader(token=self.service_token)

    def new_device_reader(self):
        """계획 결과(결과5·6)의 카메라·장비·그룹 표시 선택 필드(S15P31A202-400). PLAN_DEVICE_FIELDS=on일 때만 낸다.

        BE는 계획 결과를 엄격한 JSON으로 읽으므로 BE 수용(#395) 전에 켜면 모든 계획 생성이 INVALID가 된다.
        rule 생성기는 카메라·장비를 읽지 않고 빈 목록과 그룹 표시만 낸다.
        """
        import devices
        if not devices.enabled():
            return None
        return devices.NoDeviceReader() if self.generator == "rule" else devices.GmsDeviceReader()

    def ready(self):
        """모델을 부르지 않고 실행에 필요한 설정만 본다. GMS_KEY는 공통 클라이언트가 ai/.env에서도 읽는다."""
        if self.generator == "rule":
            return True, None
        from gms_generator import _load_gms
        try:
            _load_gms().config("GMS_PLAN_MODEL")
        except Exception as e:  # GmsError: GMS_KEY 없음
            return False, type(e).__name__
        return True, None


def respond(envelope):
    error = envelope.get("error")
    return JSONResponse(status_code=STATUS.get(error["code"], 500) if error else 200, content=envelope)


def create_app(settings=None, run=None):
    """settings를 주지 않으면 환경변수로 만든다. run을 주면 flow.run 대신 쓴다(테스트용)."""
    settings = settings or Settings.from_env()
    run = run or (lambda raw: flow.run(raw, generator=settings.new_generator(),
                                       venue_reader=settings.new_venue_reader(),
                                       device_reader=settings.new_device_reader()))

    app = FastAPI(title="Plan Generation")

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
        return {"status": "ok" if ok else "unavailable", "generator": settings.generator,
                **({"reason": reason} if reason else {})}

    @app.post("/internal/generate-plan")
    async def generate_plan(request: Request):
        try:
            raw = await request.json()
        except ValueError:
            return respond(contract.failure(None, "INVALID_REQUEST", "요청 본문이 JSON이 아닙니다."))
        try:
            req = contract.parse_request(raw)
        except contract.ContractViolation:
            req = None  # flow.run이 같은 검사로 INVALID_REQUEST 응답을 만든다

        task = asyncio.ensure_future(asyncio.to_thread(run, raw))
        if req is None:
            return respond(await task)
        budget = (req.deadlineAt - datetime.now(timezone.utc)).total_seconds() + DEADLINE_GRACE_SEC
        try:
            return respond(await asyncio.wait_for(asyncio.shield(task), max(budget, 0)))
        except TimeoutError:
            # 실행 중인 GMS 호출은 멈출 수 없어 끝나면 결과를 버린다.
            logger.error("계획 생성이 마감 뒤 %s초 안에 끝나지 않았습니다: job %s", DEADLINE_GRACE_SEC, req.jobId)
            return respond(contract.failure(req, "AI_TIMEOUT", "실행 마감이 지났습니다. 다시 생성해 주세요."))
        except Exception as e:  # flow.run은 예외를 내지 않지만 생성기 버그가 서버를 죽이지 않게 한다
            logger.exception("계획 생성 중 오류")
            return respond(contract.failure(req, "AI_UNAVAILABLE", "계획 생성 중 오류가 발생했습니다.",
                                            {"reason": "INTERNAL_ERROR", "error": type(e).__name__}))

    return app

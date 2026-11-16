"""AI 서버 공통 실행 진입점 (내부 포트 8000).

STT·보고 분석·계획 생성·최초 배정·대응안 생성·문서 추출 앱을 각자의 폴더에서 별도 uvicorn 프로세스(127.0.0.1)로 띄우고, /internal/* 요청을 경로별로 전달한다.
앱끼리 모듈 이름(server, contract)이 겹치고 모델 로딩 실패가 서로 번지지 않아야 해서 프로세스를 나눈다.
인증·계약 검사·동시 실행 제한·마감은 각 앱이 그대로 맡는다. 게이트웨이는 전달과 상태 모음, 하위 프로세스 재시작만 한다.

실행 (ai/runtime에서):
    uv run uvicorn gateway:create_app --factory --host 0.0.0.0 --port 8000

환경변수는 Settings.from_env를 참고한다. 하위 앱의 환경변수(AI_SERVICE_TOKEN 등)는 그대로 물려준다.
"""

import asyncio
import logging
import os
import sys
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from pathlib import Path

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response

AI = Path(__file__).resolve().parents[1]
# MODEL_DIR 아래 배치 (README의 모델 배치). 보고 묶음은 실행 방식별로 다르다.
REPORT_BUNDLES = {"onnx": "report-v2-onnx-int8", "torch": "report-v2"}
# 볼륨에 기본 묶음이 아직 없을 때 쓰는 이전 묶음(#357). report-v2를 배치하기 전 배포에서도 보고 앱이 뜨게 한다.
PREVIOUS_REPORT_BUNDLES = {"onnx": "report-v1-onnx-int8", "torch": "report-v1"}
STT_MODEL = "stt/large-v3"  # 폴더 이름이 STT modelVersion(faster-whisper-large-v3-int8)에 들어간다
CROWD_MODEL = "crowd/crowd-v1"  # 밀집도 ONNX 묶음(ai/crowd/README.md). modelVersion은 묶음의 model.json 값이다
# 응답에서 그대로 돌려주지 않는 hop-by-hop·길이 헤더
DROP_HEADERS = {"connection", "content-encoding", "content-length", "keep-alive", "transfer-encoding"}

# uvicorn 로그 설정을 그대로 타서 docker compose logs에 하위 프로세스 시작·종료가 남는다
logger = logging.getLogger("uvicorn.error.gateway")


@dataclass(frozen=True)
class Service:
    name: str
    cwd: Path
    port: int
    routes: tuple  # 이 서비스로 보낼 /internal/ 경로
    app: str = "server:create_app"
    required: tuple = ()  # 비어 있으면 게이트웨이가 시작하지 않는 환경변수
    # 하위 앱 응답을 기다리는 최대 시간. AI nginx proxy_read_timeout(630초)보다 짧게 둔다.
    # 계획 생성은 접수부터 600초 마감이고(backend/docs/ai-job.md) 앱이 마감 5초 뒤 AI_TIMEOUT을 직접 돌려준다.
    timeout_sec: float = 170


SERVICES = {
    "stt": Service("stt", AI / "stt", 8101, ("/internal/transcribe",), required=("AI_SERVICE_TOKEN", "STT_AUDIO_URL_PREFIXES")),
    "report": Service("report", AI / "report-api", 8102, ("/internal/analyze-report",), required=("AI_SERVICE_TOKEN",)),
    # GMS_KEY는 필수로 두지 않는다. 없으면 계획 앱 /health가 unavailable이라 게이트웨이 /health가 503이 된다(STT·보고는 계속 동작).
    "plan": Service("plan", AI / "plan", 8103, ("/internal/generate-plan",), required=("AI_SERVICE_TOKEN",),
                    timeout_sec=620),
    # 최초 배정(V9-D47). GMS가 없거나 실패하면 앱이 규칙으로 대신 배정하므로 GMS_KEY 없이도 /health가 ok다.
    "assign": Service("assign", AI / "assign", 8104, ("/internal/assign-initial",), required=("AI_SERVICE_TOKEN",),
                      timeout_sec=620),
    # AI 대응안 Multi-agent(#374). BE 마감 600초. Supervisor·전문 Agent 모델(GMS)이 없으면 /health가 unavailable이다(CHANGE_SUPERVISOR·CHANGE_AGENTS가 모두 rule이면 ok).
    "change": Service("change", AI / "change", 8105, ("/internal/generate-change",), required=("AI_SERVICE_TOKEN",),
                      timeout_sec=620),
    # 행사 종료 요약(#269). BE가 동기로 한 번 부르고 120초만 기다린다(GMS 한 번, 요약문 미보관).
    "summary": Service("summary", AI / "summary", 8108, ("/internal/summarize-event",), required=("AI_SERVICE_TOKEN",),
                       timeout_sec=130),
    # 고정 카메라 밀집도(#360, V9-D53). AI Job이 아니라 업무 서버가 직접 부르고 8초만 기다린다. 앱이 3초 넘게 밀리면 AI_BUSY로 답한다.
    # 기본 목록(DEFAULT_SERVICES)에 넣지 않는다. 모델 묶음이 없으면 /health 503이 게이트웨이 전체로 번지므로, 배치·확인 뒤 AI_SERVICES에 직접 추가한다.
    "crowd": Service("crowd", AI / "crowd", 8106, ("/internal/estimate-crowd",), required=("AI_SERVICE_TOKEN",),
                     timeout_sec=10),
    "extract": Service("extract", AI, 8107, ("/internal/extract-event-input",),
                       app="document_extraction.server:create_app",
                       required=("AI_SERVICE_TOKEN", "DOCUMENT_URL_PREFIXES",
                                 "OCR_DETECTION_MODEL_DIR", "OCR_RECOGNITION_MODEL_DIR"),
                       timeout_sec=620),
}
DEFAULT_SERVICES = ("stt", "report", "plan", "assign", "change", "summary")


@dataclass(frozen=True)
class Settings:
    services: tuple = tuple(SERVICES[name] for name in DEFAULT_SERVICES)
    restart_delay_sec: float = 5
    max_restart_delay_sec: float = 60
    child_env: dict = field(default_factory=dict)

    @classmethod
    def from_env(cls):
        names = [n.strip() for n in os.environ.get("AI_SERVICES", ",".join(DEFAULT_SERVICES)).split(",") if n.strip()]
        unknown = [n for n in names if n not in SERVICES]
        if unknown or not names:
            raise RuntimeError(f"AI_SERVICES는 {', '.join(SERVICES)} 중에서 고릅니다: {','.join(unknown) or '(비어 있음)'}")
        # 하위 앱이 시작마다 실패하며 재시작을 반복하지 않도록 필수값이 비면 여기서 멈춘다
        env = child_env(os.environ)
        missing = sorted({k for n in names for k in SERVICES[n].required if not env.get(k, "").strip()})
        if missing:
            raise RuntimeError(f"필수 환경변수가 비어 있습니다: {', '.join(missing)} (ai/runtime/.env)")
        return cls(services=tuple(SERVICES[n] for n in names), child_env=env)


def child_env(env):
    """MODEL_DIR이 있으면 모델 경로 기본값을 그 아래로 정한다. 직접 준 REPORT_MODEL_DIR·STT_MODEL이 우선한다.

    MODEL_DIR이 없으면(로컬 개발) 각 앱의 기본값(ai/checkpoints, Hugging Face 캐시)을 그대로 쓴다.
    기본 보고 묶음 폴더가 없고 이전 묶음만 있으면 이전 묶음을 쓴다. 이때 /health의 modelVersion이 이전 버전으로 나온다.
    """
    env = dict(env)
    model_dir = env.get("MODEL_DIR", "")
    if model_dir:
        runtime = env.get("REPORT_RUNTIME", "onnx")
        if not env.get("REPORT_MODEL_DIR") and runtime in REPORT_BUNDLES:
            report = Path(model_dir) / "report"
            path, previous = report / REPORT_BUNDLES[runtime], report / PREVIOUS_REPORT_BUNDLES[runtime]
            if not path.is_dir() and previous.is_dir():
                logger.warning("보고 모델 %s가 없어 이전 모델 %s를 씁니다(ai/runtime/README.md 모델 배치)", path, previous)
                path = previous
            env["REPORT_MODEL_DIR"] = str(path)
        if not env.get("STT_MODEL"):
            env["STT_MODEL"] = str(Path(model_dir) / STT_MODEL)
        if not env.get("CROWD_MODEL_DIR"):
            env["CROWD_MODEL_DIR"] = str(Path(model_dir) / CROWD_MODEL)
        if not env.get("OCR_DETECTION_MODEL_DIR"):
            env["OCR_DETECTION_MODEL_DIR"] = str(Path(model_dir) / "ocr" / "detection")
        if not env.get("OCR_RECOGNITION_MODEL_DIR"):
            env["OCR_RECOGNITION_MODEL_DIR"] = str(Path(model_dir) / "ocr" / "recognition")
    return env


def unavailable(service, reason):
    return JSONResponse(status_code=503, content={
        "jobId": None, "attempt": None,
        "error": {"code": "AI_UNAVAILABLE", "message": "AI 서비스를 사용할 수 없습니다.", "retryable": True,
                  "details": {"reason": reason, "service": service}},
    })


class Child:
    """하위 앱 프로세스 하나. 종료되면 점점 긴 간격으로 다시 띄운다."""

    def __init__(self, service, settings):
        self.service = service
        self.settings = settings
        self.process = None
        self.restarts = 0
        self.task = None

    async def spawn(self):
        cmd = [sys.executable, "-m", "uvicorn", self.service.app, "--factory",
               "--host", "127.0.0.1", "--port", str(self.service.port), "--no-access-log"]  # 요청 기록은 게이트웨이가 남긴다
        self.process = await asyncio.create_subprocess_exec(*cmd, cwd=self.service.cwd, env=self.settings.child_env)
        logger.info("%s 시작: pid %s, 127.0.0.1:%s", self.service.name, self.process.pid, self.service.port)

    async def supervise(self):
        delay = self.settings.restart_delay_sec
        loop = asyncio.get_running_loop()
        while True:
            await self.spawn()
            started = loop.time()
            code = await self.process.wait()
            self.restarts += 1
            if loop.time() - started > self.settings.max_restart_delay_sec:
                delay = self.settings.restart_delay_sec  # 오래 실행됐으면 간격을 처음으로 돌린다
            logger.error("%s 종료(코드 %s). %g초 뒤 다시 시작합니다.", self.service.name, code, delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, self.settings.max_restart_delay_sec)

    async def stop(self):
        if self.task:
            self.task.cancel()
        p = self.process
        if p and p.returncode is None:
            p.terminate()
            try:
                await asyncio.wait_for(p.wait(), 8)
            except TimeoutError:
                p.kill()
                await p.wait()


def create_app(settings=None):
    """settings를 주지 않으면 환경변수로 만든다."""
    settings = settings or Settings.from_env()
    children = {s.name: Child(s, settings) for s in settings.services}
    routes = {r: s for s in settings.services for r in s.routes}

    @asynccontextmanager
    async def lifespan(app):
        for c in children.values():
            c.task = asyncio.create_task(c.supervise())
        async with httpx.AsyncClient() as client:  # 시간 제한은 요청마다 서비스 값으로 준다
            app.state.http = client
            yield
        await asyncio.gather(*(c.stop() for c in children.values()))

    app = FastAPI(title="SceneCrew AI", lifespan=lifespan)

    async def child_health(client, child):
        p = child.process
        state = {"pid": p.pid if p else None, "restarts": child.restarts}
        if p is None or p.returncode is not None:
            return {"status": "down", **state}
        try:
            res = await client.get(f"http://127.0.0.1:{child.service.port}/health", timeout=3)
            return {**res.json(), **state}
        except (httpx.HTTPError, ValueError):
            return {"status": "starting", **state}

    @app.get("/health")
    async def health(request: Request):
        """하위 앱이 모두 ok일 때만 200이다. 모델을 불러오는 중이거나 실패했으면 503과 서비스별 상태를 준다."""
        client = request.app.state.http
        states = await asyncio.gather(*(child_health(client, c) for c in children.values()))
        services = dict(zip(children, states))
        ok = all(s.get("status") == "ok" for s in states)
        return JSONResponse(status_code=200 if ok else 503,
                            content={"status": "ok" if ok else "unavailable", "services": services})

    @app.post("/internal/{path:path}")
    async def forward(request: Request, path: str):
        service = routes.get(request.url.path)
        if service is None:
            return JSONResponse(status_code=404, content={"error": {"code": "NOT_FOUND", "message": "없는 경로입니다."}})
        headers = {k: v for k, v in request.headers.items() if k in ("authorization", "content-type")}
        try:
            res = await request.app.state.http.post(f"http://127.0.0.1:{service.port}{request.url.path}",
                                                    content=await request.body(), headers=headers,
                                                    timeout=service.timeout_sec)
        except httpx.TimeoutException:
            logger.error("%s 응답이 %g초 안에 오지 않았습니다.", service.name, service.timeout_sec)
            return unavailable(service.name, "SERVICE_TIMEOUT")
        except httpx.HTTPError as e:
            logger.error("%s에 전달하지 못했습니다: %s", service.name, type(e).__name__)
            return unavailable(service.name, "SERVICE_DOWN")
        return Response(content=res.content, status_code=res.status_code,
                        headers={k: v for k, v in res.headers.items() if k not in DROP_HEADERS})

    return app

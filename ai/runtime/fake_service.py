"""게이트웨이 테스트용 하위 앱. FAKE_STATUS로 /health 상태를 정한다."""

import os

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse


def create_app():
    app = FastAPI()

    @app.get("/health")
    def health():
        return {"status": os.environ.get("FAKE_STATUS", "ok"), "modelVersion": "fake-1"}

    @app.post("/internal/echo")
    async def echo(request: Request):
        return JSONResponse(status_code=201, content={
            "authorization": request.headers.get("authorization"),
            "body": (await request.body()).decode(),
            "reportDir": os.environ.get("REPORT_MODEL_DIR"),
        })

    @app.post("/internal/crash")
    def crash():
        os._exit(3)

    return app

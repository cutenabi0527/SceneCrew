"""이미지 안의 추론 런타임과 /models 배치를 확인한다. 서버를 띄우지 않고 모델을 직접 불러온다.

사용 (컨테이너 안, /app/runtime에서):
    python check_runtime.py                       # 기본: versions report-onnx stt document
    python check_runtime.py report-torch          # PyTorch fallback 묶음 (REPORT_RUNTIME=torch로 바꾸기 전)
    python check_runtime.py crowd                 # 밀집도 묶음 (배치한 뒤, AI_SERVICES에 crowd를 넣기 전)
    python check_runtime.py versions              # 라이브러리 버전만

모델 경로는 게이트웨이와 같은 규칙(MODEL_DIR 아래 기본값, REPORT_MODEL_DIR·STT_MODEL·CROWD_MODEL_DIR 우선)으로 정한다.
결과는 검사마다 JSON 한 줄이며, 하나라도 실패하면 종료 코드 1이다.
"""

import io
import json
import os
import platform
import sys
import time
from importlib.metadata import version
from pathlib import Path

from gateway import AI, REPORT_BUNDLES, child_env

SAMPLE = "B구역 통로에 생수가 다 떨어졌습니다."
LIBRARIES = ("onnxruntime", "torch", "transformers", "tokenizers", "faster-whisper", "ctranslate2", "av", "pypdf", "pillow",
             "fastapi", "uvicorn", "pydantic", "numpy")


def versions(env):
    return {"python": platform.python_version(), "machine": platform.machine(),
            **{name: version(name) for name in LIBRARIES}}


def report(env, runtime):
    sys.path.insert(0, str(AI / "report-api"))
    from inference import Analyzer

    directory = env.get("REPORT_MODEL_DIR") if env.get("REPORT_RUNTIME", "onnx") == runtime else ""
    if not directory:
        directory = (str(Path(env["MODEL_DIR"]) / "report" / REPORT_BUNDLES[runtime]) if env.get("MODEL_DIR")
                     else str(AI / "checkpoints" / REPORT_BUNDLES[runtime]))
    analyzer = Analyzer.load(directory, threads=int(env.get("REPORT_CPU_THREADS", 1)), runtime=runtime)
    analysis = analyzer.analyze(SAMPLE)
    return {"modelDir": directory, "modelVersion": analyzer.model_version, "scores": analysis["scores"],
            "entities": [e["text"] for e in analysis["entities"]]}


def stt(env):
    sys.path.insert(0, str(AI / "stt"))
    import numpy as np
    from transcribe import Transcriber

    model = env.get("STT_MODEL", "large-v3")
    transcriber = Transcriber(model, env.get("STT_DEVICE", "cpu"), env.get("STT_COMPUTE_TYPE", "int8"),
                              int(env.get("STT_CPU_THREADS", 2)))
    raw = transcriber.transcribe(np.zeros(16000, dtype=np.float32))  # 1초 무음: 모델·VAD 실행만 확인
    return {"model": model, "computeType": transcriber.compute_type, "text": raw["text"]}


def crowd(env):
    sys.path.insert(0, str(AI / "crowd"))
    from types import SimpleNamespace

    import numpy as np
    from PIL import Image
    from estimator import Estimator

    directory = env.get("CROWD_MODEL_DIR") or str(AI / "checkpoints" / "crowd-v1")
    model = Estimator(directory, int(env.get("CROWD_CPU_THREADS", 2)))
    buf = io.BytesIO()  # 1280x720 무늬 프레임: 모델 실행·구역 계수만 확인한다(인원 값은 의미 없음)
    Image.fromarray(np.random.default_rng(0).integers(0, 255, (720, 1280, 3), dtype=np.uint8)).save(buf, "JPEG")
    roi = [SimpleNamespace(x=x, y=y) for x, y in ((0, 0), (1, 0), (1, 1), (0, 1))]
    out = model.estimate(buf.getvalue(), [SimpleNamespace(zoneId="check", scope="FULL", roi=roi)])
    return {"modelDir": directory, "modelVersion": model.model_version, "zones": out}


def document(env):
    from pypdf import PdfReader, PdfWriter

    writer = PdfWriter()
    writer.add_blank_page(width=595, height=842)
    buf = io.BytesIO()
    writer.write(buf)
    pages = PdfReader(io.BytesIO(buf.getvalue())).pages
    return {"pages": len(pages), "text": pages[0].extract_text()}


CHECKS = {
    "versions": versions,
    "report-onnx": lambda env: report(env, "onnx"),
    "report-torch": lambda env: report(env, "torch"),
    "stt": stt,
    "crowd": crowd,
    "document": document,
}
DEFAULT = ("versions", "report-onnx", "stt", "document")  # crowd는 기본 서비스가 아니라 이름을 줘서 확인한다


def main(names):
    unknown = [n for n in names if n not in CHECKS]
    if unknown:
        sys.exit(f"알 수 없는 검사: {', '.join(unknown)} (가능: {', '.join(CHECKS)})")
    env = child_env(os.environ)
    failed = False
    for name in names or DEFAULT:
        start = time.monotonic()
        try:
            line = {"check": name, "ok": True, **CHECKS[name](env)}
        except Exception as e:  # 검사 하나가 실패해도 나머지를 계속 본다
            failed = True
            line = {"check": name, "ok": False, "error": f"{type(e).__name__}: {e}"}
        line["elapsedSec"] = round(time.monotonic() - start, 2)
        print(json.dumps(line, ensure_ascii=False), flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main(sys.argv[1:])

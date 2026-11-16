"""ONNX 배포 묶음의 파일 이름과 라벨 검사 (export_onnx.py, verify_onnx.py, ai/report-api inference.py가 함께 쓴다).

묶음은 학습 묶음의 model.safetensors 대신 INT8 그래프(model.onnx)와 변환 기록(conversion.json)을 두고,
토크나이저·labels.json·config.json·training.json은 그대로 복사한다.
라벨 검사는 ReportModel.load(model.py)와 같은 기준이다. model.py는 V7-D05 보존 대상이라 고치지 않고 여기 둔다.
"""

import json
from pathlib import Path

from data import CATEGORIES, ENTITY_TAGS, STATES
from model import LABELS_FILE

ONNX_FILE = "model.onnx"
CONVERSION_FILE = "conversion.json"


def check_labels(directory):
    """묶음의 labels.json이 지금 코드의 라벨 목록과 다르면 멈춘다."""
    path = Path(directory) / LABELS_FILE
    labels = json.loads(path.read_text(encoding="utf-8"))
    if labels != {"category": CATEGORIES, "reportedState": STATES, "entityTags": ENTITY_TAGS}:
        raise ValueError(f"{path}의 라벨 목록이 지금 코드와 다릅니다.")

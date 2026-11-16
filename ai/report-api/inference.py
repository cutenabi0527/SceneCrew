"""학습한 보고 모델로 normalizedText 하나를 분석한다.

입력이 512토큰을 넘으면 64토큰씩 겹친 구간으로 나눠 추론하고 합친다.
- 유형·상태: 구간별 확률을 구간의 내용 토큰 수 비율로 평균한다.
- 개체: 겹친 토큰은 구간 경계에서 더 먼 구간의 태그를 쓴다. 합친 태그를 글자 범위로 되돌린다.
0.65 미만 null과 구역 후보는 계약(contract.success)에서 붙인다.

모델·토큰화·태그 변환은 학습 코드(ai/report의 model.py·data.py·metrics.py)를 그대로 불러 쓴다.
실행 방식은 두 가지이고 구간 나누기·병합·0.65·구역 후보는 같은 코드를 쓴다.
- onnx: export_onnx.py로 만든 INT8 묶음(model.onnx + conversion.json)을 ONNX Runtime CPU로 실행. 배포 기본값
- torch: 학습 묶음(model.safetensors)을 PyTorch fp32로 실행. rollback용

사용 (ai/report-api에서). 묶음에 model.onnx가 있으면 onnx, 없으면 torch로 실행한다:
    uv run python inference.py ../checkpoints/report-v2-onnx-int8 examples/request.json  # 성공·실패 envelope 출력
    uv run python inference.py ../checkpoints/report-v2 examples/request.json
"""

import json
import sys
from decimal import ROUND_FLOOR, Decimal
from pathlib import Path

import numpy as np
import torch
from transformers import AutoTokenizer

REPORT = Path(__file__).resolve().parents[1] / "report"
sys.path.insert(0, str(REPORT))
import contract  # noqa: E402
import metrics  # noqa: E402
from data import CATEGORIES, MAX_LENGTH, STATES  # noqa: E402
from bundle import CONVERSION_FILE, ONNX_FILE, check_labels  # noqa: E402
from model import ReportModel  # noqa: E402

STRIDE = 64
TRAINING_FILE = "training.json"
RUNTIMES = ("onnx", "torch")


class TorchRunner:
    """PyTorch fp32 실행. 입력은 padding한 id·mask 목록, 출력은 (유형 확률, 상태 확률, 토큰별 태그 id) 목록."""

    def __init__(self, model):
        self.model = model.eval()

    @torch.no_grad()
    def __call__(self, input_ids, mask):
        out = self.model(torch.tensor(input_ids), torch.tensor(mask))
        return (out["category"].softmax(-1).tolist(), out["state"].softmax(-1).tolist(),
                out["entity"].argmax(-1).tolist())


class OnnxRunner:
    """ONNX Runtime CPU 실행. 입출력은 TorchRunner와 같다."""

    def __init__(self, path, threads=1):
        import onnxruntime as ort  # torch 실행만 쓸 때는 불러오지 않는다

        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])

    def __call__(self, input_ids, mask):
        category, state, entity = self.session.run(["category", "state", "entity"], {
            "input_ids": np.array(input_ids, dtype=np.int64), "attention_mask": np.array(mask, dtype=np.int64)})
        return _softmax(category).tolist(), _softmax(state).tolist(), entity.argmax(-1).tolist()


def _softmax(x):
    e = np.exp(x - x.max(-1, keepdims=True))
    return e / e.sum(-1, keepdims=True)


class Analyzer:
    def __init__(self, runner, tokenizer, model_version, max_length=MAX_LENGTH, stride=STRIDE):
        self.runner = runner
        self.tokenizer = tokenizer
        self.model_version = model_version
        self.max_length = max_length
        self.stride = stride

    @classmethod
    def load(cls, directory, threads=1, runtime="onnx"):
        """묶음을 CPU로 불러온다. modelVersion은 onnx면 conversion.json, torch면 training.json 값이다."""
        directory = Path(directory)
        if runtime == "onnx":
            check_labels(directory)
            version = json.loads((directory / CONVERSION_FILE).read_text(encoding="utf-8"))["modelVersion"]
            runner = OnnxRunner(directory / ONNX_FILE, threads)
        elif runtime == "torch":
            torch.set_num_threads(threads)
            version = json.loads((directory / TRAINING_FILE).read_text(encoding="utf-8"))["modelVersion"]
            runner = TorchRunner(ReportModel.load(directory))
        else:
            raise ValueError(f"실행 방식은 {RUNTIMES} 중 하나입니다: {runtime}")
        return cls(runner, AutoTokenizer.from_pretrained(directory), version)

    def windows(self, text):
        """토큰 구간 목록. 각 구간은 input_ids·offsets·content(special이 아닌 토큰 위치)를 갖는다."""
        enc = self.tokenizer(text, truncation=True, max_length=self.max_length, stride=self.stride,
                             return_overflowing_tokens=True, return_offsets_mapping=True,
                             return_special_tokens_mask=True)
        return [{
            "input_ids": ids,
            "offsets": [tuple(o) for o in offsets],
            "content": [i for i, (s, (a, b)) in enumerate(zip(special, offsets)) if not s and a != b],
        } for ids, offsets, special in zip(enc["input_ids"], enc["offset_mapping"], enc["special_tokens_mask"])]

    def predict(self, windows):
        """구간별 유형·상태 확률과 토큰별 개체 태그 id."""
        width = max(len(w["input_ids"]) for w in windows)
        pad = self.tokenizer.pad_token_id
        input_ids = [w["input_ids"] + [pad] * (width - len(w["input_ids"])) for w in windows]
        mask = [[1] * len(w["input_ids"]) + [0] * (width - len(w["input_ids"])) for w in windows]
        category, state, tags = self.runner(input_ids, mask)
        return [{**w, "category": category[i], "state": state[i], "tags": tags[i]} for i, w in enumerate(windows)]

    def analyze(self, text):
        """{scores, entities}. scores는 임계값 적용 전 최고 라벨과 확률이다."""
        return merge(text, self.predict(self.windows(text)))


def merge(text, windows):
    """구간 예측을 합친다. 한 구간이면 그 구간의 예측과 같다."""
    weights = [max(len(w["content"]), 1) for w in windows]
    category = _average([w["category"] for w in windows], weights)
    state = _average([w["state"] for w in windows], weights)

    best = {}  # 글자 범위 → (경계까지 거리, 태그)
    for w in windows:
        content = w["content"]
        for rank, i in enumerate(content):
            margin = min(rank, len(content) - 1 - rank)
            span = w["offsets"][i]
            if span not in best or margin > best[span][0]:
                best[span] = (margin, w["tags"][i])
    offsets = sorted(best)
    spans = sorted(metrics.tags_to_spans([best[o][1] for o in offsets], offsets), key=lambda s: s[1])
    return {
        "scores": {"category": _top(category, CATEGORIES), "reportedState": _top(state, STATES)},
        "entities": [{"type": t, "start": s, "end": e, "text": text[s:e]} for t, s, e in spans],
    }


def _average(probs, weights):
    total = sum(weights)
    return [sum(p[k] * w for p, w in zip(probs, weights)) / total for k in range(len(probs[0]))]


def _top(probs, labels):
    k = max(range(len(probs)), key=probs.__getitem__)
    return {"label": labels[k], "score": floor4(probs[k])}


def floor4(p):
    """점수를 소수 4자리로 버린다. 반올림하면 0.64996이 0.65가 되어 0.65 경계를 넘으므로 버림을 쓴다."""
    return float(Decimal(repr(float(p))).quantize(Decimal("0.0001"), rounding=ROUND_FLOOR))


def run(analyzer, raw):
    """요청 하나를 처리해 성공 또는 실패 envelope를 반환한다(마감 처리는 server.py)."""
    try:
        req = contract.parse_request(raw)
    except contract.ContractViolation as e:
        return contract.failure(e.request, "INVALID_REQUEST", "요청이 계약과 맞지 않습니다.",
                                {"issues": e.issues})
    return contract.success(req, analyzer.analyze(req.payload.normalizedText), analyzer.model_version)


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    if len(argv) != 2:
        print(__doc__)
        return 2
    runtime = "onnx" if (Path(argv[0]) / ONNX_FILE).exists() else "torch"
    response = run(Analyzer.load(argv[0], runtime=runtime), contract.load_json(argv[1]))
    print(json.dumps(response, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

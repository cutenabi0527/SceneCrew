"""학습한 보고 모델 묶음을 ONNX로 내보내고 MatMul/Gemm을 INT8로 동적 양자화한 배포 묶음을 만든다.

1. ReportModel을 category[B,6]·state[B,4]·entity[B,L,9] 세 로짓 출력의 fp32 ONNX로 내보낸다(batch·sequence 동적).
2. fp32 ONNX를 checker로 검사하고 ONNX Runtime CPU 출력이 PyTorch와 같은지 견본 입력으로 확인한다.
3. 가중치가 상수인 MatMul/Gemm만 INT8(QInt8, 텐서 단위)로 바꾼다. attention의 활성값×활성값 MatMul은 fp32로 둔다.
4. INT8 그래프와 원본의 토크나이저·labels·config·training.json을 출력 폴더에 묶고 변환 기록(conversion.json)을 남긴다.
   변환 기록과 SHA-256 목록은 results/<modelVersion>.conversion.json·.sha256에도 쓴다.

기준: 기획 AI 06(ONNX MatMul/Gemm INT8). fp32 ONNX는 중간 파일이라 묶음에 넣지 않는다.

사용법 (ai/report에서). ONNX 도구는 학습 환경에 덧붙여 실행한다(requirements-onnx.txt):
    uv run --with-requirements requirements-onnx.txt export_onnx.py      # report-v1 → report-v1-onnx-int8
    uv run --with-requirements requirements-onnx.txt export_onnx.py --source 묶음 --output 폴더  # 폴더가 있으면 멈춤
"""

import argparse
import collections
import importlib.metadata
import json
import platform
import shutil
import sys
import tempfile
from datetime import datetime
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import onnxscript
import torch
import transformers
from onnxruntime.quantization import QuantType, quantize_dynamic
from transformers import AutoTokenizer

import data
from bundle import CONVERSION_FILE, ONNX_FILE
from model import LABELS_FILE, WEIGHTS_FILE, ReportModel
from train import sha256

HERE = Path(__file__).resolve().parent
DEFAULT_SOURCE = HERE.parent / "checkpoints" / "report-v1"
RESULTS = HERE / "results"
OPSET = 18
INPUTS = ["input_ids", "attention_mask"]
OUTPUTS = ["category", "state", "entity"]
# 원본 묶음에서 그대로 복사하는 파일. training.json은 원본 학습 기록(sourceModelVersion의 근거)이다.
COPIED = ["config.json", "tokenizer.json", "tokenizer_config.json", LABELS_FILE, "training.json"]
QUANTIZATION = {"method": "dynamic", "opTypes": ["MatMul", "Gemm"], "weightType": "QInt8", "perChannel": False,
                "matMulConstBOnly": True}
QUANTIZED_OPS = ("MatMulInteger", "DynamicQuantizeLinear", "QGemm", "DynamicQuantizeMatMul", "MatMulIntegerToFloat")
SAMPLES = ["B구역 통로에 물이 새고 있습니다.", "메인 무대 마이크가 안 나와요 확인 부탁드립니다"]


class Logits(torch.nn.Module):
    """ReportModel의 손실 분기를 빼고 세 로짓만 순서대로 낸다."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids, attention_mask):
        out = self.model(input_ids, attention_mask)
        return out["category"], out["state"], out["entity"]


def export_fp32(model, tokenizer, path, max_length=data.MAX_LENGTH):
    """fp32 ONNX를 쓴다. batch·sequence는 동적이며 sequence 상한은 max_length다."""
    sample = tokenizer(SAMPLES, padding=True, return_tensors="pt")
    batch, sequence = torch.export.Dim("batch"), torch.export.Dim("sequence", max=max_length)
    torch.onnx.export(Logits(model).eval(), (sample["input_ids"], sample["attention_mask"]), str(path),
                      dynamo=True, opset_version=OPSET, input_names=INPUTS, output_names=OUTPUTS,
                      dynamic_shapes={name: {0: batch, 1: sequence} for name in INPUTS}, external_data=False)
    graph = onnx.load(str(path))
    # 내보내기가 남긴 중간 shape 힌트(value_info)가 양자화 전 shape 추론과 충돌해 지우고 다시 추론하게 한다.
    del graph.graph.value_info[:]
    # 노드마다 붙는 디버그 정보(소스 절대 경로·줄 번호가 든 stack trace 등)를 지워 PC·폴더·코드 줄이 달라도
    # 같은 가중치와 라이브러리 버전이면 같은 바이트가 나오게 한다.
    for node in graph.graph.node:
        del node.metadata_props[:]
    onnx.save(graph, str(path))
    onnx.checker.check_model(str(path), full_check=True)


def quantize(fp32_path, int8_path):
    quantize_dynamic(str(fp32_path), str(int8_path), op_types_to_quantize=QUANTIZATION["opTypes"],
                     weight_type=QuantType.QInt8, per_channel=QUANTIZATION["perChannel"],
                     extra_options={"MatMulConstBOnly": QUANTIZATION["matMulConstBOnly"]})
    onnx.checker.check_model(str(int8_path), full_check=True)


def op_counts(path):
    return dict(collections.Counter(n.op_type for n in onnx.load(str(path)).graph.node).most_common())


def quantized_nodes(counts):
    """양자화 연산 노드 수. MatMulInteger는 원래 MatMul/Gemm 하나에 하나씩 생긴다."""
    return {op: counts[op] for op in QUANTIZED_OPS if counts.get(op)}


def session(path, threads=1):
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def io_spec(sess):
    dims = lambda shape: [d if isinstance(d, int) else str(d) for d in shape]  # noqa: E731
    return {"inputs": [{"name": i.name, "type": i.type, "shape": dims(i.shape)} for i in sess.get_inputs()],
            "outputs": [{"name": o.name, "type": o.type, "shape": dims(o.shape)} for o in sess.get_outputs()]}


@torch.no_grad()
def compare(model, sess, tokenizer, texts=SAMPLES):
    """같은 입력의 PyTorch와 ONNX 출력 비교: 로짓 최대 절대 차이와 최고 라벨(개체는 실제 토큰만) 일치 여부."""
    enc = tokenizer(texts, padding=True, return_tensors="np")
    feed = {name: enc[name].astype(np.int64) for name in INPUTS}
    got = dict(zip(OUTPUTS, sess.run(OUTPUTS, feed)))
    want = model(torch.from_numpy(feed["input_ids"]), torch.from_numpy(feed["attention_mask"]))
    mask = feed["attention_mask"].astype(bool)
    out = {}
    for name in OUTPUTS:
        a, b = got[name], want[name].numpy()
        same = a.argmax(-1) == b.argmax(-1)
        out[name] = {"maxAbsDiff": float(np.abs(a - b).max()),
                     "argmaxSame": bool(same[mask].all() if name == "entity" else same.all())}
    return out


def environment():
    return {"python": platform.python_version(), "platform": platform.platform(), "processor": platform.processor(),
            "torch": torch.__version__, "transformers": transformers.__version__, "onnx": onnx.__version__,
            "onnxruntime": ort.__version__, "onnxscript": onnxscript.__version__, "numpy": np.__version__,
            **{name: importlib.metadata.version(name) for name in ("onnx-ir", "protobuf", "ml-dtypes")}}


def build(source, output, max_length=data.MAX_LENGTH, results=RESULTS):
    """원본 묶음(source)에서 ONNX INT8 묶음(output)을 만들고 변환 기록을 반환한다."""
    source, output = Path(source), Path(output)
    training = json.loads((source / "training.json").read_text(encoding="utf-8"))
    version = f"{training['modelVersion']}-onnx-int8"
    model = ReportModel.load(source)
    tokenizer = AutoTokenizer.from_pretrained(source)
    output.mkdir(parents=True)
    with tempfile.TemporaryDirectory() as tmp:
        fp32 = Path(tmp) / "model.fp32.onnx"
        export_fp32(model, tokenizer, fp32, max_length)
        fp32_counts, fp32_size = op_counts(fp32), fp32.stat().st_size
        fp32_parity = compare(model, session(fp32), tokenizer)
        quantize(fp32, output / ONNX_FILE)
    for name in COPIED:
        shutil.copy2(source / name, output / name)

    int8 = session(output / ONNX_FILE)
    int8_counts = op_counts(output / ONNX_FILE)
    record = {
        "modelVersion": version,
        "sourceModelVersion": training["modelVersion"],
        "sourceSha256": {name: sha256(source / name) for name in [WEIGHTS_FILE, *COPIED]},
        "convertedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "command": " ".join(["uv run --with-requirements requirements-onnx.txt export_onnx.py", *sys.argv[1:]]),
        "opset": OPSET,
        "maxSequenceLength": max_length,
        "io": io_spec(int8),
        "quantization": QUANTIZATION,
        "fp32": {"bytes": fp32_size, "ops": {op: fp32_counts.get(op, 0) for op in ("MatMul", "Gemm")},
                 "parityWithTorch": fp32_parity},
        "int8": {"bytes": (output / ONNX_FILE).stat().st_size, "quantizedNodes": quantized_nodes(int8_counts),
                 "fp32MatMulLeft": int8_counts.get("MatMul", 0), "gemmLeft": int8_counts.get("Gemm", 0),
                 "ops": int8_counts, "sampleComparisonWithTorch": compare(model, int8, tokenizer)},
        "environment": environment(),
        "files": {name: sha256(output / name) for name in [ONNX_FILE, *COPIED]},
    }
    write_json(output / CONVERSION_FILE, record)
    if results:
        results = Path(results)
        results.mkdir(parents=True, exist_ok=True)
        write_json(results / f"{version}.conversion.json", record)
        lines = [f"{sha256(output / name)}  {name}" for name in sorted([ONNX_FILE, CONVERSION_FILE, *COPIED])]
        (results / f"{version}.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return record


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")


def main():
    sys.stdout.reconfigure(encoding="utf-8")  # torch.onnx 진행 표시의 이모지를 cp949 콘솔에서도 쓴다
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="원본 저장 묶음 (기본: report-v1)")
    parser.add_argument("--output", type=Path, help="출력 폴더 (기본: 원본 옆 <modelVersion>-onnx-int8). 이미 있으면 멈춤")
    parser.add_argument("--results", type=Path, help="변환 기록·SHA-256 목록 사본 폴더 (기본: 기본 출력 폴더일 때만 results/)")
    args = parser.parse_args()

    version = json.loads((args.source / "training.json").read_text(encoding="utf-8"))["modelVersion"]
    output = args.output or args.source.parent / f"{version}-onnx-int8"
    if output.exists():
        sys.exit(f"{output}가 이미 있습니다. 덮어쓰지 않으니 다른 폴더를 지정하세요.")
    # 재현 확인용으로 다른 폴더에 다시 만들 때 저장소의 기록(results/)을 덮어쓰지 않는다.
    results = args.results or (None if args.output else RESULTS)
    record = build(args.source, output, results=results)
    print(f"{record['modelVersion']}: 양자화 노드 {record['int8']['quantizedNodes']}, "
          f"{record['fp32']['bytes']:,} → {record['int8']['bytes']:,} byte")
    print(f"→ {output}")


if __name__ == "__main__":
    main()

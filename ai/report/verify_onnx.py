"""export_onnx.py로 만든 ONNX INT8 배포 묶음을 검사하고 결과를 results/<modelVersion>.verify.json에 남긴다.

| 검사 | 기준 |
|---|---|
| 무결성 | 묶음 파일 SHA-256이 변환 기록(conversion.json)과 같고 빠지거나 더 있는 파일이 없음. 원본 묶음이 있으면 원본 SHA-256도 대조 |
| 라벨 | labels.json이 지금 코드의 유형6·상태4·태그9 목록과 같음 |
| 그래프 | onnx.checker(full_check) 통과, ONNX Runtime CPU 세션 생성, 입출력 이름과 동적 축 |
| 양자화 | MatMulInteger 등 양자화 노드가 실제 그래프에 있고 개수가 변환 기록과 같음, Gemm이 남지 않음 |
| 추론 | 짧은 입력·최대 길이(512토큰) 입력·다중 구간(64토큰 겹침) 입력의 출력 크기 [B,6]·[B,4]·[B,L,9], NaN/Inf 없음, |
|      | 토큰 id가 vocab 안·길이가 최대 길이 이하, 태그로 만든 개체 범위가 원문 안 |
| PyTorch 대조 | 원본 묶음이 있으면 같은 입력에서 유형·상태 최고 라벨, 개체 태그 일치율, 확률 최대 차이를 기록(합격 기준 아님) |

정확도 비교(평가 분할 전체)와 처리 시간은 #107에서 잰다.

사용법 (ai/report에서):
    uv run --with-requirements requirements-onnx.txt verify_onnx.py      # report-v1-onnx-int8, 원본 report-v1
    uv run --with-requirements requirements-onnx.txt verify_onnx.py --bundle 묶음 --source 원본 묶음  # 기록은 --results를 줄 때만
"""

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

import numpy as np
import onnx
import torch
from transformers import AutoTokenizer

import data
import metrics
from export_onnx import (COPIED, DEFAULT_SOURCE, INPUTS, OUTPUTS, RESULTS, SAMPLES, environment, op_counts,
                         quantized_nodes, session, write_json)
from bundle import CONVERSION_FILE, ONNX_FILE, check_labels
from model import WEIGHTS_FILE, ReportModel
from train import sha256

DEFAULT_BUNDLE = DEFAULT_SOURCE.parent / "report-v1-onnx-int8"
STRIDE = 64  # ai/report-api inference.STRIDE와 같은 겹침
SIZES = {"category": len(data.CATEGORIES), "state": len(data.STATES), "entity": len(data.ENTITY_TAGS)}


def long_text(tokenizer, tokens):
    """평가 분할 문장을 이어 붙여 tokens 토큰을 넘는 긴 입력을 만든다."""
    rows, text = data.load_splits()["test"], ""
    for row in rows:
        text = f"{text} {row['text']}".strip()
        if len(tokenizer(text)["input_ids"]) > tokens:
            return text
    raise ValueError(f"평가 분할을 모두 이어도 {tokens}토큰을 넘지 않습니다.")


def windows(tokenizer, text, max_length):
    enc = tokenizer(text, truncation=True, max_length=max_length, stride=STRIDE, return_overflowing_tokens=True,
                    return_offsets_mapping=True)
    return [{"input_ids": ids, "offsets": [tuple(o) for o in offsets]}
            for ids, offsets in zip(enc["input_ids"], enc["offset_mapping"])]


def cases(tokenizer, max_length):
    """(이름, 원문, 구간 목록). 다중 구간은 구간 길이가 달라 padding이 들어간 batch다."""
    text = long_text(tokenizer, max_length * 2)
    single = windows(tokenizer, text, max_length)[:1]
    return [("short", SAMPLES[0], windows(tokenizer, SAMPLES[0], max_length)),
            (f"max_length_{max_length}", text, single),
            ("multi_window", text, windows(tokenizer, text, max_length))]


def batch(tokenizer, parts):
    width = max(len(w["input_ids"]) for w in parts)
    pad = tokenizer.pad_token_id
    ids = np.array([w["input_ids"] + [pad] * (width - len(w["input_ids"])) for w in parts], dtype=np.int64)
    mask = np.array([[1] * len(w["input_ids"]) + [0] * (width - len(w["input_ids"])) for w in parts], dtype=np.int64)
    return {"input_ids": ids, "attention_mask": mask}


def softmax(x):
    e = np.exp(x - x.max(-1, keepdims=True))
    return e / e.sum(-1, keepdims=True)


def run_case(sess, tokenizer, name, text, parts, vocab_size, max_length, model=None):
    feed = batch(tokenizer, parts)
    b, length = feed["input_ids"].shape
    out = dict(zip(OUTPUTS, sess.run(OUTPUTS, feed)))
    shapes = {k: list(v.shape) for k, v in out.items()}
    want = {"category": [b, SIZES["category"]], "state": [b, SIZES["state"]], "entity": [b, length, SIZES["entity"]]}
    spans = [s for w, tags in zip(parts, out["entity"].argmax(-1).tolist())
             for s in metrics.tags_to_spans(tags[:len(w["input_ids"])], w["offsets"])]
    result = {
        "name": name, "batch": b, "length": length, "windowLengths": [len(w["input_ids"]) for w in parts],
        "shapes": shapes, "shapeOk": shapes == want,
        "finite": all(bool(np.isfinite(v).all()) for v in out.values()),
        "tokenRangeOk": bool(feed["input_ids"].min() >= 0 and feed["input_ids"].max() < vocab_size
                             and length <= max_length),
        "spans": len(spans), "spanRangeOk": all(0 <= s < e <= len(text) for _, s, e in spans),
    }
    if model is not None:
        result["torch"] = against_torch(model, feed, out)
    result["passed"] = all(result[k] for k in ("shapeOk", "finite", "tokenRangeOk", "spanRangeOk"))
    return result


@torch.no_grad()
def against_torch(model, feed, out):
    want = model(torch.from_numpy(feed["input_ids"]), torch.from_numpy(feed["attention_mask"]))
    mask = feed["attention_mask"].astype(bool)
    tags_same = out["entity"].argmax(-1) == want["entity"].numpy().argmax(-1)
    result = {}
    for name in ("category", "state"):
        a, b = softmax(out[name]), want[name].softmax(-1).numpy()
        result[name] = {"argmaxSame": bool((a.argmax(-1) == b.argmax(-1)).all()),
                        "maxProbDiff": round(float(np.abs(a - b).max()), 6)}
    result["entity"] = {"tagAgreement": round(float(tags_same[mask].mean()), 6), "tokens": int(mask.sum())}
    return result


def check(name, passed, **detail):
    return {"name": name, "passed": bool(passed), **detail}


def sha256_check(name, directory, expected, extra=()):
    """directory 파일의 SHA-256이 expected({이름: 해시})와 같은지. 빠진 파일은 예외 대신 실패로 남긴다."""
    missing = [k for k in expected if not (Path(directory) / k).is_file()]
    mismatched = [k for k in expected if k not in missing and sha256(Path(directory) / k) != expected[k]]
    return check(name, not (missing or mismatched or extra), mismatched=mismatched, missing=missing, extra=list(extra))


def verify(bundle, source=None, max_length=None):
    """검사 결과 dict. passed는 모든 검사와 추론 사례가 통과했는지다.

    묶음 파일이 빠졌거나 그래프가 checker를 통과하지 못하면 실패를 기록하고 뒤 검사와 추론은 하지 않는다.
    """
    bundle = Path(bundle)
    record = json.loads((bundle / CONVERSION_FILE).read_text(encoding="utf-8"))
    max_length = max_length or record["maxSequenceLength"]
    checks = []

    extra = sorted(p.name for p in bundle.iterdir() if p.is_file() and p.name not in {*record["files"], CONVERSION_FILE})
    checks.append(sha256_check("bundleSha256", bundle, record["files"], extra))
    if source:
        checks.append(sha256_check("sourceSha256", source, record["sourceSha256"]))
    if checks[0]["missing"]:
        return summary(record, checks, [])
    try:
        check_labels(bundle)
        checks.append(check("labels", True))
    except ValueError as e:
        checks.append(check("labels", False, error=str(e)))

    try:
        onnx.checker.check_model(str(bundle / ONNX_FILE), full_check=True)
    except Exception as e:  # noqa: BLE001 - checker의 검증 오류 종류가 여럿이라 모두 실패로 남긴다
        checks.append(check("onnxChecker", False, error=str(e)))
        return summary(record, checks, [])
    checks.append(check("onnxChecker", True))
    sess = session(bundle / ONNX_FILE)
    io = {"inputs": [(i.name, i.shape) for i in sess.get_inputs()],
          "outputs": [(o.name, o.shape) for o in sess.get_outputs()]}
    dynamic = all(isinstance(d, str) for _, shape in io["inputs"] for d in shape)
    checks.append(check("ortSession", [n for n, _ in io["inputs"]] == INPUTS and [n for n, _ in io["outputs"]] == OUTPUTS
                        and dynamic, providers=sess.get_providers(), io={k: [[n, list(map(str, s))] for n, s in v]
                                                                        for k, v in io.items()}))

    counts = op_counts(bundle / ONNX_FILE)
    nodes = quantized_nodes(counts)
    checks.append(check("quantizedNodes", nodes.get("MatMulInteger", 0) > 0 and nodes == record["int8"]["quantizedNodes"]
                        and counts.get("Gemm", 0) == 0, nodes=nodes, gemmLeft=counts.get("Gemm", 0),
                        fp32MatMulLeft=counts.get("MatMul", 0)))

    tokenizer = AutoTokenizer.from_pretrained(bundle)
    vocab_size = json.loads((bundle / "config.json").read_text(encoding="utf-8"))["vocab_size"]
    model = ReportModel.load(source) if source else None
    results = [run_case(sess, tokenizer, name, text, parts, vocab_size, max_length, model)
               for name, text, parts in cases(tokenizer, max_length)]
    return summary(record, checks, results)


def summary(record, checks, results):
    return {
        "modelVersion": record["modelVersion"],
        "sourceModelVersion": record["sourceModelVersion"],
        "verifiedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "command": " ".join(["uv run --with-requirements requirements-onnx.txt verify_onnx.py", *sys.argv[1:]]),
        "environment": environment(),
        "checks": checks,
        "cases": results,
        "passed": all(c["passed"] for c in checks) and all(r["passed"] for r in results),
    }


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--bundle", type=Path, default=DEFAULT_BUNDLE)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="원본 묶음. 없으면 원본 대조를 건너뜀")
    parser.add_argument("--results", type=Path, help="검사 결과 폴더 (기본: 기본 묶음일 때만 results/)")
    args = parser.parse_args()

    report = verify(args.bundle, args.source if (args.source / WEIGHTS_FILE).exists() else None)
    # 재현 확인용으로 다른 묶음을 검사할 때 저장소의 기록(results/)을 덮어쓰지 않는다(export_onnx.py와 같음).
    results = args.results or (RESULTS if args.bundle.resolve() == DEFAULT_BUNDLE else None)
    for c in report["checks"]:
        detail = {k: v for k, v in c.items() if k not in ("name", "passed") and v} if not c["passed"] else {}
        print(f"{'통과' if c['passed'] else '실패'}  {c['name']}{f' {detail}' if detail else ''}")
    for r in report["cases"]:
        torch_note = f" | PyTorch 대조 {r['torch']}" if "torch" in r else ""
        print(f"{'통과' if r['passed'] else '실패'}  {r['name']}: batch {r['batch']} × {r['length']}토큰, "
              f"출력 {r['shapes']}, 개체 {r['spans']}{torch_note}")
    if results:
        results.mkdir(parents=True, exist_ok=True)
        write_json(results / f"{report['modelVersion']}.verify.json", report)
        print(f"→ {results / (report['modelVersion'] + '.verify.json')}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())

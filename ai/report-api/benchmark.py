"""배포 모델(ONNX INT8)과 원본(PyTorch fp32)의 정확도·CPU 처리 시간·메모리를 같은 조건으로 잰다 (#107).

두 실행 방식을 각각 별도 프로세스에서 불러와(메모리·스레드가 섞이지 않게) API와 같은 추론 경로(Analyzer)로 잰다.

| 항목 | 방법 |
|---|---|
| 정확도 | 검증·평가 분할 전체를 Analyzer.analyze로 한 문장씩 추론해 유형·상태 macro F1, 개체 exact span F1. |
|        | 0.65 미만을 null(미분류)로 바꾼 API 출력 기준 F1과 null 건수도 잰다. PyTorch는 training.json 평가 지표 재현을 확인 |
| 두 방식 대조 | 같은 문장의 유형·상태 라벨 일치, 개체 집합 일치, 0.65 판정(null 여부)이 갈린 문장·헤드 (라벨 차이와 따로 센다) |
| 처리 시간 | 평가 분할 문장별 analyze 시간, 성공 예시 요청 4건의 run(계약 검사 + 분석 + 결과 envelope)을 반복해 잰 시간. |
|           | 측정 전 예열. HTTP·대기열은 포함하지 않는다 |
| 메모리 | 프로세스 상주 메모리(Windows 작업 집합, Linux VmRSS)의 불러오기 전·후·최대 |

사용 (ai/report-api에서):
    uv run python benchmark.py                       # → ../report/results/report-v1-onnx-int8.benchmark.json
    uv run python benchmark.py --repeat 50 --threads 1 --output 결과.json
    python benchmark.py --onnx-dir ~/checkpoints_2/report-v1-onnx-int8 --torch-dir ~/checkpoints/report-v1 --output 결과.json
"""

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import platform
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPORT = HERE.parent / "report"
sys.path.insert(0, str(REPORT))
import data  # noqa: E402
import metrics  # noqa: E402
from contract import SCORE_THRESHOLD as THRESHOLD  # noqa: E402 - 미분류 기준 0.65

CHECKPOINTS = HERE.parent / "checkpoints"
MODELS = {"onnx": CHECKPOINTS / "report-v1-onnx-int8", "torch": CHECKPOINTS / "report-v1"}
WEIGHTS = {"onnx": "model.onnx", "torch": "model.safetensors"}
EXAMPLES = ["request.json", "request-program.json", "request-unclassified.json", "request-long.json"]
DEFAULT_OUTPUT = REPORT / "results" / "report-v1-onnx-int8.benchmark.json"
EVAL_SPLITS = ("valid", "test")
WARMUP = 5


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def memory():
    """(현재, 최대) 상주 메모리 byte. Windows는 작업 집합, Linux는 VmRSS·VmHWM."""
    if sys.platform == "win32":
        import ctypes
        from ctypes import wintypes

        class Counters(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                        *[(name, ctypes.c_size_t) for name in (
                            "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
                            "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage",
                            "PeakPagefileUsage")]]

        counters = Counters(cb=ctypes.sizeof(Counters))
        kernel32, psapi = ctypes.windll.kernel32, ctypes.windll.psapi
        kernel32.GetCurrentProcess.restype = wintypes.HANDLE
        psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
        psapi.GetProcessMemoryInfo(kernel32.GetCurrentProcess(), ctypes.byref(counters), counters.cb)
        return counters.WorkingSetSize, counters.PeakWorkingSetSize
    status = dict(line.split(":", 1) for line in Path("/proc/self/status").read_text().splitlines() if ":" in line)
    return int(status["VmRSS"].split()[0]) * 1024, int(status["VmHWM"].split()[0]) * 1024


def cpu_name():
    try:
        if sys.platform == "win32":
            import winreg

            key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0")
            return winreg.QueryValueEx(key, "ProcessorNameString")[0].strip()
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor()


def environment(threads):
    versions = {name: importlib.metadata.version(name) for name in ("torch", "transformers", "onnxruntime", "numpy")}
    result = {"cpu": cpu_name(), "logicalCpus": os.cpu_count(), "platform": platform.platform(),
              "python": platform.python_version(), **versions, "threads": threads}
    # 공용 서버에서는 다른 작업의 부하가 시간에 섞이므로 측정 시작 때의 부하와 쓸 수 있는 코어(taskset)를 남긴다.
    # build가 자식 프로세스를 띄우기 전에 부른다.
    if hasattr(os, "sched_getaffinity"):
        result["affinityCpus"] = len(os.sched_getaffinity(0))
    if hasattr(os, "getloadavg"):
        result["loadAverage"] = [round(x, 2) for x in os.getloadavg()]
    return result


def stats(seconds):
    """밀리초 단위 요약. p50·p95는 nearest-rank."""
    ordered = sorted(seconds)

    def rank(q):
        return ordered[max(math.ceil(q * len(ordered)) - 1, 0)]

    return {"n": len(ordered), "meanMs": round(sum(ordered) / len(ordered) * 1000, 2),
            "p50Ms": round(rank(0.5) * 1000, 2), "p95Ms": round(rank(0.95) * 1000, 2),
            "maxMs": round(ordered[-1] * 1000, 2)}


# ---- 자식 프로세스: 한 실행 방식을 불러와 예측·시간·메모리를 잰다 ----

def measure(runtime, model_dir, threads, repeat):
    import contract
    import inference

    imported = memory()[0]
    started = time.perf_counter()
    analyzer = inference.Analyzer.load(model_dir, threads=threads, runtime=runtime)
    load_seconds = time.perf_counter() - started
    loaded = memory()[0]

    splits = data.load_splits()
    requests = {name: contract.load_json(HERE / "examples" / name) for name in EXAMPLES}
    for row in splits["test"][:WARMUP]:
        analyzer.analyze(row["text"])
    for raw in requests.values():
        inference.run(analyzer, raw)

    predictions, sentence_seconds = {}, []
    for name in EVAL_SPLITS:
        predictions[name] = []
        for row in splits[name]:
            t = time.perf_counter()
            out = analyzer.analyze(row["text"])
            if name == "test":
                sentence_seconds.append(time.perf_counter() - t)
            predictions[name].append({
                "id": row["id"],
                "category": out["scores"]["category"], "reportedState": out["scores"]["reportedState"],
                "spans": sorted([e["type"], e["start"], e["end"]] for e in out["entities"]),
            })

    examples = {}
    for name, raw in requests.items():
        seconds = []
        for _ in range(repeat):
            t = time.perf_counter()
            response = inference.run(analyzer, raw)
            seconds.append(time.perf_counter() - t)
        if "result" not in response:
            raise RuntimeError(f"{name} 응답이 성공이 아닙니다: {response.get('error')}")
        windows = len(analyzer.windows(raw["payload"]["normalizedText"]))
        examples[name] = {"chars": len(raw["payload"]["normalizedText"]), "windows": windows,
                          "tokens": len(analyzer.tokenizer(raw["payload"]["normalizedText"])["input_ids"]),
                          **stats(seconds)}

    return {
        "modelVersion": analyzer.model_version,
        "loadSeconds": round(load_seconds, 2),
        "memoryBytes": {"afterImport": imported, "afterLoad": loaded, "peak": memory()[1]},
        "latency": {"testSentences": stats(sentence_seconds), "examples": examples},
        "predictions": predictions,
    }


# ---- 부모 프로세스: 두 결과로 지표·대조를 계산해 기록한다 ----

def run_child(runtime, model_dir, threads, repeat):
    command = [sys.executable, __file__, "--child", runtime, "--model-dir", str(model_dir),
               "--threads", str(threads), "--repeat", str(repeat)]
    print(f"{runtime}: {model_dir.name} 측정 중", file=sys.stderr)
    out = subprocess.run(command, check=True, stdout=subprocess.PIPE, cwd=HERE,
                         env={**os.environ, "PYTHONIOENCODING": "utf-8"})
    return json.loads(out.stdout.decode("utf-8").strip().splitlines()[-1])


def scores(rows, preds, threshold=None):
    """유형·상태 macro F1과 개체 F1. threshold를 주면 그보다 낮은 라벨을 null로 바꾼다(null은 오답)."""
    def label(p, head):
        return None if threshold is not None and p[head]["score"] < threshold else p[head]["label"]

    result = {}
    for head, labels in (("category", data.CATEGORIES), ("reportedState", data.STATES)):
        pred = [label(p, head) for p in preds]
        result[head] = {"macroF1": round(metrics.macro_f1([r[head] for r in rows], pred, labels), 4),
                        "accuracy": round(sum(r[head] == p for r, p in zip(rows, pred)) / len(rows), 4),
                        "nulls": sum(p is None for p in pred)}
    if threshold is None:
        entity = metrics.span_f1([metrics.gold_spans(r) for r in rows],
                                 [{tuple(s) for s in p["spans"]} for p in preds])
        result["entitySpan"] = {k: round(v, 4) for k, v in entity.items()}
    return result


def compare(onnx_preds, torch_preds):
    """같은 문장에서 두 방식의 차이. 라벨 차이와 0.65 판정(null 여부) 차이는 따로 센다."""
    differ = {"category": [], "reportedState": [], "spans": [], "threshold": []}
    prob_diff = 0.0
    for a, b in zip(onnx_preds, torch_preds):
        for head in ("category", "reportedState"):
            label_differs = a[head]["label"] != b[head]["label"]
            null_differs = (a[head]["score"] < THRESHOLD) != (b[head]["score"] < THRESHOLD)
            if label_differs:
                differ[head].append(a["id"])
            if null_differs:
                differ["threshold"].append(f"{a['id']}:{head}")
            if not label_differs:  # 라벨이 같으면 null 판정이 갈려도 점수 차이에 넣는다
                prob_diff = max(prob_diff, abs(a[head]["score"] - b[head]["score"]))
        if a["spans"] != b["spans"]:
            differ["spans"].append(a["id"])
    n = len(onnx_preds)
    return {
        "sentences": n,
        "categoryAgreement": round(1 - len(differ["category"]) / n, 4),
        "stateAgreement": round(1 - len(differ["reportedState"]) / n, 4),
        "spanSetAgreement": round(1 - len(differ["spans"]) / n, 4),
        "maxTopScoreDiffWhenSameLabel": round(prob_diff, 4),
        "differentIds": differ,
    }


def build(threads, repeat, models=MODELS):
    splits = data.load_splits()
    split_path = data.DEFAULT_DATASET.with_suffix(".split.json")
    env = environment(threads)  # 측정 시작 때의 부하를 남기도록 자식 프로세스보다 먼저 잰다
    results = {rt: run_child(rt, models[rt], threads, repeat) for rt in models}
    training = json.loads((models["torch"] / "training.json").read_text(encoding="utf-8"))

    runtimes = {}
    for rt, r in results.items():
        accuracy = {name: {"raw": scores(splits[name], r["predictions"][name]),
                           "withThreshold": scores(splits[name], r["predictions"][name], THRESHOLD)}
                    for name in EVAL_SPLITS}
        runtimes[rt] = {"modelVersion": r["modelVersion"], "bundle": models[rt].name,
                        "weightsSha256": {WEIGHTS[rt]: sha256(models[rt] / WEIGHTS[rt])},
                        "weightsBytes": (models[rt] / WEIGHTS[rt]).stat().st_size,
                        "accuracy": accuracy, "loadSeconds": r["loadSeconds"],
                        "memoryBytes": r["memoryBytes"], "latency": r["latency"]}

    test = runtimes["torch"]["accuracy"]["test"]["raw"]
    trained = training["test"]
    reproduces = all(math.isclose(a, round(b, 4), abs_tol=1e-4) for a, b in (
        (test["category"]["macroF1"], trained["categoryMacroF1"]),
        (test["reportedState"]["macroF1"], trained["stateMacroF1"]),
        (test["entitySpan"]["f1"], trained["entitySpan"]["f1"])))
    return {
        "measuredAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "command": " ".join([Path(sys.executable).name, Path(sys.argv[0]).name, *sys.argv[1:]]),  # 실제 실행한 인터프리터
        "dataset": {"file": data.DEFAULT_DATASET.name, "sha256": sha256(data.DEFAULT_DATASET),
                    "split": split_path.name, "splitSha256": sha256(split_path),
                    "rows": {name: len(splits[name]) for name in EVAL_SPLITS}},
        "settings": {"threads": threads, "repeat": repeat, "warmup": WARMUP, "threshold": THRESHOLD,
                     "batch": 1, "examples": EXAMPLES},
        "environment": env,
        "torchReproducesTrainingTest": reproduces,
        "runtimes": runtimes,
        "onnxVsTorch": {name: compare(results["onnx"]["predictions"][name], results["torch"]["predictions"][name])
                        for name in EVAL_SPLITS},
    }


def print_summary(report):
    for rt, r in report["runtimes"].items():
        for name in EVAL_SPLITS:
            raw, cut = r["accuracy"][name]["raw"], r["accuracy"][name]["withThreshold"]
            print(f"{rt:5} {name:5} 유형 {raw['category']['macroF1']:.4f} (null 적용 {cut['category']['macroF1']:.4f}, "
                  f"null {cut['category']['nulls']}) 상태 {raw['reportedState']['macroF1']:.4f} "
                  f"(null 적용 {cut['reportedState']['macroF1']:.4f}, null {cut['reportedState']['nulls']}) "
                  f"개체 {raw['entitySpan']['f1']:.4f}")
        lat = r["latency"]
        print(f"{rt:5} 평가 문장 {lat['testSentences']} | 980자 {lat['examples']['request-long.json']} | "
              f"메모리 최대 {r['memoryBytes']['peak'] / 2**20:.0f}MiB")
    for name, c in report["onnxVsTorch"].items():
        print(f"대조 {name}: 유형 {c['categoryAgreement']} 상태 {c['stateAgreement']} 개체 {c['spanSetAgreement']} "
              f"0.65 판정 차이(문장·헤드) {len(c['differentIds']['threshold'])}")
    print(f"PyTorch가 training.json 평가 지표를 재현: {report['torchReproducesTrainingTest']}")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--threads", type=int, default=1, help="ONNX intra-op·torch 스레드 (AI10 1 thread)")
    parser.add_argument("--repeat", type=int, default=30, help="예시 요청별 반복 횟수")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--onnx-dir", type=Path, default=MODELS["onnx"], help="ONNX INT8 묶음 폴더")
    parser.add_argument("--torch-dir", type=Path, default=MODELS["torch"], help="PyTorch 묶음 폴더")
    parser.add_argument("--child", choices=list(MODELS), help=argparse.SUPPRESS)
    parser.add_argument("--model-dir", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()

    if args.child:
        print(json.dumps(measure(args.child, args.model_dir, args.threads, args.repeat), ensure_ascii=False))
        return 0
    report = build(args.threads, args.repeat, {"onnx": args.onnx_dir.resolve(), "torch": args.torch_dir.resolve()})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print_summary(report)
    print(f"→ {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

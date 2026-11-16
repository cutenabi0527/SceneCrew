"""서빙과 같은 경로(estimator.Estimator, ONNX Runtime)로 AI Hub 샘플의 구역별 오차와 요청 1건 처리 시간을 잰다.

처리 시간은 JPEG 해독부터 구역별 결과까지다(HTTP·base64 제외). 첫 1건은 준비 시간이라 빼고 잰다.

사용 (ai/crowd에서):
    uv run python evaluate.py ../labeling/crowd_sample --threads 1,2,4 --out results/evaluate.json
"""

import argparse
import json
import statistics
import sys
import time
from pathlib import Path

import contract
import estimator
from bench import EXCLUDED, ground_truth, count_rois, HERE

IDS = {f"Z{i}": f"10000000-0000-4000-8000-00000000000{i}" for i in range(1, 5)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("sample_dir")
    p.add_argument("--model-dir", default=str(HERE.parent / "checkpoints" / "crowd-v1"))
    p.add_argument("--threads", default="2")
    p.add_argument("--out")
    args = p.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    rois = count_rois.load_rois(HERE.parent / "labeling" / "crowd" / "rois.json")
    truth = ground_truth(args.sample_dir, rois)
    zones = [contract.Zone(zoneId=IDS[z["zoneId"]], scope=z["scope"], roi=z["roi"]) for z in rois]
    frames = {f: (Path(args.sample_dir) / "crowd_img" / f"Indoor_EXCO012_{f}.jpg").read_bytes() for f in truth}

    rows = []
    for threads in map(int, args.threads.split(",")):
        model = estimator.Estimator(args.model_dir, threads)
        model.estimate(next(iter(frames.values())), zones)
        per_frame, times = {}, []
        for f, data in frames.items():
            t = time.perf_counter()
            out = model.estimate(data, zones)
            times.append(time.perf_counter() - t)
            per_frame[f] = {z["zoneId"]: (c["estimatedCount"], c["qualityReasons"]) for z, c in zip(rois, out)}
        errors = {}
        for z in IDS:
            diffs = [per_frame[f][z][0] - truth[f][z] for f in truth if (f, z) not in EXCLUDED]
            errors[z] = {"mae": round(statistics.fmean(abs(d) for d in diffs), 2),
                         "bias": round(statistics.fmean(diffs), 2), "n": len(diffs)}
        reasons = sorted({r for f in per_frame.values() for _, rs in f.values() for r in rs})
        row = {"modelVersion": model.model_version, "threads": threads,
               "secMedian": round(statistics.median(times), 3), "secMax": round(max(times), 3),
               "errors": errors, "qualityReasons": reasons, "perFrame": per_frame}
        rows.append(row)
        print(f"{model.model_version} threads={threads}: {row['secMedian']:.2f}s(max {row['secMax']:.2f}) "
              + "  ".join(f"{z} MAE {e['mae']}/bias {e['bias']:+}" for z, e in errors.items())
              + f"  사유 {reasons or '없음'}", flush=True)
    if args.out:
        Path(args.out).write_text(json.dumps({"truth": truth, "rows": rows}, ensure_ascii=False, indent=1),
                                  encoding="utf-8")


if __name__ == "__main__":
    main()

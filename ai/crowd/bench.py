"""밀집도 후보 모델을 AI Hub 샘플(EXCO012 13장)로 비교한다: 구역별 인원 오차와 프레임 1장 처리 시간.

정답은 labeling/crowd/count_rois.py와 같은 규칙(머리 점이 든 구역 하나에만 센다)으로 라벨에서 센다.
540의 Z3는 라벨 누락으로 정답에서 뺀다(docs/ai/crowd-density-input-review.md 1절).
원본 이미지·라벨·가중치는 Git 밖에 둔다.

사용 (ai/crowd에서):
    uv run --group bench python bench.py ../labeling/crowd_sample <가중치 폴더> --threads 4 --out results/bench.json
"""

import argparse
import json
import statistics
import sys
import time
from pathlib import Path

import numpy as np
import torch
from PIL import Image

import candidates
import roi

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "labeling" / "crowd"))
import count_rois  # noqa: E402

CANDIDATES = ["DM-Count_SHA", "DM-Count_QNRF", "DM-Count_SHB", "CSRNet_SHA", "P2PNet_SHA", "FasterRCNN_COCO"]
EXCLUDED = {("540", "Z3")}


def ground_truth(sample_dir, zones):
    out = {}
    for path in sorted((Path(sample_dir) / "crowd_label").glob("*.json")):
        label = json.loads(path.read_text(encoding="utf-8-sig"))
        counts, total, _ = count_rois.count_frame(label, zones)
        out[path.stem.rsplit("_", 1)[-1]] = {**counts, "ALL": total}
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("sample_dir")
    p.add_argument("weights_dir")
    p.add_argument("--models", default=",".join(CANDIDATES))
    p.add_argument("--sizes", default="1920,1280", help="긴 변 픽셀 후보")
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--out")
    args = p.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    torch.set_num_threads(args.threads)

    zones = count_rois.load_rois(HERE.parent / "labeling" / "crowd" / "rois.json")
    polygons = {z["zoneId"]: [(q["x"], q["y"]) for q in z["roi"]] for z in zones}
    truth = ground_truth(args.sample_dir, zones)
    images = {f: Image.open(Path(args.sample_dir) / "crowd_img" / f"Indoor_EXCO012_{f}.jpg").convert("RGB")
              for f in truth}

    rows = []
    for name in args.models.split(","):
        model = candidates.load(name, args.weights_dir)
        for size in map(int, args.sizes.split(",")):
            candidates.predict(name, model, images[next(iter(images))], size)  # 첫 실행 준비 시간 제외
            per_frame, times = {}, []
            for f, img in images.items():
                t = time.perf_counter()
                pts, weights = candidates.predict(name, model, img, size)
                times.append(time.perf_counter() - t)
                counts = roi.count_by_zone(pts, weights, polygons)
                counts["ALL"] = float(weights.sum())
                per_frame[f] = counts
            errors = {}
            for zone in [*polygons, "ALL"]:
                diffs = [per_frame[f][zone] - truth[f][zone] for f in truth if (f, zone) not in EXCLUDED]
                errors[zone] = {"mae": statistics.fmean(abs(d) for d in diffs), "bias": statistics.fmean(diffs)}
            row = {"model": name, "longSide": size, "threads": args.threads,
                   "secMedian": statistics.median(times), "secMax": max(times), "errors": errors,
                   "perFrame": per_frame}
            rows.append(row)
            print(f"{name:16s} {size:5d}px  {row['secMedian']:.2f}s(max {row['secMax']:.2f})  "
                  + "  ".join(f"{z} MAE {e['mae']:.1f}/bias {e['bias']:+.1f}" for z, e in errors.items()), flush=True)
        del model

    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps({"truth": truth, "rows": rows}, ensure_ascii=False, indent=1,
                                             default=float), encoding="utf-8")


if __name__ == "__main__":
    main()

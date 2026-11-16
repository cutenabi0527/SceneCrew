"""고른 밀도 맵 후보를 ONNX 묶음(model.onnx + model.json)으로 바꾸고 PyTorch 출력과 대조한다.

사용 (ai/crowd에서):
    uv run --group bench python export_onnx.py <가중치 폴더> DM-Count_QNRF ../checkpoints/crowd-v1 \
        --model-version crowd-dmcount-qnrf-v1 --long-side 1280 --check ../labeling/crowd_sample/crowd_img/Indoor_EXCO012_535.jpg
"""

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from PIL import Image

import candidates


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("weights_dir")
    p.add_argument("name")
    p.add_argument("out_dir")
    p.add_argument("--model-version", required=True)
    p.add_argument("--long-side", type=int, required=True)
    p.add_argument("--check", help="대조할 이미지")
    args = p.parse_args()
    if not args.name.startswith(("DM-Count", "CSRNet")):
        raise SystemExit("밀도 맵 후보만 바꿀 수 있습니다.")

    model = candidates.load(args.name, args.weights_dir)
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    onnx_path = out / "model.onnx"
    dummy = torch.zeros(1, 3, 720, 1280)
    torch.onnx.export(model, (dummy,), str(onnx_path), input_names=["image"], output_names=["density"],
                      dynamic_axes={"image": {2: "height", 3: "width"}, "density": {2: "h", 3: "w"}},
                      opset_version=17, dynamo=False)
    sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    meta = {"modelVersion": args.model_version, "output": "density", "longSide": args.long_side, "multiple": 8,
            "mean": list(candidates.MEAN), "std": list(candidates.STD), "sha256": sha,
            "source": {"candidate": args.name, "torch": torch.__version__, "onnxruntime": ort.__version__}}
    (out / "model.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{onnx_path} sha256 {sha}")

    if args.check:
        image = Image.open(args.check).convert("RGB")
        w0, h0 = image.size
        s = args.long_side / max(w0, h0)
        size = (round(w0 * s / 8) * 8, round(h0 * s / 8) * 8)
        x = candidates.to_tensor(image, size)
        with torch.inference_mode():
            ref = model(x)[0, 0].numpy()
        sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        got = sess.run(None, {"image": x.numpy()})[0][0, 0]
        print(f"합계 torch {ref.sum():.3f} onnx {got.sum():.3f}, 칸 최대 차이 {np.abs(ref - got).max():.2e}")


if __name__ == "__main__":
    main()

"""프레임 1장에서 구역별 인원을 추정한다 (ONNX Runtime CPU).

모델 묶음 폴더(CROWD_MODEL_DIR)에는 model.onnx와 model.json이 있다.
model.json: {"modelVersion", "output": "density", "longSide", "multiple", "mean", "std", "sha256"}
- density: 출력 (1,1,h,w) 밀도 맵. 칸 값의 합이 인원이다. 칸 중심이 든 구역에 센다.

구역 규칙(docs/planning/ai/11-crowd-density.md, docs/ai/crowd-density-input-review.md 2절):
- ROI 안만 센다. 화면 밖으로 나간 부분은 세지 않고 전체 구역으로 늘리지 않는다(ROI_OUTSIDE_FRAME).
- ROI가 화면에 전혀 없으면 미관측이다: 인원 null, OUT_OF_VIEW. 0명으로 채우지 않는다.
- scope UNDETERMINED는 어디까지 보이는지 몰라 세지 않는다(인원 null). 사유 UNDETERMINED_SCOPE는 BE가 붙인다.
- 한 점은 먼저 나온 구역 하나에만 센다. 연속 프레임을 더하지 않는다(요청 하나 = 프레임 하나).
- 너무 어두우면 0명처럼 보이므로 인원 null과 LOW_LIGHT, 어두운 편이면 인원과 LOW_LIGHT를 함께 낸다.
  흐림(BLURRED)·과노출(OVEREXPOSED)도 사유만 붙인다. 기준값은 아래 상수이며 현장 영상으로 다시 맞춘다.
- 개인별 위치(밀도 칸)는 계수에만 쓰고 결과·로그에 남기지 않는다.
"""

import hashlib
import io
import json
import threading
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

import roi as roi_mod

# 품질 기준(0~255 밝기). EXCO012 낮 실내 샘플의 ROI 평균 밝기는 이 범위 안이다(README 품질 기준).
TOO_DARK = 20
DARK = 45
TOO_BRIGHT = 235
# 전체 프레임(긴 변 640으로 줄인 회색조)의 라플라시안 분산이 이보다 작으면 흐림
BLUR_VAR = 15.0
# 화면 안에 남은 ROI 면적이 원래의 이 비율보다 작으면 화면 밖으로 나갔다고 본다
CLIP_TOLERANCE = 0.98
MIN_VISIBLE_AREA = 1e-4
MAX_PIXELS = 4096 * 4096


class FrameError(ValueError):
    """이미지를 읽을 수 없음(INVALID_REQUEST)."""


@dataclass(frozen=True)
class Bundle:
    model_version: str
    long_side: int
    multiple: int
    mean: tuple
    std: tuple
    path: Path

    @classmethod
    def load(cls, model_dir):
        model_dir = Path(model_dir)
        meta = json.loads((model_dir / "model.json").read_text(encoding="utf-8"))
        if meta.get("output") != "density":
            raise ValueError(f"지원하지 않는 출력 형식입니다: {meta.get('output')}")
        onnx = model_dir / "model.onnx"
        digest = hashlib.sha256(onnx.read_bytes()).hexdigest()
        if meta.get("sha256") and meta["sha256"] != digest:
            raise ValueError(f"model.onnx의 sha256이 model.json과 다릅니다: {digest}")
        return cls(meta["modelVersion"], int(meta["longSide"]), int(meta.get("multiple", 8)),
                   tuple(meta["mean"]), tuple(meta["std"]), onnx)


class Estimator:
    def __init__(self, model_dir, threads=2):
        import onnxruntime as ort
        self.bundle = Bundle.load(model_dir)
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = threads
        opts.inter_op_num_threads = 1
        opts.enable_mem_pattern = False  # 입력 크기는 카메라 해상도마다 다를 수 있다
        self.session = ort.InferenceSession(str(self.bundle.path), opts, providers=["CPUExecutionProvider"])
        self.input_name = self.session.get_inputs()[0].name
        self.lock = threading.Lock()

    @property
    def model_version(self):
        return self.bundle.model_version

    def density(self, image):
        """RGB PIL 이미지 → 밀도 맵 (h,w) float32."""
        b = self.bundle
        w0, h0 = image.size
        scale = b.long_side / max(w0, h0)
        w = max(b.multiple, round(w0 * scale / b.multiple) * b.multiple)
        h = max(b.multiple, round(h0 * scale / b.multiple) * b.multiple)
        arr = np.asarray(image.resize((w, h), Image.BILINEAR), dtype=np.float32) / 255.0
        arr = (arr - np.array(b.mean, dtype=np.float32)) / np.array(b.std, dtype=np.float32)
        x = arr.transpose(2, 0, 1)[None].astype(np.float32)
        with self.lock:  # 세션 하나를 한 번에 하나씩 쓴다(스레드 수는 세션이 정한다)
            out = self.session.run(None, {self.input_name: x})[0]
        return np.maximum(out[0, 0], 0)

    def estimate(self, jpeg, zones):
        """zones: contract.Zone 목록. 응답 zones 목록을 돌려준다."""
        image = decode(jpeg)
        gray = np.asarray(ImageOps.grayscale(image).resize(_small(image.size), Image.BILINEAR), dtype=np.float32)
        frame_reasons = ["BLURRED"] if laplacian_var(gray) < BLUR_VAR else []

        plans = [plan_zone(z) for z in zones]
        dens = self.density(image) if any(p["polygon"] for p in plans) else None
        if dens is not None:
            pts, weights = density_cells(dens)
            counted = roi_mod.count_by_zone(pts, weights, {i: p["polygon"] for i, p in enumerate(plans) if p["polygon"]})

        out = []
        for i, (zone, p) in enumerate(zip(zones, plans)):
            reasons = list(p["reasons"])
            count = None
            if p["polygon"]:
                count = round(counted[i], 1)
                brightness = mean_inside(gray, p["polygon"])
                if brightness is not None and brightness < TOO_DARK:
                    count = None
                if brightness is not None and brightness < DARK:
                    reasons.append("LOW_LIGHT")
                elif brightness is not None and brightness > TOO_BRIGHT:
                    reasons.append("OVEREXPOSED")
                reasons += frame_reasons
            out.append({"zoneId": zone.zoneId, "estimatedCount": count, "qualityReasons": _dedupe(reasons)})
        return out


def decode(jpeg):
    try:
        with Image.open(io.BytesIO(jpeg)) as img:
            if img.format != "JPEG":
                raise FrameError("JPEG가 아닙니다.")
            if img.width * img.height > MAX_PIXELS:
                raise FrameError("이미지가 너무 큽니다.")
            return img.convert("RGB")
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as e:
        raise FrameError("이미지를 읽을 수 없습니다.") from e


def plan_zone(zone):
    """구역을 셀지 정한다. polygon이 None이면 세지 않는다."""
    if zone.scope == "UNDETERMINED":
        return {"polygon": None, "reasons": []}
    points = [(p.x, p.y) for p in (zone.roi or [])]
    if len(points) < 3 or polygon_area(points) < MIN_VISIBLE_AREA:
        return {"polygon": None, "reasons": ["ROI_MISSING"]}
    visible = clip_unit(points)
    visible_area = polygon_area(visible) if len(visible) >= 3 else 0.0
    if visible_area < MIN_VISIBLE_AREA:
        return {"polygon": None, "reasons": ["OUT_OF_VIEW"]}
    reasons = ["ROI_OUTSIDE_FRAME"] if visible_area < polygon_area(points) * CLIP_TOLERANCE else []
    return {"polygon": visible, "reasons": reasons}


def polygon_area(points):
    if len(points) < 3:
        return 0.0
    xs = np.array([p[0] for p in points])
    ys = np.array([p[1] for p in points])
    return float(abs(np.dot(xs, np.roll(ys, -1)) - np.dot(ys, np.roll(xs, -1))) / 2)


def clip_unit(points):
    """다각형을 화면(0~1 정사각형)으로 자른다(Sutherland–Hodgman)."""
    def clip(poly, inside, cross):
        out = []
        for i, cur in enumerate(poly):
            prev = poly[i - 1]
            if inside(cur):
                if not inside(prev):
                    out.append(cross(prev, cur))
                out.append(cur)
            elif inside(prev):
                out.append(cross(prev, cur))
        return out

    def at_x(v):
        return lambda a, b: (v, a[1] + (b[1] - a[1]) * (v - a[0]) / (b[0] - a[0]))

    def at_y(v):
        return lambda a, b: (a[0] + (b[0] - a[0]) * (v - a[1]) / (b[1] - a[1]), v)

    poly = list(points)
    for inside, cross in ((lambda p: p[0] >= 0, at_x(0.0)), (lambda p: p[0] <= 1, at_x(1.0)),
                          (lambda p: p[1] >= 0, at_y(0.0)), (lambda p: p[1] <= 1, at_y(1.0))):
        if not poly:
            break
        poly = clip(poly, inside, cross)
    return poly


def density_cells(density):
    h, w = density.shape
    ys, xs = np.mgrid[0:h, 0:w]
    pts = np.stack([(xs.ravel() + 0.5) / w, (ys.ravel() + 0.5) / h], 1)
    return pts, density.ravel().astype(np.float64)


def mean_inside(gray, polygon):
    h, w = gray.shape
    ys, xs = np.mgrid[0:h, 0:w]
    pts = np.stack([(xs.ravel() + 0.5) / w, (ys.ravel() + 0.5) / h], 1)
    mask = roi_mod.inside(pts, polygon)
    return float(gray.ravel()[mask].mean()) if mask.any() else None


def laplacian_var(gray):
    lap = (-4 * gray[1:-1, 1:-1] + gray[:-2, 1:-1] + gray[2:, 1:-1] + gray[1:-1, :-2] + gray[1:-1, 2:])
    return float(lap.var())


def _small(size, long_side=640):
    w, h = size
    s = min(1.0, long_side / max(w, h))
    return max(1, round(w * s)), max(1, round(h * s))


def _dedupe(items):
    return list(dict.fromkeys(items))

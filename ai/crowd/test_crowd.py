"""계약·구역 계수·품질 사유·HTTP 경로 테스트. 모델 묶음이 있으면 실제 ONNX 추정도 확인한다.

    uv run python -m unittest -v
"""

import base64
import hashlib
import io
import json
import os
import unittest
from pathlib import Path

import numpy as np
from fastapi.testclient import TestClient
from PIL import Image

import contract
import estimator
import roi
import server

HERE = Path(__file__).resolve().parent
TOKEN = "test-token"
Z = ["10000000-0000-4000-8000-00000000000" + str(i) for i in range(1, 6)]


def jpeg(color=(128, 128, 128), size=(320, 180), noise=True):
    arr = np.full((size[1], size[0], 3), color, dtype=np.uint8)
    if noise:  # 흐림 판정에 걸리지 않을 만큼 무늬를 넣는다
        rng = np.random.default_rng(0)
        arr = np.clip(arr.astype(int) + rng.integers(-40, 40, arr.shape), 0, 255).astype(np.uint8)
    buf = io.BytesIO()
    Image.fromarray(arr).save(buf, format="JPEG", quality=95)
    return buf.getvalue()


def rect(x0, y0, x1, y1):
    return [{"x": x0, "y": y0}, {"x": x1, "y": y0}, {"x": x1, "y": y1}, {"x": x0, "y": y1}]


def request(data=None, zones=None):
    data = data or jpeg()
    zones = zones if zones is not None else [{"zoneId": Z[0], "scope": "FULL", "roi": rect(0, 0, 0.5, 1)},
                                             {"zoneId": Z[1], "scope": "PARTIAL", "roi": rect(0.5, 0, 1, 1)}]
    return {"schemaVersion": "1", "eventId": "80000000-0000-4000-8000-000000000001",
            "cameraId": "40000000-0000-4000-8000-000000000001",
            "streamSessionId": "50000000-0000-4000-8000-000000000001", "sequence": "12",
            "capturedAt": "2026-11-18T10:00:00Z", "inputHash": hashlib.sha256(data).hexdigest(),
            "image": {"contentType": "image/jpeg", "base64": base64.b64encode(data).decode()}, "zones": zones}


class UniformModel(estimator.Estimator):
    """모델 대신 화면 전체에 고르게 total명을 퍼뜨린 밀도 맵을 쓴다."""

    def __init__(self, total=40.0, grid=(20, 40)):
        self.total, self.grid = total, grid
        self.bundle = None

    @property
    def model_version(self):
        return "uniform-test"

    def density(self, image):
        h, w = self.grid
        return np.full((h, w), self.total / (h * w), dtype=np.float32)


def zones_of(raw):
    req, data = contract.parse_request(raw)
    return req, data


class ContractTest(unittest.TestCase):
    def test_example_pair(self):
        req = json.loads((HERE / "examples" / "request.json").read_text(encoding="utf-8"))
        res = json.loads((HERE / "examples" / "success.json").read_text(encoding="utf-8"))
        parsed, _ = contract.parse_request(req)
        contract.check_response(parsed, res)

    def test_hash_must_match_image(self):
        raw = request()
        raw["inputHash"] = "0" * 64
        with self.assertRaises(contract.ContractViolation):
            contract.parse_request(raw)

    def test_rejects_unknown_field_and_duplicate_zone(self):
        raw = request()
        raw["extra"] = 1
        with self.assertRaises(contract.ContractViolation):
            contract.parse_request(raw)
        raw = request(zones=[{"zoneId": Z[0], "scope": "FULL", "roi": rect(0, 0, 1, 1)}] * 2)
        with self.assertRaises(contract.ContractViolation):
            contract.parse_request(raw)


class RoiTest(unittest.TestCase):
    def test_point_counted_once_in_first_zone(self):
        pts = np.array([[0.5, 0.5], [0.1, 0.1]])
        out = roi.count_by_zone(pts, [1, 1], {"a": [(0, 0), (0.6, 0), (0.6, 0.6), (0, 0.6)],
                                              "b": [(0.4, 0.4), (1, 0.4), (1, 1), (0.4, 1)]})
        self.assertEqual(out, {"a": 2.0, "b": 0.0})

    def test_clip_unit(self):
        clipped = estimator.clip_unit([(0.5, 0.5), (1.5, 0.5), (1.5, 1.5), (0.5, 1.5)])
        self.assertAlmostEqual(estimator.polygon_area(clipped), 0.25)
        self.assertEqual(estimator.clip_unit([(1.1, 0), (2, 0), (2, 1)]), [])


class EstimateTest(unittest.TestCase):
    def run_zones(self, zones, data=None, model=None):
        req, data = zones_of(request(data, zones))
        return {z["zoneId"]: z for z in (model or UniformModel()).estimate(data, req.zones)}

    def test_counts_only_inside_roi(self):
        out = self.run_zones([{"zoneId": Z[0], "scope": "FULL", "roi": rect(0, 0, 0.5, 1)},
                              {"zoneId": Z[1], "scope": "FULL", "roi": rect(0.5, 0, 0.75, 0.5)}])
        self.assertAlmostEqual(out[Z[0]]["estimatedCount"], 20.0)
        self.assertAlmostEqual(out[Z[1]]["estimatedCount"], 5.0)
        self.assertEqual(out[Z[0]]["qualityReasons"], [])

    def test_partial_counts_visible_part_without_extrapolation(self):
        # 화면 오른쪽 밖으로 절반이 나간 구역: 보이는 절반만 센다
        out = self.run_zones([{"zoneId": Z[0], "scope": "PARTIAL", "roi": rect(0.75, 0, 1.25, 1)}])
        self.assertAlmostEqual(out[Z[0]]["estimatedCount"], 10.0)
        self.assertEqual(out[Z[0]]["qualityReasons"], ["ROI_OUTSIDE_FRAME"])

    def test_out_of_view_is_null_not_zero(self):
        out = self.run_zones([{"zoneId": Z[0], "scope": "FULL", "roi": rect(1.1, 0, 1.5, 1)},
                              {"zoneId": Z[1], "scope": "FULL", "roi": None},
                              {"zoneId": Z[2], "scope": "UNDETERMINED", "roi": rect(0, 0, 1, 1)}])
        self.assertEqual(out[Z[0]], {"zoneId": Z[0], "estimatedCount": None, "qualityReasons": ["OUT_OF_VIEW"]})
        self.assertEqual(out[Z[1]]["qualityReasons"], ["ROI_MISSING"])
        self.assertIsNone(out[Z[1]]["estimatedCount"])
        self.assertIsNone(out[Z[2]]["estimatedCount"])

    def test_dark_frame_is_flagged_or_null(self):
        zones = [{"zoneId": Z[0], "scope": "FULL", "roi": rect(0, 0, 1, 1)}]
        dim = self.run_zones(zones, jpeg((35, 35, 35)))
        self.assertIsNotNone(dim[Z[0]]["estimatedCount"])
        self.assertIn("LOW_LIGHT", dim[Z[0]]["qualityReasons"])
        dark = self.run_zones(zones, jpeg((5, 5, 5), noise=False))
        self.assertIsNone(dark[Z[0]]["estimatedCount"])
        self.assertIn("LOW_LIGHT", dark[Z[0]]["qualityReasons"])

    def test_flat_frame_is_blurred(self):
        out = self.run_zones([{"zoneId": Z[0], "scope": "FULL", "roi": rect(0, 0, 1, 1)}], jpeg(noise=False))
        self.assertIn("BLURRED", out[Z[0]]["qualityReasons"])

    def test_not_jpeg(self):
        buf = io.BytesIO()
        Image.new("RGB", (8, 8)).save(buf, format="PNG")
        req, data = zones_of(request(buf.getvalue()))
        with self.assertRaises(estimator.FrameError):
            UniformModel().estimate(data, req.zones)


class ServerTest(unittest.TestCase):
    def client(self, model=None):
        settings = server.Settings(service_token=TOKEN)
        return TestClient(server.create_app(settings, model=model or UniformModel()))

    def post(self, client, body, token=TOKEN):
        return client.post("/internal/estimate-crowd", json=body, headers={"Authorization": f"Bearer {token}"})

    def test_success_matches_be_parser_rules(self):
        raw = request()
        res = self.post(self.client(), raw)
        self.assertEqual(res.status_code, 200)
        req, _ = contract.parse_request(raw)
        contract.check_response(req, res.json())
        self.assertEqual(res.json()["modelVersion"], "uniform-test")

    def test_auth_and_invalid(self):
        c = self.client()
        self.assertEqual(self.post(c, request(), token="wrong").status_code, 401)
        bad = request()
        bad["inputHash"] = "1" * 64
        res = self.post(c, bad)
        self.assertEqual(res.status_code, 400)
        self.assertEqual(res.json()["error"]["code"], "INVALID_REQUEST")

    def test_health_without_model(self):
        settings = server.Settings(service_token=TOKEN, model_dir=str(HERE / "no-such-bundle"))
        c = TestClient(server.create_app(settings))
        self.assertEqual(c.get("/health").status_code, 503)
        self.assertEqual(self.post(c, request()).status_code, 503)


MODEL_DIR = os.environ.get("CROWD_MODEL_DIR") or str(server.DEFAULT_MODEL_DIR)
SAMPLE = HERE.parent / "labeling" / "crowd_sample" / "crowd_img" / "Indoor_EXCO012_535.jpg"


@unittest.skipUnless((Path(MODEL_DIR) / "model.onnx").exists() and SAMPLE.exists(), "모델 묶음·샘플 없음")
class RealModelTest(unittest.TestCase):
    def test_exco_sample_zones(self):
        rois = json.loads((HERE.parent / "labeling" / "crowd" / "rois.json").read_text(encoding="utf-8"))["zones"]
        zones = [{"zoneId": Z[i], "scope": z["scope"], "roi": z["roi"]} for i, z in enumerate(rois)]
        zones.append({"zoneId": Z[4], "scope": "FULL", "roi": rect(1.2, 0, 1.4, 0.2)})  # 화면 밖
        req, data = zones_of(request(SAMPLE.read_bytes(), zones))
        out = {z["zoneId"]: z for z in estimator.Estimator(MODEL_DIR).estimate(data, req.zones)}
        # 535 정답: Z1 0, Z2 2, Z3 71, Z4(화면 안) 21
        self.assertLess(out[Z[0]]["estimatedCount"], 1)
        self.assertLess(abs(out[Z[1]]["estimatedCount"] - 2), 2)
        self.assertLess(abs(out[Z[3]]["estimatedCount"] - 21), 5)
        self.assertEqual(out[Z[3]]["qualityReasons"], [])
        # 알려진 실패: 먼 뒤쪽 Z3는 정답의 10% 미만을 사유 없이 돌려준다. AI는 사람 크기를 판정하지 못하므로
        # 이런 구역은 켜는 조건(README)에서 카메라 ROI 매핑에서 빼야 한다. 모델이 바뀌어 이 값이 달라지면 조건을 다시 잰다.
        self.assertLess(out[Z[2]]["estimatedCount"], 71 * 0.1)
        self.assertEqual(out[Z[2]]["qualityReasons"], [])
        self.assertIsNone(out[Z[4]]["estimatedCount"])
        self.assertEqual(out[Z[4]]["qualityReasons"], ["OUT_OF_VIEW"])


if __name__ == "__main__":
    unittest.main()

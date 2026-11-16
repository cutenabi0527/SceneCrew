"""ONNX 변환·INT8 양자화·배포 묶음 검사 회귀 테스트 (사전학습 모델 없이 실행된다).

test_report의 작은 토크나이저·RoBERTa로 저장 묶음을 만들어 export_onnx.build와 verify_onnx.verify를 돌린다.
onnx 도구(requirements-onnx.txt)가 없으면 건너뛴다.
실행: cd ai/report && uv run --with-requirements requirements-onnx.txt python -m unittest -v
"""

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

from test_report import ROW, tiny_model, tiny_tokenizer

HAS_ONNX = all(importlib.util.find_spec(m) for m in ("onnx", "onnxruntime", "onnxscript"))
MAX_LENGTH = 128  # 작은 모델의 위치 임베딩(130) 안이고 겹침(64)보다 길다


@unittest.skipUnless(HAS_ONNX, "onnx 도구 없음 (uv run --with-requirements requirements-onnx.txt)")
class OnnxExportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import export_onnx

        sys.stdout.reconfigure(encoding="utf-8")  # torch.onnx 진행 표시의 이모지를 cp949 콘솔에서도 쓴다(export_onnx.main과 같음)
        cls.tmp = tempfile.TemporaryDirectory()
        root = Path(cls.tmp.name)
        cls.source, cls.bundle = root / "tiny", root / "tiny-onnx-int8"
        tok = tiny_tokenizer([ROW["text"]])
        tiny_model(tok).save(cls.source, tok)
        (cls.source / "training.json").write_text(json.dumps({"modelVersion": "tiny"}), encoding="utf-8")
        cls.record = export_onnx.build(cls.source, cls.bundle, MAX_LENGTH, results=None)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_record_identifies_versions_and_quantized_nodes(self):
        self.assertEqual((self.record["modelVersion"], self.record["sourceModelVersion"]), ("tiny-onnx-int8", "tiny"))
        self.assertEqual(self.record["fp32"]["ops"]["Gemm"], 2)  # [CLS] 위치의 유형·상태 헤드
        # 상수 가중치 MatMul(층당 6 + 개체 헤드)과 Gemm 2개가 모두 MatMulInteger가 된다
        self.assertEqual(self.record["int8"]["quantizedNodes"]["MatMulInteger"],
                         self.record["fp32"]["ops"]["MatMul"] - self.record["int8"]["fp32MatMulLeft"] + 2)
        self.assertEqual(self.record["int8"]["gemmLeft"], 0)
        self.assertTrue(all(v["argmaxSame"] for v in self.record["fp32"]["parityWithTorch"].values()))
        shapes = {o["name"]: o["shape"] for o in self.record["io"]["outputs"]}
        self.assertEqual(shapes, {"category": ["batch", 6], "state": ["batch", 4], "entity": ["batch", "sequence", 9]})

    def test_bundle_passes_verification(self):
        import verify_onnx

        report = verify_onnx.verify(self.bundle, self.source, MAX_LENGTH)
        self.assertTrue(report["passed"], report)
        self.assertEqual([c["name"] for c in report["cases"]], ["short", f"max_length_{MAX_LENGTH}", "multi_window"])
        self.assertEqual(report["cases"][1]["length"], MAX_LENGTH)
        self.assertGreater(report["cases"][2]["batch"], 1)

    def test_verification_fails_on_changed_bundle(self):
        import shutil

        import verify_onnx
        from model import LABELS_FILE

        changed = Path(self.tmp.name) / "changed"
        shutil.copytree(self.bundle, changed)
        labels = json.loads((changed / LABELS_FILE).read_text(encoding="utf-8"))
        labels["category"] = labels["category"][::-1]
        (changed / LABELS_FILE).write_text(json.dumps(labels), encoding="utf-8")
        report = verify_onnx.verify(changed, self.source, MAX_LENGTH)
        failed = {c["name"] for c in report["checks"] if not c["passed"]}
        self.assertEqual((report["passed"], failed), (False, {"bundleSha256", "labels"}))

    def test_verification_records_missing_and_extra_files(self):
        import shutil

        import verify_onnx

        changed = Path(self.tmp.name) / "missing"
        shutil.copytree(self.bundle, changed)
        (changed / "tokenizer.json").unlink()
        (changed / "model.fp32.onnx").write_bytes(b"")
        report = verify_onnx.verify(changed, self.source, MAX_LENGTH)
        bundle_check = report["checks"][0]
        self.assertFalse(report["passed"])
        self.assertEqual((bundle_check["missing"], bundle_check["extra"]), (["tokenizer.json"], ["model.fp32.onnx"]))
        self.assertEqual(report["cases"], [])


if __name__ == "__main__":
    unittest.main()

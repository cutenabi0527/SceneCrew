import asyncio
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import types
import unittest
from unittest import mock

from .ocr_worker import DETECTION, RECOGNITION, OcrWorker, _load_ocr, _wait_with_memory_budget
from .source import DocumentError


class OfflineModelTest(unittest.TestCase):
    def test_missing_model_fails_before_import(self):
        with TemporaryDirectory() as folder, mock.patch.dict(sys.modules, {"paddleocr": None}):
            with self.assertRaisesRegex(DocumentError, "OCR_MODEL_MISSING"):
                _load_ocr(folder, folder)

    def test_explicit_models_and_no_optional_downloads(self):
        with TemporaryDirectory() as folder:
            detection, recognition = (Path(folder) / name for name in ("det", "rec"))
            for directory in (detection, recognition):
                directory.mkdir()
                for name in ("inference.yml", "inference.json", "inference.pdiparams"):
                    (directory / name).write_bytes(b"fixture")
            factory = mock.Mock()
            factory.return_value.predict.return_value = [{"rec_texts": ["날짜", "행사"]}]
            cv2 = types.SimpleNamespace(setNumThreads=mock.Mock(), imdecode=mock.Mock(return_value=object()),
                                        IMREAD_COLOR=1)
            numpy = types.SimpleNamespace(frombuffer=mock.Mock(), uint8=object())
            modules = {"paddleocr": types.SimpleNamespace(PaddleOCR=factory), "cv2": cv2, "numpy": numpy}
            with mock.patch.dict(sys.modules, modules):
                reader = _load_ocr(str(detection), str(recognition))
                self.assertEqual(reader(b"fixture"), "날짜\n행사")
            factory.assert_called_once_with(
                device="cpu", cpu_threads=1, enable_mkldnn=False,
                text_detection_model_name=DETECTION, text_detection_model_dir=str(detection),
                text_recognition_model_name=RECOGNITION, text_recognition_model_dir=str(recognition),
                use_doc_orientation_classify=False, use_doc_unwarping=False, use_textline_orientation=False,
            )


class WorkerTest(unittest.IsolatedAsyncioTestCase):
    async def test_waiting_job_past_deadline_never_spawns(self):
        worker = OcrWorker("detection", "recognition")
        deadline = datetime.now(timezone.utc) - timedelta(seconds=1)
        with mock.patch("sys.platform", "linux"), mock.patch("shutil.which", return_value="pdftoppm"), \
                mock.patch("asyncio.create_subprocess_exec") as spawn:
            async with worker.slot:
                waiting = asyncio.create_task(worker.run([], deadline))
                await asyncio.sleep(0)
                self.assertFalse(waiting.done())
            with self.assertRaises(TimeoutError):
                await waiting
            spawn.assert_not_called()

    async def test_corrupt_model_sets_unavailable_reason(self):
        worker = OcrWorker("detection", "recognition")
        with mock.patch.object(worker, "run", side_effect=DocumentError("OCR_FAILED")):
            await worker.probe()
        self.assertEqual(worker.status, "OCR_FAILED")

    @unittest.skipUnless(sys.platform == "linux", "Linux process-group check")
    async def test_memory_budget_kills_and_reaps_worker(self):
        process = await asyncio.create_subprocess_exec(
            sys.executable, "-c", "import time; time.sleep(30)", start_new_session=True,
        )
        with mock.patch("ai.document_extraction.ocr_worker._group_rss", return_value=2 * 1024**3):
            with self.assertRaisesRegex(DocumentError, "OCR_MEMORY_LIMIT"):
                await _wait_with_memory_budget(process)
        self.assertEqual(process.returncode, -9)

    @unittest.skipUnless(sys.platform == "linux", "Linux process-group check")
    async def test_deadline_reaps_worker_and_removes_ipc_before_releasing_slot(self):
        worker = OcrWorker("detection", "recognition")
        spawn = asyncio.create_subprocess_exec
        process = None
        directory = None
        started = asyncio.Event()

        async def slow_worker(*args, **kwargs):
            nonlocal process, directory
            directory = Path(args[4])
            process = await spawn(sys.executable, "-c", "import time; time.sleep(30)", **kwargs)
            started.set()
            return process

        with mock.patch("asyncio.create_subprocess_exec", side_effect=slow_worker):
            task = asyncio.create_task(worker.run([]))
            await asyncio.wait_for(started.wait(), 5)
            with self.assertRaises(TimeoutError):
                await asyncio.wait_for(task, 0.05)
        self.assertEqual(process.returncode, -9)
        self.assertFalse(directory.exists())
        self.assertFalse(worker.slot.locked())

"""Linux subprocess for offline PDF extraction and OCR."""

import asyncio
import ctypes
from datetime import datetime, timezone
import errno
import os
from pathlib import Path
import pickle
import shutil
import signal
import sys
from tempfile import TemporaryDirectory

from .execution import extract
from .rendering import render_with_poppler
from .source import DocumentError

MEMORY_LIMIT = 2 * 1024**3
DETECTION = "PP-OCRv5_mobile_det"
RECOGNITION = "korean_PP-OCRv5_mobile_rec"
INIT_TIMEOUT = 90


def _load_ocr(detection, recognition):
    for directory in (detection, recognition):
        for name in ("inference.yml", "inference.json", "inference.pdiparams"):
            path = Path(directory) / name
            if not path.is_file() or path.stat().st_size == 0:
                raise DocumentError("OCR_MODEL_MISSING")
    from paddleocr import PaddleOCR
    import cv2
    import numpy

    cv2.setNumThreads(1)
    pipeline = PaddleOCR(
        device="cpu", cpu_threads=1, enable_mkldnn=False,
        text_detection_model_name=DETECTION, text_detection_model_dir=detection,
        text_recognition_model_name=RECOGNITION, text_recognition_model_dir=recognition,
        use_doc_orientation_classify=False, use_doc_unwarping=False, use_textline_orientation=False,
    )

    def recognize(png):
        try:
            image = cv2.imdecode(numpy.frombuffer(png, dtype=numpy.uint8), cv2.IMREAD_COLOR)
            if image is None:
                raise DocumentError("OCR_FAILED")
            return "\n".join(text for item in pipeline.predict(image) for text in item["rec_texts"])
        except Exception as exc:
            raise DocumentError(_ocr_error(exc)) from None

    return recognize


def _ocr_error(exc):
    if isinstance(exc, DocumentError):
        return exc.code
    if isinstance(exc, MemoryError) or any(
        reason in str(exc).lower() for reason in ("out of memory", "bad_alloc", "cannot allocate memory")
    ):
        return "OCR_MEMORY_LIMIT"
    return "OCR_FAILED"


def _restrict_worker(parent_pid):
    # If Uvicorn exits before it can cancel the job, tear down this worker group.
    signal.signal(signal.SIGTERM, lambda *_: os.killpg(os.getpgrp(), signal.SIGKILL))
    libc = ctypes.CDLL(None)
    if libc.prctl(1, signal.SIGTERM, 0, 0, 0) != 0:  # PR_SET_PDEATHSIG
        raise DocumentError("OCR_SANDBOX_UNAVAILABLE")
    if os.getppid() != parent_pid:
        os.killpg(os.getpgrp(), signal.SIGKILL)

    # No network sockets in this worker or its renderer subprocess. Model paths
    # are still explicit to prevent PaddleX from trying its model cache.
    seccomp = ctypes.CDLL("libseccomp.so.2")
    seccomp.seccomp_init.argtypes = [ctypes.c_uint32]
    seccomp.seccomp_init.restype = ctypes.c_void_p
    seccomp.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
    seccomp.seccomp_rule_add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]
    seccomp.seccomp_load.argtypes = [ctypes.c_void_p]
    seccomp.seccomp_release.argtypes = [ctypes.c_void_p]
    context = seccomp.seccomp_init(0x7FFF0000)  # SCMP_ACT_ALLOW
    if not context:
        raise DocumentError("OCR_SANDBOX_UNAVAILABLE")
    try:
        syscall = seccomp.seccomp_syscall_resolve_name(b"socket")
        deny = 0x00050000 | errno.EPERM  # SCMP_ACT_ERRNO(EPERM)
        if syscall < 0 or seccomp.seccomp_rule_add(context, deny, syscall, 0) or seccomp.seccomp_load(context):
            raise DocumentError("OCR_SANDBOX_UNAVAILABLE")
    finally:
        seccomp.seccomp_release(context)


def _worker(directory, detection, recognition, parent_pid):
    root = Path(directory)
    output = root / "result"
    try:
        _restrict_worker(int(parent_pid))
    except (OSError, ValueError, DocumentError):
        output.write_bytes(pickle.dumps(("OCR_SANDBOX_UNAVAILABLE", None)))
        return
    try:
        with (root / "request").open("rb") as source:
            originals = pickle.load(source)
        if originals is None:
            _load_ocr(detection, recognition)
            response = (None, None)
        else:
            recognize = None

            def ocr(png):
                nonlocal recognize
                if recognize is None:
                    try:
                        recognize = _load_ocr(detection, recognition)
                    except Exception as exc:
                        raise DocumentError(_ocr_error(exc)) from None
                return recognize(png)

            response = (None, extract(originals, render_page=render_with_poppler, ocr=ocr))
    except Exception as exc:
        response = (_ocr_error(exc), None)
    with output.open("wb") as target:
        pickle.dump(response, target)


def _group_rss(group):
    pages = 0
    for entry in Path("/proc").iterdir():
        if not entry.name.isdecimal():
            continue
        try:
            # The executable name may itself contain spaces or parentheses.
            fields = (entry / "stat").read_text().rsplit(")", 1)[1].split()
            if int(fields[2]) == group:
                pages += int(fields[21])
        except (OSError, ValueError, IndexError):
            continue  # a process can exit during the snapshot
    return pages * os.sysconf("SC_PAGE_SIZE")


async def _stop_group(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    await process.wait()


async def _wait_with_memory_budget(process):
    waiter = asyncio.create_task(process.wait())
    try:
        while not waiter.done():
            if _group_rss(process.pid) >= MEMORY_LIMIT:
                raise DocumentError("OCR_MEMORY_LIMIT")
            await asyncio.wait({waiter}, timeout=0.05)
        if waiter.result() != 0:
            await _stop_group(process)
            raise DocumentError("OCR_WORKER_FAILED")
    finally:
        if not waiter.done():
            await _stop_group(process)
            await waiter


class OcrWorker:
    def __init__(self, detection, recognition):
        self.detection = detection
        self.recognition = recognition
        self.slot = asyncio.Lock()
        self.status = "OCR_INITIALIZING"

    async def probe(self):
        try:
            async with asyncio.timeout(INIT_TIMEOUT):
                await self.run(None)
        except TimeoutError:
            self.status = "OCR_INIT_TIMEOUT"
        except DocumentError as exc:
            self.status = exc.code
        else:
            self.status = None

    async def run(self, originals, deadline=None):
        if sys.platform != "linux":
            raise DocumentError("OCR_SANDBOX_UNAVAILABLE")
        if not shutil.which("pdftoppm"):
            raise DocumentError("PDF_RENDERER_UNAVAILABLE")
        async with self.slot:
            if deadline is not None and datetime.now(timezone.utc) >= deadline:
                raise TimeoutError
            # The child sees only app-owned IPC files and no service credentials.
            with TemporaryDirectory(prefix="scenecrew-ocr-") as directory:
                root = Path(directory)
                with (root / "request").open("wb") as target:
                    pickle.dump(originals, target)
                environment = {
                    key: os.environ[key] for key in ("PATH", "LANG", "LD_LIBRARY_PATH") if key in os.environ
                }
                environment.update(HOME=directory, PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK="1",
                                   HF_HUB_OFFLINE="1", OPENBLAS_NUM_THREADS="1", OMP_NUM_THREADS="1",
                                   MALLOC_ARENA_MAX="2")
                if deadline is not None and datetime.now(timezone.utc) >= deadline:
                    raise TimeoutError
                # Start in its own process group so Poppler is also stopped on cancellation.
                spawn = asyncio.create_task(asyncio.create_subprocess_exec(
                    sys.executable, "-B", "-m", f"{__package__}.ocr_worker",
                    directory, self.detection, self.recognition, str(os.getpid()),
                    env=environment, cwd=Path(__file__).resolve().parents[len(__package__.split(".")) - 1].parent,
                    stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL,
                    stderr=asyncio.subprocess.DEVNULL, start_new_session=True,
                ))
                process = None
                try:
                    process = await asyncio.shield(spawn)
                    await _wait_with_memory_budget(process)
                except asyncio.CancelledError:
                    if process is None:
                        await _stop_group(await spawn)
                    raise
                except OSError:
                    raise DocumentError("OCR_WORKER_FAILED") from None
                try:
                    with (root / "result").open("rb") as result:
                        error, extraction = pickle.load(result)
                except (OSError, EOFError, pickle.UnpicklingError):
                    raise DocumentError("OCR_WORKER_FAILED") from None
                if error:
                    raise DocumentError(error)
                return extraction


if __name__ == "__main__":
    _worker(*sys.argv[1:])

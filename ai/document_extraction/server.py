"""ASGI endpoint for the BE extraction request4 / result3 contract."""

import asyncio
from datetime import datetime, timezone
import hmac
from importlib.util import find_spec
import json
import os
import re
import shutil
from uuid import UUID

from .conditions import REQUIRED_FIELDS
from .documents import MAX_DOCUMENTS, MAX_PDF_PAGES, MAX_TOTAL_BYTES, PDF, TEXT_TYPES, Original
from .execution import extract
from .ocr_worker import OcrWorker
from .rendering import render_with_poppler
from .source import DOCUMENT_PATH, MAX_FILE_BYTES, DocumentError, document_origin, download_original

PATH = "/internal/extract-event-input"
KIND = "EXTRACT_EVENT_INPUT"
IDENTITY = ("jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision")
SHA256 = re.compile(r"[0-9a-fA-F]{64}")
MAX_REQUEST_BYTES = 32_768
# Result3 carries Event basics and supported confirmedInput candidates as strings.
RESULT_FIELDS = {
    "name", "description", "date", "prepStartAt", "openAt", "closeAt", "teardownEndAt",
    "locationName", "address", "totalCapacity", "expectedVisitors", "peakVisitors",
    "zones", "programOrder",
}


def _uuid(value):
    if not isinstance(value, str) or str(UUID(value)) != value:
        raise ValueError("Expected a canonical UUID")
    return UUID(value)


def _integer(value, minimum, maximum):
    return type(value) is int and minimum <= value <= maximum


def _identity(raw):
    _uuid(raw["jobId"])
    if not (
        _integer(raw["attempt"], 1, 2**31 - 1)
        and raw["requestSchemaVersion"] == "4"
        and isinstance(raw["inputHash"], str) and SHA256.fullmatch(raw["inputHash"])
        and _integer(raw["contextRevision"], 0, 2**63 - 1)
    ):
        raise ValueError("Invalid extraction identity")
    return {key: raw[key] for key in IDENTITY}


def _request(raw, prefixes):
    if raw["kind"] != KIND:
        raise ValueError("Invalid extraction kind")
    deadline = datetime.fromisoformat(raw["deadlineAt"])
    if deadline.utcoffset() is None:
        raise ValueError("Deadline must include a timezone")
    if "scope" in raw:
        _uuid(raw["scope"]["eventId"])
        _uuid(raw["scope"]["chiefMemberId"])
    documents = raw["payload"]["documents"]
    if not isinstance(documents, list) or not 1 <= len(documents) <= MAX_DOCUMENTS:
        raise ValueError("Invalid document count")

    seen = set()
    total_bytes = total_pages = 0
    for document in documents:
        document_id = _uuid(document["documentId"])
        mime, size, pages = document["mime"], document["bytes"], document["pageCount"]
        if (
            document_id in seen or not isinstance(document["name"], str) or not document["name"]
            or mime not in TEXT_TYPES | {PDF}
            or not _integer(size, 1, MAX_FILE_BYTES)
            or not isinstance(document["sha256"], str) or not SHA256.fullmatch(document["sha256"])
            or document["url"] not in {prefix + str(document_id) for prefix in prefixes}
        ):
            raise ValueError("Invalid document reference")
        if mime == PDF:
            if not _integer(pages, 1, MAX_PDF_PAGES):
                raise ValueError("Invalid PDF page count")
            total_pages += pages
        elif pages is not None:
            raise ValueError("Only PDFs have a page count")
        total_bytes += size
        seen.add(document_id)
    if total_bytes > MAX_TOTAL_BYTES or total_pages > MAX_PDF_PAGES:
        raise ValueError("Document bundle exceeds limits")
    return deadline, documents


def _failure(identity, code, retryable=False):
    return {**identity, "error": {"code": code, "message": code, "retryable": retryable}}


def _result3(result, condition_keys=frozenset()):
    # 기본 키 목록과 계획서 부록 표의 조건 키(#401)만 넘긴다. 그 밖(performanceStart 등)은 쓰는 곳이 없어 뺀다.
    allowed = RESULT_FIELDS | condition_keys
    fields = {key: value for key, value in result["fields"].items() if key in allowed}
    conflicts = [item for item in result["conflictingValues"] if item["field"] in allowed]
    missing = list(result["missingRequired"])
    rejected = {key for key, value in fields.items() if len(value) > 1000}
    for item in conflicts:
        if any(len(value) > 1000 for value in item["values"]):
            rejected.add(item["field"])
    missing.extend(key for key in REQUIRED_FIELDS if key in rejected and key not in missing)
    return {
        **result,
        "fields": {key: value for key, value in fields.items() if key not in rejected},
        "missingRequired": missing,
        "conflictingValues": [item for item in conflicts if item["field"] not in rejected],
        "resultStatus": "PARTIAL" if rejected else result["resultStatus"],
    }


async def _send(send, status, response):
    body = json.dumps(response, ensure_ascii=False).encode("utf-8")
    await send({"type": "http.response.start", "status": status,
                "headers": [(b"content-type", b"application/json"), (b"cache-control", b"no-store")]})
    await send({"type": "http.response.body", "body": body})


def _prefixes(setting):
    prefixes = tuple(value.strip() for value in setting.split(",") if value.strip())
    if not prefixes:
        raise ValueError("DOCUMENT_URL_PREFIXES is required")
    for prefix in prefixes:
        origin = prefix.removesuffix(DOCUMENT_PATH)
        if prefix != document_origin(origin) + DOCUMENT_PATH:
            raise ValueError("Invalid document URL prefix")
        if not origin.startswith(("https://", "http://127.0.0.1:", "http://localhost:")):
            raise ValueError("Document URL prefix must use HTTPS or local test HTTP")
    return prefixes


def create_app(*, service_token=None, document_base_url=None, document_url_prefixes=None,
               transport=None, render_page=render_with_poppler, ocr=None, worker=None):
    """Create the standalone app. OCR is supplied by the approved runtime adapter."""
    if service_token is None:
        service_token = os.environ.get("AI_SERVICE_TOKEN", "")
    if not re.fullmatch(r"[!-~]+", service_token):
        raise ValueError("Extraction service configuration is missing or invalid")
    if document_base_url is not None:
        document_url_prefixes = document_origin(document_base_url) + DOCUMENT_PATH
    prefixes = _prefixes(document_url_prefixes if document_url_prefixes is not None
                         else os.environ.get("DOCUMENT_URL_PREFIXES", ""))
    detection = os.environ.get("OCR_DETECTION_MODEL_DIR")
    recognition = os.environ.get("OCR_RECOGNITION_MODEL_DIR")
    if worker is None and ocr is None and detection and recognition:
        worker = OcrWorker(detection, recognition)
    expected_auth = f"Bearer {service_token}".encode("ascii")
    missing = []
    if find_spec("pypdf") is None:
        missing.append("PDF_PARSER_UNAVAILABLE")
    if render_page is None or (render_page is render_with_poppler and not shutil.which("pdftoppm")):
        missing.append("PDF_RENDERER_UNAVAILABLE")
    if ocr is None and worker is None:
        missing.append("OCR_UNAVAILABLE")

    async def app(scope, receive, send):
        if scope["type"] == "lifespan":
            message = await receive()
            if message["type"] == "lifespan.startup":
                probe = asyncio.create_task(worker.probe()) if worker is not None else None
                try:
                    await send({"type": "lifespan.startup.complete"})
                    message = await receive()
                finally:
                    if probe is not None:
                        probe.cancel()
                        await asyncio.gather(probe, return_exceptions=True)
            if message["type"] == "lifespan.shutdown":
                await send({"type": "lifespan.shutdown.complete"})
            return
        if scope["type"] != "http":
            raise ValueError("Only HTTP is supported")
        if scope["path"] == "/health" and scope["method"] == "GET":
            # Match the plan child: Gateway derives readiness from the JSON status.
            issues = missing + ([worker.status] if worker is not None and worker.status else [])
            await _send(send, 200, {"status": "unavailable", "missing": issues} if issues else {"status": "ok"})
            return
        if scope["path"] != PATH or scope["method"] != "POST":
            await _send(send, 404 if scope["path"] != PATH else 405, {})
            return
        authorization = [value for key, value in scope["headers"] if key.lower() == b"authorization"]
        if len(authorization) != 1 or not hmac.compare_digest(authorization[0], expected_auth):
            await _send(send, 401, _failure({}, "UNAUTHENTICATED"))
            return

        identity = {}
        try:
            body = bytearray()
            while True:
                message = await receive()
                if message["type"] == "http.disconnect":
                    return
                chunk = message["body"]
                if len(body) + len(chunk) > MAX_REQUEST_BYTES:
                    await _send(send, 413, _failure({}, "INVALID_REQUEST"))
                    return
                body.extend(chunk)
                if not message.get("more_body"):
                    break
            raw = json.loads(body)
            identity = _identity(raw)
            deadline, documents = _request(raw, prefixes)
        except (KeyError, ValueError, TypeError, RecursionError):
            await _send(send, 400, _failure(identity, "INVALID_REQUEST"))
            return

        budget = (deadline - datetime.now(timezone.utc)).total_seconds()
        if budget <= 0:
            await _send(send, 504, _failure(identity, "AI_TIMEOUT"))
            return
        try:
            async with asyncio.timeout(budget):
                originals = []
                for document in documents:
                    document_id = UUID(document["documentId"])
                    content = await download_original(
                        base_url=document["url"].removesuffix(DOCUMENT_PATH + str(document_id)),
                        document_id=document_id, job_id=UUID(identity["jobId"]),
                        attempt=identity["attempt"], service_token=service_token,
                        sha256=document["sha256"], timeout=budget, transport=transport,
                    )
                    if len(content) != document["bytes"]:
                        raise DocumentError("FETCH_FAILED")
                    originals.append(Original(document_id, document["mime"], document["sha256"], content))
                if worker is not None and any(original.mime == PDF for original in originals):
                    extraction = await worker.run(originals, deadline)
                else:
                    extraction = await asyncio.to_thread(extract, originals, render_page=render_page, ocr=ocr)
                result = _result3(extraction.result, extraction.condition_keys)
                pdf_page_counts = {
                    doc["documentId"]: doc["pageCount"] for doc in documents if doc["mime"] == PDF
                }
                # Candidates come from the parsed PDF. The BE also checks the request's page bound.
                floor_plan_pages = [
                    page for page in extraction.floor_plan_metadata()
                    if _integer(page["pageNumber"], 1, pdf_page_counts.get(page["documentId"], 0))
                ]
                if floor_plan_pages:
                    result["floorPlanPages"] = floor_plan_pages
        except TimeoutError:
            await _send(send, 504, _failure(identity, "AI_TIMEOUT"))
            return
        except DocumentError as exc:
            if exc.code.startswith("OCR_") or exc.code == "PDF_RENDERER_UNAVAILABLE":
                error = _failure(identity, "AI_UNAVAILABLE")
                error["error"]["details"] = {"reason": exc.code}
                await _send(send, 503, error)
                return
            code = "AI_UNAVAILABLE" if exc.retryable else "FETCH_FAILED"
            await _send(send, 503 if exc.retryable else 422, _failure(identity, code, exc.retryable))
            return
        except Exception:
            await _send(send, 500, _failure(identity, "AI_UNAVAILABLE"))
            return

        await _send(send, 200, {
            **identity, "resultSchemaVersion": "3", "modelVersion": "scenecrew-extraction-rules-v1",
            "result": result,
        })

    return app

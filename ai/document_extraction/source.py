"""Read the original through the internal document service (Jira #180)."""

import asyncio
import hashlib
import hmac
import math
import re
from urllib.parse import urlsplit
from uuid import UUID

import httpx

MAX_FILE_BYTES = 20_971_520
DOCUMENT_PATH = "/internal/intake-documents/"


class DocumentError(Exception):
    def __init__(self, code: str, *, retryable: bool = False):
        super().__init__(code)
        self.code = code
        self.retryable = retryable


def check_original(content: bytes, sha256: str) -> None:
    if len(content) > MAX_FILE_BYTES:
        raise DocumentError("TOO_LARGE")
    if not re.fullmatch(r"[a-fA-F0-9]{64}", sha256) or not hmac.compare_digest(
        hashlib.sha256(content).hexdigest(), sha256.lower()
    ):
        raise DocumentError("FETCH_FAILED")


def document_origin(base_url: str) -> str:
    base = urlsplit(base_url)
    if (
        base.scheme not in {"https", "http"}
        or not base.hostname
        or base.username is not None
        or base.path not in {"", "/"}
        or base.query
        or base.fragment
        or any(c in base_url for c in "\\?#%")
        or any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in base_url)
    ):
        raise ValueError("Expected a configured document service origin")
    return base_url.rstrip("/")


async def download_original(
    *,
    base_url: str,
    document_id: UUID,
    job_id: UUID,
    attempt: int,
    service_token: str,
    sha256: str,
    timeout: float,
    transport: httpx.AsyncBaseTransport | None = None,
) -> bytes:
    """base_url and token come from server configuration, never from the document."""
    origin = document_origin(base_url)
    if not isinstance(document_id, UUID) or not isinstance(job_id, UUID):
        raise ValueError("Expected canonical UUID identifiers")
    if (
        type(attempt) is not int or attempt < 1
        or not re.fullmatch(r"[!-~]+", service_token)
        or not math.isfinite(timeout) or timeout <= 0
    ):
        raise ValueError("Expected positive attempt and timeout and a configured service token")

    url = origin + DOCUMENT_PATH + str(document_id)
    headers = {
        "Authorization": f"Bearer {service_token}",
        "X-SceneCrew-Job-Id": str(job_id),
        "X-SceneCrew-Job-Attempt": str(attempt),
        "Accept-Encoding": "identity",
    }
    data = bytearray()
    try:
        async with asyncio.timeout(timeout):
            async with httpx.AsyncClient(
                transport=transport, timeout=timeout, follow_redirects=False, trust_env=False
            ) as client:
                async with client.stream("GET", url, headers=headers) as response:
                    if response.status_code != 200:
                        raise DocumentError("FETCH_FAILED", retryable=500 <= response.status_code < 600)
                    if response.headers.get("content-encoding", "identity").lower() != "identity":
                        raise DocumentError("FETCH_FAILED")
                    async for chunk in response.aiter_bytes(chunk_size=65_536):
                        if len(data) + len(chunk) > MAX_FILE_BYTES:
                            raise DocumentError("TOO_LARGE")
                        data.extend(chunk)
    except (httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError, TimeoutError):
        raise DocumentError("FETCH_FAILED", retryable=True) from None

    content = bytes(data)
    check_original(content, sha256)
    return content

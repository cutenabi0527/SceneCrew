"""Read verified originals and identify floor-plan pages."""

from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from io import BytesIO
import re
from uuid import UUID

from .rendering import render_with_poppler
from .source import DocumentError, check_original

MAX_DOCUMENTS = 3
MAX_TOTAL_BYTES = 31_457_280
MAX_PDF_PAGES = 30
MAX_TEXT_CHARS = 60_000
PAGE_DPI = 150
PDF = "application/pdf"
TEXT_TYPES = {"text/plain", "text/markdown"}
FLOOR_PLAN_HEADING = re.compile(
    r"^\s*(?:#{1,6}\s*)?(?:\d+[.)쪽]\s*(?:[·.:-]\s*)?)?"
    r"(?:(?:공연장\s*)?평면도|floor\s+plan)(?:\s*\(.*\))?\s*$",
    re.IGNORECASE,
)

# Adapters take original bytes and a one-based page number. Rendering returns PNG bytes.
RenderPage = Callable[[bytes, int, int], bytes]
ReadOcr = Callable[[bytes], str]


@dataclass(frozen=True)
class Original:
    document_id: UUID
    mime: str
    sha256: str
    content: bytes = field(repr=False)


@dataclass(frozen=True)
class FloorPlanPage:
    document_id: UUID
    page_number: int
    source_sha256: str
    png: bytes = field(repr=False)


@dataclass(frozen=True)
class FloorPlanReference:
    document_id: UUID
    page_number: int
    source_sha256: str


@dataclass(frozen=True)
class ReadDocuments:
    document_pages: list[list[str]] = field(repr=False)
    unreadable: list[dict[str, str]]
    floor_plans: list[FloorPlanPage]
    floor_plan_pages: tuple[FloorPlanReference, ...]
    # 쪽 단위 원문 (문서 id, 1부터 세는 쪽 번호, 글). 빈 쪽은 빠지지만 번호는 원래 쪽 그대로다(일정표 출처용).
    pages: tuple[tuple[UUID, int, str], ...] = field(default=(), repr=False)


def _text(value: str) -> str:
    return value.replace("\r\n", "\n").replace("\r", "\n").strip()


def read_documents(
    documents: Iterable[Original],
    *,
    render_page: RenderPage | None = None,
    ocr: ReadOcr | None = None,
) -> ReadDocuments:
    """Adapters report expected page failures as DocumentError; other failures propagate."""
    documents = tuple(documents)
    if len(documents) > MAX_DOCUMENTS or sum(len(doc.content) for doc in documents) > MAX_TOTAL_BYTES:
        raise DocumentError("BUNDLE_LIMIT")
    if len({doc.document_id for doc in documents}) != len(documents):
        raise ValueError("Duplicate document ID")

    document_pages = []
    numbered = []
    floor_plans = []
    references: dict[tuple[UUID, int], FloorPlanReference] = {}
    unreadable = []
    page_count = 0
    remaining = MAX_TEXT_CHARS
    for doc in documents:
        error = None
        pages = []
        try:
            check_original(doc.content, doc.sha256)
            if doc.mime in TEXT_TYPES:
                try:
                    value = _text(doc.content.decode("utf-8-sig"))
                except UnicodeDecodeError:
                    raise DocumentError("INVALID_ENCODING") from None
                if not value:
                    raise DocumentError("EMPTY")
                source_pages = iter([(value, None, None, None)])
            elif doc.mime == PDF:
                reader, count = _read_pdf(doc.content)
                page_count += count
                if page_count > MAX_PDF_PAGES:
                    raise DocumentError("PAGE_LIMIT")
                source_pages = _pdf_pages(doc, reader, render_page, ocr)
            else:
                raise DocumentError("UNSUPPORTED_FORMAT")

            for number, (value, image, page_error, reference) in enumerate(source_pages, start=1):
                if len(value) > remaining:
                    # A truncated line can turn a partial value into an apparently valid field.
                    error = error or "TEXT_LIMIT"
                    remaining = 0
                elif value:
                    pages.append(value)
                    numbered.append((doc.document_id, number, value))
                    remaining -= len(value)
                if image is not None:
                    floor_plans.append(image)
                if reference is not None:
                    references.setdefault((reference.document_id, reference.page_number), reference)
                error = error or page_error
        except DocumentError as exc:
            error = exc.code
        if error:
            unreadable.append({"documentId": str(doc.document_id), "code": error})
        if pages:
            document_pages.append(pages)
    return ReadDocuments(document_pages, unreadable, floor_plans, tuple(references.values()), tuple(numbered))


def _read_pdf(content):
    if not content.startswith(b"%PDF-"):
        raise DocumentError("INVALID_PDF")
    from pypdf import PdfReader
    from pypdf.errors import PyPdfError

    try:
        reader = PdfReader(BytesIO(content))
        if reader.is_encrypted:
            raise DocumentError("ENCRYPTED_PDF")
        count = len(reader.pages)
    except (PyPdfError, ValueError, RecursionError):
        raise DocumentError("INVALID_PDF") from None
    if not count:
        raise DocumentError("EMPTY")
    return reader, count


def render_pdf_page(
    original: Original, page_number: int, *, render_page: RenderPage = render_with_poppler,
) -> bytes:
    """Render a verified PDF's one-based page at 150 dpi; the caller authorizes the download."""
    if original.mime != PDF:
        raise DocumentError("UNSUPPORTED_FORMAT")
    check_original(original.content, original.sha256)
    reader, count = _read_pdf(original.content)
    if count > MAX_PDF_PAGES:
        raise DocumentError("PAGE_LIMIT")
    if type(page_number) is not int or not 1 <= page_number <= count:
        raise DocumentError("INVALID_PAGE")
    from pypdf.errors import PyPdfError

    try:
        return _render(render_page, original, page_number, reader.pages[page_number - 1])
    except (PyPdfError, ValueError, RecursionError):
        raise DocumentError("INVALID_PDF") from None


def _pdf_pages(doc, reader, render_page, ocr):
    from pypdf.errors import PyPdfError

    for number, page in enumerate(reader.pages, start=1):
        try:
            value = _text(page.extract_text(extraction_mode="layout") or "") if "/Contents" in page else ""
        except (PyPdfError, ValueError, RecursionError):
            value = ""

        png = None
        error = None
        if not value:
            try:
                if render_page is None or ocr is None:
                    raise DocumentError("OCR_UNAVAILABLE")
                png = _render(render_page, doc, number, page)
                value = _text(ocr(png))
                if not value:
                    raise DocumentError("UNREADABLE_PAGE")
            except DocumentError as exc:
                error = exc.code

        # A plain zone table or an inline reference to a map is insufficient.
        heading = next((line for line in value.splitlines() if line.strip()), "")
        image = None
        reference = None
        try:
            if FLOOR_PLAN_HEADING.fullmatch(heading) and (png is not None or len(page.images)):
                reference = FloorPlanReference(doc.document_id, number, doc.sha256.lower())
                if png is None:
                    if render_page is None:
                        raise DocumentError("RENDER_UNAVAILABLE")
                    png = _render(render_page, doc, number, page)
                image = FloorPlanPage(doc.document_id, number, doc.sha256.lower(), png)
        except (PyPdfError, ValueError, RecursionError):
            error = error or "UNREADABLE_PAGE"
        except DocumentError as exc:
            error = error or exc.code
        yield value, image, error, reference


def _render(render_page, doc, number, page):
    # Bound the raster before invoking an adapter on an untrusted page.
    if max(abs(page.mediabox.width), abs(page.mediabox.height)) * page.user_unit * PAGE_DPI / 72 > 4096:
        raise DocumentError("RASTER_LIMIT")
    png = render_page(doc.content, number, PAGE_DPI)
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise DocumentError("RENDER_FAILED")
    return png

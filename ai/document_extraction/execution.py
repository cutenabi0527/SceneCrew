"""Local orchestration. Public result3 and internal image artifacts stay separate."""

from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import NotRequired, TypedDict

from .conditions import appendix_keys, build_result, structured
from .documents import FloorPlanPage, FloorPlanReference, Original, ReadOcr, RenderPage, read_documents


class ExtractionResult(TypedDict):
    fields: dict[str, str]
    missingRequired: list[str]
    conflictingValues: list[dict]
    unreadableFiles: list[dict[str, str]]
    resultStatus: str
    # 구조 결과(V9-D66). 문서에 해당 표가 없으면 키가 없다.
    programs: NotRequired[list[dict]]
    scheduleItems: NotRequired[list[dict]]
    workGroups: NotRequired[list[dict]]


@dataclass(frozen=True)
class Extraction:
    result: ExtractionResult = field(repr=False)
    floor_plans: list[FloorPlanPage]
    floor_plan_pages: tuple[FloorPlanReference, ...] = ()
    # 계획서 부록 "키 값" 표에서 나온 조건 키(#401). 결과3의 기본 키 목록 밖이어도 넘긴다.
    condition_keys: frozenset[str] = frozenset()

    def floor_plan_metadata(self) -> list[dict[str, str | int]]:
        """Project the ordered PDF candidates to result3 metadata without image data."""
        return [
            {"documentId": str(page.document_id), "pageNumber": page.page_number}
            for page in self.floor_plan_pages
        ]


def extract(
    documents: Iterable[Original],
    *,
    render_page: RenderPage | None = None,
    ocr: ReadOcr | None = None,
) -> Extraction:
    read = read_documents(documents, render_page=render_page, ocr=ocr)
    result = build_result(read.document_pages, read.unreadable)
    # 공연 순서 시각·일정표 행·직무 그룹(V9-D66). 날짜가 하나로 정해졌을 때만 시각을 붙인다.
    result.update(structured(read.pages, result["fields"].get("date")))
    return Extraction(result, read.floor_plans, read.floor_plan_pages, appendix_keys(read.document_pages))

"""행사 종료 요약(SUMMARIZE_EVENT) 계약. 요청4 / 결과2. S15P31A202-269.

BE(080)가 종료된 행사의 사실을 공통 요청4 envelope로 보내면 GMS를 한 번 불러 요약문을 만들어 돌려준다.
- 성공: {식별 필드, resultSchemaVersion "2", modelVersion, result{text, format}}
- 실패: {식별 필드, error{code, message, retryable, details}}

요약문은 응답으로만 보낸다. 이 앱은 요약문을 파일·로그 어디에도 남기지 않는다(POLICIES P07, V9 D06).
입력에는 개인을 알아볼 수 있는 정보(이름·계정·팀원 id)·채팅·음성·보고 원문을 넣지 않는다(BE가 빼고 보낸다).
"""
from datetime import datetime, timezone
from typing import Annotated, Literal

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, StringConstraints, ValidationError

KIND = "SUMMARIZE_EVENT"
REQUEST_SCHEMA_VERSION = "4"
RESULT_SCHEMA_VERSION = "2"
MAX_TEXT = 4000                 # 요약문 상한(코드포인트)
MAX_WORK_GROUPS = 31
MAX_PROGRAMS = 20
MAX_ISSUES = 100
MAX_CHANGES = 50
ERROR_RETRYABLE = {"INVALID_REQUEST": False, "AI_TIMEOUT": False, "AI_UNAVAILABLE": True}
IDENTITY_FIELDS = ("jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision")

Uuid = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
Name = Annotated[str, StringConstraints(min_length=1, max_length=100)]
Text = Annotated[str, StringConstraints(max_length=1000)]
Count = Annotated[int, Field(ge=0)]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EventFacts(Strict):
    name: Name
    date: str | None = None
    openAt: AwareDatetime | None = None
    closeAt: AwareDatetime | None = None
    endedAt: AwareDatetime | None = None
    locationName: Text | None = None
    totalCapacity: Count | None = None
    expectedVisitors: Count | None = None


class TaskCounts(Strict):
    total: Count
    done: Count
    inProgress: Count
    waiting: Count
    canceled: Count


class WorkGroupFacts(Strict):
    name: Name
    tasks: TaskCounts


class ProgramFacts(Strict):
    name: Name
    zone: Name | None = None
    startAt: AwareDatetime
    endAt: AwareDatetime


class IssueFacts(Strict):
    title: Text
    status: Literal["RECEIVED", "RESPONDING", "RESOLUTION_REPORTED", "CLOSED"]
    zone: Name | None = None
    situation: str | None = None      # 총괄이 확인한 상황 유형(예: EQUIPMENT_ANOMALY), 없으면 null
    reports: Count
    createdAt: AwareDatetime
    closedAt: AwareDatetime | None = None


class ChangeFacts(Strict):
    mode: Literal["MANUAL", "AI"]
    rationale: Text
    operations: Count
    appliedAt: AwareDatetime


class SummaryPayload(Strict):
    event: EventFacts
    workGroups: list[WorkGroupFacts] = Field(max_length=MAX_WORK_GROUPS)
    programs: list[ProgramFacts] = Field(max_length=MAX_PROGRAMS)
    issues: list[IssueFacts] = Field(max_length=MAX_ISSUES)
    changes: list[ChangeFacts] = Field(max_length=MAX_CHANGES)


class SummaryRequest(Strict):
    jobId: Uuid
    attempt: int = Field(ge=1)
    requestSchemaVersion: Literal["4"]
    kind: Literal["SUMMARIZE_EVENT"]
    inputHash: Sha256
    contextRevision: int = Field(ge=0)
    deadlineAt: AwareDatetime
    payload: SummaryPayload


class ContractViolation(Exception):
    def __init__(self, issues):
        super().__init__("; ".join(issues))
        self.issues = issues


def parse_request(raw):
    try:
        return SummaryRequest.model_validate(raw)
    except ValidationError as e:
        raise ContractViolation([".".join(str(x) for x in err["loc"]) for err in e.errors()][:20]) from None


def identity(req):
    return {"jobId": req.jobId, "attempt": req.attempt, "requestSchemaVersion": REQUEST_SCHEMA_VERSION,
            "inputHash": req.inputHash, "contextRevision": req.contextRevision}


def success(req, text, fmt, model_version):
    return {**identity(req), "resultSchemaVersion": RESULT_SCHEMA_VERSION, "modelVersion": model_version,
            "result": {"text": text, "format": fmt}}


def failure(req, code, message, details=None):
    ids = identity(req) if req is not None else {f: None for f in IDENTITY_FIELDS}
    return {**ids, "error": {"code": code, "message": message, "retryable": ERROR_RETRYABLE[code],
                             "details": details or {}}}


def deadline_passed(req, now=None):
    return (now or datetime.now(timezone.utc)) >= req.deadlineAt

"""AI 대응안(GENERATE_CHANGE) 공통 실행 계약과 결과 분기. 요청4 / 결과6.

BE(#343)가 보고·현재 계획 스냅샷을 공통 요청4 envelope로 보내면 Multi-agent 흐름(graph.py)이
변경 연산 후보를 만들어 세 갈래 중 하나로 반환한다.
- 성공(READY): 성공 envelope, result.status=READY, 연산 1개 이상
- 추가 질문(NEEDS_INFO): 성공 envelope, result.status=NEEDS_INFO, 연산 없이 questions만
- 실패: 실패 envelope, error{code,message,retryable,details}

결과6은 결과5(operations/rationale/alternatives/questions/coordination)에
evidenceRefs/simulationRefs/situation을 더한다(docs/planning/ai/07-contracts.md "변경 결과6").
장비 예약 연산 3종은 이번 범위에서 만들지 않는다. 연산 형식은 BE change/service/Operations.java와 같다.
AI는 제안만 한다. 실제 적용은 총괄의 변경안 승인이다.
"""
from datetime import datetime, timezone
from typing import Annotated, Literal, Union

from pydantic import (AfterValidator, AwareDatetime, BaseModel, ConfigDict, Field, StringConstraints,
                      ValidationError, model_validator)

KIND = "GENERATE_CHANGE"
REQUEST_SCHEMA_VERSION = "4"
RESULT_SCHEMA_VERSION = "6"

MAX_ZONES = 10
MAX_PROGRAMS = 20
MAX_WORK_GROUPS = 31
MAX_MEMBERS = 31
MAX_TASKS = 200
MAX_REPORTS = 20
MAX_OPERATIONS = 50   # BE Operations.MAX_OPERATIONS

ERROR_RETRYABLE = {
    "INVALID_REQUEST": False,
    "READ_CONTEXT_CHANGED": False,
    "AI_TIMEOUT": False,
    "AI_REVIEW_FAILED": False,
    "AI_UNAVAILABLE": True,
}
REVIEW_FAILED_REASONS = ("REVIEW_REJECTED", "BUDGET_EXHAUSTED", "INVALID_ARTIFACT")
IDENTITY_FIELDS = ("jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision")
SITUATION_TYPES = ("SCHEDULE_DELAY", "STAFF_GAP", "TASK_CONFLICT", "EQUIPMENT_ANOMALY", "PERFORMANCE_ORDER_CHANGE",
                   "AUDIENCE_SAFETY")


def _not_blank(v):
    if not v.strip():
        raise ValueError("공백만 있는 값은 허용하지 않습니다.")
    return v


Uuid = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")]
TempId = Annotated[str, StringConstraints(pattern=r"^tmp-[A-Za-z0-9_-]{1,40}$")]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
Key = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,40}$")]
Name = Annotated[str, StringConstraints(min_length=1, max_length=30), AfterValidator(_not_blank)]
Title = Annotated[str, StringConstraints(min_length=1, max_length=100), AfterValidator(_not_blank)]
Text = Annotated[str, StringConstraints(max_length=1000)]
Body = Annotated[str, StringConstraints(min_length=1, max_length=1000), AfterValidator(_not_blank)]
ReportBody = Annotated[str, StringConstraints(min_length=1, max_length=2000), AfterValidator(_not_blank)]
Short = Annotated[str, StringConstraints(min_length=1, max_length=200), AfterValidator(_not_blank)]
Step = Annotated[str, StringConstraints(min_length=1, max_length=120), AfterValidator(_not_blank)]
CheckText = Annotated[str, StringConstraints(min_length=1, max_length=80), AfterValidator(_not_blank)]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


# ---- 요청 (BE #343이 보내는 현재 계획 스냅샷) ----
# 개인 정보는 보내지 않는다. 팀원은 id·팀원 번호(crewNo)·그룹·가용 여부만 받는다.

class ReportInput(Strict):
    id: Uuid
    body: ReportBody
    reportedAt: AwareDatetime


class IssueInput(Strict):
    id: Uuid
    zoneId: Uuid | None = None
    reports: list[ReportInput] = Field(min_length=1, max_length=MAX_REPORTS)


class EventInput(Strict):
    openAt: AwareDatetime
    closeAt: AwareDatetime
    teardownEndAt: AwareDatetime


class ZoneInput(Strict):
    id: Uuid
    name: Name


class ProgramInput(Strict):
    id: Uuid
    zoneId: Uuid | None = None
    name: Name
    startAt: AwareDatetime
    endAt: AwareDatetime


class WorkGroupInput(Strict):
    id: Uuid
    name: Name


class MemberInput(Strict):
    id: Uuid
    crewNo: int = Field(ge=1)
    workGroupId: Uuid | None = None
    available: bool
    isReserve: bool


class TaskInput(Strict):
    id: Uuid
    title: Title
    workGroupId: Uuid
    zoneId: Uuid
    programId: Uuid | None = None
    assigneeId: Uuid | None = None
    startAt: AwareDatetime
    endAt: AwareDatetime
    status: Literal["TODO", "IN_PROGRESS", "DONE", "CANCELLED"]


class ChangePayload(Strict):
    issue: IssueInput
    instruction: Text = ""            # 총괄이 확인한 지시. 비어 있을 수 있다
    situation: dict | None = None     # 보고 분류(#297) 결과(V9OperationalSituation). 그대로 결과에 싣는다
    now: AwareDatetime                # BE가 정한 기준 시각. 제안은 이 시각 뒤로만 한다
    event: EventInput
    zones: list[ZoneInput] = Field(max_length=MAX_ZONES)
    programs: list[ProgramInput] = Field(max_length=MAX_PROGRAMS)
    workGroups: list[WorkGroupInput] = Field(max_length=MAX_WORK_GROUPS)
    members: list[MemberInput] = Field(max_length=MAX_MEMBERS)
    tasks: list[TaskInput] = Field(max_length=MAX_TASKS)


class ChangeRequest(Strict):
    jobId: Uuid
    attempt: int = Field(ge=1)
    requestSchemaVersion: Literal["4"]
    kind: Literal["GENERATE_CHANGE"]
    inputHash: Sha256
    contextRevision: int = Field(ge=0)
    deadlineAt: AwareDatetime
    payload: ChangePayload


# ---- 연산 (BE Operations.java와 같은 형식) ----

class ChecklistItem(Strict):
    key: Key
    text: CheckText
    required: bool


class AddTask(Strict):
    workGroupId: Uuid
    bundleKey: Short
    zoneId: Uuid
    programId: Uuid | None
    title: Title
    startAt: AwareDatetime
    endAt: AwareDatetime
    steps: list[Step] = Field(min_length=1, max_length=6)
    checklist: list[ChecklistItem] = Field(max_length=6)
    tempId: TempId
    assigneeId: Uuid | None


class UpdateTask(Strict):
    taskId: Uuid
    zoneId: Uuid | None = None
    programId: Uuid | None = None
    title: Title | None = None
    startAt: AwareDatetime | None = None
    endAt: AwareDatetime | None = None


class AssignTask(Strict):
    taskId: Uuid
    fromMemberId: Uuid | None
    toMemberId: Uuid | None


class CancelTask(Strict):
    taskId: Uuid
    reason: Short


class UpdateProgram(Strict):
    programId: Uuid
    startAt: AwareDatetime | None = None
    endAt: AwareDatetime | None = None


class UpdateInstructions(Strict):
    scope: Literal["EVENT", "WORK_GROUP"]
    workGroupId: Uuid | None
    body: Body


PAYLOADS = {"ADD_TASK": AddTask, "UPDATE_TASK": UpdateTask, "ASSIGN_TASK": AssignTask, "CANCEL_TASK": CancelTask,
            "UPDATE_PROGRAM": UpdateProgram, "UPDATE_INSTRUCTIONS": UpdateInstructions}


class Operation(Strict):
    type: Literal["ADD_TASK", "UPDATE_TASK", "ASSIGN_TASK", "CANCEL_TASK", "UPDATE_PROGRAM", "UPDATE_INSTRUCTIONS"]
    payload: Union[AddTask, UpdateTask, AssignTask, CancelTask, UpdateProgram, UpdateInstructions]

    @model_validator(mode="before")
    @classmethod
    def _payload_by_type(cls, data):
        if isinstance(data, dict) and data.get("type") in PAYLOADS and isinstance(data.get("payload"), dict):
            data = {**data, "payload": PAYLOADS[data["type"]].model_validate(data["payload"])}
        return data

    @model_validator(mode="after")
    def _shape(self):
        if not isinstance(self.payload, PAYLOADS[self.type]):
            raise ValueError(f"{self.type}의 payload 형식이 아닙니다.")
        p = self.payload
        if isinstance(p, (UpdateTask, UpdateProgram)) and len(p.model_dump(exclude_none=True)) < 2:
            raise ValueError(f"{self.type}은 바꿀 칸이 하나 이상 있어야 합니다.")
        if isinstance(p, UpdateInstructions) and (p.scope == "EVENT") != (p.workGroupId is None):
            raise ValueError("UPDATE_INSTRUCTIONS의 workGroupId는 WORK_GROUP일 때만 있습니다.")
        return self

    def dump(self):
        """BE에 보낼 모양. UPDATE_* 연산은 바꿀 칸만 남긴다(BE가 null 칸을 '지움'으로 읽지 않게)."""
        exclude_none = isinstance(self.payload, (UpdateTask, UpdateProgram))
        payload = self.payload.model_dump(exclude_none=exclude_none)
        for key in ("startAt", "endAt"):   # 시각은 BE처럼 UTC(Z)로 보낸다
            if payload.get(key) is not None:
                payload[key] = utc(payload[key])
        return {"type": self.type, "payload": _json(payload)}


def utc(at: datetime):
    return at.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _json(value):
    """model_dump(python) 결과를 JSON 값으로 바꾼다(체크 항목 같은 중첩 모델 포함)."""
    if isinstance(value, dict):
        return {k: _json(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_json(v) for v in value]
    if isinstance(value, datetime):
        return utc(value)
    return value


# ---- 결과6 ----

Role = Literal["SUPERVISOR", "SCHEDULE", "STAFFING", "GUIDE", "REVIEW"]
EvidenceKind = Literal["REPORT", "TASK", "MEMBER", "PROGRAM", "ZONE"]


class EvidenceRef(Strict):
    """V9EvidenceRef. 검토에 쓴 사실의 참조. 스냅샷에서 읽었으므로 revision·해시·관측 시각은 비운다."""
    kind: EvidenceKind
    id: Uuid
    revision: str | None = None
    contentHash: Sha256 | None = None
    observedAt: AwareDatetime | None = None
    preconditions: list[Short] = Field(default_factory=list)


class Question(Strict):
    key: Key
    field: Short
    question: Body


class Alternative(Strict):
    summary: Short


class AgentTaskNode(Strict):
    taskId: Key
    parentTaskId: Key | None = None
    role: Role
    status: Literal["SUCCEEDED", "NEEDS_INFO", "FAILED"]


class Redelegation(Strict):
    taskId: Key
    reason: Short


class ResolvedConflict(Strict):
    refs: list[str] = Field(min_length=1)
    resolution: Short


class Contribution(Strict):
    role: Role
    summary: Short


class Coordination(Strict):
    """작업 그래프·재위임 이유·충돌 해결·역할별 기여만 둔다. 원시 프롬프트·모델 사고·보고 원문은 넣지 않는다."""
    graph: list[AgentTaskNode] = Field(default_factory=list)
    redelegations: list[Redelegation] = Field(default_factory=list)
    resolvedConflicts: list[ResolvedConflict] = Field(default_factory=list)
    contributions: list[Contribution] = Field(default_factory=list)


class ChangeResult(Strict):
    status: Literal["READY", "NEEDS_INFO"]
    operations: list[Operation] = Field(max_length=MAX_OPERATIONS)
    rationale: Text
    alternatives: list[Alternative] = Field(default_factory=list, max_length=3)
    questions: list[Question] = Field(default_factory=list)
    coordination: Coordination
    evidenceRefs: list[EvidenceRef] = Field(default_factory=list)
    simulationRefs: list[dict] = Field(default_factory=list, max_length=0)   # 이번 범위는 복구 계산을 쓰지 않는다
    situation: dict | None = None

    @model_validator(mode="after")
    def _branch(self):
        if self.status == "READY" and (not self.operations or not self.rationale.strip()):
            raise ValueError("READY는 연산 1개 이상과 이유가 있어야 합니다.")
        if self.status == "NEEDS_INFO" and (self.operations or not self.questions):
            raise ValueError("NEEDS_INFO는 연산 없이 질문만 있어야 합니다.")
        return self


class Identity(Strict):
    jobId: Uuid
    attempt: int = Field(ge=1)
    requestSchemaVersion: Literal["4"]
    inputHash: Sha256
    contextRevision: int = Field(ge=0)


class ChangeSuccess(Identity):
    resultSchemaVersion: Literal["6"]
    modelVersion: Short
    result: ChangeResult


class ErrorBody(Strict):
    code: Literal["INVALID_REQUEST", "READ_CONTEXT_CHANGED", "AI_TIMEOUT", "AI_REVIEW_FAILED", "AI_UNAVAILABLE"]
    message: Short
    retryable: bool
    details: dict = Field(default_factory=dict)

    @model_validator(mode="after")
    def _retryable(self):
        if self.retryable != ERROR_RETRYABLE[self.code]:
            raise ValueError(f"{self.code}의 retryable은 {ERROR_RETRYABLE[self.code]}입니다.")
        if self.code == "AI_REVIEW_FAILED" and self.details.get("reason") not in REVIEW_FAILED_REASONS:
            raise ValueError(f"AI_REVIEW_FAILED는 details.reason이 {REVIEW_FAILED_REASONS} 중 하나여야 합니다.")
        return self


class ChangeFailure(Strict):
    jobId: Uuid | None
    attempt: int | None = Field(ge=1)
    requestSchemaVersion: Literal["4"] | None
    inputHash: Sha256 | None
    contextRevision: int | None = Field(ge=0)
    error: ErrorBody

    @model_validator(mode="after")
    def _identity(self):
        if self.error.code != "INVALID_REQUEST" and any(getattr(self, f) is None for f in IDENTITY_FIELDS):
            raise ValueError("접수 뒤 실패는 식별 필드를 모두 반환합니다.")
        return self


# ---- 요청 해석과 응답 만들기 ----

class ContractViolation(Exception):
    def __init__(self, issues, request=None):
        super().__init__("; ".join(f"{i['path']}: {i['message']}" for i in issues))
        self.issues = issues
        self.request = request


def schema_issues(e: ValidationError, prefix):
    return [{"path": ".".join([prefix] + [str(x) for x in err["loc"]]), "code": "SCHEMA", "message": err["msg"]}
            for err in e.errors()]


def request_issues(req: ChangeRequest):
    """스키마로 못 보는 요청 검사: 참조가 스냅샷 안에 있는지, 시각 순서."""
    p = req.payload
    out = []
    zones = {z.id for z in p.zones}
    groups = {g.id for g in p.workGroups}
    programs = {x.id for x in p.programs}
    members = {m.id for m in p.members}
    for name, items in (("zones", p.zones), ("programs", p.programs), ("workGroups", p.workGroups),
                        ("members", p.members), ("tasks", p.tasks)):
        ids = [x.id for x in items]
        if len(ids) != len(set(ids)):
            out.append({"path": f"payload.{name}", "code": "DUPLICATE", "message": "같은 id가 두 번 있습니다."})
    if p.issue.zoneId and p.issue.zoneId not in zones:
        out.append({"path": "payload.issue.zoneId", "code": "REFERENCE", "message": "스냅샷에 없는 구역입니다."})
    for i, x in enumerate(p.programs):
        if x.zoneId and x.zoneId not in zones:
            out.append({"path": f"payload.programs[{i}].zoneId", "code": "REFERENCE", "message": "없는 구역입니다."})
        if x.startAt >= x.endAt:
            out.append({"path": f"payload.programs[{i}]", "code": "TIME", "message": "시작이 끝보다 앞서야 합니다."})
    for i, m in enumerate(p.members):
        if m.workGroupId and m.workGroupId not in groups:
            out.append({"path": f"payload.members[{i}].workGroupId", "code": "REFERENCE", "message": "없는 그룹입니다."})
    for i, t in enumerate(p.tasks):
        for field, ok in (("workGroupId", t.workGroupId in groups), ("zoneId", t.zoneId in zones),
                          ("programId", t.programId is None or t.programId in programs),
                          ("assigneeId", t.assigneeId is None or t.assigneeId in members)):
            if not ok:
                out.append({"path": f"payload.tasks[{i}].{field}", "code": "REFERENCE", "message": "스냅샷에 없는 참조입니다."})
        if t.startAt >= t.endAt:
            out.append({"path": f"payload.tasks[{i}]", "code": "TIME", "message": "시작이 끝보다 앞서야 합니다."})
    return out


def parse_request(raw):
    try:
        req = ChangeRequest.model_validate(raw)
    except ValidationError as e:
        raise ContractViolation(schema_issues(e, "request")) from None
    issues = request_issues(req)
    if issues:
        raise ContractViolation(issues, req)
    return req


def identity(req):
    return {"jobId": req.jobId, "attempt": req.attempt, "requestSchemaVersion": REQUEST_SCHEMA_VERSION,
            "inputHash": req.inputHash, "contextRevision": req.contextRevision}


def success(req, result: dict, model_version):
    """결과를 계약으로 한 번 더 검사한 뒤 envelope로 만든다. 형식이 틀리면 ValidationError가 그대로 올라간다."""
    model = ChangeSuccess.model_validate({**identity(req), "resultSchemaVersion": RESULT_SCHEMA_VERSION,
                                         "modelVersion": model_version, "result": result})
    out = model.model_dump(mode="json")
    out["result"]["operations"] = [op.dump() for op in model.result.operations]
    return out


def failure(req, code, message, details=None):
    ident = identity(req) if req is not None else {f: None for f in IDENTITY_FIELDS}
    body = ChangeFailure.model_validate({**ident, "error": {
        "code": code, "message": message, "retryable": ERROR_RETRYABLE[code], "details": details or {}}})
    return body.model_dump(mode="json")


def deadline_passed(req, now: datetime):
    return now >= req.deadlineAt

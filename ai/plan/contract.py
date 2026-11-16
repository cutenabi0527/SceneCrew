"""최초 계획(GENERATE_PLAN) 공통 실행 계약과 결과 분기.

공통 요청4 envelope로 받은 현재 DRAFT 입력을 검증하고, 결과는 세 갈래 중 하나로 반환한다.

- 성공(READY): 성공 envelope, result.status=READY, PlanArtifact 전체
- 추가 질문(NEEDS_INFO): 성공 envelope, result.status=NEEDS_INFO, questions만 있고 계획 배열은 비움
- 실패: 실패 envelope, error{code,message,retryable,details}

기준: docs/planning/ai/07-contracts.md, 02-document-extraction.md, 05-failure-manual.md, be/06-ai-contract.md
질문 분기의 성공 envelope와 result.status는 V7-D07에서 확정했다. BE/FE 실행 수용은 별도다.

사용 (ai/plan에서):
    uv run python contract.py check examples/request.json examples/success-ready.json
    uv run python contract.py schema schema
"""

import json
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Annotated, Literal

from pydantic import (AfterValidator, AwareDatetime, BaseModel, ConfigDict, Field, StringConstraints,
                      ValidationError, model_validator)

KIND = "GENERATE_PLAN"
REQUEST_SCHEMA_VERSION = "4"
RESULT_SCHEMA_VERSION = "5"
# 결과6 = 결과5 + venueDraft(V9-D45·V9-D54, S15P31A202-298). 요청 payload에 floorPlan 키가 있을 때만 쓴다.
# 키가 없는 요청(현재 BE)에는 결과5를 그대로 돌려줘 BE의 엄격한 결과5 검사를 깨지 않는다.
VENUE_RESULT_SCHEMA_VERSION = "6"

# 하루 행사 날짜 검증은 요청에 쓰인 오프셋과 무관하게 현재 서비스 시간대를 따른다(V7-D11).
SERVICE_TIMEZONE = timezone(timedelta(hours=9), name="Asia/Seoul")

# P01 상한
MAX_ZONES = 10
MAX_SCHEDULE_ITEMS = 50   # 요청4 scheduleItems 상한(V9-D66)
MAX_PROGRAMS = 20
MAX_WORK_GROUPS = 31
MAX_TASKS = 200
MAX_MEMBERS = 31

# 실패 코드별 재시도 가능 여부. AI_UNAVAILABLE만 남은 마감 안에서 BE 전달 재시도(총3회·5/30초) 대상이다.
ERROR_RETRYABLE = {
    "INVALID_REQUEST": False,
    "READ_CONTEXT_CHANGED": False,
    "AI_TIMEOUT": False,
    "AI_REVIEW_FAILED": False,
    "AI_UNAVAILABLE": True,
}
REVIEW_FAILED_REASONS = ("REVIEW_REJECTED", "BUDGET_EXHAUSTED", "INVALID_ARTIFACT")
IDENTITY_FIELDS = ("jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision")


def _not_blank(v):
    if not v.strip():
        raise ValueError("공백만 있는 값은 허용하지 않습니다.")
    return v


Uuid = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")]
TempId = Annotated[str, StringConstraints(pattern=r"^tmp-[A-Za-z0-9_-]{1,40}$")]
Ref = Uuid | TempId
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
Key = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,40}$")]
Name = Annotated[str, StringConstraints(min_length=1, max_length=20), AfterValidator(_not_blank)]
ZoneName = Annotated[str, StringConstraints(min_length=1, max_length=30), AfterValidator(_not_blank)]
Title = Annotated[str, StringConstraints(min_length=1, max_length=100), AfterValidator(_not_blank)]
Text = Annotated[str, StringConstraints(max_length=1000)]
Body = Annotated[str, StringConstraints(min_length=1, max_length=1000), AfterValidator(_not_blank)]
Short = Annotated[str, StringConstraints(min_length=1, max_length=200), AfterValidator(_not_blank)]
Step = Annotated[str, StringConstraints(min_length=1, max_length=120), AfterValidator(_not_blank)]
CheckText = Annotated[str, StringConstraints(min_length=1, max_length=80), AfterValidator(_not_blank)]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


# ---- 요청 ----

class EventInput(Strict):
    name: Name
    description: Text
    date: date
    prepStartAt: AwareDatetime
    openAt: AwareDatetime
    closeAt: AwareDatetime
    teardownEndAt: AwareDatetime
    locationName: Text
    address: Text
    totalCapacity: int = Field(ge=1, le=MAX_MEMBERS)
    expectedVisitors: int | None = Field(default=None, ge=0)
    peakVisitors: int | None = Field(default=None, ge=0)
    desiredReserveCount: int = Field(ge=0, le=MAX_MEMBERS)
    confirmedInput: dict = Field(default_factory=dict)


class ZoneInput(Strict):
    id: Uuid
    name: ZoneName
    description: Text | None = None


class ProgramInput(Strict):
    id: Uuid
    zoneId: Uuid | None = None
    name: Name
    startAt: AwareDatetime
    endAt: AwareDatetime


class WorkGroupInput(Strict):
    id: Uuid
    name: Name


class FloorPlanInput(Strict):
    """평면도 페이지(V9-D54). 추출 결과3의 floorPlanPages 첫 장의 원본 문서와 쪽 번호다.

    AI가 url(BE 내부 문서 내려받기)로 원본을 받아 sha256을 대조하고 그 쪽을 그림으로 렌더링한다.
    문서 항목 모양은 추출 요청4의 documents[]와 같다. pageNumber는 PDF의 1부터 세는 쪽 번호이고 그림 파일이면 1이다.
    """
    documentId: Uuid
    pageNumber: int = Field(ge=1)
    mime: Literal["application/pdf", "image/png", "image/jpeg"]
    sha256: Sha256
    url: Annotated[str, StringConstraints(min_length=1, max_length=2000)]


# 문서 일정표 한 행(S15P31A202-408 제안 → V9-D66 요청4 선택 키 scheduleItems). schedule.py가 규칙을 맡는다.
ScheduleKey = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,30}$")]  # 추가 업무 tempId(tmp-sched-<key>) 40자 안
Label = Annotated[str, StringConstraints(min_length=1, max_length=40)]


class ScheduleSource(Strict):
    """원문 위치. documentId·pageNumber는 floorPlan과 같은 뜻이다(추출 요청4 documents[]의 문서, PDF 1부터 세는 쪽)."""
    documentId: Uuid
    pageNumber: int = Field(ge=1)


class ScheduleItem(Strict):
    """일정표 한 행. 필수는 key·title·startAt·endAt·source이고, 시각을 확정할 수 없으면 startAt·endAt을 null로 둔다.

    timeText는 원문 시각 표기(예: "17:30~17:50", "미정")다. 시각을 읽지 못한 이유를 사람이 확인할 때 쓴다.
    workGroupName·zoneName은 원문의 담당 직무·구역 칸을 요청의 직무 그룹·구역 이름으로 맞춘 값이다.
    bundle은 원문의 묶음 표시(예: "묶음 1", "A구역 담당")이며, 같은 직무·같은 묶음 항목끼리만 시간 겹침을 검사한다.
    """
    key: ScheduleKey
    title: Title
    startAt: AwareDatetime | None
    endAt: AwareDatetime | None
    timeText: Label | None = None
    workGroupName: Name | None = None
    zoneName: ZoneName | None = None
    bundle: Label | None = None
    source: ScheduleSource
    # 원문 "수행 절차"·"점검 항목" 칸(V9-D66). 있으면 그 업무의 steps·checklist가 된다. 없으면 일정표 안내 한 줄이다.
    steps: list[Step] | None = Field(default=None, min_length=1, max_length=6)
    checklist: list[CheckText] | None = Field(default=None, min_length=1, max_length=6)



class PlanPayload(Strict):
    event: EventInput
    zones: list[ZoneInput] = Field(max_length=MAX_ZONES)
    programs: list[ProgramInput] = Field(max_length=MAX_PROGRAMS)
    workGroups: list[WorkGroupInput] = Field(default_factory=list, max_length=MAX_WORK_GROUPS)
    # 평면도 쪽이 있으면 결과6으로 답한다. 키가 없거나 null(평면도 없음·원본 만료)이면 지금처럼 결과5다(2026-11-11 성하빈 합의).
    floorPlan: FloorPlanInput | None = None
    # 문서 추출이 읽은 업무 일정표 행(V9-D66). 키가 없거나 null이면 지금처럼 일정표 없이 생성한다.
    scheduleItems: list[ScheduleItem] | None = Field(default=None, max_length=MAX_SCHEDULE_ITEMS)

    @property
    def wants_venue(self):
        return self.floorPlan is not None


class PlanRequest(Strict):
    jobId: Uuid
    attempt: int = Field(ge=1)
    requestSchemaVersion: Literal["4"]
    kind: Literal["GENERATE_PLAN"]
    inputHash: Sha256
    contextRevision: int = Field(ge=0)
    deadlineAt: AwareDatetime
    payload: PlanPayload


# ---- 결과(PlanArtifact) ----

class ZonePlan(Strict):
    id: Uuid | None = None
    tempId: TempId | None = None
    name: ZoneName
    description: Text | None = None

    @model_validator(mode="after")
    def _one_ref(self):
        return _check_one_ref(self)


class ProgramPlan(Strict):
    id: Uuid | None = None
    tempId: TempId | None = None
    zoneId: Ref | None = None
    name: Name
    startAt: AwareDatetime
    endAt: AwareDatetime

    @model_validator(mode="after")
    def _one_ref(self):
        return _check_one_ref(self)


class WorkGroupPlan(Strict):
    id: Uuid | None = None
    tempId: TempId | None = None
    name: Name
    # 선택 필드(V9-D58·D59·D61, S15P31A202-400). 연결 업무를 넣을 그룹 표시. 키 자체는 devices.enabled()일 때만 내보낸다.
    purpose: Literal["SAFETY", "EQUIPMENT"] | None = None

    @model_validator(mode="after")
    def _one_ref(self):
        return _check_one_ref(self)


class ChecklistItem(Strict):
    key: Key
    text: CheckText
    required: bool


class TaskPlan(Strict):
    tempId: TempId
    workGroupId: Ref
    bundleKey: Key
    zoneId: Ref
    programId: Ref | None = None
    title: Title
    startAt: AwareDatetime
    endAt: AwareDatetime
    steps: list[Step] = Field(min_length=1, max_length=6)
    checklist: list[ChecklistItem] = Field(default_factory=list, max_length=6)
    assigneeId: None = None  # 실제 담당은 publish·그룹 참여 뒤 ASSIGN_INITIAL에서만 정한다


class Instruction(Strict):
    scope: Literal["EVENT", "WORK_GROUP"]
    targetId: Ref | None = None
    body: Body

    @model_validator(mode="after")
    def _target(self):
        if (self.scope == "EVENT") != (self.targetId is None):
            raise ValueError("EVENT 안내는 targetId가 null, WORK_GROUP 안내는 그룹 참조가 필요합니다.")
        return self


class BundleSummary(Strict):
    bundleKey: Key
    workGroupId: Ref
    taskCount: int = Field(ge=1)
    startAt: AwareDatetime
    endAt: AwareDatetime


class Question(Strict):
    key: Key
    field: Short  # 확인이 필요한 입력 위치. 예: payload.zones
    question: Short


class Assumption(Strict):
    key: Key
    statement: Short
    refs: list[str] = Field(default_factory=list)


class Unresolved(Strict):
    key: Key
    description: Short
    refs: list[str] = Field(default_factory=list)


Role = Literal["SUPERVISOR", "PLANNING", "SCHEDULE", "STAFFING", "GUIDE", "REVIEW"]


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
    """작업 그래프·재위임 이유·근거 ID·충돌 해결·최종 기여만 둔다. 원시 프롬프트·모델 사고·원문은 넣지 않는다."""
    graph: list[AgentTaskNode] = Field(default_factory=list)
    redelegations: list[Redelegation] = Field(default_factory=list)
    evidenceRefs: list[str] = Field(default_factory=list)
    resolvedConflicts: list[ResolvedConflict] = Field(default_factory=list)
    contributions: list[Contribution] = Field(default_factory=list)


class VenueZoneRect(Strict):
    """평면도 그림에서 읽은 구역 하나. 좌표는 그림 왼쪽 위가 (0,0), 오른쪽 아래가 (1,1)인 비율이다.

    name은 같은 결과 zones의 이름과 정확히 같다. BE가 이름으로 zones의 id/tempId를 찾아 네 꼭짓점으로 바꾼다(V9-D54).
    """
    name: ZoneName
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    width: float = Field(gt=0, le=1)
    height: float = Field(gt=0, le=1)

    @model_validator(mode="after")
    def _inside(self):
        if self.x + self.width > 1 + 1e-9 or self.y + self.height > 1 + 1e-9:
            raise ValueError("사각형이 그림 밖으로 나갑니다(x+width ≤ 1, y+height ≤ 1).")
        return self


class VenueSource(Strict):
    documentId: Uuid
    pageNumber: int = Field(ge=1)


class VenueLayout(Strict):
    """width·height는 AI가 읽은 렌더링 그림의 픽셀 크기다. 0~1 좌표를 그릴 때의 가로세로 비율로 쓴다."""
    source: VenueSource
    width: int = Field(gt=0)
    height: int = Field(gt=0)
    zones: list[VenueZoneRect] = Field(min_length=1, max_length=MAX_ZONES)


class CameraPlan(Strict):
    """선택 필드 cameras의 한 항목. zoneName은 같은 결과 zones의 이름이다(V9-D58)."""
    name: Title
    zoneName: ZoneName


class AssetPlan(Strict):
    """선택 필드 equipmentDraft.assets의 한 항목(V9-D59). autoObservationKind는 QLXD4 수신기만 "QLXD4"다."""
    name: Title
    kind: Title | None = None
    model: Title | None = None
    location: Short | None = None
    autoObservationKind: Literal["QLXD4"] | None = None


class EquipmentDraft(Strict):
    """이번 범위는 assets만 생산한다. connections·requirements·reservations·dependencies는 만들지 않는다(V9-D45·D59)."""
    assets: list[AssetPlan] = Field(max_length=50)


class VenueDraft(Strict):
    """결과6의 평면도 초안. 이번 범위는 layout만 생산한다(V9-D45). profiles·equipmentDraft는 만들지 않는다."""
    layout: VenueLayout


class PlanResult(Strict):
    status: Literal["READY", "NEEDS_INFO"]
    workGroups: list[WorkGroupPlan] = Field(max_length=MAX_WORK_GROUPS)
    zones: list[ZonePlan] = Field(max_length=MAX_ZONES)
    programs: list[ProgramPlan] = Field(max_length=MAX_PROGRAMS)
    tasks: list[TaskPlan] = Field(max_length=MAX_TASKS)
    instructions: list[Instruction]
    bundles: list[BundleSummary]
    assumptions: list[Assumption]
    unresolved: list[Unresolved]
    questions: list[Question]
    coordination: Coordination
    # 결과6에만 있는 키. null은 평면도 없음·읽지 못함이다. 결과5에서는 키 자체를 내보내지 않는다.
    venueDraft: VenueDraft | None = None
    # 결과5·6 선택 필드(V9-D58·D59·D61, S15P31A202-400). BE 수용(#395) 전에는 키를 내보내지 않는다(devices.enabled()).
    cameras: list[CameraPlan] = Field(default_factory=list, max_length=20)
    equipmentDraft: EquipmentDraft | None = None

    @property
    def has_venue_key(self):
        return "venueDraft" in self.model_fields_set

    @property
    def has_device_keys(self):
        return bool({"cameras", "equipmentDraft"} & self.model_fields_set)

    @model_validator(mode="after")
    def _branch(self):
        plan = (self.workGroups, self.zones, self.programs, self.tasks, self.instructions, self.bundles)
        if self.status == "NEEDS_INFO":
            if not self.questions:
                raise ValueError("NEEDS_INFO는 질문이 하나 이상 필요합니다.")
            if any(plan):
                raise ValueError("NEEDS_INFO는 부분 계획을 반환하지 않습니다. 계획 배열을 비워야 합니다.")
            if self.venueDraft is not None:
                raise ValueError("NEEDS_INFO에는 평면도 초안을 싣지 않습니다(venueDraft=null).")
            if self.cameras or self.equipmentDraft is not None:
                raise ValueError("NEEDS_INFO에는 카메라·장비 초안을 싣지 않습니다.")
        else:
            if self.questions:
                raise ValueError("READY에는 질문을 넣지 않습니다. 조건 확인이 필요하면 NEEDS_INFO를 반환합니다.")
            if not self.workGroups or not self.tasks:
                raise ValueError("READY는 직무 그룹과 업무가 하나 이상 필요합니다.")
        return self


def _check_one_ref(obj):
    if (obj.id is None) == (obj.tempId is None):
        raise ValueError("id(기존 DRAFT 항목)와 tempId(새 항목) 중 정확히 하나가 필요합니다.")
    return obj


# ---- 응답 envelope ----

class Identity(Strict):
    jobId: Uuid
    attempt: int = Field(ge=1)
    requestSchemaVersion: Literal["4"]
    inputHash: Sha256
    contextRevision: int = Field(ge=0)


class PlanSuccess(Identity):
    resultSchemaVersion: Literal["5", "6"]
    modelVersion: Short
    result: PlanResult

    @model_validator(mode="after")
    def _version(self):
        want = VENUE_RESULT_SCHEMA_VERSION if self.result.has_venue_key else RESULT_SCHEMA_VERSION
        if self.resultSchemaVersion != want:
            raise ValueError("venueDraft 키가 있으면 결과6, 없으면 결과5입니다.")
        return self


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


class PlanFailure(Strict):
    """접수 뒤 실패는 식별·입력 기준을 그대로 반환한다.

    인증/JSON 파싱 실패처럼 식별을 신뢰할 수 없는 사전 오류만 INVALID_REQUEST로 모르는 값을 null로 둔다.
    """
    jobId: Uuid | None
    attempt: int | None = Field(ge=1)
    requestSchemaVersion: Literal["4"] | None
    inputHash: Sha256 | None
    contextRevision: int | None = Field(ge=0)
    error: ErrorBody

    @model_validator(mode="after")
    def _identity(self):
        if self.error.code != "INVALID_REQUEST" and any(getattr(self, f) is None for f in IDENTITY_FIELDS):
            raise ValueError("접수 뒤 실패는 jobId/attempt/requestSchemaVersion/inputHash/contextRevision을 모두 반환합니다.")
        return self


# ---- 결정적 검사 ----

def issue(path, code, message):
    return {"path": path, "code": code, "message": message}


def schema_issues(e, prefix):
    return [issue(".".join([prefix, *map(str, err["loc"])]), "SCHEMA", err["msg"]) for err in e.errors()]


def request_issues(req):
    """BE가 보낸 현재 DRAFT 입력의 같은 행사 참조·시간 순서를 검사한다. 이상이 있으면 INVALID_REQUEST다."""
    p, e = req.payload, req.payload.event
    out = []
    for field in ("prepStartAt", "teardownEndAt"):
        if getattr(e, field).astimezone(SERVICE_TIMEZONE).date() != e.date:
            out.append(issue(f"payload.event.{field}", "DATE_MISMATCH",
                             "준비 시작·정리 종료는 행사 날짜(Asia/Seoul) 안에 있어야 합니다."))
    if not (e.prepStartAt <= e.openAt < e.closeAt <= e.teardownEndAt):
        out.append(issue("payload.event", "TIME_OUT_OF_RANGE", "prepStartAt ≤ openAt < closeAt ≤ teardownEndAt이어야 합니다."))
    if e.desiredReserveCount >= e.totalCapacity:
        out.append(issue("payload.event.desiredReserveCount", "INVALID_REQUEST", "희망 예비 인원은 총인원보다 적어야 합니다."))
    out += _duplicates(p.zones, "payload.zones", lambda z: z.id, "id")
    out += _duplicates(p.zones, "payload.zones", lambda z: z.name, "name")
    out += _duplicates(p.programs, "payload.programs", lambda x: x.id, "id")
    out += _duplicates(p.workGroups, "payload.workGroups", lambda g: g.id, "id")
    out += _duplicates(p.workGroups, "payload.workGroups", lambda g: g.name, "name")
    zone_ids = {z.id for z in p.zones}
    for i, prog in enumerate(p.programs):
        path = f"payload.programs[{i}]"
        if prog.zoneId is not None and prog.zoneId not in zone_ids:
            out.append(issue(f"{path}.zoneId", "INVALID_REFERENCE", "같은 행사의 구역이 아닙니다."))
        out += _window(prog, path, e.openAt, e.closeAt, "운영")
    return out


def precheck_questions(req):
    """모델을 부르기 전에 코드로 판단할 수 있는 필수 조건 누락을 질문으로 만든다."""
    questions = []
    if not req.payload.zones:
        questions.append(Question(key="zones", field="payload.zones",
                                  question="업무를 배치할 구역이 없습니다. 행사장 구역을 한 곳 이상 입력해 주세요."))
    return questions


def result_issues(req, result):
    """PlanArtifact의 참조·이름 중복·시간·묶음·요약 일치를 같은 요청 기준으로 검사한다.

    이름 유일은 구역·직무 그룹만 검사한다. 사전 A의 Program에는 이름 유일 조건이 없다.
    """
    e = req.payload.event
    out = []
    zones = _refs(result.zones, {z.id for z in req.payload.zones}, "result.zones", out)
    programs = _refs(result.programs, {x.id for x in req.payload.programs}, "result.programs", out)
    groups = _refs(result.workGroups, {g.id for g in req.payload.workGroups}, "result.workGroups", out)
    out += _duplicates(result.zones, "result.zones", lambda z: z.name, "name")
    out += _duplicates(result.workGroups, "result.workGroups", lambda g: g.name, "name")
    out += _duplicates(result.tasks, "result.tasks", lambda t: t.tempId, "tempId")

    for i, prog in enumerate(result.programs):
        path = f"result.programs[{i}]"
        if prog.zoneId is not None and prog.zoneId not in zones:
            out.append(issue(f"{path}.zoneId", "INVALID_REFERENCE", "결과의 구역 목록에 없는 참조입니다."))
        out += _window(prog, path, e.openAt, e.closeAt, "운영")

    by_program = {x.id or x.tempId: x for x in result.programs}
    by_bundle = {}
    for i, t in enumerate(result.tasks):
        path = f"result.tasks[{i}]"
        for field, known in (("workGroupId", groups), ("zoneId", zones), ("programId", programs)):
            ref = getattr(t, field)
            if ref is not None and ref not in known:
                out.append(issue(f"{path}.{field}", "INVALID_REFERENCE", "결과 목록에 없는 참조입니다."))
        out += _window(t, path, e.prepStartAt, e.teardownEndAt, "준비~정리")
        out += _program_link(t, by_program.get(t.programId), path)
        out += _duplicates(t.checklist, f"{path}.checklist", lambda c: c.key, "key")
        by_bundle.setdefault(t.bundleKey, []).append((i, t))

    for key, items in by_bundle.items():
        group_ids = {t.workGroupId for _, t in items}
        if len(group_ids) > 1:
            out.append(issue(f"result.tasks[{items[0][0]}].bundleKey", "BUNDLE_MISMATCH",
                             f"묶음 {key}의 업무가 서로 다른 직무 그룹에 있습니다."))
        ordered = sorted(items, key=lambda it: it[1].startAt)
        for (_, a), (j, b) in zip(ordered, ordered[1:]):
            if b.startAt < a.endAt:
                out.append(issue(f"result.tasks[{j}]", "TIME_OVERLAP",
                                 f"묶음 {key} 안에서 {a.tempId}와 시간이 겹칩니다. 한 사람이 전담할 수 없습니다."))

    expected = {
        key: (items[0][1].workGroupId, len(items), min(t.startAt for _, t in items), max(t.endAt for _, t in items))
        for key, items in by_bundle.items()
    }
    actual = {b.bundleKey: (b.workGroupId, b.taskCount, b.startAt, b.endAt) for b in result.bundles}
    out += _duplicates(result.bundles, "result.bundles", lambda b: b.bundleKey, "bundleKey")
    if actual != expected:
        out.append(issue("result.bundles", "BUNDLE_MISMATCH", "bundles 요약이 tasks의 bundleKey·workGroupId 집계와 다릅니다."))

    seen_targets = set()
    for i, ins in enumerate(result.instructions):
        path = f"result.instructions[{i}]"
        if ins.scope == "WORK_GROUP" and ins.targetId not in groups:
            out.append(issue(f"{path}.targetId", "INVALID_REFERENCE", "결과의 직무 그룹이 아닙니다."))
        if (ins.scope, ins.targetId) in seen_targets:
            out.append(issue(path, "DUPLICATE", "같은 대상의 안내가 이미 있습니다."))
        seen_targets.add((ins.scope, ins.targetId))
    return out + venue_issues(req, result) + device_issues(req, result)


def device_issues(req, result):
    """카메라·장비·그룹 표시 선택 필드(결과5·6, V9-D61)의 구역 이름 대응과 이름 중복을 검사한다."""
    out = []
    names = {z.name for z in result.zones}
    for i, c in enumerate(result.cameras):
        if c.zoneName not in names:
            out.append(issue(f"result.cameras[{i}].zoneName", "INVALID_REFERENCE", "결과의 구역 이름이 아닙니다."))
    out += _duplicates(result.cameras, "result.cameras", lambda c: c.name, "name")
    if result.equipmentDraft is not None:
        out += _duplicates(result.equipmentDraft.assets, "result.equipmentDraft.assets", lambda a: a.name, "name")
    return out


def venue_issues(req, result):
    """결과6 요청 여부와 venueDraft 키, 평면도 출처, 구역 이름 대응을 검사한다."""
    p = req.payload
    if p.wants_venue != result.has_venue_key:
        return [issue("result.venueDraft", "VENUE_MISMATCH",
                      "요청에 floorPlan이 있을 때만 venueDraft(결과6)를 반환합니다.")]
    draft = result.venueDraft
    if draft is None:
        return []
    out = []
    if (draft.layout.source.documentId, draft.layout.source.pageNumber) != (
            p.floorPlan.documentId, p.floorPlan.pageNumber):
        out.append(issue("result.venueDraft.layout.source", "INVALID_REFERENCE", "요청의 평면도 문서·쪽과 다릅니다."))
    names = {z.name for z in result.zones}
    for i, z in enumerate(draft.layout.zones):
        if z.name not in names:
            out.append(issue(f"result.venueDraft.layout.zones[{i}].name", "INVALID_REFERENCE",
                             "결과 zones에 없는 구역 이름입니다. 평면도에만 있는 구역을 만들지 않습니다."))
    out += _duplicates(draft.layout.zones, "result.venueDraft.layout.zones", lambda z: z.name, "name")
    return out


def _program_link(task, prog, path):
    """프로그램을 참조한 Task는 프로그램의 구역에 있고 프로그램 시간과 겹친다(V7-AI-011 제안).

    프로그램 전 준비·후 정리처럼 시간이 겹치지 않는 업무는 programId 없이 둔다.
    """
    if prog is None:
        return []
    out = []
    if prog.zoneId is not None and task.zoneId != prog.zoneId:
        out.append(issue(f"{path}.zoneId", "INVALID_REFERENCE", "참조한 프로그램의 구역과 다릅니다."))
    if not (task.startAt < prog.endAt and prog.startAt < task.endAt):
        out.append(issue(path, "TIME_OUT_OF_RANGE", "참조한 프로그램 시간과 겹치지 않습니다. 준비·정리 업무는 programId 없이 둡니다."))
    return out


def _refs(items, known_ids, path, out):
    """기존 id는 요청에 있는 항목만 허용한다. tempId는 결과 안에서 새로 정의한다."""
    for i, item in enumerate(items):
        if item.id is not None and item.id not in known_ids:
            out.append(issue(f"{path}[{i}].id", "INVALID_REFERENCE", "요청의 현재 DRAFT에 없는 id입니다. 새 항목은 tempId를 씁니다."))
    out += _duplicates(items, path, lambda x: x.id or x.tempId, "id/tempId")
    return {x.id or x.tempId for x in items}


def _duplicates(items, path, key, label):
    seen, out = set(), []
    for i, item in enumerate(items):
        k = key(item)
        if k in seen:
            out.append(issue(f"{path}[{i}]", "DUPLICATE", f"{label} {k}이(가) 중복됩니다."))
        seen.add(k)
    return out


def _window(item, path, start, end, label):
    if not item.startAt < item.endAt:
        return [issue(path, "TIME_OUT_OF_RANGE", "startAt이 endAt보다 앞서야 합니다.")]
    if item.startAt < start or item.endAt > end:
        return [issue(path, "TIME_OUT_OF_RANGE", f"행사 {label} 구간을 벗어납니다.")]
    return []


# ---- 응답 생성(AI 실행 측) ----

class ContractViolation(Exception):
    def __init__(self, issues, request=None):
        super().__init__(f"계약 위반 {len(issues)}건")
        self.issues = issues
        self.request = request


def parse_request(raw):
    """요청을 검증한다. 실패하면 ContractViolation(INVALID_REQUEST 대상)을 낸다.

    구조는 맞고 결정적 검사만 실패하면 e.request에 해석된 요청을 담는다.
    이때 failure(e.request, "INVALID_REQUEST", ...)로 식별을 그대로 반환하고, e.request가 None이면 접수 전 실패다.
    """
    try:
        req = PlanRequest.model_validate(raw)
    except ValidationError as e:
        raise ContractViolation(schema_issues(e, "request"))
    issues = request_issues(req)
    if issues:
        raise ContractViolation(issues, req)
    return req


def identity(req):
    return {f: getattr(req, f) for f in IDENTITY_FIELDS}


def ready(req, result, model_version):
    """READY 결과를 검증해 성공 envelope를 만든다. 위반이 있으면 내보내지 않고 ContractViolation을 낸다."""
    result = PlanResult.model_validate({**result, "status": "READY"})
    issues = result_issues(req, result)
    if issues:
        raise ContractViolation(issues)
    return _dump(PlanSuccess(**identity(req), resultSchemaVersion=_result_version(result),
                             modelVersion=model_version, result=result))


def needs_info(req, questions, model_version, coordination=None, assumptions=(), unresolved=()):
    """추가 질문 결과. 계획 배열은 비우고 DRAFT 반영 대상이 아님을 status로 구분한다.

    결과6 요청이면 venueDraft 키를 null로 둔다(V9-D54: NEEDS_INFO에는 평면도를 싣지 않는다).
    """
    venue = {"venueDraft": None} if req.payload.wants_venue else {}
    result = PlanResult(status="NEEDS_INFO", workGroups=[], zones=[], programs=[], tasks=[], instructions=[],
                        bundles=[], questions=list(questions), assumptions=list(assumptions),
                        unresolved=list(unresolved), coordination=coordination or Coordination(), **venue)
    return _dump(PlanSuccess(**identity(req), resultSchemaVersion=_result_version(result),
                             modelVersion=model_version, result=result))


def _result_version(result):
    return VENUE_RESULT_SCHEMA_VERSION if result.has_venue_key else RESULT_SCHEMA_VERSION


def failure(req, code, message, details=None):
    """실패 envelope. req가 None이면(식별을 신뢰할 수 없는 사전 오류) 식별 필드를 null로 둔다."""
    ids = identity(req) if req is not None else {f: None for f in IDENTITY_FIELDS}
    return _dump(PlanFailure(**ids, error=ErrorBody(code=code, message=message, retryable=ERROR_RETRYABLE[code],
                                                     details=details or {})))


def deadline_passed(req, now):
    return now >= req.deadlineAt


def _dump(model):
    out = model.model_dump(mode="json")
    result = getattr(model, "result", None)
    if result is not None and not result.has_venue_key:  # 결과5에는 venueDraft 키를 내보내지 않는다
        out["result"].pop("venueDraft", None)
    if result is not None:  # 선택 필드는 넣은 경우에만 내보낸다(BE 엄격 JSON, S15P31A202-400)
        for key in ("cameras", "equipmentDraft"):
            if key not in result.model_fields_set:
                out["result"].pop(key, None)
        for g, dumped in zip(result.workGroups, out["result"]["workGroups"]):
            if "purpose" not in g.model_fields_set:
                dumped.pop("purpose", None)
    return out


# ---- 응답 검증(BE·계약 시험 측) ----

def validate_response(raw_request, raw_response):
    """요청과 응답을 대조해 (분기, 위반 목록)을 반환한다. 분기는 READY/NEEDS_INFO/FAILED/INVALID다.

    요청은 parse_request와 같은 기준으로 먼저 판정한다.
    - 해석할 수 없는 요청(접수 전): INVALID_REQUEST 실패만 허용하고 식별 필드는 null 또는 요청 원래 값이다.
    - 해석은 되지만 결정적 검사를 통과하지 못한 요청: INVALID_REQUEST 실패만 허용하고 식별을 그대로 반환한다.
    - 유효한 요청: 성공 또는 접수 뒤 실패이며 식별을 그대로 반환한다.
    """
    try:
        req = PlanRequest.model_validate(raw_request)
        rejected = request_issues(req)
    except ValidationError as e:
        req, rejected = None, schema_issues(e, "request")

    if not isinstance(raw_response, dict) or "error" not in raw_response:
        if rejected:
            return "INVALID", rejected + [_expected_invalid_request()]
        try:
            res = PlanSuccess.model_validate(raw_response)
        except ValidationError as e:
            return "INVALID", schema_issues(e, "response")
        issues = _echo_issues(identity(req), res) + result_issues(req, res.result)
        return (res.result.status, []) if not issues else ("INVALID", issues)

    try:
        res = PlanFailure.model_validate(raw_response)
    except ValidationError as e:
        return "INVALID", schema_issues(e, "response")
    if rejected and res.error.code != "INVALID_REQUEST":
        return "INVALID", rejected + [_expected_invalid_request()]
    if req is None:
        known = raw_request if isinstance(raw_request, dict) else {}
        issues = [issue(f"response.{f}", "ECHO_MISMATCH", "접수 전 실패의 식별 필드는 null 또는 요청 원래 값이어야 합니다.")
                  for f in IDENTITY_FIELDS if getattr(res, f) is not None and getattr(res, f) != known.get(f)]
    else:
        issues = _echo_issues(identity(req), res)
    return ("FAILED", []) if not issues else ("INVALID", issues)


def _expected_invalid_request():
    return issue("response", "EXPECTED_INVALID_REQUEST", "유효하지 않은 요청에는 INVALID_REQUEST 실패만 반환합니다.")


def _echo_issues(expected, res):
    return [issue(f"response.{f}", "ECHO_MISMATCH", "요청의 값을 그대로 반환해야 합니다.")
            for f in IDENTITY_FIELDS if getattr(res, f) != expected[f]]


def json_schemas():
    return {
        "request.schema.json": PlanRequest.model_json_schema(),
        "success.schema.json": PlanSuccess.model_json_schema(),
        "failure.schema.json": PlanFailure.model_json_schema(),
    }


def _load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    if len(argv) >= 2 and argv[0] == "check":
        # 응답 없이 요청만 주면 요청의 유효성으로, 응답을 함께 주면 응답의 계약 일치로 종료 코드를 정한다.
        req, ok = _load(argv[1]), True
        try:
            parse_request(req)
            print(f"{argv[1]}: REQUEST OK")
        except ContractViolation as e:
            ok = len(argv) > 2
            print(f"{argv[1]}: REQUEST INVALID (INVALID_REQUEST 실패만 허용)")
            for i in e.issues:
                print(f"  {i['code']} {i['path']}: {i['message']}")
        for path in argv[2:]:
            branch, issues = validate_response(req, _load(path))
            ok &= branch != "INVALID"
            print(f"{path}: {branch}")
            for i in issues:
                print(f"  {i['code']} {i['path']}: {i['message']}")
        return 0 if ok else 1
    if len(argv) == 2 and argv[0] == "schema":
        out = Path(argv[1])
        out.mkdir(parents=True, exist_ok=True)
        for name, schema in json_schemas().items():
            (out / name).write_text(json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

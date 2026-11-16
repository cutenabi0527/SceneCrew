"""최초 배정(ASSIGN_INITIAL) 공통 실행 계약.

공통 요청4 envelope로 받은 그룹 하나의 미배정 묶음과 가용·비예비 팀원으로 bundleKey→memberId 제안을 만들고,
결과는 성공(결과 규격 1) 또는 실패 envelope 중 하나로 반환한다.

기준: docs/planning/records/DECISIONS.md V9-D47, ai/07-contracts.md(ASSIGN_INITIAL), be/04-server-processing.md
- payload: workGroupId, bundles[{bundleKey, tasks[{taskId, title, startAt, endAt, zoneId}]}],
  members[{memberId, busy[{startAt, endAt}]}], allowExternalAi. 이름 등 개인정보는 받지 않는다(extra 금지).
- result: assignments[{bundleKey, memberId}], unassigned, reasons, generator(GMS|RULE)는 필수, coordination은 선택.
- unassigned·reasons의 세부 모양은 기획에 없어 이 파일이 제안한다(BE #267과 확인 필요):
  unassigned[{bundleKey, code}], reasons[{bundleKey, text}].
- 같은 팀원에게 시간이 겹치는 묶음을 주지 않는다. 이미 맡은 시간(busy)과도 겹치지 않는다. 끝과 시작이 같은 시각은 허용한다.
- 시간이 비는 팀원이 남아 있는 묶음은 미배정으로 둘 수 없다. 사유(reasons)는 묶음당 하나다.
- coordination은 값이 없으면 키를 빼고 내보낸다(null을 싣지 않는다).

사용 (ai/assign에서):
    uv run python contract.py check examples/request.json examples/success-rule.json
"""

import json
import sys
from pathlib import Path
from typing import Annotated, Literal

from pydantic import (AfterValidator, AwareDatetime, BaseModel, ConfigDict, Field, StringConstraints,
                      ValidationError, model_validator)

KIND = "ASSIGN_INITIAL"
REQUEST_SCHEMA_VERSION = "4"
RESULT_SCHEMA_VERSION = "1"

# P01 상한: 행사 총인원 31명. 묶음은 한 사람이 전담하므로 그룹의 묶음도 31개를 넘지 않는다.
MAX_MEMBERS = 31
MAX_BUNDLES = 31
MAX_TASKS_PER_BUNDLE = 50
MAX_BUSY = 200
MAX_TITLE = 100
MAX_REASON = 200

GENERATORS = ("GMS", "RULE")
# 미배정 사유 코드(제안). NO_AVAILABLE_MEMBER: 시간이 비는 팀원이 없음, NO_MEMBER: 요청에 팀원이 없음
UNASSIGNED_CODES = ("NO_AVAILABLE_MEMBER", "NO_MEMBER")

ERROR_RETRYABLE = {
    "INVALID_REQUEST": False,
    "AI_TIMEOUT": False,
    "AI_UNAVAILABLE": True,
}
IDENTITY_FIELDS = ("jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision")


def _not_blank(v):
    if not v.strip():
        raise ValueError("공백만 있는 값은 허용하지 않습니다.")
    return v


Uuid = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
BundleKey = Annotated[str, StringConstraints(min_length=1, max_length=100), AfterValidator(_not_blank)]
Title = Annotated[str, StringConstraints(min_length=1, max_length=MAX_TITLE), AfterValidator(_not_blank)]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Interval(Strict):
    startAt: AwareDatetime
    endAt: AwareDatetime


# ---- 요청 ----

class TaskInput(Interval):
    taskId: Uuid
    title: Title
    zoneId: Uuid


class BundleInput(Strict):
    bundleKey: BundleKey
    tasks: list[TaskInput] = Field(min_length=1, max_length=MAX_TASKS_PER_BUNDLE)


class MemberInput(Strict):
    memberId: Uuid
    busy: list[Interval] = Field(default_factory=list, max_length=MAX_BUSY)


class AssignPayload(Strict):
    workGroupId: Uuid
    bundles: list[BundleInput] = Field(min_length=1, max_length=MAX_BUNDLES)
    members: list[MemberInput] = Field(max_length=MAX_MEMBERS)  # 비어 있으면 모든 묶음이 NO_MEMBER
    allowExternalAi: bool


class AssignRequest(Strict):
    jobId: Uuid
    attempt: int = Field(ge=1)
    requestSchemaVersion: Literal["4"]
    kind: Literal["ASSIGN_INITIAL"]
    inputHash: Sha256
    contextRevision: int = Field(ge=0)
    deadlineAt: AwareDatetime
    payload: AssignPayload


# ---- 결과(결과 규격 1) ----

class Assignment(Strict):
    bundleKey: BundleKey
    memberId: Uuid


class Unassigned(Strict):
    bundleKey: BundleKey
    code: Literal[UNASSIGNED_CODES]


class Reason(Strict):
    bundleKey: BundleKey
    text: str = Field(min_length=1, max_length=MAX_REASON)


class AssignResult(Strict):
    assignments: list[Assignment]
    unassigned: list[Unassigned]
    reasons: list[Reason]
    generator: Literal[GENERATORS]
    coordination: dict | None = None  # 선택(V9-D47). 원시 프롬프트·개인정보는 넣지 않는다


class Identity(Strict):
    jobId: Uuid | None
    attempt: int | None = Field(ge=1)
    requestSchemaVersion: Literal["4"] | None
    inputHash: Sha256 | None
    contextRevision: int | None = Field(ge=0)


class AssignSuccess(Identity):
    jobId: Uuid
    attempt: int = Field(ge=1)
    requestSchemaVersion: Literal["4"]
    inputHash: Sha256
    contextRevision: int = Field(ge=0)
    resultSchemaVersion: Literal["1"]
    modelVersion: str = Field(min_length=1, max_length=200)
    result: AssignResult


class ErrorBody(Strict):
    code: Literal[tuple(ERROR_RETRYABLE)]
    message: str = Field(min_length=1)
    retryable: bool
    details: dict = Field(default_factory=dict)

    @model_validator(mode="after")
    def _retryable(self):
        if self.retryable != ERROR_RETRYABLE[self.code]:
            raise ValueError(f"{self.code}의 retryable은 {ERROR_RETRYABLE[self.code]}여야 합니다.")
        return self


class AssignFailure(Identity):
    error: ErrorBody

    @model_validator(mode="after")
    def _identity(self):
        if self.error.code != "INVALID_REQUEST" and any(getattr(self, f) is None for f in IDENTITY_FIELDS):
            raise ValueError("접수 뒤 실패는 식별 필드를 모두 반환해야 합니다.")
        return self


# ---- 결정적 검사 ----

def issue(path, code, message):
    return {"path": path, "code": code, "message": message}


def schema_issues(e, prefix):
    return [issue(".".join([prefix, *(str(p) for p in err["loc"])]), "SCHEMA", err["msg"]) for err in e.errors()]


def overlaps(a, b):
    """시간 구간 a, b가 겹치는지. 끝과 시작이 같은 시각은 겹치지 않는다."""
    return a.startAt < b.endAt and b.startAt < a.endAt


def _duplicates(values):
    return sorted({v for v in values if values.count(v) > 1})


def request_issues(req):
    """중복·시간 순서·묶음 안 시간 겹침. 위반이 있으면 INVALID_REQUEST 대상이다."""
    p, out = req.payload, []
    for dup in _duplicates([b.bundleKey for b in p.bundles]):
        out.append(issue("request.payload.bundles", "DUPLICATE", f"bundleKey {dup}가 중복됩니다."))
    for dup in _duplicates([t.taskId for b in p.bundles for t in b.tasks]):
        out.append(issue("request.payload.bundles", "DUPLICATE", f"taskId {dup}가 중복됩니다."))
    for dup in _duplicates([m.memberId for m in p.members]):
        out.append(issue("request.payload.members", "DUPLICATE", f"memberId {dup}가 중복됩니다."))
    for i, b in enumerate(p.bundles):
        for j, t in enumerate(b.tasks):
            if t.startAt >= t.endAt:
                out.append(issue(f"request.payload.bundles[{i}].tasks[{j}]", "TIME_ORDER", "startAt < endAt이어야 합니다."))
        ordered = sorted(b.tasks, key=lambda t: t.startAt)
        if any(overlaps(x, y) for x, y in zip(ordered, ordered[1:])):
            out.append(issue(f"request.payload.bundles[{i}]", "TIME_OVERLAP",
                             "한 묶음의 업무는 한 사람이 맡으므로 시간이 겹치지 않아야 합니다."))
    for i, m in enumerate(p.members):
        for j, s in enumerate(m.busy):
            if s.startAt >= s.endAt:
                out.append(issue(f"request.payload.members[{i}].busy[{j}]", "TIME_ORDER", "startAt < endAt이어야 합니다."))
    return out


def result_issues(req, result):
    """같은 요청 기준으로 참조·누락·시간 충돌을 검사한다. 모든 묶음은 assignments나 unassigned에 정확히 한 번 나온다."""
    p, out = req.payload, []
    bundles = {b.bundleKey: b for b in p.bundles}
    members = {m.memberId: m for m in p.members}
    seen = [a.bundleKey for a in result.assignments] + [u.bundleKey for u in result.unassigned]
    for dup in _duplicates(seen):
        out.append(issue("result", "DUPLICATE", f"bundleKey {dup}가 두 번 이상 나옵니다."))
    for key in sorted(set(seen) - set(bundles)):
        out.append(issue("result", "INVALID_REFERENCE", f"요청에 없는 bundleKey입니다: {key}"))
    for key in sorted(set(bundles) - set(seen)):
        out.append(issue("result", "MISSING", f"결과에 없는 묶음입니다: {key}"))
    for i, r in enumerate(result.reasons):
        if r.bundleKey not in bundles:
            out.append(issue(f"result.reasons[{i}]", "INVALID_REFERENCE", f"요청에 없는 bundleKey입니다: {r.bundleKey}"))
    for dup in _duplicates([r.bundleKey for r in result.reasons]):
        out.append(issue("result.reasons", "DUPLICATE", f"묶음 {dup}의 사유가 두 개 이상입니다. 묶음당 하나만 둡니다."))
    taken = {m: list(members[m].busy) for m in members}
    for i, a in enumerate(result.assignments):
        path = f"result.assignments[{i}]"
        if a.memberId not in members:
            out.append(issue(path, "INVALID_REFERENCE", "요청에 없는 팀원입니다."))
            continue
        if a.bundleKey not in bundles:
            continue
        tasks = bundles[a.bundleKey].tasks
        if any(overlaps(t, s) for t in tasks for s in taken[a.memberId]):
            out.append(issue(path, "TIME_CONFLICT", "이 팀원이 이미 맡은 시간과 겹칩니다."))
        taken[a.memberId].extend(tasks)
    # 미배정 사유 코드가 사실인지: 팀원이 없을 때만 NO_MEMBER, 남은 시간으로 맡길 수 있는 묶음은 미배정으로 둘 수 없다
    for i, u in enumerate(result.unassigned):
        path = f"result.unassigned[{i}]"
        if (u.code == "NO_MEMBER") != (not members):
            out.append(issue(path, "WRONG_CODE", "NO_MEMBER는 요청에 팀원이 없을 때만 씁니다."))
        if u.bundleKey in bundles and any(
                not any(overlaps(t, s) for t in bundles[u.bundleKey].tasks for s in taken[m]) for m in members):
            out.append(issue(path, "ASSIGNABLE", "시간이 비는 팀원이 있는데 미배정으로 두었습니다."))
    return out


# ---- 응답 생성(AI 실행 측) ----

class ContractViolation(Exception):
    def __init__(self, issues, request=None):
        super().__init__(f"계약 위반 {len(issues)}건")
        self.issues = issues
        self.request = request


def parse_request(raw):
    """요청을 검증한다. 구조는 맞고 결정적 검사만 실패하면 e.request에 해석된 요청을 담는다."""
    try:
        req = AssignRequest.model_validate(raw)
    except ValidationError as e:
        raise ContractViolation(schema_issues(e, "request"))
    issues = request_issues(req)
    if issues:
        raise ContractViolation(issues, req)
    return req


def identity(req):
    return {f: getattr(req, f) for f in IDENTITY_FIELDS}


def success(req, result, model_version):
    """result: AssignResult 또는 같은 모양의 dict. 위반이 있으면 내보내지 않는다."""
    result = AssignResult.model_validate(result) if isinstance(result, dict) else result
    issues = result_issues(req, result)
    if issues:
        raise ContractViolation(issues)
    return _dump(AssignSuccess(**identity(req), resultSchemaVersion=RESULT_SCHEMA_VERSION,
                               modelVersion=model_version, result=result))


def failure(req, code, message, details=None):
    """실패 envelope. req가 None이면(식별을 신뢰할 수 없는 사전 오류) 식별 필드를 null로 둔다."""
    ids = identity(req) if req is not None else {f: None for f in IDENTITY_FIELDS}
    return _dump(AssignFailure(**ids, error=ErrorBody(code=code, message=message, retryable=ERROR_RETRYABLE[code],
                                                      details=details or {})))


def validate_response(raw_request, raw_response):
    """요청과 응답을 대조해 위반 목록을 돌려준다(빈 목록이면 통과)."""
    try:
        req = parse_request(raw_request)
    except ContractViolation as e:
        return [issue("request", "INVALID_REQUEST", f"요청 위반 {len(e.issues)}건")]
    if "error" in raw_response:
        try:
            AssignFailure.model_validate(raw_response)
        except ValidationError as e:
            return schema_issues(e, "response")
        return [issue("response", "ECHO_MISMATCH", "식별 필드가 요청과 다릅니다.")
                for f in IDENTITY_FIELDS if raw_response.get(f) != getattr(req, f)][:1]
    try:
        res = AssignSuccess.model_validate(raw_response)
    except ValidationError as e:
        return schema_issues(e, "response")
    echo = [issue("response", "ECHO_MISMATCH", "식별 필드가 요청과 다릅니다.")
            for f in IDENTITY_FIELDS if getattr(res, f) != getattr(req, f)][:1]
    return echo + result_issues(req, res.result)


def _dump(model):
    # 성공 결과의 선택 필드(coordination)는 값이 없으면 키를 뺀다. 실패의 식별 필드 null은 그대로 둔다(키가 필요하다).
    if isinstance(model, AssignSuccess):
        dumped = model.model_dump(mode="json")
        dumped["result"] = model.result.model_dump(mode="json", exclude_none=True)
        return dumped
    return model.model_dump(mode="json")


def main(argv):
    if len(argv) == 3 and argv[0] == "check":
        req = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
        res = json.loads(Path(argv[2]).read_text(encoding="utf-8"))
        issues = validate_response(req, res)
        print(json.dumps(issues, ensure_ascii=False, indent=2) if issues else "OK")
        return 1 if issues else 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

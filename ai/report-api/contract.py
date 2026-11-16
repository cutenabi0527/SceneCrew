"""보고 분석(ANALYZE_REPORT) 공통 실행 계약.

공통 요청4 envelope로 받은 보고 문장과 같은 revision의 구역·프로그램·직무 그룹을 검증하고,
결과는 성공(결과 규격 4) 또는 실패 envelope 중 하나로 반환한다.

- 유형·상태 점수가 0.65 미만이면 값은 null로 두고, scores에는 가장 높은 라벨과 점수를 그대로 남긴다.
- 개체 범위는 normalizedText의 코드포인트 인덱스(start 포함, end 제외)다.
- 구역 후보는 개체와 요청의 허용 목록으로 결정적으로 만든다(zones.py). 응답 검증도 같은 함수로 대조한다.
- analysisValidity는 AI가 항상 CURRENT로 반환한다. PARTIAL_STALE 투영은 BE가 반영할 때 판단한다.
- 결과4(V9-D48)는 운영 상황 추천 situation{primary, related}를 더한다. 0.65 적용 뒤 category와 문장 단서로
  결정적으로 만든다(situation.py). 응답 검증도 같은 함수로 대조한다. primary=null이 미분류다.

기준: docs/planning/ai/06-voice-model.md, 07-contracts.md, be/03-data-dictionary-b.md, be/06-ai-contract.md

사용 (ai/report-api에서):
    uv run python contract.py check examples/request.json examples/success.json
    uv run python contract.py schema schema
"""

import json
import re
import sys
import unicodedata
from pathlib import Path
from typing import Annotated, Literal

from pydantic import (AfterValidator, AwareDatetime, BaseModel, ConfigDict, Field, StringConstraints,
                      ValidationError, model_validator)

import situation as situations
import zones

KIND = "ANALYZE_REPORT"
REQUEST_SCHEMA_VERSION = "4"
RESULT_SCHEMA_VERSION = "4"
SCORE_THRESHOLD = 0.65

# 라벨 목록은 ai/labeling/label_schema.json(학습 데이터 v1)과 같다.
CATEGORIES = ("EQUIPMENT", "SUPPLY", "CONGESTION", "DELAY", "STAFF_GAP", "OTHER")
STATES = ("OCCURRED", "RESOLVED_MENTION", "RECURRED", "UNSPECIFIED")
ENTITY_TYPES = ("ZONE", "ITEM", "PROGRAM", "ROLE")

# P01 상한과 Report.text 길이
MAX_ZONES = 10
MAX_PROGRAMS = 20
MAX_WORK_GROUPS = 31
MAX_TEXT = 1000

# 실패 코드별 재시도 가능 여부. AI_UNAVAILABLE만 남은 마감 안에서 BE 전달 재시도 대상이다.
ERROR_RETRYABLE = {
    "INVALID_REQUEST": False,
    "AI_TIMEOUT": False,
    "AI_UNAVAILABLE": True,
}
IDENTITY_FIELDS = ("jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision")


def normalize(text):
    """학습 데이터와 같은 정규화(NFC, 연속 공백 1개, 앞뒤 공백 제거). validate_labels.normalize와 같다."""
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", text)).strip()


def _not_blank(v):
    if not v.strip():
        raise ValueError("공백만 있는 값은 허용하지 않습니다.")
    return v


Uuid = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
# Program·WorkGroup 이름은 기획(사전 A)에 길이 상한이 없어 AI도 두지 않는다. 원문보다 긴 이름은 후보만 생기지 않는다.
Name = Annotated[str, StringConstraints(min_length=1), AfterValidator(_not_blank)]
ZoneName = Annotated[str, StringConstraints(min_length=1, max_length=30), AfterValidator(_not_blank)]
ReportText = Annotated[str, StringConstraints(min_length=1, max_length=MAX_TEXT), AfterValidator(_not_blank)]
Score = Annotated[float, Field(ge=0, le=1)]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


# ---- 요청 ----

class ZoneInput(Strict):
    id: Uuid
    name: ZoneName


class ProgramInput(Strict):
    id: Uuid
    name: Name
    zoneId: Uuid | None  # 구역 미정이면 null


class WorkGroupInput(Strict):
    id: Uuid
    name: Name
    zoneIds: list[Uuid] = Field(max_length=MAX_ZONES)  # 현재 Task.workGroupId/zoneId에서 BE가 산출한 허용 구역


class AnalyzePayload(Strict):
    reportId: Uuid
    normalizedText: ReportText
    allowedZones: list[ZoneInput] = Field(max_length=MAX_ZONES)
    allowedPrograms: list[ProgramInput] = Field(max_length=MAX_PROGRAMS)
    allowedWorkGroups: list[WorkGroupInput] = Field(max_length=MAX_WORK_GROUPS)


class AnalyzeRequest(Strict):
    jobId: Uuid
    attempt: int = Field(ge=1)
    requestSchemaVersion: Literal["4"]
    kind: Literal["ANALYZE_REPORT"]
    inputHash: Sha256
    contextRevision: int = Field(ge=0)
    deadlineAt: AwareDatetime
    payload: AnalyzePayload


# ---- 결과(결과 규격 4) ----

class LabelScore(Strict):
    label: str
    score: Score


class CategoryScore(LabelScore):
    label: Literal[CATEGORIES]


class StateScore(LabelScore):
    label: Literal[STATES]


class Scores(Strict):
    category: CategoryScore
    reportedState: StateScore


class Entity(Strict):
    type: Literal[ENTITY_TYPES]
    start: int = Field(ge=0)
    end: int = Field(ge=1)
    text: str = Field(min_length=1)


class ZoneCandidate(Strict):
    zoneId: Uuid
    source: Literal[zones.SOURCES]
    sourceRef: Uuid  # ZONE은 Zone.id, PROGRAM은 Program.id, ROLE은 WorkGroup.id
    contextRevision: int = Field(ge=0)


class Situation(Strict):
    """운영 상황 추천. primary는 6종 또는 null(미분류), related는 primary를 뺀 중복 없는 6종 목록이다."""
    primary: Literal[situations.SITUATIONS] | None
    related: list[Literal[situations.SITUATIONS]]

    @model_validator(mode="after")
    def _related(self):
        if len(set(self.related)) != len(self.related):
            raise ValueError("related에 같은 상황이 중복됩니다.")
        if self.primary in self.related:
            raise ValueError("related에 primary를 다시 넣지 않습니다.")
        if self.primary is None and self.related:
            raise ValueError("primary가 null(미분류)이면 related는 비어 있어야 합니다.")
        return self


class AnalysisResult(Strict):
    category: Literal[CATEGORIES] | None  # 점수 0.65 미만이면 null
    reportedState: Literal[STATES] | None
    scores: Scores
    entities: list[Entity]
    zoneCandidates: list[ZoneCandidate]
    analysisValidity: Literal["CURRENT"]
    situation: Situation


class Identity(Strict):
    jobId: Uuid | None
    attempt: int | None = Field(ge=1)
    requestSchemaVersion: Literal["4"] | None
    inputHash: Sha256 | None
    contextRevision: int | None = Field(ge=0)


class AnalyzeSuccess(Identity):
    jobId: Uuid
    attempt: int = Field(ge=1)
    requestSchemaVersion: Literal["4"]
    inputHash: Sha256
    contextRevision: int = Field(ge=0)
    resultSchemaVersion: Literal["4"]
    modelVersion: str = Field(min_length=1)
    result: AnalysisResult


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


class AnalyzeFailure(Identity):
    error: ErrorBody

    @model_validator(mode="after")
    def _identity(self):
        # 식별을 신뢰할 수 없는 접수 전 실패(INVALID_REQUEST)만 식별 필드를 null로 둘 수 있다.
        if self.error.code != "INVALID_REQUEST" and any(getattr(self, f) is None for f in IDENTITY_FIELDS):
            raise ValueError("접수 뒤 실패는 식별 필드를 모두 반환해야 합니다.")
        return self


# ---- 결정적 검사 ----

def issue(path, code, message):
    return {"path": path, "code": code, "message": message}


def schema_issues(e, prefix):
    return [issue(".".join([prefix, *(str(p) for p in err["loc"])]), "SCHEMA", err["msg"]) for err in e.errors()]


def request_issues(req):
    """같은 행사 참조·중복·정규화 위반. 위반이 있으면 INVALID_REQUEST 대상이다."""
    p, out = req.payload, []
    if p.normalizedText != normalize(p.normalizedText):
        out.append(issue("request.payload.normalizedText", "NOT_NORMALIZED", "NFC·공백 정리가 된 문장이어야 합니다."))
    ids = [z.id for z in p.allowedZones] + [x.id for x in p.allowedPrograms] + [g.id for g in p.allowedWorkGroups]
    for dup in sorted({i for i in ids if ids.count(i) > 1}):
        out.append(issue("request.payload", "DUPLICATE", f"id {dup}가 중복됩니다."))
    for name, items in (("allowedZones", p.allowedZones), ("allowedWorkGroups", p.allowedWorkGroups)):
        names = [zones.key(x.name) for x in items]
        for i, k in enumerate(names):
            if names.index(k) != i:
                out.append(issue(f"request.payload.{name}[{i}].name", "DUPLICATE", "같은 행사에서 이름이 중복됩니다."))
    zone_ids = {z.id for z in p.allowedZones}
    for i, prog in enumerate(p.allowedPrograms):
        if prog.zoneId is not None and prog.zoneId not in zone_ids:
            out.append(issue(f"request.payload.allowedPrograms[{i}].zoneId", "INVALID_REFERENCE",
                             "요청에 없는 구역입니다."))
    for i, group in enumerate(p.allowedWorkGroups):
        if len(set(group.zoneIds)) != len(group.zoneIds):
            out.append(issue(f"request.payload.allowedWorkGroups[{i}].zoneIds", "DUPLICATE", "구역이 중복됩니다."))
        if not set(group.zoneIds) <= zone_ids:
            out.append(issue(f"request.payload.allowedWorkGroups[{i}].zoneIds", "INVALID_REFERENCE",
                             "요청에 없는 구역입니다."))
    return out


def result_issues(req, result):
    """같은 요청을 기준으로 미분류 규칙·개체 범위·구역 후보·상황 추천을 검사한다."""
    text, out = req.payload.normalizedText, []
    for field in ("category", "reportedState"):
        value, top = getattr(result, field), getattr(result.scores, field)
        if top.score < SCORE_THRESHOLD and value is not None:
            out.append(issue(f"result.{field}", "THRESHOLD", f"점수 {SCORE_THRESHOLD} 미만이면 null이어야 합니다."))
        if top.score >= SCORE_THRESHOLD and value != top.label:
            out.append(issue(f"result.{field}", "THRESHOLD", f"점수 {SCORE_THRESHOLD} 이상이면 scores의 라벨이어야 합니다."))
    prev_end = 0
    for i, ent in enumerate(result.entities):
        path = f"result.entities[{i}]"
        if not ent.start < ent.end <= len(text):
            out.append(issue(path, "SPAN", "범위가 문장을 벗어나거나 비어 있습니다."))
            continue
        if ent.start < prev_end:
            out.append(issue(path, "SPAN", "개체는 시작 위치 순서이고 겹치지 않아야 합니다."))
        if ent.text != text[ent.start:ent.end]:
            out.append(issue(path, "SPAN", "text가 normalizedText[start:end]와 다릅니다."))
        prev_end = ent.end
    expected = zones.candidates(req.payload, [e.model_dump() for e in result.entities], req.contextRevision)
    if [c.model_dump() for c in result.zoneCandidates] != expected:
        out.append(issue("result.zoneCandidates", "ZONE_CANDIDATES",
                         "개체와 허용 목록으로 만든 구역 후보와 다릅니다(zones.candidates)."))
    if result.situation.model_dump() != situations.recommend(text, result.category):
        out.append(issue("result.situation", "SITUATION",
                         "문장과 category로 만든 상황 추천과 다릅니다(situation.recommend)."))
    return out


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
        req = AnalyzeRequest.model_validate(raw)
    except ValidationError as e:
        raise ContractViolation(schema_issues(e, "request"))
    issues = request_issues(req)
    if issues:
        raise ContractViolation(issues, req)
    return req


def identity(req):
    return {f: getattr(req, f) for f in IDENTITY_FIELDS}


def success(req, analysis, model_version):
    """모델 출력(inference.analyze)에 구역 후보를 붙여 성공 envelope를 만든다. 위반이 있으면 내보내지 않는다.

    analysis: {category, reportedState, scores, entities}. category/reportedState는 임계값 적용 전 최고 라벨이다.
    """
    category = _threshold(analysis["scores"]["category"])
    result = AnalysisResult(
        category=category,
        reportedState=_threshold(analysis["scores"]["reportedState"]),
        scores=analysis["scores"],
        entities=analysis["entities"],
        zoneCandidates=zones.candidates(req.payload, analysis["entities"], req.contextRevision),
        analysisValidity="CURRENT",
        situation=situations.recommend(req.payload.normalizedText, category),
    )
    issues = result_issues(req, result)
    if issues:
        raise ContractViolation(issues)
    return _dump(AnalyzeSuccess(**identity(req), resultSchemaVersion=RESULT_SCHEMA_VERSION,
                                modelVersion=model_version, result=result))


def _threshold(top):
    return top["label"] if top["score"] >= SCORE_THRESHOLD else None


def failure(req, code, message, details=None):
    """실패 envelope. req가 None이면(식별을 신뢰할 수 없는 사전 오류) 식별 필드를 null로 둔다."""
    ids = identity(req) if req is not None else {f: None for f in IDENTITY_FIELDS}
    return _dump(AnalyzeFailure(**ids, error=ErrorBody(code=code, message=message, retryable=ERROR_RETRYABLE[code],
                                                       details=details or {})))


def _dump(model):
    return model.model_dump(mode="json")


# ---- 응답 검증(BE·계약 시험 측) ----

def validate_response(raw_request, raw_response):
    """요청과 응답을 대조해 (분기, 위반 목록)을 반환한다. 분기는 SUCCEEDED/FAILED/INVALID다.

    - 해석할 수 없는 요청(접수 전): INVALID_REQUEST 실패만 허용하고 식별 필드는 null 또는 요청 원래 값이다.
    - 해석은 되지만 결정적 검사를 통과하지 못한 요청: INVALID_REQUEST 실패만 허용하고 식별을 그대로 반환한다.
    - 유효한 요청: 성공 또는 접수 뒤 실패이며 식별을 그대로 반환한다.
    """
    try:
        req = AnalyzeRequest.model_validate(raw_request)
        rejected = request_issues(req)
    except ValidationError as e:
        req, rejected = None, schema_issues(e, "request")

    if not isinstance(raw_response, dict) or "error" not in raw_response:
        if rejected:
            return "INVALID", rejected + [_expected_invalid_request()]
        try:
            res = AnalyzeSuccess.model_validate(raw_response)
        except ValidationError as e:
            return "INVALID", schema_issues(e, "response")
        issues = _echo_issues(identity(req), res) + result_issues(req, res.result)
        return ("SUCCEEDED", []) if not issues else ("INVALID", issues)

    try:
        res = AnalyzeFailure.model_validate(raw_response)
    except ValidationError as e:
        return "INVALID", schema_issues(e, "response")
    if rejected and res.error.code != "INVALID_REQUEST":
        return "INVALID", rejected + [_expected_invalid_request()]
    if not rejected and res.error.code == "INVALID_REQUEST":
        return "INVALID", [issue("response.error.code", "UNEXPECTED_INVALID_REQUEST",
                                 "유효한 요청에는 INVALID_REQUEST 실패를 반환하지 않습니다.")]
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
        "request.schema.json": AnalyzeRequest.model_json_schema(),
        "success.schema.json": AnalyzeSuccess.model_json_schema(),
        "failure.schema.json": AnalyzeFailure.model_json_schema(),
    }


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    if len(argv) >= 2 and argv[0] == "check":
        # 응답 없이 요청만 주면 요청의 유효성으로, 응답을 함께 주면 응답의 계약 일치로 종료 코드를 정한다.
        req, ok = load_json(argv[1]), True
        try:
            parse_request(req)
            print(f"{argv[1]}: REQUEST OK")
        except ContractViolation as e:
            ok = len(argv) > 2
            print(f"{argv[1]}: REQUEST INVALID (INVALID_REQUEST 실패만 허용)")
            for i in e.issues:
                print(f"  {i['code']} {i['path']}: {i['message']}")
        for path in argv[2:]:
            branch, issues = validate_response(req, load_json(path))
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

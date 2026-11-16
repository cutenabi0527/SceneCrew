"""LLM Supervisor(GMS). 보고와 현재 계획 요약을 보고 다음 할 일을 정한다.

- 처음: 어떤 전문 Agent(SCHEDULE·STAFFING)에게 무엇을 맡길지, 조치 업무(Need)가 필요한지, 질문으로 끝낼지
- 충돌이 남았을 때: 누구에게 다시 맡길지, 재위임하지 않을지

모델에는 판단에 필요한 사실만 보낸다(보고 문장·지시·상황 유형·구역·프로그램 시간·가용 인원 수). 팀원 개인 정보는 없다.
출력은 json_schema(엄격)로 받고 agents.SupervisorDecision·Redelegate로 다시 검사한다. 모델이 정한 범위를 넘는 일
(등록되지 않은 Agent, 재위임 2회, 검토 생략)은 graph.py의 울타리가 막는다. 호출은 결정마다 1회이며 재시도하지 않는다.
"""
import importlib.util
import json
from pathlib import Path

from agents import Delegation, Need, Redelegate, SupervisorDecision, response_window
from contract import Question

GMS_PATH = Path(__file__).resolve().parents[1] / "labeling" / "generate" / "gms.py"
MAX_CALL_SECONDS = 60
# 모델이 정한 조치 업무 시작(기준 시각부터 몇 분 뒤)·걸리는 시간의 허용 범위. 넘으면 범위 끝으로 맞춘다.
MAX_START_AFTER_MINUTES = 600
MIN_DURATION_MINUTES, MAX_DURATION_MINUTES = 5, 180

SYSTEM = """너는 콘서트 현장 운영의 작업 관리자(Supervisor)다. 현장 보고를 읽고 대응안을 만들기 위해 어떤 전문 담당에게 무엇을 맡길지 정한다.
직접 일정이나 담당자를 정하지 않는다. 전문 담당의 결과는 코드가 검사한다. JSON 객체 하나만 출력한다.
goal·need.title·questions·reason 같은 글은 모두 한국어로 쓴다. 조치 업무 이름은 현장 팀원 화면에 그대로 보인다.

전문 담당
- SCHEDULE(일정): 영향받는 공연·업무 시간과 조치 업무 시간을 정한다. 시간을 옮기거나 조치 업무를 새로 넣어야 할 때 맡긴다.
- STAFFING(인력): 비어 있는 가용 인원 중 담당자를 고른다. 조치 업무 담당이 필요하거나 빠진 인원의 업무를 넘겨야 할 때 맡긴다.

판단 기준
- 꼭 필요한 담당만 고른다. 인원이 빠진 보고처럼 시간 변경이 필요 없으면 STAFFING만 맡긴다.
- 서로의 결과가 필요 없는 일은 동시에 맡긴다(delegate에 함께 넣는다).
- 장비 이상·현장 문제처럼 누군가 가서 처리해야 하면 need(조치 업무)를 정한다: 짧은 한국어 이름(예: "프로젝터 교체"), 보고 구역 id,
  기준 시각부터 몇 분 뒤 시작해 몇 분 걸리는지. 담당자가 이동·준비할 시간을 두고 보통 5~10분 뒤에 시작한다.
- 구역을 알 수 없거나 무엇을 해야 할지 판단할 사실이 없으면 delegate를 비우고 questions로 총괄에게 묻는다. 사실을 지어내지 않는다.
- reason에는 왜 그렇게 정했는지 한 문장으로 쓴다."""

REDELEGATE_SYSTEM = """너는 콘서트 현장 운영의 작업 관리자(Supervisor)다. 일정(SCHEDULE)과 인력(STAFFING) 담당의 결과를 합쳤더니
같은 사람이 같은 시간에 두 업무를 맡는 충돌이 생겼다. 한 담당에게만 다시 맡겨 충돌을 풀어야 한다. JSON 객체 하나만 출력한다.
- 담당자를 바꾸면 풀리는 충돌이면 STAFFING, 시간을 조정해야 풀리는 충돌이면 SCHEDULE을 고른다.
- 다시 맡겨도 풀 수 없다고 판단하면 role을 null로 한다.
- goal에는 다시 맡길 일을 한 문장으로, reason에는 이유를 한 문장으로 한국어로 쓴다."""

DECISION_SCHEMA = {"name": "supervisor_decision", "strict": True, "schema": {
    "type": "object", "additionalProperties": False,
    "required": ["delegate", "need", "questions", "reason"],
    "properties": {
        "delegate": {"type": "array", "maxItems": 2, "items": {
            "type": "object", "additionalProperties": False, "required": ["role", "goal"],
            "properties": {"role": {"type": "string", "enum": ["SCHEDULE", "STAFFING"]}, "goal": {"type": "string"}}}},
        "need": {"anyOf": [{"type": "null"}, {
            "type": "object", "additionalProperties": False,
            "required": ["title", "zoneId", "startAfterMinutes", "durationMinutes"],
            "properties": {"title": {"type": "string"}, "zoneId": {"type": "string"},
                           "startAfterMinutes": {"type": "integer"}, "durationMinutes": {"type": "integer"}}}]},
        "questions": {"type": "array", "items": {
            "type": "object", "additionalProperties": False, "required": ["key", "field", "question"],
            "properties": {"key": {"type": "string"}, "field": {"type": "string"}, "question": {"type": "string"}}}},
        "reason": {"type": "string"}}}}

REDELEGATE_SCHEMA = {"name": "supervisor_redelegate", "strict": True, "schema": {
    "type": "object", "additionalProperties": False, "required": ["role", "goal", "reason"],
    "properties": {"role": {"anyOf": [{"type": "null"}, {"type": "string", "enum": ["SCHEDULE", "STAFFING"]}]},
                   "goal": {"type": "string"}, "reason": {"type": "string"}}}}


class SupervisorUnavailable(Exception):
    """모델에 연결하지 못함(AI_UNAVAILABLE)"""


def _load_gms():
    spec = importlib.util.spec_from_file_location("gms", GMS_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def facts(snap):
    """모델에 보내는 사실. 개인 정보·업무 담당자 id는 넣지 않는다."""
    p = snap.req.payload
    s = p.situation or {}
    return {
        "now": p.now.isoformat(),
        "reports": [r.body for r in p.issue.reports],
        "instruction": p.instruction,
        "reportZoneId": p.issue.zoneId,
        "situationTypes": [t for t in [s.get("primaryType"), *(s.get("relatedTypes") or [])] if t],
        "zones": [{"id": z.id, "name": z.name} for z in p.zones],
        "programs": [{"name": x.name, "zoneId": x.zoneId, "startAt": x.startAt.isoformat(), "endAt": x.endAt.isoformat()}
                     for x in sorted(p.programs, key=lambda x: x.startAt) if x.endAt > p.now][:10],
        "availableMembers": sum(1 for m in p.members if m.available),
        "unavailableMembers": sum(1 for m in p.members if not m.available),
    }


class GmsSupervisor:
    def __init__(self, chat=None, model_var="GMS_CHANGE_MODEL"):
        self._chat = chat
        self.model_var = model_var
        self.model_version = "change-supervisor-gms"
        self.usage = []

    def _call(self, system, user, schema):
        chat = self._chat or _load_gms().chat_json
        try:
            out, model, usage = chat([{"role": "system", "content": system},
                                      {"role": "user", "content": json.dumps(user, ensure_ascii=False)}],
                                     temperature=0.2, retries=0, model_var=self.model_var,
                                     response_format={"type": "json_schema", "json_schema": schema},
                                     timeout=MAX_CALL_SECONDS)
        except Exception as e:
            if type(e).__name__ == "GmsError":
                raise SupervisorUnavailable(str(e)) from None
            raise
        self.model_version = f"change-supervisor-{model}"
        self.usage.append(usage)
        return out

    def decide(self, snap):
        out = self._call(SYSTEM, facts(snap), DECISION_SCHEMA)
        need = None
        if out.get("need"):
            n = out["need"]
            after = min(MAX_START_AFTER_MINUTES, max(0, int(n["startAfterMinutes"])))
            minutes = min(MAX_DURATION_MINUTES, max(MIN_DURATION_MINUTES, int(n["durationMinutes"])))
            start, end = response_window(snap.req.payload, after, minutes)
            need = Need(title=n["title"][:200], zoneId=n["zoneId"], startAt=start, endAt=end)
        return SupervisorDecision(delegate=[Delegation(**d) for d in out["delegate"]], need=need,
                                  questions=[Question(**q) for q in out["questions"]], reason=out["reason"][:200])

    def redelegate(self, snap, conflicts):
        out = self._call(REDELEGATE_SYSTEM, {"facts": facts(snap), "conflicts": conflicts}, REDELEGATE_SCHEMA)
        return Redelegate(role=out["role"], goal=out["goal"][:200], reason=out["reason"][:200])

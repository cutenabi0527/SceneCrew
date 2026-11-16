"""Supervisor와 전문 Agent(일정·인력·안내·검토)의 입력·출력 모양과 기본 구현.

각 Agent는 run(task, tools) 하나만 가진다. task는 Supervisor가 맡긴 목표와 사실, tools는 그 역할에 허용된
도구 묶음(tools.ToolBox)이다. 출력은 아래 산출물(Artifact) 모양이어야 하며, 흐름(graph.py)이 다시 검사한다.

여기 있는 Rule* 구현은 모델 없이 도는 규칙 기반 Agent다. 흐름·재위임·검사를 시험하고, 모델이 없을 때의
대체 경로로 쓴다. GMS 지시문 Agent는 같은 run 모양으로 specialists.py에 있다(#375)
(ai/plan의 RuleGenerator ↔ GmsGenerator와 같은 방식, CHANGE_AGENTS로 고른다).

Supervisor는 LLM이 다음 할 일을 정한다(GmsSupervisor). 누구에게 맡길지·재위임할지·질문으로 끝낼지는 모델이
판단하고, 등록된 Agent만·재위임 1회·검토 필수·마감 같은 울타리는 graph.py가 코드로 지킨다.
"""
import re
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Literal, Protocol

from pydantic import AwareDatetime, Field

from contract import AddTask, ChecklistItem, Operation, Question, Short, Step, Strict, Uuid, UpdateProgram, UpdateTask
from tools import ToolBox

Delegate = Literal["SCHEDULE", "STAFFING"]
NEED_REF = "need"                 # 인력 Agent가 '조치 업무'를 가리키는 이름
RESPONSE_TEMP_ID = "tmp-response"  # 일정 Agent가 만드는 조치 업무의 tempId


# ---- 산출물 ----

class Need(Strict):
    """보고에 대응하려면 필요한 조치 업무. Supervisor가 정하고 일정·인력 Agent가 함께 받는다."""
    title: Short
    zoneId: Uuid
    startAt: AwareDatetime
    endAt: AwareDatetime


class Delegation(Strict):
    role: Delegate
    goal: Short


class SupervisorDecision(Strict):
    """처음 판단. delegate가 비고 questions가 있으면 질문으로 끝낸다."""
    delegate: list[Delegation] = Field(default_factory=list, max_length=2)
    need: Need | None = None
    questions: list[Question] = Field(default_factory=list)
    reason: Short


class Redelegate(Strict):
    """충돌이 남았을 때 누구에게 무엇을 다시 맡길지. role이 null이면 재위임하지 않는다."""
    role: Delegate | None
    goal: Short
    reason: Short


class Affected(Strict):
    """보고로 영향받는 프로그램·업무(id)와 이유. 일정 Agent가 확인한 영향 범위다."""
    ref: Uuid
    reason: Short


class ScheduleArtifact(Strict):
    operations: list[Operation] = Field(default_factory=list)
    affected: list[Affected] = Field(default_factory=list)
    summary: Short
    questions: list[Question] = Field(default_factory=list)


class Assignment(Strict):
    taskRef: str          # NEED_REF 또는 기존 업무 id
    memberId: Uuid
    reason: Short


class StaffingArtifact(Strict):
    assignments: list[Assignment] = Field(default_factory=list)
    unassigned: list[str] = Field(default_factory=list)   # 맡길 사람을 찾지 못한 대상(NEED_REF 또는 업무 id)
    summary: Short
    questions: list[Question] = Field(default_factory=list)


def unassigned_question(snap, refs):
    """맡길 사람이 없는 결원 업무를 총괄에게 묻는다. 일부만 배정한 대응안을 READY로 내지 않는다."""
    names = ", ".join(f"'{snap.tasks[r].title}'" for r in refs if r in snap.tasks)
    return Question(key="staff", field="members",
                    question=f"{names} 업무를 맡을 수 있는 인원이 없습니다. 예비 인원이나 다른 그룹에서 지원할 사람을 알려 주세요."[:1000])


class Guide(Strict):
    ref: str              # tempId 또는 업무 id
    steps: list[Step] = Field(min_length=1, max_length=6)
    checklist: list[ChecklistItem] = Field(max_length=6)


class GuideArtifact(Strict):
    guides: list[Guide] = Field(default_factory=list)
    summary: Short


class ReviewArtifact(Strict):
    passed: bool
    findings: list[Short] = Field(default_factory=list)
    summary: Short


@dataclass
class AgentTask:
    """전문 Agent에게 맡기는 일. feedback은 재위임 때만 있다."""
    taskId: str
    role: str
    goal: str
    need: Need | None
    operations: list = field(default_factory=list)     # 지금까지 합친 연산(재위임·안내·검토에서 참고)
    feedback: dict | None = None


class Agent(Protocol):
    def run(self, task: AgentTask, tools: ToolBox): ...


# ---- 규칙 기반 Supervisor (모델 없이) ----

EQUIPMENT_WORDS = ("고장", "안 나", "안나", "꺼짐", "이상", "끊김", "작동")
STAFF_WORDS = ("결원", "불참", "못 옴", "못옴", "빠짐", "아파", "연락 안", "안 옴")


def _text(snap):
    p = snap.req.payload
    return " ".join([r.body for r in p.issue.reports] + [p.instruction])


def _types(snap):
    s = snap.req.payload.situation or {}
    return {s.get("primaryType")} | set(s.get("relatedTypes") or [])


def is_equipment(snap):
    """장비 이상 보고인지. 업무 이름(모델이 정함)이 아니라 상황 유형과 보고 문장으로 판단한다."""
    reports = " ".join(r.body for r in snap.req.payload.issue.reports)
    return "EQUIPMENT_ANOMALY" in _types(snap) or any(w in reports for w in EQUIPMENT_WORDS)


NO_GROUP = Question(key="workGroup", field="workGroups",
                    question="조치 업무를 맡길 직무 그룹이 없습니다. 직무 그룹을 먼저 만들어 주세요.")


def response_window(p, after_minutes, minutes):
    """조치 업무 (시작, 끝). 기준 시각에서 after_minutes 뒤부터 minutes 동안이다.

    BE는 업무가 준비 시작~철수 종료 안에 있는지 검사한다(OperationEngine.requireEventWindow). AI 요청에는 준비 시작이 없어,
    이미 저장된 업무 중 가장 이른 시작(BE가 준비 시작 이후로 검사한 값)과 운영 시작(openAt) 가운데 이른 것을 하한으로 쓴다.
    준비 중(사운드 체크 등)에 받은 보고는 기준 시각 그대로 센다. 공연 전날 보고처럼 '지금'이 운영 구간 밖이면 하한부터 센다.
    철수 직전이나 철수가 끝난 뒤의 요청처럼 그대로 두면 철수 종료(teardownEndAt)를 넘기면, 시간과 관계없이 대응안을 만들 수 있게
    철수 종료에 끝나도록 운영 구간 안으로 당긴다. 이때 업무는 기준 시각보다 앞설 수 있다(check_window가 허용한다).
    운영 구간이 minutes보다 짧으면 하한부터 철수 종료까지로 줄인다.
    """
    earliest = min([p.event.openAt] + [t.startAt for t in p.tasks])
    start = max(p.now, earliest) + timedelta(minutes=after_minutes)
    length = timedelta(minutes=minutes)
    if start + length > p.event.teardownEndAt:
        start = max(p.event.teardownEndAt - length, earliest)
    return start, min(start + length, p.event.teardownEndAt)


def default_need(snap, title="현장 조치"):
    """보고 구역에서 기준 시각(공연 전이면 운영 시작) 5분 뒤부터 20분 조치. 철수 종료를 넘기면 그 안으로 당긴다. 구역을 모르면 None."""
    p = snap.req.payload
    if not p.issue.zoneId:
        return None
    start, end = response_window(p, 5, 20)
    return Need(title=title, zoneId=p.issue.zoneId, startAt=start, endAt=end)


class RuleSupervisor:
    model_version = "change-rule-supervisor-v1"

    def decide(self, snap):
        text, types = _text(snap), _types(snap)
        if "STAFF_GAP" in types or any(w in text for w in STAFF_WORDS):
            return SupervisorDecision(delegate=[Delegation(role="STAFFING", goal="빠진 인원의 남은 업무를 맡을 사람 찾기")],
                                      reason="인력 공백 보고라 인력 Agent만 맡긴다")
        need = default_need(snap, "장비 조치" if is_equipment(snap) else "현장 조치")
        if need is None:
            return SupervisorDecision(questions=[Question(key="zone", field="issue.zoneId",
                                                          question="어느 구역에서 생긴 문제인지 알려 주세요.")],
                                      reason="구역을 몰라 조치 업무를 정할 수 없다")
        if not snap.groups:
            return SupervisorDecision(questions=[NO_GROUP], reason="조치 업무를 넣을 직무 그룹이 없다")
        return SupervisorDecision(delegate=[Delegation(role="SCHEDULE", goal="영향받는 일정과 조치 업무 시간 정하기"),
                                            Delegation(role="STAFFING", goal="조치 업무를 맡을 사람 찾기")],
                                  need=need, reason="일정 영향과 조치 인력을 함께 봐야 한다")

    def redelegate(self, snap, conflicts):
        return Redelegate(role="STAFFING", goal="겹치지 않는 다른 담당자 고르기",
                          reason=f"담당자 시간 겹침 {len(conflicts)}건")


# ---- 규칙 기반 전문 Agent ----

DELAY = re.compile(r"(\d{1,3})\s*분\s*(?:지연|늦|미루)")


class RuleSchedule:
    """조치 업무를 Need 시간에 추가하고, 지시에 'N분 지연'이 있으면 그 구역의 다음 프로그램과 딸린 업무를 미룬다.
    딸린 업무 중 이미 시작한 업무(진행 중이거나 시작 시각이 지남)는 시작을 옮기지 않고 끝 시각만 늘린다."""

    def run(self, task, tools):
        need = task.need
        ops = []
        if need:
            first = next((t for t in tools.get_related_tasks(zone_id=need.zoneId)), None)
            group = first.workGroupId if first else next(iter(tools.snap.groups), None)
            if group is None:
                return ScheduleArtifact(summary="조치 업무를 넣을 직무 그룹이 없음", questions=[NO_GROUP])
            ops.append(Operation(type="ADD_TASK", payload=AddTask(
                workGroupId=group, bundleKey="response", zoneId=need.zoneId, programId=None, title=need.title,
                startAt=need.startAt, endAt=need.endAt, steps=["현장 확인"], checklist=[], tempId=RESPONSE_TEMP_ID,
                assigneeId=None)))
        m = DELAY.search(_text(tools.snap))
        if m and need:
            minutes = timedelta(minutes=int(m.group(1)))
            nxt = next((x for x in tools.get_programs(need.zoneId) if x.startAt >= tools.snap.now), None)
            if nxt:
                ops.append(Operation(type="UPDATE_PROGRAM", payload=UpdateProgram(
                    programId=nxt.id, startAt=nxt.startAt + minutes, endAt=nxt.endAt + minutes)))
                for t in tools.get_related_tasks(program_id=nxt.id):
                    started = t.status == "IN_PROGRESS" or t.startAt < tools.snap.now
                    ops.append(Operation(type="UPDATE_TASK", payload=UpdateTask(taskId=t.id, endAt=t.endAt + minutes)
                                         if started else UpdateTask(taskId=t.id, startAt=t.startAt + minutes,
                                                                    endAt=t.endAt + minutes)))
        conflicts, _ = tools.check_time_overlap(ops)
        summary = f"조치 업무 {1 if need else 0}건 추가" + (f", 다음 프로그램 {m.group(1)}분 지연" if m and need else "")
        return ScheduleArtifact(operations=ops, summary=summary + (f", 겹침 {len(conflicts)}건 확인" if conflicts else ""))


class RuleStaffing:
    """조치 업무(Need)가 있으면 그 담당을, 없으면 빠진 인원의 남은 업무를 맡을 비어 있는 가용 팀원을 고른다.
    예비 인원은 뒤로 미룬다. 재위임이면 일정 변경을 적용한 시간표로 다시 고르고, 겹침이 난 사람은 뺀다."""

    def run(self, task, tools):
        ops = task.operations if task.feedback else []
        exclude = set((task.feedback or {}).get("excludeMembers", []))
        out, used, missing = [], set(), []
        if task.need:
            ms = tools.get_eligible_members(task.need.startAt, task.need.endAt, ops, exclude | used)
            if ms:
                out.append(Assignment(taskRef=NEED_REF, memberId=ms[0].id, reason=f"팀원 {ms[0].crewNo}이 그 시간에 비어 있음"))
                used.add(ms[0].id)
        snap = tools.snap
        gone = {m.id for m in snap.members.values() if not m.available} if task.need is None else set()
        for t in sorted(snap.tasks.values(), key=lambda x: x.startAt):
            if t.assigneeId in gone and t.status == "TODO" and t.startAt >= snap.now:
                ms = tools.get_eligible_members(t.startAt, t.endAt, ops, exclude | used)
                if ms:
                    out.append(Assignment(taskRef=t.id, memberId=ms[0].id, reason=f"'{t.title}' 시간에 팀원 {ms[0].crewNo}이 비어 있음"))
                    used.add(ms[0].id)
                else:
                    missing.append(t.id)
        if not out:
            return StaffingArtifact(summary="맡길 수 있는 가용 인원이 없음", questions=[Question(
                key="staff", field="members", question="지금 투입할 수 있는 인원이 없습니다. 예비 인원이나 다른 그룹에서 지원할 사람을 알려 주세요.")])
        if missing:
            return StaffingArtifact(assignments=out, unassigned=missing, questions=[unassigned_question(snap, missing)],
                                    summary=f"담당 후보 {len(out)}명 선정, 결원 업무 {len(missing)}건은 맡을 사람 없음")
        if task.feedback:
            names = ", ".join(f"팀원 {snap.members[a.memberId].crewNo}" for a in out)
            return StaffingArtifact(assignments=out, summary=f"겹침을 피해 다시 선정: {names}")
        return StaffingArtifact(assignments=out, summary=f"담당 후보 {len(out)}명 선정")


EQUIPMENT_STEPS = ["고장 장비 상태 확인과 전원·연결 점검", "예비 장비 준비", "교체 후 작동 확인", "총괄에 결과 보고"]
GENERIC_STEPS = ["현장 상황 확인", "필요한 조치 수행", "총괄에 결과 보고"]


class RuleGuide:
    """새 업무에 수행 절차와 체크 항목을 붙인다. 장비 이상 보고면 장비 절차를 쓴다(업무 이름의 단어로 고르지 않는다)."""

    def run(self, task, tools):
        guides = []
        equipment = is_equipment(tools.snap)
        for op in task.operations:
            if op.type != "ADD_TASK":
                continue
            tools.get_task_context(op.payload.tempId, task.operations)   # 근거(구역) 기록
            guides.append(Guide(ref=op.payload.tempId, steps=EQUIPMENT_STEPS if equipment else GENERIC_STEPS, checklist=[
                ChecklistItem(key="checked", text="현장 상태를 직접 확인함", required=True),
                ChecklistItem(key="reported", text="총괄에 결과를 보고함", required=True)]))
        return GuideArtifact(guides=guides, summary=f"새 업무 {len(guides)}건에 절차·체크 항목 작성")


class RuleReview:
    """작성한 Agent의 판단과 별개로 참조·시간 범위·담당 겹침을 다시 검사한다."""

    def run(self, task, tools):
        ops = task.operations
        findings = tools.check_references(ops) + tools.check_window(ops)
        conflicts, projected = tools.check_time_overlap(ops)
        findings += [c.describe(tools.snap, projected) for c in conflicts]
        if not ops:
            findings.append("제안할 연산이 없음")
        return ReviewArtifact(passed=not findings, findings=[f[:200] for f in findings],
                              summary="참조·시간·담당 겹침 검사 통과" if not findings else f"문제 {len(findings)}건 발견")


def rule_agents():
    return {"SCHEDULE": RuleSchedule(), "STAFFING": RuleStaffing(), "GUIDE": RuleGuide(), "REVIEW": RuleReview()}

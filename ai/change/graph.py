"""AI 대응안 Multi-agent 실행 구조(S15P31A202-374). LangGraph로 Supervisor와 전문 Agent를 잇는다.

    START → supervisor ─┬─(Send)→ schedule ─┐
                        ├─(Send)→ staffing ─┴→ merge ─┬─ 충돌·재위임 전 → redelegate ─(Send)→ schedule|staffing → merge
                        └─ 질문만 → END               ├─ 담당 후보 없음 → END(질문)
                                                      └→ guide → review → END

- Supervisor(LLM)가 누구에게 무엇을 맡길지 정한다. 서로 의존하지 않는 일정·인력은 같은 단계에서 동시에 실행된다(Send).
- 합친 결과의 담당 시간 겹침은 코드(tools.time_conflicts)가 찾는다. 남으면 Supervisor가 한 담당에게 다시 맡긴다.
- 울타리(코드): 등록된 전문 Agent만 부른다, 재위임은 최대 1회, 검토(Review)를 거치지 않고는 READY가 될 수 없다,
  단계마다 마감을 확인한다, Supervisor 출력이 형식에 맞지 않으면 규칙 Supervisor의 판단으로 대신한다.
- 결과 coordination에는 작업 그래프·재위임 이유·충돌 해결·역할별 기여만 남긴다(모델 원문·프롬프트 없음).
"""
import operator
import threading
from datetime import datetime, timezone
from typing import Annotated, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import Send
from pydantic import ValidationError

import contract
from agents import (NEED_REF, RESPONSE_TEMP_ID, AgentTask, Delegation, GuideArtifact, Redelegate, ReviewArtifact, RuleSupervisor,
                    ScheduleArtifact, StaffingArtifact, SupervisorDecision, default_need, rule_agents)
from contract import AddTask, AssignTask, Operation
from supervisor import SupervisorUnavailable
from agents import NO_GROUP
from tools import Evidence, Snapshot, ToolBox, ToolNotAllowed, time_conflicts

MAX_REDELEGATIONS = 1
ARTIFACTS = {"SCHEDULE": ScheduleArtifact, "STAFFING": StaffingArtifact, "GUIDE": GuideArtifact, "REVIEW": ReviewArtifact}


class DeadlinePassed(Exception):
    pass


class AgentFailed(Exception):
    def __init__(self, role, reason):
        super().__init__(f"{role}: {reason}")
        self.role = role
        self.reason = reason


class AgentUnavailable(Exception):
    """전문 Agent가 모델에 연결하지 못함. #375의 GMS Agent가 연결·응답 장애에 던진다(AI_UNAVAILABLE)."""


class State(TypedDict, total=False):
    decision: SupervisorDecision
    schedule: ScheduleArtifact
    staffing: StaffingArtifact
    operations: list
    conflicts: list
    redelegations: int
    guide: GuideArtifact
    review: ReviewArtifact
    questions: list
    pending: Redelegate
    events: Annotated[list, operator.add]   # coordination 기록. 동시에 실행된 노드가 함께 더한다


class Run:
    """요청 하나의 실행. 그래프는 요청마다 새로 만들고, 스냅샷·도구·근거는 노드가 함께 본다."""

    def __init__(self, req, supervisor, agents, clock):
        self.req = req
        self.snap = Snapshot(req)
        self.supervisor = supervisor
        self.fallback = RuleSupervisor()
        self.agents = agents
        self.clock = clock
        self.evidence = Evidence()
        self._lock = threading.Lock()
        self._next = 1
        for r in req.payload.issue.reports:
            self.evidence.add("REPORT", r.id)

    # ---- 공통 ----
    def task_id(self):
        with self._lock:
            self._next += 1
            return f"t{self._next}"

    def check_deadline(self):
        if contract.deadline_passed(self.req, self.clock()):
            raise DeadlinePassed()

    def call_agent(self, role, task):
        """전문 Agent 하나를 허용 도구로 실행하고 산출물 모양을 검사한다."""
        self.check_deadline()
        tools = ToolBox(self.snap, role, self.evidence)
        try:
            out = self.agents[role].run(task, tools)
        except (AgentUnavailable, DeadlinePassed):
            raise
        except ToolNotAllowed as e:
            raise AgentFailed(role, f"허용되지 않은 도구 {e.tool}") from None
        except Exception as e:
            raise AgentFailed(role, type(e).__name__) from None
        if not isinstance(out, ARTIFACTS[role]):
            try:
                out = ARTIFACTS[role].model_validate(out)
            except ValidationError:
                raise AgentFailed(role, "산출물 형식 오류") from None
        self.check_deadline()
        return out

    def node_event(self, task_id, role, status, summary, parent="t1"):
        return {"type": "node", "taskId": task_id, "parentTaskId": parent, "role": role, "status": status,
                "summary": summary[:200]}

    # ---- 노드 ----
    def supervisor_node(self, state):
        self.check_deadline()
        note = ""
        try:
            decision = self.supervisor.decide(self.snap)
            if decision.need and decision.need.zoneId not in self.snap.zones:
                raise ValueError("need.zoneId")
            if len({d.role for d in decision.delegate}) != len(decision.delegate):
                raise ValueError("delegate 중복")
            if any(d.role == "SCHEDULE" for d in decision.delegate) and decision.need is None:
                decision = decision.model_copy(update={"need": default_need(self.snap)})
            if not decision.delegate and not decision.questions:
                # 맡길 담당도 질문도 없으면 검토 없이 끝나 REVIEW_REJECTED가 된다(운영 2026-11-12, "현장 점검 필요 → 위임: 없음").
                # 조치 업무를 정했으면 그 업무를 맡을 사람을 인력 Agent에게 찾게 하고, 아무것도 정하지 않았으면 기본 판단을 쓴다.
                if decision.need is None:
                    decision = self.fallback.decide(self.snap)
                    note = " (Supervisor가 맡길 담당을 정하지 않아 기본 판단 사용)"
                else:
                    decision = decision.model_copy(update={
                        "delegate": [Delegation(role="STAFFING", goal="조치 업무를 맡을 사람 찾기")]})
                    note = " (조치 업무만 정해 인력 Agent에게 담당자 찾기를 맡김)"
        except SupervisorUnavailable:
            raise
        except (ValidationError, ValueError, KeyError, TypeError, ArithmeticError):
            decision = self.fallback.decide(self.snap)
            note = " (Supervisor 출력 형식 오류로 기본 판단 사용)"
        if decision.need and not self.snap.groups:   # 조치 업무를 넣을 그룹이 없으면 맡기지 않고 묻는다
            decision = SupervisorDecision(questions=[NO_GROUP], reason="조치 업무를 넣을 직무 그룹이 없다")
        status = "NEEDS_INFO" if decision.questions and not decision.delegate else "SUCCEEDED"
        roles = "·".join(d.role for d in decision.delegate) or "없음"
        return {"decision": decision, "redelegations": 0, "events": [
            self.node_event("t1", "SUPERVISOR", status, f"{decision.reason} → 위임: {roles}{note}", parent=None)]}

    def after_supervisor(self, state):
        d = state["decision"]
        if not d.delegate:
            return END
        return [Send(x.role.lower(), {"taskId": self.task_id(), "goal": x.goal, "need": d.need, "feedback": None})
                for x in d.delegate]

    def specialist(self, role):
        def run(arg):
            state_ops = arg.get("operations", [])
            task = AgentTask(taskId=arg["taskId"], role=role, goal=arg["goal"], need=arg.get("need"),
                             operations=state_ops, feedback=arg.get("feedback"))
            out = self.call_agent(role, task)
            status = "NEEDS_INFO" if out.questions and not (getattr(out, "operations", None) or getattr(out, "assignments", None)) \
                else "SUCCEEDED"
            parent = "t1" if not arg.get("feedback") else arg["feedback"]["fromTaskId"]
            events = [self.node_event(arg["taskId"], role, status, out.summary, parent)]
            if arg.get("redelegation"):
                events.append({"type": "redelegation", **arg["redelegation"]})
            return {role.lower(): out, "events": events}
        return run

    def merge_node(self, state):
        """일정·인력 산출물을 연산 목록으로 합치고 담당 시간 겹침을 계산한다(코드)."""
        self.check_deadline()
        decision = state["decision"]
        schedule, staffing = state.get("schedule"), state.get("staffing")
        ops = [op.model_copy(deep=True) for op in (schedule.operations if schedule else [])]
        if decision.need and not any(op.type == "ADD_TASK" and op.payload.tempId == RESPONSE_TEMP_ID for op in ops):
            ops.insert(0, self.response_task(decision.need))   # 일정 Agent 없이 인력만 맡긴 경우
        for a in (staffing.assignments if staffing else []):
            if a.taskRef == NEED_REF:
                for i, op in enumerate(ops):
                    if op.type == "ADD_TASK" and op.payload.tempId == RESPONSE_TEMP_ID:
                        ops[i] = Operation(type="ADD_TASK", payload=op.payload.model_copy(update={"assigneeId": a.memberId}))
            elif a.taskRef in self.snap.tasks:
                ops.append(Operation(type="ASSIGN_TASK", payload=AssignTask(
                    taskId=a.taskRef, fromMemberId=self.snap.tasks[a.taskRef].assigneeId, toMemberId=a.memberId)))
        questions = [q for art in (schedule, staffing) if art for q in art.questions]
        unassigned = (decision.need and staffing is not None and not any(a.taskRef == NEED_REF for a in staffing.assignments)) \
            or bool(staffing and staffing.unassigned)   # 결원 업무 일부만 배정했으면 READY로 내지 않는다
        if questions and (not ops or unassigned):
            return {"operations": ops, "conflicts": [], "questions": questions}
        projected = self.snap.project(ops)
        found = time_conflicts(projected)
        # 모델에 보내는 문장에는 팀원 번호 대신 '담당자 A·B'를 쓴다(팀원 식별 정보를 모델에 보내지 않는다)
        labels = {}
        for c in found:
            labels.setdefault(c.memberId, f"담당자 {chr(ord('A') + len(labels))}")
        conflicts = [{"members": [c.memberId], "refs": list(c.refs), "text": c.describe(self.snap, projected, labels[c.memberId])}
                     for c in found]
        events = []
        before = state.get("conflicts") or []
        if before and not conflicts:
            events.append({"type": "resolved", "refs": sorted({r for c in before for r in c["refs"]}),
                           "resolution": "재위임 결과로 담당 시간 겹침 해소"})
        return {"operations": ops, "conflicts": conflicts, "questions": [], "events": events}

    def response_task(self, need):
        """일정 Agent 없이 조치 업무가 필요할 때 쓰는 기본 업무. 그룹이 없는 경우는 supervisor_node에서 이미 질문으로 끝난다."""
        first = next((t for t in self.snap.tasks.values() if t.zoneId == need.zoneId), None)
        group = first.workGroupId if first else next(iter(self.snap.groups))
        return Operation(type="ADD_TASK", payload=AddTask(
            workGroupId=group, bundleKey="response", zoneId=need.zoneId, programId=None, title=need.title,
            startAt=need.startAt, endAt=need.endAt, steps=["현장 확인"], checklist=[], tempId=RESPONSE_TEMP_ID,
            assigneeId=None))

    def after_merge(self, state):
        if state.get("questions"):
            return END
        if state.get("conflicts") and state.get("redelegations", 0) < MAX_REDELEGATIONS:
            return "redelegate"
        return "guide"

    def redelegate_node(self, state):
        self.check_deadline()
        texts = [c["text"] for c in state["conflicts"]]
        try:
            r = self.supervisor.redelegate(self.snap, texts)
        except SupervisorUnavailable:
            raise
        except (ValidationError, ValueError, KeyError, TypeError, ArithmeticError):
            r = self.fallback.redelegate(self.snap, texts)
        # 처음 맡긴 담당에게만 다시 맡긴다. 맡기지 않은 담당을 새로 부르면 그 결과를 합칠 앞 단계가 없다.
        if r.role is not None and r.role not in {d.role for d in state["decision"].delegate}:
            r = Redelegate(role=None, goal=r.goal, reason=f"처음 맡기지 않은 담당({r.role})이라 재위임하지 않음")
        return {"redelegations": state.get("redelegations", 0) + 1, "pending": r}

    def after_redelegate(self, state):
        r = state["pending"]
        if r.role is None:
            return "guide"
        tid = self.task_id()
        schedule = state.get("schedule")
        feedback = {"fromTaskId": "t1", "reason": r.reason, "conflicts": [c["text"] for c in state["conflicts"]],
                    "excludeMembers": sorted({m for c in state["conflicts"] for m in c["members"]})}
        ops = schedule.operations if (r.role == "STAFFING" and schedule) else state["operations"]
        return [Send(r.role.lower(), {"taskId": tid, "goal": r.goal, "need": state["decision"].need, "feedback": feedback,
                                      "operations": ops, "redelegation": {"taskId": tid, "reason": r.reason}})]

    def guide_node(self, state):
        ops = state["operations"]
        tid = self.task_id()
        out = self.call_agent("GUIDE", AgentTask(taskId=tid, role="GUIDE", goal="새 조치 업무의 절차와 체크 항목 작성",
                                                 need=state["decision"].need, operations=ops))
        guides = {g.ref: g for g in out.guides}
        merged = []
        for op in ops:
            if op.type == "ADD_TASK" and op.payload.tempId in guides:
                g = guides[op.payload.tempId]
                op = Operation(type="ADD_TASK", payload=op.payload.model_copy(update={"steps": g.steps, "checklist": g.checklist}))
            merged.append(op)
        return {"operations": merged, "guide": out, "events": [self.node_event(tid, "GUIDE", "SUCCEEDED", out.summary)]}

    def review_node(self, state):
        tid = self.task_id()
        out = self.call_agent("REVIEW", AgentTask(taskId=tid, role="REVIEW", goal="참조·시간·담당 겹침 독립 검토",
                                                  need=state["decision"].need, operations=state["operations"]))
        return {"review": out, "events": [self.node_event(tid, "REVIEW", "SUCCEEDED" if out.passed else "FAILED", out.summary)]}

    # ---- 그래프 ----
    def build(self):
        g = StateGraph(State)
        g.add_node("supervisor", self.supervisor_node)
        g.add_node("schedule", self.specialist("SCHEDULE"))
        g.add_node("staffing", self.specialist("STAFFING"))
        g.add_node("merge", self.merge_node)
        g.add_node("redelegate", self.redelegate_node)
        g.add_node("guide", self.guide_node)
        g.add_node("review", self.review_node)
        g.add_edge(START, "supervisor")
        g.add_conditional_edges("supervisor", self.after_supervisor, ["schedule", "staffing", END])
        g.add_edge("schedule", "merge")
        g.add_edge("staffing", "merge")
        g.add_conditional_edges("merge", self.after_merge, ["redelegate", "guide", END])
        g.add_conditional_edges("redelegate", self.after_redelegate, ["schedule", "staffing", "guide"])
        g.add_edge("guide", "review")
        g.add_edge("review", END)
        return g.compile()

    def execute(self):
        return self.build().invoke({"events": []}, config={"recursion_limit": 20})


def coordination(events, final_status):
    nodes = sorted((e for e in events if e["type"] == "node"), key=lambda e: int(e["taskId"][1:]))
    graph = [{"taskId": e["taskId"], "parentTaskId": e["parentTaskId"], "role": e["role"], "status": e["status"]} for e in nodes]
    if graph and final_status != "SUCCEEDED":
        graph[0]["status"] = final_status
    return {
        "graph": graph,
        "redelegations": [{"taskId": e["taskId"], "reason": e["reason"]} for e in events if e["type"] == "redelegation"],
        "resolvedConflicts": [{"refs": e["refs"], "resolution": e["resolution"]} for e in events if e["type"] == "resolved"],
        "contributions": [{"role": e["role"], "summary": e["summary"]} for e in nodes],
    }


def default_clock():
    return datetime.now(timezone.utc)


def run(raw, supervisor=None, agents=None, clock=default_clock):
    """요청 하나를 실행해 성공(READY/NEEDS_INFO) 또는 실패 envelope를 돌려준다."""
    try:
        req = contract.parse_request(raw)
    except contract.ContractViolation as e:
        return contract.failure(None, "INVALID_REQUEST", "요청 형식이 계약과 다릅니다.",
                                {"issues": [i["path"] for i in e.issues][:20]})
    if contract.deadline_passed(req, clock()):
        return contract.failure(req, "AI_TIMEOUT", "마감이 지나 대응안을 만들지 않았습니다.")
    supervisor = supervisor or RuleSupervisor()
    r = Run(req, supervisor, agents or rule_agents(), clock)
    try:
        final = r.execute()
    except DeadlinePassed:
        return contract.failure(req, "AI_TIMEOUT", "마감 안에 대응안을 끝내지 못했습니다.")
    except (SupervisorUnavailable, AgentUnavailable):
        return contract.failure(req, "AI_UNAVAILABLE", "대응안 모델에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요.")
    except AgentFailed as e:
        return contract.failure(req, "AI_REVIEW_FAILED", "전문 Agent 결과가 계약과 맞지 않아 대응안을 만들지 않았습니다.",
                                {"reason": "INVALID_ARTIFACT", "stage": e.role, "cause": e.reason})
    model_version = version(supervisor, r.agents)
    decision = final["decision"]
    events = final.get("events", [])
    base = {"evidenceRefs": r.evidence.dump(), "simulationRefs": [], "situation": req.payload.situation,
            "alternatives": []}
    questions = final.get("questions") or (decision.questions if not decision.delegate else [])
    if questions:
        result = {"status": "NEEDS_INFO", "operations": [], "rationale": decision.reason,
                  "questions": [q.model_dump() for q in questions], "coordination": coordination(events, "NEEDS_INFO"), **base}
        return _envelope(req, result, model_version)
    review = final.get("review")
    if review is None or not review.passed:
        return contract.failure(req, "AI_REVIEW_FAILED", "검토 Agent가 대응안을 통과시키지 않았습니다.",
                                {"reason": "REVIEW_REJECTED", "findings": (review.findings if review else [])[:10],
                                 "coordination": coordination(events, "FAILED")})
    rationale = " / ".join([decision.reason] + [a.summary for a in (final.get("schedule"), final.get("staffing")) if a])
    result = {"status": "READY", "operations": [op.dump() for op in final["operations"]], "rationale": rationale[:1000],
              "questions": [], "coordination": coordination(events, "SUCCEEDED"), **base}
    return _envelope(req, result, model_version)


def version(supervisor, agents):
    """Supervisor 버전에 실제로 모델을 부른 전문 Agent의 모델을 덧붙인다(예: change-rule-supervisor-v1+agents-gpt-4o).
    규칙 전문 Agent만 썼으면 Supervisor 버전 그대로다."""
    base = getattr(supervisor, "model_version", "change")
    models = sorted({m for m in (getattr(a, "model", None) for a in agents.values()) if m})
    return (base + "".join(f"+agents-{m}" for m in models))[:200]


def _envelope(req, result, model_version):
    try:
        return contract.success(req, result, model_version)
    except ValidationError as e:
        return contract.failure(req, "AI_REVIEW_FAILED", "대응안 결과가 계약과 맞지 않아 반환하지 않았습니다.",
                                {"reason": "INVALID_ARTIFACT", "issues": [i["path"] for i in contract.schema_issues(e, "result")][:20]})

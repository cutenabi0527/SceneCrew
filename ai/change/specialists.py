"""GMS 전문 Agent(일정·인력·안내·검토, S15P31A202-375). #374 실행 구조의 규칙 Agent(agents.Rule*)와 같은 run 모양이다.

Agent마다 지시문(system)·입력(user JSON)·출력(json_schema)·허용 도구가 다르다. 공통 순서는 같다.
1. 허용 도구로 스냅샷의 사실을 읽는다(읽은 id는 근거 evidenceRefs가 된다).
2. 모델에 판단에 필요한 사실만 보낸다. 팀원·업무·프로그램 id와 팀원 번호 대신 별칭(후보 A, P1, T1…)을 쓴다.
3. 모델 출력을 별칭에서 실제 id로 되돌리고 코드로 다시 검사한다. 목록 밖 별칭·근거 없는 지연·겹치는 배정은 버린다.
4. 출력 형식이 틀리면 같은 역할의 규칙 Agent 판단으로 대신하고 summary에 남긴다. 모델 연결 실패는 AgentUnavailable이다.

모델은 의미 해석과 선택만 한다. 시간 겹침·참조·시간 범위 계산은 도구(코드)가 한다(AI01).
"""
import json
import re
from datetime import timedelta

from pydantic import ValidationError

from agents import (NEED_REF, NO_GROUP, RESPONSE_TEMP_ID, Affected, Assignment, Guide, GuideArtifact, ReviewArtifact,
                    RuleGuide, RuleReview, RuleSchedule, RuleStaffing, ScheduleArtifact, StaffingArtifact, _text, _types,
                    unassigned_question)
from contract import AddTask, AssignTask, CancelTask, ChecklistItem, Operation, Question, UpdateInstructions, UpdateProgram, \
    UpdateTask
from graph import AgentUnavailable
from tools import hm, overlaps

MAX_CALL_SECONDS = 60
MAX_DELAY_MINUTES = 120
MAX_MODEL_TARGETS = 10      # 인력 모델에 보내는 대상 수(출력 assignments maxItems와 같다). 넘는 대상은 코드가 배정한다
FALLBACK_NOTE = " (모델 출력 형식 오류로 규칙 판단 사용)"
PARSE_ERRORS = (ValidationError, ValueError, KeyError, TypeError, AttributeError)

COMMON = """모든 글은 한국어로 쓴다. 입력에 없는 사람·장비 모델명·수치·번호를 지어내지 않는다. JSON 객체 하나만 출력한다."""


def _strict(name, properties):
    return {"name": name, "strict": True, "schema": {"type": "object", "additionalProperties": False,
                                                    "required": list(properties), "properties": properties}}


def _obj(properties):
    return {"type": "object", "additionalProperties": False, "required": list(properties), "properties": properties}


STR, INT, BOOL = {"type": "string"}, {"type": "integer"}, {"type": "boolean"}
QUESTIONS = {"type": "array", "maxItems": 3, "items": _obj({"key": STR, "field": STR, "question": STR})}


def _label(i):
    return chr(ord("A") + i) if i < 26 else f"Z{i - 25}"


def norm(label):
    """모델이 별칭을 조금 다르게 써도(후보A, A, p1, "NEED ") 같은 별칭으로 본다."""
    return re.sub(r"\s|후보", "", str(label)).upper()


def lookup(table):
    return {norm(k): v for k, v in table.items()}


def _minutes(a, b):
    return int((b - a).total_seconds() // 60)


def _questions(raw):
    out = []
    for q in raw or []:
        key = re.sub(r"[^A-Za-z0-9_-]", "", str(q.get("key", "")))[:40] or f"q{len(out) + 1}"
        out.append(Question(key=key, field=str(q["field"])[:200] or "instruction", question=str(q["question"])[:1000]))
    return out


def report_facts(snap):
    """모든 전문 Agent가 받는 보고 사실. 개인 정보 없음."""
    p = snap.req.payload
    return {"now": hm(p.now), "reports": [r.body for r in p.issue.reports], "instruction": p.instruction,
            "situationTypes": sorted(t for t in _types(snap) if t),
            "reportZone": snap.zones[p.issue.zoneId].name if p.issue.zoneId in snap.zones else None}


class GmsAgent:
    """전문 Agent 공통: 모델 호출 1회와 형식 오류 시 규칙 Agent 대체."""
    role = ""
    system = ""
    schema = {}

    def __init__(self, chat=None, model_var="GMS_CHANGE_MODEL"):
        self._chat = chat
        self.model_var = model_var
        self.model = None
        self.usage = []

    def call(self, user):
        if self._chat is None:
            from supervisor import _load_gms
            self._chat = _load_gms().chat_json
        try:
            out, model, usage = self._chat([{"role": "system", "content": self.system},
                                            {"role": "user", "content": json.dumps(user, ensure_ascii=False, default=str)}],
                                           temperature=0.2, retries=0, model_var=self.model_var,
                                           response_format={"type": "json_schema", "json_schema": self.schema},
                                           timeout=MAX_CALL_SECONDS)
        except Exception as e:
            if type(e).__name__ == "GmsError":
                raise AgentUnavailable(f"{self.role}: {e}") from None
            raise
        self.model = model
        self.usage.append(usage)
        return out

    def fallback(self):
        raise NotImplementedError

    def run(self, task, tools):
        try:
            return self.decide(task, tools)
        except PARSE_ERRORS:
            out = self.fallback().run(task, tools)
            return out.model_copy(update={"summary": (out.summary + FALLBACK_NOTE)[:200]})


# ---- 일정 ----

SCHEDULE_SYSTEM = f"""너는 콘서트 현장 운영의 일정 담당(Schedule Agent)이다. 현장 보고 때문에 영향받는 공연(프로그램)·업무 시간을 판단하고,
꼭 바꿔야 할 시간만 제안한다. 담당자는 정하지 않는다(인력 담당이 따로 정한다). {COMMON}

입력
- reports·instruction: 현장 보고와 총괄이 확인한 지시. now는 기준 시각(한국 시간)이다.
- response: 이미 정해진 조치 업무(이름·구역·시간). 이 시간은 바꾸지 않는다.
- programs: 보고 구역의 아직 끝나지 않은 프로그램(P1…). started=true면 이미 시작했다.
- tasks: 보고 구역 또는 조치 시간과 겹치는 진행 전·진행 중 업무(T1…). program은 딸린 프로그램 번호다.
- workGroups: 직무 그룹(G1…)과 보고 구역에서 맡은 업무 수(zoneTasks).

판단 기준
- 프로그램은 지시나 보고에 지연 시간이 분 단위 숫자로 적혀 있을 때만 미룬다. 그 숫자를 그대로 쓴다.
  숫자가 없으면 미루지 않는다. 미룰지 총괄 판단이 꼭 필요할 때만 questions로 묻는다.
- 미룬 프로그램에 딸린 업무는 코드가 함께 옮긴다. 다른 업무를 따로 옮기지 않는다.
- affected에는 조치 시간과 겹치거나 보고된 문제로 진행이 어려워지는 프로그램·업무만 넣고, 이유를 한 문장으로 쓴다.
- responseGroup에는 조치 업무를 넣기에 맞는 직무 그룹 하나를 고른다. 보고 내용과 그룹 이름, 그 구역에서 맡은 업무를 함께 본다.
- 목록에 없는 P·T·G 번호를 만들지 않는다."""

SCHEDULE_SCHEMA = _strict("schedule_artifact", {
    "responseGroup": STR,
    "delays": {"type": "array", "maxItems": 3, "items": _obj({"program": STR, "minutes": INT, "reason": STR})},
    "affected": {"type": "array", "maxItems": 10, "items": _obj({"ref": STR, "reason": STR})},
    "questions": QUESTIONS})


def stated_minutes(snap):
    """보고·지시에 적힌 분 단위 숫자. 근거 없는 지연을 막는 데 쓴다."""
    return {int(n) for n in re.findall(r"(\d{1,3})\s*분", _text(snap))}


def delay_ops(tools, program, minutes):
    """프로그램 하나와 딸린 업무를 minutes만큼 미루는 연산. 이미 시작한 것은 끝 시각만 늘린다."""
    now = tools.snap.now
    d = timedelta(minutes=minutes)
    started = program.startAt < now
    ops = [Operation(type="UPDATE_PROGRAM", payload=UpdateProgram(programId=program.id, endAt=program.endAt + d) if started
                     else UpdateProgram(programId=program.id, startAt=program.startAt + d, endAt=program.endAt + d))]
    for t in tools.get_related_tasks(program_id=program.id):
        begun = t.status == "IN_PROGRESS" or t.startAt < now
        ops.append(Operation(type="UPDATE_TASK", payload=UpdateTask(taskId=t.id, endAt=t.endAt + d) if begun
                             else UpdateTask(taskId=t.id, startAt=t.startAt + d, endAt=t.endAt + d)))
    return ops


class GmsSchedule(GmsAgent):
    role, system, schema = "SCHEDULE", SCHEDULE_SYSTEM, SCHEDULE_SCHEMA

    def fallback(self):
        return RuleSchedule()

    def decide(self, task, tools):
        need, snap = task.need, tools.snap
        if need is None:
            return RuleSchedule().run(task, tools)
        if not snap.groups:
            return ScheduleArtifact(summary="조치 업무를 넣을 직무 그룹이 없음", questions=[NO_GROUP])
        programs = [x for x in tools.get_programs(need.zoneId) if x.endAt > snap.now]
        related = {t.id: t for t in tools.get_related_tasks(zone_id=need.zoneId)}
        related.update({t.id: t for t in tools.get_related_tasks(start=need.startAt, end=need.endAt)})
        tasks = sorted(related.values(), key=lambda t: t.startAt)[:20]
        P = {f"P{i + 1}": x for i, x in enumerate(programs)}
        T = {f"T{i + 1}": t for i, t in enumerate(tasks)}
        G = {f"G{i + 1}": g for i, g in enumerate(snap.groups.values())}
        p_label = {x.id: k for k, x in P.items()}
        zone_tasks = {g.id: sum(1 for t in related.values() if t.workGroupId == g.id and t.zoneId == need.zoneId)
                      for g in snap.groups.values()}
        user = {**report_facts(snap),
                "response": {"title": need.title, "zone": snap.zones[need.zoneId].name, "startAt": hm(need.startAt),
                             "endAt": hm(need.endAt)},
                "programs": [{"ref": k, "name": x.name, "startAt": hm(x.startAt), "endAt": hm(x.endAt),
                              "started": x.startAt < snap.now} for k, x in P.items()],
                "tasks": [{"ref": k, "title": t.title, "zone": snap.zones[t.zoneId].name, "startAt": hm(t.startAt),
                           "endAt": hm(t.endAt), "status": t.status, "program": p_label.get(t.programId)} for k, t in T.items()],
                "workGroups": [{"ref": k, "name": g.name, "zoneTasks": zone_tasks[g.id]} for k, g in G.items()]}
        out = self.call(user)

        P, T, G = lookup(P), lookup(T), lookup(G)
        group = G.get(norm(out["responseGroup"]))
        notes = []
        if group is None:
            first = next(iter(tools.get_related_tasks(zone_id=need.zoneId)), None)
            group = snap.groups[first.workGroupId] if first else next(iter(snap.groups.values()))
            notes.append("그룹 번호 오류로 기본 그룹")
        ops = [Operation(type="ADD_TASK", payload=AddTask(
            workGroupId=group.id, bundleKey="response", zoneId=need.zoneId, programId=None, title=need.title,
            startAt=need.startAt, endAt=need.endAt, steps=["현장 확인"], checklist=[], tempId=RESPONSE_TEMP_ID,
            assigneeId=None))]
        allowed = stated_minutes(snap)
        delayed = []
        for d in out["delays"]:
            x, minutes = P.get(norm(d["program"])), int(d["minutes"])
            if x is None or x.id in {p.id for p, _ in delayed}:
                continue
            if minutes not in allowed or not 0 < minutes <= MAX_DELAY_MINUTES:
                notes.append(f"근거 없는 {minutes}분 지연 제외")
                continue
            delayed.append((x, minutes))
            ops += delay_ops(tools, x, minutes)
        affected = []
        for a in out["affected"]:
            ref = P.get(norm(a["ref"])) or T.get(norm(a["ref"]))
            if ref is not None and ref.id not in {x.ref for x in affected}:
                affected.append(Affected(ref=ref.id, reason=str(a["reason"])[:200] or "영향"))
        conflicts, _ = tools.check_time_overlap(ops)
        parts = [f"조치 업무 1건 추가({group.name})"] + [f"'{x.name}' {m}분 지연" for x, m in delayed]
        if affected:
            parts.append(f"영향 {len(affected)}건")
        if conflicts:
            parts.append(f"겹침 {len(conflicts)}건 확인")
        summary = ", ".join(parts) + (f" ({'; '.join(notes)})" if notes else "")
        return ScheduleArtifact(operations=ops, affected=affected, summary=summary[:200],
                                questions=_questions(out["questions"]))


# ---- 인력 ----

STAFFING_SYSTEM = f"""너는 콘서트 현장 운영의 인력 담당(Staffing Agent)이다. 맡길 업무(targets)마다 후보 중 한 명을 고른다.
후보는 코드가 이미 '그 시간에 비어 있고 가용한 팀원'만 걸렀다. 시간을 바꾸거나 업무를 새로 만들지 않는다. {COMMON}

입력
- targets: 맡길 업무(W1…, 조치 업무는 NEED). 이름·구역·직무 그룹·시간과 고를 수 있는 후보 목록이 있다.
- candidates: 후보(후보 A…). 직무 그룹, 예비 인원 여부(isReserve), 지금 맡은 업무 수·총 시간(분),
  대상 구역에서 맡은 업무 수(zoneTasks), 대상 업무가 끝난 뒤 다음 업무까지 남은 시간(nextTaskAfterMinutes, 없으면 null).
- feedback: 재위임이면 앞선 배정이 다른 업무와 겹친 내용. 겹친 사람은 이미 후보에서 빠졌다.

판단 기준
- 업무와 같은 직무 그룹이거나 그 구역 업무를 맡고 있는 사람을 먼저 고른다. 장비 조치는 그 구역 무대·장비 그룹이 낫다.
- 예비 인원은 정규 인원이 마땅치 않을 때 쓴다. 업무 수·총 시간이 적고 다음 업무까지 여유가 있는 사람을 고른다.
- 한 사람에게 시간이 겹치는 두 업무를 맡기지 않는다. 각 대상의 후보 목록 밖에서 고르지 않는다.
- targets의 ref마다 assignments에 꼭 한 항목을 넣는다. target에는 ref(NEED, W1…)를, candidate에는 '후보 A'처럼 별칭을 그대로 쓴다.
  후보 목록이 있는 대상을 비워 두지 않는다.
- reason에는 고른 이유를 한 문장으로 쓴다. 사람은 '후보 A'처럼 별칭으로만 부른다."""

STAFFING_SCHEMA = _strict("staffing_artifact", {
    "assignments": {"type": "array", "maxItems": 10, "items": _obj({"target": STR, "candidate": STR, "reason": STR})},
    "questions": QUESTIONS})

NO_STAFF = Question(key="staff", field="members",
                    question="지금 투입할 수 있는 인원이 없습니다. 예비 인원이나 다른 그룹에서 지원할 사람을 알려 주세요.")


class GmsStaffing(GmsAgent):
    role, system, schema = "STAFFING", STAFFING_SYSTEM, STAFFING_SCHEMA

    def fallback(self):
        return RuleStaffing()

    def targets(self, task, snap):
        """(라벨, ref, 이름, 구역, 그룹, 시작, 끝). 조치 업무가 있으면 그것만, 없으면 빠진 인원의 남은 업무 전부."""
        if task.need:
            n = task.need
            return [("NEED", NEED_REF, n.title, n.zoneId, None, n.startAt, n.endAt)]
        gone = {m.id for m in snap.members.values() if not m.available}
        tasks = [t for t in sorted(snap.tasks.values(), key=lambda x: x.startAt)
                 if t.assigneeId in gone and t.status == "TODO" and t.startAt >= snap.now]
        return [(f"W{i + 1}", t.id, t.title, t.zoneId, t.workGroupId, t.startAt, t.endAt) for i, t in enumerate(tasks)]

    def decide(self, task, tools):
        snap = tools.snap
        ops = task.operations if task.feedback else []
        exclude = set((task.feedback or {}).get("excludeMembers", []))
        targets = self.targets(task, snap)
        asked = targets[:MAX_MODEL_TARGETS]   # 나머지 대상은 모델 없이 비어 있는 첫 후보로 배정한다(빠뜨리지 않는다)
        eligible = {t[0]: tools.get_eligible_members(t[5], t[6], ops, exclude) for t in targets}
        if not any(eligible.values()):
            return StaffingArtifact(summary="맡길 수 있는 가용 인원이 없음", questions=[NO_STAFF])
        people = list({m.id: m for t in asked for m in eligible[t[0]]}.values())
        C = {f"후보 {_label(i)}": m for i, m in enumerate(people)}
        label = {m.id: k for k, m in C.items()}
        projected = [t for t in snap.project(list(ops)).values() if t.active]
        group_name = lambda g: snap.groups[g].name if g in snap.groups else None   # noqa: E731
        candidates = []
        for k, m in C.items():
            load = tools.get_workload(m.id, ops)
            mine = [t for t in projected if t.assigneeId == m.id]
            candidates.append({"ref": k, "group": group_name(m.workGroupId), "isReserve": m.isReserve,
                               "tasks": load["tasks"], "minutes": load["minutes"],
                               "zoneTasks": {t[0]: sum(1 for x in mine if x.zoneId == t[3]) for t in asked},
                               "nextTaskAfterMinutes": {t[0]: min((_minutes(t[6], x.startAt) for x in mine if x.startAt >= t[6]),
                                                                  default=None) for t in asked}})
        user = {**report_facts(snap),
                "targets": [{"ref": t[0], "title": t[2], "zone": snap.zones[t[3]].name, "group": group_name(t[4]),
                             "startAt": hm(t[5]), "endAt": hm(t[6]), "candidates": [label[m.id] for m in eligible[t[0]]]}
                            for t in asked],
                "candidates": candidates,
                "feedback": {"reason": task.feedback.get("reason"), "conflicts": task.feedback.get("conflicts", [])}
                if task.feedback else None}
        out = self.call(user)

        by_label = lookup(C)
        by_target = {norm(t[2]): t[0] for t in asked} | {norm(t[0]): t[0] for t in asked}   # 이름으로 적어도 받는다
        picks = {}
        for a in out["assignments"]:
            key = by_target.get(norm(a["target"]), norm(a["target"]))
            picks.setdefault(norm(key), (by_label.get(norm(a["candidate"])), str(a["reason"]), str(a["candidate"])))
        chosen, result, dropped, missing = [], [], [], []
        model_keys = {t[0] for t in asked}
        for key, ref, title, _, _, start, end in targets:
            def free(m):
                return all(not (mid == m.id and overlaps(s, e, start, end)) for mid, s, e in chosen) \
                    and not tools.check_assignment_conflict(m.id, start, end, ops)
            m, reason, raw = picks.get(norm(key), (None, "", "선택 없음"))
            if m is None or m not in eligible[key] or not free(m):
                m = next((x for x in eligible[key] if free(x)), None)
                reason = f"'{title}' 시간에 비어 있는 첫 후보"
                if m is not None and key in model_keys:
                    dropped.append(f"{key}←'{raw[:10]}'")
            if m is None:
                missing.append(ref)
                continue
            chosen.append((m.id, start, end))
            for k in sorted(C, key=len, reverse=True):
                reason = reason.replace(k, f"팀원 {C[k].crewNo}")
            result.append(Assignment(taskRef=ref, memberId=m.id, reason=reason[:200] or "비어 있음"))
        if NEED_REF in missing or not result:
            questions = _questions(out["questions"]) or [NO_STAFF]
        elif missing:                       # 결원 업무 일부를 맡길 사람이 없다: 흐름이 NEEDS_INFO로 끝낸다
            questions = [unassigned_question(snap, missing)]
        else:
            questions = []
        names = ", ".join(f"팀원 {snap.members[a.memberId].crewNo}" for a in result)
        head = "겹침을 피해 다시 선정" if task.feedback else f"담당 후보 {len(result)}명 선정"
        summary = f"{head}: {names}" if result else "맡길 수 있는 가용 인원이 없음"
        if dropped:
            summary += f" (모델 선택 {', '.join(dropped)}이 후보 목록 밖이라 비어 있는 첫 후보로 대신함)"
        if len(targets) > len(asked):
            summary += f", 대상 {len(targets) - len(asked)}건은 모델 없이 비어 있는 첫 후보로 배정"
        if missing and result:
            summary += f", {len(missing)}건은 맡을 사람 없음"
        return StaffingArtifact(assignments=result, unassigned=missing, summary=summary[:200], questions=questions)


# ---- 안내 ----

GUIDE_SYSTEM = f"""너는 콘서트 현장 운영의 안내 담당(Guide Agent)이다. 새로 추가하는 조치 업무마다 현장 팀원이 앱에서 보고 따라 할
수행 절차(steps)와 완료 확인 체크 항목(checklist)을 쓴다. {COMMON}

입력
- reports·instruction·situationTypes: 현장 보고, 총괄 지시, 상황 유형(EQUIPMENT_ANOMALY 장비 이상, AUDIENCE_SAFETY 관객 안전·혼잡,
  STAFF_GAP 인력 공백, SCHEDULE_DELAY 일정 지연 등)
- tasks: 절차를 붙일 업무(N1…). 이름·구역·직무 그룹·시간(분).

작성 기준
- steps는 3~6개, 순서대로, 한 단계에 한 동작을 짧은 한 문장(60자 안쪽)으로 쓴다. 보고된 문제와 지시에 맞춘다.
  장비 이상: 안전 확보(전원·케이블) → 상태 확인 → 예비 장비 교체 또는 복구 → 작동 확인 → 총괄 보고 순서를 따른다.
  관객 혼잡·안전: 현장 상황 파악 → 통제선·동선 조정 → 관객 안내 → 상태 재확인 → 총괄 보고.
- 마지막 단계는 총괄에게 결과를 보고하는 것이다. 다른 업무를 새로 만들거나 다른 사람에게 따로 일을 시키는 단계는 쓰지 않는다.
- checklist는 2~4개, 완료를 증명하는 확인 항목(40자 안쪽)이다. 안전·작동 확인·보고 항목은 required=true다.
- 입력에 없는 장비 모델명·수량·사람 이름을 지어내지 않는다."""

MIN_STEPS, MAX_STEPS, MIN_CHECKS, MAX_CHECKS = 3, 6, 2, 4
GUIDE_SCHEMA = _strict("guide_artifact", {
    "guides": {"type": "array", "maxItems": 10, "items": _obj({
        "ref": STR, "steps": {"type": "array", "minItems": MIN_STEPS, "maxItems": MAX_STEPS, "items": STR},
        "checklist": {"type": "array", "minItems": MIN_CHECKS, "maxItems": MAX_CHECKS,
                      "items": _obj({"text": STR, "required": BOOL})}})}})


class GmsGuide(GmsAgent):
    role, system, schema = "GUIDE", GUIDE_SYSTEM, GUIDE_SCHEMA

    def fallback(self):
        return RuleGuide()

    def decide(self, task, tools):
        adds = [op.payload for op in task.operations if op.type == "ADD_TASK"]
        if not adds:
            return GuideArtifact(guides=[], summary="새 업무가 없어 절차를 붙이지 않음")
        N = {f"N{i + 1}": p for i, p in enumerate(adds)}
        items = []
        for k, p in N.items():
            ctx = tools.get_task_context(p.tempId, task.operations) or {}
            items.append({"ref": k, "title": p.title, "zone": ctx.get("zone"), "group": ctx.get("group"),
                          "program": ctx.get("program"), "startAt": hm(p.startAt), "minutes": _minutes(p.startAt, p.endAt)})
        out = self.call({**report_facts(tools.snap), "tasks": items})

        got = {norm(g["ref"]): g for g in out["guides"]}
        rule = {g.ref: g for g in RuleGuide().run(task, _NoTools(tools)).guides}
        guides, fallback = [], 0
        for k, p in N.items():
            g = got.get(norm(k))
            steps = [s.strip()[:120] for s in (g or {}).get("steps", []) if s.strip()][:MAX_STEPS]
            checks = [ChecklistItem(key=f"check-{i + 1}", text=c["text"].strip()[:80], required=bool(c["required"]))
                      for i, c in enumerate([c for c in (g or {}).get("checklist", []) if c["text"].strip()][:MAX_CHECKS])]
            if len(steps) < MIN_STEPS or len(checks) < MIN_CHECKS:   # 기준에 못 미치면 규칙 절차
                guides.append(rule[p.tempId])
                fallback += 1
                continue
            guides.append(Guide(ref=p.tempId, steps=steps, checklist=checks))
        summary = f"새 업무 {len(guides)}건에 절차·체크 항목 작성" \
            + "".join(f", {N[k].title} 절차 {len(g.steps)}단계·체크 {len(g.checklist)}개" for k, g in zip(N, guides))
        if fallback:
            summary += f" (모델 결과가 빈 {fallback}건은 기본 절차)"
        return GuideArtifact(guides=guides, summary=summary[:200])


class _NoTools:
    """규칙 안내를 대체용으로 미리 만들 때 쓰는 도구 묶음. 근거·호출 기록을 다시 남기지 않는다."""

    def __init__(self, tools):
        self.snap = tools.snap

    def get_task_context(self, *a, **k):
        return None


# ---- 검토 ----

REVIEW_SYSTEM = f"""너는 콘서트 현장 운영의 검토 담당(Review Agent)이다. 다른 담당(일정·인력·안내)이 만든 대응안을 그들의 설명을 믿지
않고 따로 확인한다. 시간 겹침·참조·시간 범위는 코드가 이미 검사해 통과했다. 너는 의미상 빠진 것과 어긋난 것을 찾는다. {COMMON}

입력
- reports·instruction: 현장 보고와 총괄 지시
- operations: 대응안의 변경 목록(번호 operations[i]). 사람은 '담당자 A'처럼 별칭이다.
  시간을 옮긴 변경에는 코드가 계산한 이동 분(startShiftMinutes·endShiftMinutes)이 있다. 지연 시간은 직접 빼서 계산하지 말고 이 값을 본다.
- leftover: 빠진 인원이 맡았는데 대응안에서 다시 맡기지 않은 남은 업무

problems: 보고에 적힌, 처리해야 할 문제를 하나씩 나눠 적는다. 보고가 여러 건이거나 한 보고에 문제가 둘 이상이면 문제마다 한 항목이다.
각 문제에 그 문제를 처리하는 변경 번호(operations[i])를 모두 적는다. 처리하는 변경이 없으면 빈 목록으로 둔다.
방법이 아쉬워도 그 문제를 다루는 변경이면 적는다. 빈 목록인 문제가 있으면 코드가 대응안을 통과시키지 않는다.

찾을 것(findings의 rule)
- INSTRUCTION_MISSING: 지시에 있는 일(예: 'N분 지연', '안내 방송')이 변경에 없다.
- DELAY_MISMATCH: 지시·보고의 지연 시간과 변경된 시간이 다르다.
- GUIDE_MISMATCH: 조치 업무의 절차·체크 항목이 다른 문제를 다루거나 비어 있다.
- STAFF_GAP_LEFT: leftover에 곧 시작할 업무가 남았는데 보고가 그 인원 공백에 관한 것이다.
- OTHER: 위에 없는 명백한 모순.

판정
- 입력에서 근거를 댈 수 있는 확실한 문제만 blocking=true로 한다. 애매하거나 개선 제안이면 blocking=false다.
- '더 구체적이면 좋겠다', '원인 파악 단계가 없다'처럼 있는 조치를 보완하자는 의견은 blocking=false다.
  보고 대응은 현장 팀원이 절차를 따라 처리하고 결과를 총괄에게 보고하는 수준이면 충분하다.
- 문제가 없으면 findings를 빈 목록으로 둔다. detail에는 무엇이 어떻게 어긋났는지 한 문장으로 쓴다."""

RULES = ["INSTRUCTION_MISSING", "DELAY_MISMATCH", "GUIDE_MISMATCH", "STAFF_GAP_LEFT", "OTHER"]
REVIEW_SCHEMA = _strict("review_artifact", {
    "problems": {"type": "array", "maxItems": 8, "items": _obj({
        "problem": STR, "operations": {"type": "array", "maxItems": 10, "items": STR}})},
    "findings": {"type": "array", "maxItems": 8, "items": _obj({
        "rule": {"type": "string", "enum": RULES}, "target": STR, "detail": STR, "blocking": BOOL})}})


def describe_operations(snap, ops):
    """검토 모델에 보낼 변경 목록. 사람은 '담당자 A' 별칭으로, 시각은 한국 시간으로 쓴다."""
    people = {}

    def who(mid):
        if mid is None:
            return "없음"
        if mid not in people:
            m = snap.members.get(mid)
            people[mid] = f"담당자 {_label(len(people))}" + ("" if m is None or m.available else "(가용 아님)")
        return people[mid]

    zone = lambda z: snap.zones[z].name if z in snap.zones else z   # noqa: E731
    out = []
    for i, op in enumerate(ops):
        p, line = op.payload, {"ref": f"operations[{i}]", "type": op.type}
        if isinstance(p, AddTask):
            line.update(title=p.title, zone=zone(p.zoneId), time=f"{hm(p.startAt)}~{hm(p.endAt)}", assignee=who(p.assigneeId),
                        steps=p.steps, checklist=[c.text for c in p.checklist])
        elif isinstance(p, UpdateProgram):
            x = snap.programs.get(p.programId)
            if x:
                line.update(program=x.name, before=f"{hm(x.startAt)}~{hm(x.endAt)}",
                            after=f"{hm(p.startAt or x.startAt)}~{hm(p.endAt or x.endAt)}",
                            startShiftMinutes=_minutes(x.startAt, p.startAt or x.startAt),
                            endShiftMinutes=_minutes(x.endAt, p.endAt or x.endAt))
        elif isinstance(p, UpdateTask):
            t = snap.tasks.get(p.taskId)
            if t:
                line.update(task=t.title, before=f"{hm(t.startAt)}~{hm(t.endAt)}",
                            after=f"{hm(p.startAt or t.startAt)}~{hm(p.endAt or t.endAt)}",
                            startShiftMinutes=_minutes(t.startAt, p.startAt or t.startAt),
                            endShiftMinutes=_minutes(t.endAt, p.endAt or t.endAt))
        elif isinstance(p, AssignTask):
            t = snap.tasks.get(p.taskId)
            line.update(task=t.title if t else p.taskId, time=f"{hm(t.startAt)}~{hm(t.endAt)}" if t else None,
                        fromMember=who(p.fromMemberId), toMember=who(p.toMemberId))
            m = snap.members.get(p.fromMemberId)
            if m is not None and not m.available:
                line["note"] = "가용 아님(결원) 담당자의 업무를 다른 담당자에게 넘김"
        elif isinstance(p, CancelTask):
            t = snap.tasks.get(p.taskId)
            line.update(task=t.title if t else p.taskId, reason=p.reason)
        elif isinstance(p, UpdateInstructions):
            line.update(scope=p.scope, body=p.body)
        out.append(line)
    return out, who


class GmsReview(GmsAgent):
    role, system, schema = "REVIEW", REVIEW_SYSTEM, REVIEW_SCHEMA

    def fallback(self):
        return _CodeOnlyReview()

    def decide(self, task, tools):
        ops = task.operations
        code = RuleReview().run(task, tools)          # 참조·시간 범위·담당 겹침은 코드 도구로 먼저
        if not code.passed:
            return code
        snap = tools.snap
        lines, who = describe_operations(snap, ops)
        projected = snap.project(ops)
        gone = {m.id for m in snap.members.values() if not m.available}
        leftover = [{"task": t.title, "time": f"{hm(t.startAt)}~{hm(t.endAt)}", "assignee": who(t.assigneeId)}
                    for t in sorted(projected.values(), key=lambda x: x.startAt)
                    if t.active and t.assigneeId in gone and t.startAt >= snap.now][:10]
        out = self.call({**report_facts(snap), "operations": lines, "leftover": leftover})

        # 모델의 blocking 판정을 코드가 낮추지 않는다. 보고 미대응은 모델이 나눈 문제와 변경 번호의 연결로 코드가 판정한다.
        blocking, notes = _unaddressed(out["problems"], len(ops)), []
        for f in out["findings"]:
            text = f"[{f['rule']}] {f['target'] or '대응안'}: {f['detail']}"[:200]
            (blocking if f["blocking"] else notes).append(text)
        summary = ("참조·시간·담당 겹침 검사와 의미 검토 통과" if not blocking else f"의미 검토에서 문제 {len(blocking)}건 발견") \
            + (f"(참고 {len(notes)}건)" if notes else "")
        return ReviewArtifact(passed=not blocking, findings=blocking, summary=summary[:200])


def _op_index(ref):
    m = re.search(r"\d+", str(ref))
    return int(m.group()) if m else None


def _unaddressed(problems, n_ops):
    """처리하는 변경 번호가 하나도 맞지 않는 문제를 막는 지적으로 만든다. 문제를 하나도 나누지 않았어도 막는다."""
    if not problems:
        return ["[REPORT_NOT_ADDRESSED] 대응안: 보고에서 처리할 문제를 확인하지 못함"]
    out = []
    for p in problems:
        if not any((i := _op_index(r)) is not None and 0 <= i < n_ops for r in p["operations"]):
            out.append(f"[REPORT_NOT_ADDRESSED] {str(p['problem'])[:80]}: 이 문제를 처리하는 변경이 없음"[:200])
    return out




class _CodeOnlyReview:
    def run(self, task, tools):
        out = RuleReview().run(task, tools)
        return out.model_copy(update={"summary": "의미 검토 없이 코드 검사만 적용: " + out.summary})


def gms_agents(chat=None):
    """요청마다 새로 만든다. 각 Agent는 실행 하나의 모델 이름·사용량을 들고 있다."""
    return {"SCHEDULE": GmsSchedule(chat), "STAFFING": GmsStaffing(chat), "GUIDE": GmsGuide(chat), "REVIEW": GmsReview(chat)}

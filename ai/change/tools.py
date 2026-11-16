"""Agent가 쓰는 도구. BE가 보낸 계획 스냅샷 안에서만 읽고 계산한다(DB·외부 검색·파일 쓰기 없음).

- 읽기 도구는 스냅샷의 사실만 돌려준다. 돌려준 사실의 id는 근거(evidenceRefs)로 모은다.
- 시간 겹침·참조 확인 같은 판정은 코드로 계산한다. Agent(LLM)는 의미 해석과 대안 선택만 한다(AI01).
- 역할마다 쓸 수 있는 도구가 정해져 있고(ALLOWED), 목록에 없는 도구를 부르면 ToolNotAllowed다.
  Supervisor가 재위임해도 허용 도구는 늘어나지 않는다.
"""
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from contract import ChangeRequest, Operation, AddTask, UpdateTask, AssignTask, CancelTask, UpdateProgram

ALLOWED = {
    "SUPERVISOR": frozenset(),   # Supervisor는 도구 대신 전문 Agent를 고른다
    "SCHEDULE": frozenset({"get_programs", "get_related_tasks", "check_time_overlap"}),
    "STAFFING": frozenset({"get_eligible_members", "get_workload", "check_assignment_conflict"}),
    "GUIDE": frozenset({"get_task_context"}),
    "REVIEW": frozenset({"check_references", "check_time_overlap", "check_window"}),
}


# 사람이 읽는 문장(충돌·검토 결과)의 시각은 서비스 시간대로 쓴다. BE가 UTC로 보내도 18:05처럼 보이게 한다.
SERVICE_TZ = timezone(timedelta(hours=9), name="Asia/Seoul")


def hm(at: datetime):
    return at.astimezone(SERVICE_TZ).strftime("%H:%M")


class ToolNotAllowed(Exception):
    def __init__(self, role, tool):
        super().__init__(f"{role}은 {tool} 도구를 쓸 수 없습니다.")
        self.role = role
        self.tool = tool


@dataclass
class ProjectedTask:
    """연산을 적용해 본 업무. 기존 업무는 UUID, 새 업무는 tempId가 ref다."""
    ref: str
    title: str
    zoneId: str
    programId: str | None
    workGroupId: str
    assigneeId: str | None
    startAt: datetime
    endAt: datetime
    active: bool = True


class Snapshot:
    """요청의 현재 계획을 찾기 쉽게 정리한 읽기 전용 묶음. 연산을 '적용해 본' 결과를 계산할 수 있다."""

    def __init__(self, req: ChangeRequest):
        p = req.payload
        self.req = req
        self.now = p.now
        self.zones = {z.id: z for z in p.zones}
        self.programs = {x.id: x for x in p.programs}
        self.groups = {g.id: g for g in p.workGroups}
        self.members = {m.id: m for m in p.members}
        self.tasks = {t.id: t for t in p.tasks}

    def project(self, operations: list[Operation]) -> dict[str, ProjectedTask]:
        """연산을 차례로 적용한 업무 목록. 끝난·취소된 업무는 비활성이다. 없는 대상을 가리키는 연산은 건너뛴다
        (참조 오류는 check_references가 따로 찾는다)."""
        out = {t.id: ProjectedTask(t.id, t.title, t.zoneId, t.programId, t.workGroupId, t.assigneeId, t.startAt, t.endAt,
                                   t.status in ("TODO", "IN_PROGRESS")) for t in self.tasks.values()}
        programs = {x.id: (x.startAt, x.endAt) for x in self.programs.values()}
        for op in operations:
            p = op.payload
            if isinstance(p, AddTask):
                out[p.tempId] = ProjectedTask(p.tempId, p.title, p.zoneId, p.programId, p.workGroupId, p.assigneeId,
                                              p.startAt, p.endAt)
            elif isinstance(p, UpdateTask) and p.taskId in out:
                t = out[p.taskId]
                t.startAt = p.startAt or t.startAt
                t.endAt = p.endAt or t.endAt
                t.zoneId = p.zoneId or t.zoneId
                t.programId = p.programId or t.programId
                t.title = p.title or t.title
            elif isinstance(p, AssignTask) and p.taskId in out:
                out[p.taskId].assigneeId = p.toMemberId
            elif isinstance(p, CancelTask) and p.taskId in out:
                out[p.taskId].active = False
            elif isinstance(p, UpdateProgram) and p.programId in programs:
                start, end = programs[p.programId]
                programs[p.programId] = (p.startAt or start, p.endAt or end)
        return out


def overlaps(a_start, a_end, b_start, b_end):
    """반개구간 [start, end) 겹침. 끝과 다음 시작이 같으면 겹치지 않는다."""
    return a_start < b_end and b_start < a_end


@dataclass
class Conflict:
    memberId: str
    refs: tuple[str, str]
    window: tuple[datetime, datetime]

    def describe(self, snap: Snapshot, tasks, who=None):
        """사람이 읽는 충돌 문장. who를 주면 팀원 번호 대신 그 이름을 쓴다(모델에 보낼 때 팀원 식별을 빼려고)."""
        if who is None:
            m = snap.members.get(self.memberId)
            who = f"팀원 {m.crewNo}" if m else "담당자"
        a, b = (tasks[r] for r in self.refs)
        return (f"{who}이(가) '{a.title}'({hm(a.startAt)}~{hm(a.endAt)})와 "
                f"'{b.title}'({hm(b.startAt)}~{hm(b.endAt)})에 동시에 배정됨")


def time_conflicts(tasks: dict[str, ProjectedTask]) -> list[Conflict]:
    """같은 사람이 같은 시간에 두 활성 업무를 맡는 경우. BE의 TIME_OVERLAP과 같은 판정이다."""
    by_member = {}
    for t in tasks.values():
        if t.active and t.assigneeId:
            by_member.setdefault(t.assigneeId, []).append(t)
    out = []
    for member, items in by_member.items():
        items.sort(key=lambda x: (x.startAt, x.ref))
        for i, a in enumerate(items):
            for b in items[i + 1:]:
                if b.startAt >= a.endAt:
                    break
                if overlaps(a.startAt, a.endAt, b.startAt, b.endAt):
                    out.append(Conflict(member, (a.ref, b.ref), (max(a.startAt, b.startAt), min(a.endAt, b.endAt))))
    return out


@dataclass
class Evidence:
    """도구가 돌려준 사실의 참조. 결과의 evidenceRefs가 된다(중복 없이 순서 유지)."""
    refs: dict = field(default_factory=dict)

    def add(self, kind, id_):
        if id_ and len(id_) == 36:   # tempId(새 업무)는 근거가 아니다
            self.refs.setdefault((kind, id_), None)

    def dump(self):
        return [{"kind": k, "id": i, "revision": None, "contentHash": None, "observedAt": None, "preconditions": []}
                for k, i in self.refs]


class ToolBox:
    """한 역할이 쓰는 도구 묶음. 허용 목록 밖 도구는 막고, 읽은 사실은 근거로 남긴다."""

    def __init__(self, snap: Snapshot, role: str, evidence: Evidence):
        self.snap = snap
        self.role = role
        self.evidence = evidence
        self.calls = []

    def _use(self, name):
        if name not in ALLOWED[self.role]:
            raise ToolNotAllowed(self.role, name)
        self.calls.append(name)

    # ---- 일정 ----
    def get_programs(self, zone_id=None):
        """구역(없으면 전체)의 프로그램 시간표"""
        self._use("get_programs")
        out = [x for x in self.snap.programs.values() if zone_id is None or x.zoneId == zone_id]
        for x in out:
            self.evidence.add("PROGRAM", x.id)
        return sorted(out, key=lambda x: x.startAt)

    def get_related_tasks(self, zone_id=None, start=None, end=None, program_id=None):
        """구역·시간·프로그램으로 걸러 낸 진행 전·진행 중 업무"""
        self._use("get_related_tasks")
        out = []
        for t in self.snap.tasks.values():
            if t.status not in ("TODO", "IN_PROGRESS"):
                continue
            if zone_id and t.zoneId != zone_id:
                continue
            if program_id and t.programId != program_id:
                continue
            if start and end and not overlaps(t.startAt, t.endAt, start, end):
                continue
            out.append(t)
            self.evidence.add("TASK", t.id)
        return sorted(out, key=lambda x: x.startAt)

    def check_time_overlap(self, operations):
        """연산을 적용했을 때 같은 사람이 같은 시간에 두 업무를 맡는지"""
        self._use("check_time_overlap")
        tasks = self.snap.project(operations)
        conflicts = time_conflicts(tasks)
        for c in conflicts:
            self.evidence.add("MEMBER", c.memberId)
            for r in c.refs:
                self.evidence.add("TASK", r)
        return conflicts, tasks

    # ---- 인력 ----
    def get_eligible_members(self, start, end, operations=(), exclude=()):
        """그 시간에 비어 있는 가용 팀원. operations를 주면 그 변경을 적용한 시간표로 판단한다."""
        self._use("get_eligible_members")
        tasks = self.snap.project(list(operations))
        busy = {t.assigneeId for t in tasks.values()
                if t.active and t.assigneeId and overlaps(t.startAt, t.endAt, start, end)}
        out = [m for m in self.snap.members.values() if m.available and m.id not in busy and m.id not in exclude]
        for m in out:
            self.evidence.add("MEMBER", m.id)
        return sorted(out, key=lambda m: (m.isReserve, m.crewNo))

    def get_workload(self, member_id, operations=()):
        """팀원이 맡은 활성 업무 수와 총 시간(분)"""
        self._use("get_workload")
        tasks = [t for t in self.snap.project(list(operations)).values() if t.active and t.assigneeId == member_id]
        self.evidence.add("MEMBER", member_id)
        return {"tasks": len(tasks), "minutes": int(sum((t.endAt - t.startAt).total_seconds() for t in tasks) // 60)}

    def check_assignment_conflict(self, member_id, start, end, operations=()):
        """그 팀원에게 그 시간을 맡기면 겹치는 업무 목록"""
        self._use("check_assignment_conflict")
        tasks = self.snap.project(list(operations))
        return [t for t in tasks.values()
                if t.active and t.assigneeId == member_id and overlaps(t.startAt, t.endAt, start, end)]

    # ---- 안내 ----
    def get_task_context(self, ref, operations=()):
        """업무 하나의 구역·프로그램·그룹 이름"""
        self._use("get_task_context")
        t = self.snap.project(list(operations)).get(ref)
        if t is None:
            return None
        zone = self.snap.zones.get(t.zoneId)
        program = self.snap.programs.get(t.programId) if t.programId else None
        self.evidence.add("ZONE", t.zoneId)
        return {"title": t.title, "zone": zone.name if zone else None, "program": program.name if program else None,
                "group": self.snap.groups[t.workGroupId].name if t.workGroupId in self.snap.groups else None,
                "startAt": t.startAt, "endAt": t.endAt}

    # ---- 검토 ----
    def check_references(self, operations):
        """연산이 가리키는 구역·그룹·업무·팀원·프로그램이 스냅샷에 있는지"""
        self._use("check_references")
        s = self.snap
        temp = {op.payload.tempId for op in operations if isinstance(op.payload, AddTask)}
        out = []
        for i, op in enumerate(operations):
            p = op.payload
            refs = []
            if isinstance(p, AddTask):
                refs = [("zone", p.zoneId, s.zones), ("workGroup", p.workGroupId, s.groups),
                        ("program", p.programId, s.programs), ("member", p.assigneeId, s.members)]
            elif isinstance(p, UpdateTask):
                refs = [("task", p.taskId, s.tasks), ("zone", p.zoneId, s.zones), ("program", p.programId, s.programs)]
            elif isinstance(p, CancelTask):
                refs = [("task", p.taskId, s.tasks)]
            elif isinstance(p, AssignTask):
                refs = [("task", p.taskId, s.tasks), ("member", p.fromMemberId, s.members),
                        ("member", p.toMemberId, s.members)]
                if p.taskId in temp:
                    refs = refs[1:]
            elif isinstance(p, UpdateProgram):
                refs = [("program", p.programId, s.programs)]
            for name, value, table in refs:
                if value is not None and value not in table:
                    out.append(f"operations[{i}] {name} {value}가 현재 계획에 없음")
            if isinstance(p, AssignTask) and p.taskId in s.tasks and s.tasks[p.taskId].assigneeId != p.fromMemberId:
                out.append(f"operations[{i}] 현재 담당자와 fromMemberId가 다름")
        return out

    def check_window(self, operations):
        """새·변경 업무와 프로그램이 기준 시각 뒤, 행사 정리 종료 전이고 시작 < 끝인지.
        업무 수정이 시각 한쪽만 바꾸면 지금 시각과 합쳐 다시 본다(끝만 당겨 시작보다 앞서는 경우).
        철수 종료에 끝나는 연산이고 기준 시각 뒤로는 같은 길이가 정리 종료 안에 들어가지 않으면(철수 직전·철수 뒤 요청)
        기준 시각보다 앞선 시작을 허용한다.
        시간과 관계없이 대응안을 만들 수 있게 agents.response_window가 운영 구간 끝으로 당긴 업무다."""
        self._use("check_window")
        e = self.snap.req.payload.event
        out = []
        for i, op in enumerate(operations):
            p = op.payload
            start, end = getattr(p, "startAt", None), getattr(p, "endAt", None)
            if isinstance(p, UpdateTask) and p.taskId in self.snap.tasks:
                t = self.snap.tasks[p.taskId]
                whole = (start or t.startAt, end or t.endAt)
            elif isinstance(p, UpdateProgram) and p.programId in self.snap.programs:
                x = self.snap.programs[p.programId]
                whole = (start or x.startAt, end or x.endAt)
            else:
                whole = (start, end)
            # response_window가 당긴 업무는 항상 철수 종료에 끝난다. 철수 종료에 끝나지 않는 연산의 과거 시작은 허용하지 않는다.
            pinned = whole[1] == e.teardownEndAt
            no_room = pinned and whole[0] and self.snap.now + (whole[1] - whole[0]) > e.teardownEndAt
            if start and start < self.snap.now and not no_room:
                out.append(f"operations[{i}] 시작 시각이 기준 시각보다 앞섬")
            if end and end > e.teardownEndAt:
                out.append(f"operations[{i}] 끝 시각이 행사 정리 종료보다 늦음")
            if whole[0] and whole[1] and whole[0] >= whole[1]:
                out.append(f"operations[{i}] 시작이 끝보다 늦음")
        return out

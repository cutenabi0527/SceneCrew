"""최초 계획(GENERATE_PLAN) 최소 실행 흐름.

확인된 행사 조건(공통 요청4)을 받아 직무 그룹·업무·일정 후보를 만들고 contract.py의 결과5로 반환한다.

    요청 검증 → 마감 → 선검사 질문(구역·준비/정리 시간) → Planning(생성기) → Schedule(선행·시간) → Staffing(묶음 수)
    → Guide(절차·안내) → Review(contract.result_issues) → [결과6이면 평면도 읽기(floor_plan)] → READY/NEEDS_INFO/실패

필수 업무 품질 게이트(quality.py, S15P31A202-409)가 STAFFING 뒤에 구역·직무 그룹의 필수 업무 단계를 판정한다.
빠진 단계는 그 구역·직무를 맡은 묶음의 빈 시간에 결정적으로 보완하고, 보완하지 못한 단계는 unresolved로 남긴 채
READY로 내보낸다(막지 않음, 252 MVP 기준·AUD-T09). 252의 평가 지표(coverage_issues)는 evaluate.py가 그대로 센다. 묶음 수가 전담 가능 인원을 넘으면 (보완을 반영하는
생성기라면) Planning을 1회 다시 불러 줄이도록 요청하고, 그래도 넘으면 인원 질문(NEEDS_INFO)으로 끝낸다.

생성기는 draft(req, feedback=None)와 model_version을 갖는 객체다. 기본 RuleGenerator는 모델 호출 없이 구역·프로그램·
운영 시간으로 후보를 만든다. GMS 생성기는 같은 인터페이스로 바꿔 끼운다.
선행 관계(PlanDraft.precedences)는 결과 계약에 필드로 넣지 않는다. 시간 순서로 배치하고
coordination.evidenceRefs에 "precedence:선행>후행"으로만 남긴다. 업무 시각을 기준으로 삼아,
시각과 맞지 않거나 없는 업무를 가리키는 선행 관계는 실패시키지 않고 버린다(버린 건수는 SCHEDULE 기여에 남긴다).

이 흐름은 역할별 결정적 단계이며 공통 Supervisor/전문 Agent 실행 구조가 아니다.

사용 (ai/plan에서):
    uv run python flow.py run examples/request.json --now 2026-11-01T03:00:00Z
    uv run python flow.py run examples/request.json examples/flow/ready.json --now 2026-11-01T03:00:00Z
    uv run python flow.py run examples/request.json --generator gms   # GMS 1회 호출(ai/.env 필요)
"""

import json
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

from pydantic import ValidationError

import contract
import devices
import quality
from contract import (AgentTaskNode, Assumption, ContractViolation, Contribution, Coordination, Question,
                      Redelegation)

MODEL_VERSION = "plan-rule-v1"
CHECK_MINUTES = 20        # 개장 전 점검 시간
# 생성기 호출 전 시간 선검사 기준. 11/09 확정 시연 PDF(준비 30분·정리 15분)가 통과하도록 2026-11-11 낮췄다(S15P31A202-298,
# 담당자 최재영 확인). 이전 기준은 준비 50분(설치 30+점검 20)·정리 30분이었다. 규칙 생성기 배치는 점검 CHECK_MINUTES를 그대로 쓴다.
MIN_PREP_MINUTES = 30     # 개장 전 준비(설치·점검) 최소 시간. CHECK_MINUTES보다 길어야 규칙 생성기 설치 업무가 생긴다
MIN_TEARDOWN_MINUTES = 15
SHIFT_SPLIT_HOURS = 4     # 운영 구간이 이보다 길면 입장 안내를 두 교대로 나눈다

SETUP_GROUP = "현장 설치"
PROGRAM_GROUP = "프로그램 운영"
ENTRY_GROUP = "입장 안내"


@dataclass
class PlanDraft:
    """Planning 산출물. tasks는 TaskPlan 필드의 dict이며 steps가 비어 있으면 Guide가 채운다.

    precedences는 (선행 tempId, 후행 tempId)다. 선행 업무가 끝나야 후행 업무를 시작한다.
    kinds는 Guide가 절차 템플릿을 고를 때 쓰는 내부 업무 종류(tempId → setup/check/program/entry/teardown)다.
    conditionRefs는 (confirmedInput 키, 반영 대상) 목록이다. 대상은 "task:<tempId>" 또는
    "instruction:EVENT"·"instruction:WORK_GROUP:<그룹 참조>"다. 결과 계약에는 넣지 않고 근거로만 남긴다.
    """
    workGroups: list = field(default_factory=list)
    tasks: list = field(default_factory=list)
    precedences: list = field(default_factory=list)
    kinds: dict = field(default_factory=dict)
    instructions: list = field(default_factory=list)
    assumptions: list = field(default_factory=list)
    unresolved: list = field(default_factory=list)
    questions: list = field(default_factory=list)
    conditionRefs: list = field(default_factory=list)
    # 문서 일정표 항목(S15P31A202-408 시험 경로) → 반영 업무. (항목 key, 업무 tempId) 목록이며 근거로만 남긴다.
    scheduleRefs: list = field(default_factory=list)
    scheduleSummary: dict = field(default_factory=dict)


# ---- Planning: 규칙 기반 생성기 ----

def time_questions(req):
    """준비·정리 시간이 구역 설치·점검·정리에 모자라면 생성기를 부르기 전에 질문한다(모든 생성기 공통)."""
    e = req.payload.event
    out = []
    if e.openAt - e.prepStartAt < timedelta(minutes=MIN_PREP_MINUTES):
        out.append(Question(
            key="prep-time", field="payload.event.prepStartAt",
            question=f"구역 설치와 개장 전 점검에 개장 {MIN_PREP_MINUTES}분 전 준비 시작이 필요합니다. 준비 시작 시각을 확인해 주세요."))
    if e.teardownEndAt - e.closeAt < timedelta(minutes=MIN_TEARDOWN_MINUTES):
        out.append(Question(
            key="teardown-time", field="payload.event.teardownEndAt",
            question=f"정리·철수에 운영 종료 뒤 {MIN_TEARDOWN_MINUTES}분 이상이 필요합니다. 정리 종료 시각을 확인해 주세요."))
    return out


class RuleGenerator:
    """구역마다 설치→점검→정리, 프로그램마다 진행 지원, 운영 구간에 입장 안내 업무를 만든다.

    준비·정리 시간 부족은 흐름의 선검사(time_questions)가 생성기 호출 전에 질문한다.
    """
    model_version = MODEL_VERSION
    revises = False  # 보완 요청(feedback)을 반영하지 않으므로 인원 초과 보완은 건너뛴다

    def draft(self, req, feedback=None):
        """feedback(이전 후보의 검사 위반)은 쓰지 않는다. 규칙 후보는 항상 같은 업무를 만든다."""
        p, e = req.payload, req.payload.event
        out = PlanDraft()
        check = timedelta(minutes=CHECK_MINUTES)
        added = [name for name, gid in _template_map(p.workGroups).items() if gid is None]
        if len(p.workGroups) + len(added) > contract.MAX_WORK_GROUPS:
            out.questions.append(Question(
                key="work-groups", field="payload.workGroups",
                question=f"직무 그룹 {len(p.workGroups)}개에 기본 그룹 {len(added)}개를 더하면 상한 {contract.MAX_WORK_GROUPS}개를 넘습니다. 쓰지 않는 그룹을 정리해 주세요."))
        if out.questions:
            return out

        groups, setup_groups = _groups(p.workGroups, out)
        entry_zone = _entry_zone(p.zones, p.programs)
        operating = {z.id: [] for z in p.zones}  # 구역별 운영 업무 tempId (점검 뒤, 정리 전)

        for i, prog in enumerate(sorted(p.programs, key=lambda x: (x.startAt, x.name)), 1):
            zone_id = prog.zoneId
            if zone_id is None:
                zone_id = entry_zone.id
                out.assumptions.append(Assumption(
                    key=f"program-zone-{i}", statement=f"구역이 없는 프로그램 '{prog.name}'은 {entry_zone.name}에서 진행한다고 가정했습니다.",
                    refs=[prog.id]))
            tid = f"tmp-program-{i}"
            _task(out, tid, "program", groups[PROGRAM_GROUP], None, zone_id, prog.id,
                  f"{prog.name} 진행 지원", prog.startAt, prog.endAt)
            operating[zone_id].append(tid)
        _bundle_by_interval(out, groups[PROGRAM_GROUP], "program")

        shifts = [(e.openAt, e.closeAt)]
        if e.closeAt - e.openAt > timedelta(hours=SHIFT_SPLIT_HOURS):
            mid = _round_half_hour(e.openAt + (e.closeAt - e.openAt) / 2)
            shifts = [(e.openAt, mid), (mid, e.closeAt)]
        for i, (start, end) in enumerate(shifts, 1):
            tid = f"tmp-entry-{i}"
            title = "입장 안내·방문객 응대" if len(shifts) == 1 else f"입장 안내·방문객 응대 ({'오전' if i == 1 else '오후'})"
            _task(out, tid, "entry", groups[ENTRY_GROUP], f"entry-{i}", entry_zone.id, None, title, start, end)
            operating[entry_zone.id].append(tid)

        for i, z in enumerate(p.zones, 1):
            setup, chk, down = f"tmp-setup-z{i}", f"tmp-check-z{i}", f"tmp-teardown-z{i}"
            bundle, group = f"setup-z{i}", setup_groups[(i - 1) % len(setup_groups)]
            _task(out, setup, "setup", group, bundle, z.id, None, f"{z.name} 설치",
                  e.prepStartAt, e.openAt - check)
            _task(out, chk, "check", group, bundle, z.id, None, f"{z.name} 개장 전 점검",
                  e.openAt - check, e.openAt)
            _task(out, down, "teardown", group, bundle, z.id, None, f"{z.name} 정리·철수",
                  e.closeAt, e.teardownEndAt)
            out.precedences.append((setup, chk))
            for tid in operating[z.id]:
                out.precedences += [(chk, tid), (tid, down)]

        out.assumptions += [
            Assumption(key="check-time", statement=f"개장 전 점검은 개장 {CHECK_MINUTES}분 전부터 진행한다고 가정했습니다.",
                       refs=["payload.event.openAt"]),
            Assumption(key="reserve-unbundled",
                       statement=f"희망 예비 {e.desiredReserveCount}명은 묶음에 넣지 않고 총괄 승인 투입용으로 남깁니다.",
                       refs=["payload.event.desiredReserveCount"]),
        ]
        out.tasks.sort(key=lambda t: (t["startAt"], t["tempId"]))
        return out


def _groups(existing, out):
    """템플릿 그룹 참조와 구역 설치 묶음을 맡을 그룹 목록을 정한다. 기존 그룹은 모두 후보에 남긴다.

    같은 이름의 기존 그룹이 있으면 그 id를 쓴다. 없으면 기존 직무를 이름 낱말(devices.purpose)로 맞춘다(S15P31A202-409):
    구역 설치·점검·정리는 장비 직무들이 구역 순서대로 돌아가며 맡고, 입장 안내는 첫 안전 직무가 맡는다. 맞는 기존 그룹이
    없을 때만 템플릿 그룹을 tempId로 만든다. 그래도 업무가 없는 기존 그룹은 필수 업무 게이트(quality.recover)가 채운다.
    """
    by_name = {g.name: g.id for g in existing}
    mapped = _template_map(existing)
    equipment = _purpose_ids(existing, "EQUIPMENT")
    refs = {}
    for name, temp in ((SETUP_GROUP, "tmp-wg-setup"), (PROGRAM_GROUP, "tmp-wg-program"), (ENTRY_GROUP, "tmp-wg-entry")):
        if mapped[name] is None:
            out.workGroups.append({"id": None, "tempId": temp, "name": name})
        refs[name] = mapped[name] or temp
    out.workGroups += [{"id": g.id, "tempId": None, "name": g.name} for g in existing]
    setup_groups = [by_name[SETUP_GROUP]] if SETUP_GROUP in by_name else (equipment or [refs[SETUP_GROUP]])
    return refs, setup_groups


def _purpose_ids(existing, purpose):
    return [g.id for g in existing if g.name not in (SETUP_GROUP, PROGRAM_GROUP, ENTRY_GROUP) and devices.purpose(g.name) == purpose]


def _template_map(existing):
    """템플릿 그룹 이름 → 맡을 기존 그룹 id(없으면 None). 사전 상한 검사와 _groups가 같은 매핑을 쓴다."""
    by_name = {g.name: g.id for g in existing}
    equipment, safety = _purpose_ids(existing, "EQUIPMENT"), _purpose_ids(existing, "SAFETY")
    return {SETUP_GROUP: by_name.get(SETUP_GROUP) or (equipment[0] if equipment else None),
            PROGRAM_GROUP: by_name.get(PROGRAM_GROUP),
            ENTRY_GROUP: by_name.get(ENTRY_GROUP) or (safety[0] if safety else None)}


def _entry_zone(zones, programs):
    """프로그램이 없는 첫 구역(로비 등)을 입장 구역으로 쓰고, 모두 프로그램이 있으면 첫 구역을 쓴다."""
    used = {x.zoneId for x in programs}
    return next((z for z in zones if z.id not in used), zones[0])


def _task(out, tid, kind, group, bundle, zone_id, program_id, title, start, end):
    out.tasks.append({"tempId": tid, "workGroupId": group, "bundleKey": bundle, "zoneId": zone_id,
                      "programId": program_id, "title": title, "startAt": start, "endAt": end,
                      "steps": [], "checklist": [], "assigneeId": None})
    out.kinds[tid] = kind


def _bundle_by_interval(out, group, prefix):
    """bundleKey가 없는 그룹 업무를 시작 순으로 보고, 끝난 묶음이 있으면 이어 붙이고 없으면 새 묶음을 연다."""
    ends = []
    for t in sorted((t for t in out.tasks if t["workGroupId"] == group and t["bundleKey"] is None),
                    key=lambda t: t["startAt"]):
        slot = next((i for i, end in enumerate(ends) if end <= t["startAt"]), None)
        if slot is None:
            ends.append(t["endAt"])
            slot = len(ends) - 1
        ends[slot] = t["endAt"]
        t["bundleKey"] = f"{prefix}-{slot + 1}"


def _round_half_hour(t):
    return t.replace(minute=0 if t.minute < 30 else 30, second=0, microsecond=0)


# ---- Schedule·Staffing·Guide ----

def precedence_issues(draft):
    """선행 업무의 종료가 후행 업무의 시작보다 늦지 않은지 확인한다. 끝과 시작이 같은 시각은 허용한다."""
    by_id = {t["tempId"]: t for t in draft.tasks}
    out = []
    for before, after in draft.precedences:
        a, b = by_id.get(before), by_id.get(after)
        if a is None or b is None:
            out.append(contract.issue(f"precedence:{before}>{after}", "INVALID_REFERENCE", "업무 목록에 없는 선행 관계입니다."))
        elif a["endAt"] > b["startAt"]:
            out.append(contract.issue(f"precedence:{before}>{after}", "TIME_OVERLAP",
                                      f"{before}가 끝나기 전에 {after}가 시작합니다."))
    return out


def min_bundle_count(tasks):
    """그룹마다 시간이 겹치지 않게 이어 붙였을 때의 최소 묶음 수(_bundle_by_interval과 같은 방식, 끝=시작 허용)."""
    total = 0
    for group in dict.fromkeys(t["workGroupId"] for t in tasks):
        ends = []
        for t in sorted((t for t in tasks if t["workGroupId"] == group), key=lambda t: (t["startAt"], t["endAt"])):
            slot = next((i for i, end in enumerate(ends) if end <= t["startAt"]), None)
            if slot is None:
                ends.append(t["endAt"])
            else:
                ends[slot] = t["endAt"]
        total += len(ends)
    return total


def staffing_issue(req, bundles, tasks=None):
    """묶음 수 초과를 생성기에 보완 요청하는 위반 항목.

    tasks를 주면 그룹별 최소 묶음 수와 비교한다. 이미 최소이면(GMS 생성기가 다시 묶은 경우) 같은 그룹 업무를 합치라는
    안내로는 줄일 수 없으므로, 동시에 진행하는 업무 수를 줄이라고 안내한다(MR !278 리뷰).
    """
    e = req.payload.event
    available = e.totalCapacity - e.desiredReserveCount
    head = f"업무 묶음 {len(bundles)}개가 전담 가능 인원 {available}명(총인원−희망 예비)보다 많습니다. "
    if tasks is not None and len(bundles) <= min_bundle_count(tasks):
        return contract.issue("staffing:bundles", "TOO_MANY_BUNDLES",
                              head + f"같은 직무 그룹에서 시간이 겹치지 않는 업무는 이미 모두 합쳐 최소 {len(bundles)}개입니다. "
                              f"묶음을 {available}개 이하로 줄이려면 같은 시각에 동시에 진행하는 업무 수를 줄이세요.")
    return contract.issue("staffing:bundles", "TOO_MANY_BUNDLES",
                          head + f"같은 직무 그룹에서 시간이 겹치지 않는 업무를 같은 bundleKey로 합쳐 묶음을 {available}개 이하로 줄이세요.")


def staffing_question(req, bundles):
    """묶음 하나를 한 사람이 전담한다. 총인원에서 희망 예비를 뺀 인원보다 묶음이 많으면 조건을 확인한다."""
    e = req.payload.event
    available = e.totalCapacity - e.desiredReserveCount
    if len(bundles) <= available:
        return None
    return Question(key="staffing", field="payload.event.totalCapacity",
                    question=f"업무 묶음 {len(bundles)}개를 전담할 인원이 {available}명입니다. 총인원이나 희망 예비 인원을 확인해 주세요.")


GUIDES = {
    "setup": (["배치도와 필요 물품 목록을 확인한다", "테이블·안내판·장비를 배치한다", "전원 연결과 통로 안전을 확인한다"],
              [{"key": "layout-done", "text": "배치 완료", "required": True}]),
    "check": (["설치 상태를 배치도와 대조한다", "이상이 있으면 총괄에게 바로 보고한다"],
              [{"key": "ready-reported", "text": "개장 준비 완료 보고", "required": True}]),
    "program": (["시작 전 장비와 진행 순서를 확인한다", "진행 중 이상은 즉시 총괄에게 보고한다", "종료 뒤 다음 순서를 위해 구역을 정돈한다"],
                [{"key": "started-on-time", "text": "정시 시작 확인", "required": True}]),
    "entry": (["입장 동선과 안내판 위치를 확인한다", "방문객에게 입장 방법과 구역 위치를 안내한다", "혼잡하면 총괄에게 보고한다"],
              []),
    "teardown": (["장비 전원을 끄고 반납 목록과 대조한다", "물품을 철수하고 구역을 원상 복구한다", "분실물·파손은 총괄에게 보고한다"],
                 [{"key": "area-cleared", "text": "구역 원상 복구", "required": True}]),
}
GROUP_GUIDES = {
    SETUP_GROUP: "개장 전 점검까지 끝낸 뒤 총괄에게 준비 완료를 보고합니다. 정리는 운영 종료 뒤 시작합니다.",
    PROGRAM_GROUP: "프로그램 시작 전 담당 구역의 점검 완료를 확인하고, 진행 중 이상은 바로 보고합니다.",
    ENTRY_GROUP: "입장 안내는 개장 시각부터 시작합니다. 혼잡이나 분실물은 앱 보고로 알려 주세요.",
}


def guide(req, draft):
    """steps가 빈 업무에 종류별 절차·체크를 채우고, 안내가 없으면 공통·그룹 안내를 만든다."""
    for t in draft.tasks:
        if not t["steps"]:
            steps, checklist = GUIDES.get(draft.kinds.get(t["tempId"]), GUIDES["program"])
            t["steps"], t["checklist"] = list(steps), [dict(c) for c in checklist]
    if draft.instructions:
        return
    e = req.payload.event
    lines = [f"{e.name} 운영 안내입니다.", e.description]
    for key, value in confirmed_conditions(req).items():
        lines.append(f"확인된 조건: {value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)}")
        draft.conditionRefs.append((key, "instruction:EVENT"))
    draft.instructions.append({"scope": "EVENT", "targetId": None, "body": " ".join(x for x in lines if x)[:1000]})
    for g in draft.workGroups:
        if g["name"] in GROUP_GUIDES:
            draft.instructions.append({"scope": "WORK_GROUP", "targetId": g["id"] or g["tempId"],
                                       "body": GROUP_GUIDES[g["name"]]})


# ---- Review: 필수 업무 누락 검사 (S15P31A202-252, AI 내부 품질 검사) ----

def _filled(value):
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict)):
        return bool(value)
    return value is not None


def confirmed_conditions(req):
    """값이 비어 있지 않은 confirmedInput 항목. 빈 문자열·null·빈 목록/객체는 확인된 조건으로 보지 않는다."""
    return {k: v for k, v in req.payload.event.confirmedInput.items() if _filled(v)}


def _instruction_target(ins):
    return "instruction:EVENT" if ins.get("scope") == "EVENT" else f"instruction:WORK_GROUP:{ins.get('targetId')}"


def _z(value):
    return _at(value).astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _at(value):
    return value if isinstance(value, datetime) else datetime.fromisoformat(value)


def coverage_issues(req, tasks, instructions=(), condition_refs=()):
    """READY 후보가 운영에 필요한 업무를 빠뜨리지 않았는지 업무의 구역·시간·조건 참조만으로 판정한다.

    - 구역마다 준비 업무(endAt ≤ openAt)와 정리 업무(startAt ≥ closeAt)가 각각 1건 이상
    - 입장 운영: programId 없이 개장 시각에 진행 중인 업무(startAt ≤ openAt < endAt) 1건 이상
    - 확인된 조건(confirmedInput) 키마다 그 키를 참조하는 업무 또는 안내 1건 이상
    업무의 의미(설치인지 점검인지)와 조건 반영 내용의 정확성은 판정하지 않는다. 시각은 datetime 또는 ISO 문자열이다.
    반환은 (위반 목록, 근거 목록)이며 근거는 "condition:키>대상"이다.
    """
    e = req.payload.event
    out = []
    for z in req.payload.zones:
        mine = [t for t in tasks if t["zoneId"] == z.id]
        if not any(_at(t["endAt"]) <= e.openAt for t in mine):
            out.append(contract.issue(f"coverage:zone:{z.id}:prep", "MISSING_TASK",
                                      f"구역 '{z.name}'({z.id})에 개장({_z(e.openAt)}) 이전에 끝나는(endAt ≤ openAt) 설치·점검 업무가 없습니다. "
                                      f"기존 준비 업무의 종료 시각을 앞당기거나 업무를 추가하세요."))
        if not any(_at(t["startAt"]) >= e.closeAt for t in mine):
            out.append(contract.issue(f"coverage:zone:{z.id}:teardown", "MISSING_TASK",
                                      f"구역 '{z.name}'({z.id})에 운영 종료({_z(e.closeAt)}) 이후 시작하는(startAt ≥ closeAt) 정리·철수 업무가 없습니다. "
                                      f"마지막 프로그램 종료 시각이 아니라 운영 종료 시각 이후로 기존 정리 업무를 옮기세요."))
    if not any(t.get("programId") is None and _at(t["startAt"]) <= e.openAt < _at(t["endAt"]) for t in tasks):
        out.append(contract.issue("coverage:entry", "MISSING_TASK",
                                  "개장 시각에 진행 중인 입장 운영 업무(programId 없음)가 없습니다."))
    targets = {f"task:{t['tempId']}" for t in tasks} | {_instruction_target(i) for i in instructions}
    evidence, referenced = [], set()
    conditions = confirmed_conditions(req)
    for key, target in condition_refs:  # 입력에 없는 키(프롬프트 예시 유입 등)와 없는 대상은 근거로 세지 않는다
        if key in referenced or key not in conditions or target not in targets:
            continue
        referenced.add(key)
        evidence.append(f"condition:{key}>{target}")
    for key in conditions:
        if key not in referenced:
            out.append(contract.issue(f"coverage:condition:{key}", "MISSING_CONDITION",
                                      f"확인된 조건 '{key}'을 반영했다고 참조한 업무나 안내가 없습니다."))
    return out, evidence


def bundle_summaries(tasks):
    out = {}
    for t in tasks:
        b = out.setdefault(t["bundleKey"], {"bundleKey": t["bundleKey"], "workGroupId": t["workGroupId"],
                                             "taskCount": 0, "startAt": t["startAt"], "endAt": t["endAt"]})
        b["taskCount"] += 1
        b["startAt"], b["endAt"] = min(b["startAt"], t["startAt"]), max(b["endAt"], t["endAt"])
    return list(out.values())


# ---- Supervisor: 실행 순서와 분기 ----

class GeneratorUnavailable(RuntimeError):
    """생성 모델·연결 장애. 흐름은 AI_UNAVAILABLE(retryable)로 반환한다."""


class _DeadlinePassed(Exception):
    pass


UNAVAILABLE_ERRORS = (GeneratorUnavailable, OSError)  # OSError: ConnectionError·TimeoutError·URLError 포함


class NoFloorPlanReader:
    """평면도를 읽지 않는 실행(규칙 생성기 등). floorPlan이 있으면 venueDraft를 null로 두고 이유를 남긴다."""
    calls = 0

    def read(self, req, zone_names):
        from floor_plan import VenueOutcome, _unresolved_failure
        fp = req.payload.floorPlan
        if fp is None:
            return VenueOutcome(summary="평면도 없음(venueDraft=null)")
        return VenueOutcome(unresolved=[_unresolved_failure(fp, "이 실행은 평면도를 읽지 않음")],
                            summary="평면도 읽기를 쓰지 않는 실행이라 venueDraft=null")


def run(raw_request, now=None, generator=None, venue_reader=None, device_reader=None):
    """요청 하나를 실행해 성공(READY/NEEDS_INFO) 또는 실패 envelope dict를 반환하고, 예외를 밖으로 내지 않는다.

    now는 고정 시각(datetime)이나 현재 시각을 돌려주는 함수다. 생략하면 실제 UTC 시각을 쓴다.
    deadlineAt은 시작·생성기 호출 뒤·단계마다·반환 직전에 다시 확인한다.
    venue_reader는 요청에 floorPlan 키가 있을 때(결과6)만 READY 직전에 한 번 쓴다. 생략하면 평면도를 읽지 않는다.
    device_reader는 READY 결과(결과5·6)에 카메라·장비·그룹 표시 선택 필드(S15P31A202-400, V9-D61)를 낼 때만 준다.
    생략하면 키를 내보내지 않는다.
    """
    generator = generator or RuleGenerator()
    clock = now if callable(now) else (lambda: now) if now is not None else (lambda: datetime.now(timezone.utc))
    try:
        req = contract.parse_request(raw_request)
    except ContractViolation as e:
        return contract.failure(e.request, "INVALID_REQUEST", "요청이 최초 계획 계약을 위반했습니다.",
                                {"issues": [i["path"] for i in e.issues]})
    try:
        return _execute(req, generator, lambda: _check_deadline(req, clock), venue_reader, device_reader)
    except _DeadlinePassed:
        return contract.failure(req, "AI_TIMEOUT", "실행 마감이 지났습니다. 다시 생성해 주세요.")


def _check_deadline(req, clock):
    if contract.deadline_passed(req, clock()):
        raise _DeadlinePassed


def _invalid_artifact(req, stage, refs=()):
    return contract.failure(req, "AI_REVIEW_FAILED", "계획 후보가 계약 검사를 통과하지 못했습니다. 직접 구성해 주세요.",
                            {"reason": "INVALID_ARTIFACT", "stage": stage, "unresolvedRefs": list(refs)})


def _execute(req, generator, check_deadline, venue_reader=None, device_reader=None):
    check_deadline()
    trace = _Trace()
    trace.venue_reader = venue_reader or NoFloorPlanReader()
    trace.device_reader = device_reader
    questions = contract.precheck_questions(req) + time_questions(req)
    if questions:
        return contract.needs_info(req, questions, generator.model_version, trace.coordination())

    outcome = _plan_round(req, generator, trace, check_deadline, None)
    if isinstance(outcome, list):  # 묶음 수 초과: 다음 PLANNING 노드로 1회 재위임한다(실행당 Planning 최대 2회)
        trace.redelegations.append(Redelegation(taskId=f"t{len(trace.graph) + 1}",
                                                reason="업무 묶음을 전담 가능 인원 이하로 줄이도록 보완 요청"))
        outcome = _plan_round(req, generator, trace, check_deadline, outcome)
    return outcome


def _plan_round(req, generator, trace, check_deadline, feedback):
    """Planning부터 Review까지 한 번 실행한다. 응답 dict 또는 묶음 수 초과 보완 요청 목록을 반환한다.

    보완 회차(feedback 있음)에서는 묶음 수가 여전히 넘으면 인원 질문으로 끝나므로 항상 응답 dict를 반환한다.
    """
    check_deadline()  # 회차마다 생성기 호출 직전에 마감을 확인한다(만료 뒤 보완 호출 방지)
    trace.evidence = []
    planning = trace.node("PLANNING")
    try:
        draft = generator.draft(req) if feedback is None else generator.draft(req, feedback=feedback)
    except UNAVAILABLE_ERRORS:
        check_deadline()
        return contract.failure(req, "AI_UNAVAILABLE", "계획 생성 모델에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요.",
                                {"stage": "PLANNING"})
    except Exception as e:  # 모델 출력 변환 오류: 필드 경로만 진단으로 남기고 원문·메시지는 내보내지 않는다
        check_deadline()
        path = getattr(e, "path", None)
        return _invalid_artifact(req, "PLANNING", [f"model.{path}"] if isinstance(path, str) else ())
    check_deadline()

    stage = "PLANNING"
    try:
        if draft.questions:
            trace.done(planning, "NEEDS_INFO", f"조건 확인 질문 {len(draft.questions)}건 반환")
            return contract.needs_info(req, draft.questions, generator.model_version, trace.coordination(),
                                       draft.assumptions, draft.unresolved)
        trace.done(planning, "SUCCEEDED", f"직무 그룹 {len(draft.workGroups)}개와 업무 {len(draft.tasks)}건 구성")

        stage = "SCHEDULE"
        schedule = trace.node(stage)
        dropped = {i["path"] for i in precedence_issues(draft)}  # 업무 시각을 기준으로 맞지 않는 선행 관계는 버린다
        draft.precedences = [(a, b) for a, b in draft.precedences if f"precedence:{a}>{b}" not in dropped]
        trace.evidence += [f"precedence:{a}>{b}" for a, b in draft.precedences]
        trace.evidence += [f"schedule:{key}>task:{tid}" for key, tid in draft.scheduleRefs]
        trace.done(schedule, "SUCCEEDED", f"선행 관계 {len(draft.precedences)}건을 시간 순서로 확인"
                   + (f", 업무 시각과 맞지 않는 {len(dropped)}건 제외" if dropped else "")
                   + (f", 문서 일정 {len(draft.scheduleRefs)}건을 원문 시각으로 반영" if draft.scheduleRefs else ""))
        check_deadline()

        stage = "STAFFING"
        staffing = trace.node(stage)
        bundles = bundle_summaries(draft.tasks)
        question = staffing_question(req, bundles)
        if question and feedback is None and getattr(generator, "revises", True):
            trace.done(staffing, "FAILED", f"묶음 {len(bundles)}개가 전담 가능 인원보다 많아 보완 요청", final=False)
            return [staffing_issue(req, bundles, draft.tasks)]
        if question:
            trace.done(staffing, "NEEDS_INFO", f"묶음 {len(bundles)}개에 비해 전담 인원이 부족")
            return contract.needs_info(req, [question], generator.model_version, trace.coordination(),
                                       draft.assumptions, draft.unresolved)
        e = req.payload.event
        trace.done(staffing, "SUCCEEDED",
                   f"총 {e.totalCapacity}명 중 예비 {e.desiredReserveCount}명을 뺀 인원으로 묶음 {len(bundles)}개 수용 가능")
        check_deadline()

        stage = "REVIEW"  # 필수 업무 품질 게이트(S15P31A202-409): 결정적으로 보완하고, 못 채운 누락은 unresolved로 남긴다(막지 않음)
        gate = trace.node(stage)
        filled = quality.recover(req, draft)
        trace.evidence += [f"required:{path}>task:{tid}" for path, tid in filled]
        left = quality.leave_unresolved(draft, quality.required_issues(req, draft.tasks))
        bundles = bundle_summaries(draft.tasks)
        notes = [f"빠진 업무 {len(filled)}건을 담당 묶음의 빈 시간에 보완" if filled else "",
                 f"보완하지 못한 누락 {len(left)}건을 미해결로 남김(차단하지 않음)" if left else ""]
        trace.done(gate, "SUCCEEDED", ", ".join(["구역·직무 그룹 필수 업무 검사" + ("" if left else " 통과")] + [x for x in notes if x]))
        check_deadline()

        stage = "GUIDE"
        guide_node = trace.node(stage)
        guide(req, draft)
        trace.done(guide_node, "SUCCEEDED", f"업무 절차와 공통·그룹 안내 {len(draft.instructions)}건 작성")
        check_deadline()

        stage = "REVIEW"
        review = trace.node(stage)
        # 조건 참조는 차단하지 않는다. 잘못된 참조는 버리고, 빠진 조건은 EVENT 안내에 원문을 붙이거나 unresolved로 남긴다
        cond = quality.recover_conditions(req, draft)
        _, condition_evidence = coverage_issues(req, draft.tasks, draft.instructions, draft.conditionRefs)
        trace.evidence = ([z.id for z in req.payload.zones] + [x.id for x in req.payload.programs]
                          + trace.evidence + condition_evidence)
        notes = [f"입력에 없는 조건 참조 {cond['dropped']}건 제외" if cond["dropped"] else "",
                 f"참조 없던 조건 {len(cond['attached'])}건을 공통 안내에 첨부" if cond["attached"] else "",
                 f"반영 위치를 찾지 못한 조건 {len(cond['unresolved'])}건 미해결" if cond["unresolved"] else ""]
        trace.done(review, "SUCCEEDED", ", ".join(["참조·시간·묶음·안내 계약 검사 통과"] + [n for n in notes if n]))
        venue = {}
        if req.payload.wants_venue:  # 결과6: 계획이 READY로 정해진 뒤 평면도를 한 번만 읽는다
            outcome = trace.read_venue(req)
            check_deadline()
            venue = {"venueDraft": outcome.draft}
            draft.unresolved = list(draft.unresolved) + list(outcome.unresolved)
        if trace.device_reader is not None:  # 카메라·장비·그룹 표시(결과5·6 선택 필드, S15P31A202-400, V9-D61)
            found = trace.read_devices(req)
            check_deadline()
            venue |= {"cameras": found.cameras, "equipmentDraft": {"assets": found.assets}}
            draft.workGroups = [{**g, "purpose": devices.purpose(g["name"])} for g in draft.workGroups]
        result = {
            "workGroups": draft.workGroups,
            "zones": [z.model_dump() for z in req.payload.zones],
            "programs": [x.model_dump() for x in req.payload.programs],
            "tasks": draft.tasks,
            "instructions": draft.instructions,
            "bundles": bundles,
            "assumptions": [a.model_dump() for a in draft.assumptions],
            "unresolved": [u.model_dump() for u in draft.unresolved],
            "questions": [],
            "coordination": trace.coordination().model_dump(),
            **venue,
        }
        response = contract.ready(req, result, generator.model_version)
    except _DeadlinePassed:
        raise
    except ContractViolation as e:
        return _invalid_artifact(req, stage, [i["path"] for i in e.issues])
    except ValidationError as e:
        return _invalid_artifact(req, stage, [i["path"] for i in contract.schema_issues(e, "result")])
    except Exception:
        return _invalid_artifact(req, stage)
    check_deadline()
    return response


class _Trace:
    """coordination에 남길 작업 그래프·근거·기여. 원시 입력이나 생성기 내부 값은 넣지 않는다."""

    def __init__(self):
        self.graph = [AgentTaskNode(taskId="t1", role="SUPERVISOR", status="SUCCEEDED")]
        self.contributions = []
        self.evidence = []
        self.redelegations = []
        self.venue_reader = None
        self._venue = None
        self.device_reader = None

    def read_venue(self, req):
        """평면도 읽기(PLANNING 노드). 실행당 한 번만 부르고 결과를 다시 쓴다. 실패해도 계획은 막지 않는다."""
        if self._venue is None:
            node = self.node("PLANNING")
            self._venue = self.venue_reader.read(req, [z.name for z in req.payload.zones])
            self.done(node, "SUCCEEDED", self._venue.summary)
        return self._venue

    def read_devices(self, req):
        """카메라·장비 읽기(PLANNING 노드). 실패해도 계획은 막지 않고 빈 목록이다."""
        node = self.node("PLANNING")
        found = self.device_reader.read(req)
        self.done(node, "SUCCEEDED", found.summary)
        return found

    def node(self, role):
        node = AgentTaskNode(taskId=f"t{len(self.graph) + 1}", parentTaskId="t1", role=role, status="SUCCEEDED")
        self.graph.append(node)
        return node

    def done(self, node, status, summary, final=True):
        """final=False는 보완 요청으로 이어지는 중간 실패라 Supervisor 상태를 바꾸지 않는다."""
        node.status = status
        self.contributions.append(Contribution(role=node.role, summary=summary))
        if status != "SUCCEEDED" and final:
            self.graph[0].status = status

    def coordination(self):
        return Coordination(graph=self.graph, redelegations=self.redelegations, evidenceRefs=self.evidence,
                            contributions=self.contributions)


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    now, generator, venue_reader = None, None, None
    if "--now" in argv:
        i = argv.index("--now")
        now = datetime.fromisoformat(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
    if "--generator" in argv:
        i = argv.index("--generator")
        if argv[i + 1] == "gms":
            from gms_generator import GmsGenerator  # GMS 호출은 이 옵션을 줄 때만 일어난다
            generator = GmsGenerator()
        argv = argv[:i] + argv[i + 2:]
    if "--floor-plan" in argv:  # 결과6 요청의 평면도를 로컬 파일로 읽는다(GMS 이미지 입력 1회). 내려받기는 건너뛴다
        i = argv.index("--floor-plan")
        from floor_plan import GmsFloorPlanReader
        data = Path(argv[i + 1]).read_bytes()
        venue_reader = GmsFloorPlanReader(fetcher=lambda req: data)
        argv = argv[:i] + argv[i + 2:]
    if len(argv) in (2, 3) and argv[0] == "run":
        response = run(json.loads(Path(argv[1]).read_text(encoding="utf-8")), now, generator, venue_reader)
        text = json.dumps(response, ensure_ascii=False, indent=2) + "\n"
        if len(argv) == 3:
            out = Path(argv[2])
            out.write_text(text, encoding="utf-8")
            if getattr(generator, "raw_outputs", None):  # GMS 원본 출력은 <출력>-raw.json에 남긴다
                out.with_name(f"{out.stem}-raw.json").write_text(
                    json.dumps(generator.raw_outputs, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            if getattr(venue_reader, "raw_outputs", None):
                out.with_name(f"{out.stem}-floor-plan-raw.json").write_text(
                    json.dumps(venue_reader.raw_outputs, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        else:
            print(text, end="")
        if getattr(generator, "usage", None):
            print(f"GMS 호출 {generator.calls}회, 사용량: {generator.usage}", file=sys.stderr)
        if getattr(venue_reader, "usage", None):
            print(f"평면도 GMS 호출 {venue_reader.calls}회, 사용량: {venue_reader.usage}", file=sys.stderr)
        for error in getattr(generator, "errors", []):
            print(error, file=sys.stderr)
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    # gms_generator가 import하는 flow 모듈과 같은 GeneratorUnavailable을 쓰도록 모듈로 다시 불러 실행한다.
    import flow
    sys.exit(flow.main(sys.argv[1:]))

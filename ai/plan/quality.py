"""필수 업무 품질 게이트 (S15P31A202-409).

모델 후보가 요청에 있는 구역·직무 그룹의 필수 업무 단계를 빠뜨리면 규칙으로 채우고, 채우지 못한 단계는 결과의
unresolved로 드러낸다. 계획은 막지 않는다(252 MVP 기준·AUD-T09). 판정·보완은 업무의 구역·직무 그룹·묶음·시각과
제목 낱말만 쓰는 결정적 규칙이며 모델을 다시 부르지 않는다.

필수 업무 (위반 경로)
- 구역마다 준비(endAt ≤ openAt)·운영([openAt, closeAt)와 겹침)·정리(startAt ≥ closeAt) 업무: coverage:zone:<id>:prep|operation|teardown
- 개장 시각 입장 운영(programId 없음, startAt ≤ openAt < endAt, 제목에 ENTRY_WORDS 낱말): coverage:entry
- 요청의 기존 직무 그룹마다 업무 1건 이상: coverage:group:<id>:tasks
- 장비 직무(devices.purpose == EQUIPMENT)의 운영 중 대기·운용(운영 구간과 겹치고, 프로그램에 연결됐거나 제목에 STANDBY_WORDS
  낱말)과 철수(startAt ≥ closeAt): coverage:group:<id>:operation|teardown
  업무 종류는 모델 출력에 없어 제목 낱말로 본다. 점검·설치만 하는 업무는 입장·대기로 인정하지 않는다.

보완 (recover)
- 빠진 단계를 그 구역·직무를 이미 맡은 묶음(한 사람)의 빈 시간에 업무로 더한다. 준비는 개장 직전, 정리·철수는 운영 종료
  직후 최대 30분, 운영은 운영 구간 안 가장 긴 빈 시간이다. 빈 시간이 5분 미만이면 그 묶음에 넣지 않는다.
- 맡은 묶음이 없거나 모두 바쁘면, 묶음 수가 전담 가능 인원(총인원 − 희망 예비)보다 적을 때만 새 묶음을 연다.
- 업무가 하나도 없는 기존 직무 그룹은 새 묶음으로 채운다(장비 직무는 준비·운영·철수, 그 밖은 운영 업무 1건).
- 더한 업무마다 assumptions(required-fill-n)와 근거(required:<경로>>task:<tempId>)를 남긴다.
보완하지 못한 경로는 leave_unresolved가 unresolved(required-n)로 남기고 계획은 READY로 나간다. 총괄이 직접 채운다.

조건 참조 (recover_conditions, 차단하지 않음)
- 통과: 값이 있는 confirmedInput 키마다 그 키를 conditionRefs에 적은 업무·안내가 결과에 있다.
- 입력에 없는 키를 적은 참조(예: 프롬프트 예시 entryRule)와 결과에 없는 대상을 가리키는 참조는 버린다.
- 참조가 없는 키는 EVENT 안내 끝에 "확인된 조건(키): 값"으로 원문을 붙여 전달하고 참조를 단다.
- 예외: 붙이면 안내 본문 상한(1000자)을 넘는 키는 붙이지 않고 unresolved(condition-<키>)로 남긴다. 계획 전체는 막지 않는다.
  참조는 존재만 보장하며, 업무 절차에 조건을 맞게 반영했는지는 사람이 확인한다.
"""

import json
import re
from datetime import datetime, timedelta

import contract
import devices
from contract import Assumption, Unresolved

FILL_MINUTES = 30      # 보완 업무 하나의 최대 길이(준비·정리·철수)
MIN_FILL_MINUTES = 5   # 이보다 짧은 빈 시간에는 업무를 넣지 않는다
BODY_MAX = 1000
# 입장 운영·장비 대기로 인정하는 제목 낱말 (저장된 실제 GMS 출력 제목 기준)
ENTRY_WORDS = ("입장", "입구", "게이트", "검표", "매표", "티켓", "체크인", "팔찌")
STANDBY_WORDS = ("대기", "상태 확인", "모니터링", "상주", "운영", "운용", "장애 대응", "오퍼레이")
PHASES = ("prep", "operation", "teardown")

STEPS = {
    "setup": ["담당 구역의 배치와 필요 물품을 확인한다", "통로·전원·장비 상태를 점검한다", "개장 준비 완료를 총괄에게 보고한다"],
    "operation": ["담당 구역의 관객 흐름과 현장 상태를 확인한다", "혼잡·이상이 보이면 총괄에게 바로 보고한다"],
    "standby": ["운영 중 장비 상태를 확인한다", "장애가 나면 예비 장비로 전환하고 총괄에게 보고한다"],
    "teardown": ["장비 전원을 끄고 반납 목록과 대조한다", "물품을 철수하고 구역을 원상 복구한다", "분실물·파손은 총괄에게 보고한다"],
    "entry": ["입장 동선과 안내판 위치를 확인한다", "관객에게 입장 방법과 구역 위치를 안내한다", "혼잡하면 총괄에게 보고한다"],
}


def _at(value):
    return value if isinstance(value, datetime) else datetime.fromisoformat(value)


def _span(t):
    return _at(t["startAt"]), _at(t["endAt"])


def equipment_groups(req):
    return [g for g in req.payload.workGroups if devices.purpose(g.name) == "EQUIPMENT"]


def _phases(event, mine):
    spans = [_span(t) for t in mine]
    return {"prep": any(x <= event.openAt for _, x in spans),
            "operation": any(s < event.closeAt and x > event.openAt for s, x in spans),
            "teardown": any(s >= event.closeAt for s, _ in spans)}


def _has(title, words):
    return any(w in (title or "") for w in words)


def _entry_ok(event, tasks):
    return any(t.get("programId") is None and _span(t)[0] <= event.openAt < _span(t)[1] and _has(t.get("title"), ENTRY_WORDS)
               for t in tasks)


def _standby(event, t):
    s, x = _span(t)
    return s < event.closeAt and x > event.openAt and (t.get("programId") is not None or _has(t.get("title"), STANDBY_WORDS))


def required_issues(req, tasks):
    """필수 업무 위반 목록(contract.issue). 순서는 구역 → 입장 → 직무 그룹이며 결정적이다."""
    e = req.payload.event
    out = []
    for z in req.payload.zones:
        found = _phases(e, [t for t in tasks if t["zoneId"] == z.id])
        for p in PHASES:
            if not found[p]:
                out.append(contract.issue(f"coverage:zone:{z.id}:{p}", "MISSING_TASK", f"구역 '{z.name}'의 {_LABEL[p]} 업무가 없습니다."))
    if not _entry_ok(e, tasks):
        out.append(contract.issue("coverage:entry", "MISSING_TASK", "개장 시각에 진행 중인 입장 운영 업무(programId 없음, 제목에 입장 낱말)가 없습니다."))
    equipment = {g.id for g in equipment_groups(req)}
    for g in req.payload.workGroups:
        mine = [t for t in tasks if t["workGroupId"] == g.id]
        if not mine:
            out.append(contract.issue(f"coverage:group:{g.id}:tasks", "MISSING_TASK", f"직무 그룹 '{g.name}'의 업무가 없습니다."))
            continue
        if g.id in equipment:
            found = _phases(e, mine)
            found["operation"] = any(_standby(e, t) for t in mine)
            for p in ("operation", "teardown"):
                if not found[p]:
                    out.append(contract.issue(f"coverage:group:{g.id}:{p}", "MISSING_TASK",
                                              f"장비 직무 '{g.name}'의 {_LABEL['standby' if p == 'operation' else p]} 업무가 없습니다."))
    return out


_LABEL = {"prep": "개장 전 준비", "operation": "운영 중", "teardown": "정리·철수", "standby": "운영 중 장비 대기"}


# ---- 보완 ----

class _Filler:
    def __init__(self, req, draft):
        self.req, self.draft, self.e = req, draft, req.payload.event
        self.filled = []   # (경로, tempId)
        ids = {t["tempId"] for t in draft.tasks} | {g.get("tempId") for g in draft.workGroups if g.get("tempId")}
        self.ids = ids
        self.available = self.e.totalCapacity - self.e.desiredReserveCount

    # 묶음 정보
    def bundles(self):
        out = {}
        for t in self.draft.tasks:
            out.setdefault(t["bundleKey"], []).append(t)
        return out

    def free(self, bundle_tasks, lo, hi):
        """[lo, hi) 안에서 묶음이 비어 있는 구간 목록(시간 순)."""
        busy = sorted(_span(t) for t in bundle_tasks)
        out, cur = [], lo
        for s, x in busy:
            if x <= cur or s >= hi:
                continue
            if s > cur:
                out.append((cur, min(s, hi)))
            cur = max(cur, x)
            if cur >= hi:
                break
        if cur < hi:
            out.append((cur, hi))
        return [(s, x) for s, x in out if x - s >= timedelta(minutes=MIN_FILL_MINUTES)]

    def slot(self, bundle_tasks, phase):
        e, cap = self.e, timedelta(minutes=FILL_MINUTES)
        if phase == "prep":
            segs = self.free(bundle_tasks, e.prepStartAt, e.openAt)
            return (max(segs[-1][0], segs[-1][1] - cap), segs[-1][1]) if segs else None
        if phase == "teardown":
            segs = self.free(bundle_tasks, e.closeAt, e.teardownEndAt)
            return (segs[0][0], min(segs[0][1], segs[0][0] + cap)) if segs else None
        if phase == "entry":
            segs = [s for s in self.free(bundle_tasks, e.prepStartAt, e.closeAt) if s[0] <= e.openAt < s[1]]
            return (e.openAt, min(segs[0][1], e.openAt + cap)) if segs else None
        segs = self.free(bundle_tasks, e.openAt, e.closeAt)
        return max(segs, key=lambda s: (s[1] - s[0], -s[0].timestamp())) if segs else None

    def new_bundle_key(self):
        used, n = set(self.bundles()), 1
        while f"req-{n}" in used:
            n += 1
        return f"req-{n}"

    def can_open(self):
        return len(self.bundles()) < self.available

    def add(self, path, kind, group, bundle, zone, title, span):
        n = 1
        while f"tmp-req-{n}" in self.ids:
            n += 1
        tid = f"tmp-req-{n}"
        self.ids.add(tid)
        self.draft.tasks.append({"tempId": tid, "workGroupId": group, "bundleKey": bundle, "zoneId": zone,
                                 "programId": None, "title": title[:100], "startAt": span[0], "endAt": span[1],
                                 "steps": list(STEPS[kind]), "checklist": [], "assigneeId": None})
        self.draft.kinds[tid] = kind
        self.filled.append((path, tid))
        return tid

    def place(self, path, kind, phase, candidates, zone_of, title_of, new_group):
        """candidates 묶음 중 빈 시간이 있는 첫 묶음에 넣고, 없으면 여유가 있을 때 new_group의 새 묶음에 넣는다."""
        bundles = self.bundles()
        for key in candidates:
            span = self.slot(bundles[key], phase)
            if span:
                group = bundles[key][0]["workGroupId"]
                self.add(path, kind, group, key, zone_of(bundles[key], span), title_of(group), span)
                return True
        if new_group is not None and self.can_open():
            span = self.slot([], phase)
            if span:
                self.add(path, kind, new_group, self.new_bundle_key(), zone_of([], span), title_of(new_group), span)
                return True
        return False


def _nearest_zone(bundle_tasks, span, default):
    if not bundle_tasks:
        return default
    mid = span[0] + (span[1] - span[0]) / 2
    return min(bundle_tasks, key=lambda t: (abs((_span(t)[0] - mid).total_seconds()), t["tempId"]))["zoneId"]


def _entry_zone(req):
    used = {x.zoneId for x in req.payload.programs}
    return next((z.id for z in req.payload.zones if z.id not in used), req.payload.zones[0].id)


def recover(req, draft):
    """필수 업무 누락을 결정적으로 보완한다. 더한 (경로, tempId) 목록을 반환한다. draft를 직접 바꾼다."""
    f = _Filler(req, draft)
    p, e = req.payload, req.payload.event
    zone_names = {z.id: z.name for z in p.zones}
    group_names = {g["id"] or g["tempId"]: g["name"] for g in draft.workGroups}
    group_names.update({g.id: g.name for g in p.workGroups})
    equipment = {g.id for g in equipment_groups(req)}
    program_zone = next((x.zoneId for x in sorted(p.programs, key=lambda x: (x.startAt, x.name)) if x.zoneId), None)

    def pending(prefix):
        return [i["path"] for i in required_issues(req, draft.tasks) if i["path"].startswith(prefix)]

    # 1. 업무가 없는 기존 직무 그룹: 새 묶음 하나로 채운다
    for path in pending("coverage:group:"):
        if not path.endswith(":tasks"):
            continue
        gid = path.split(":")[2]
        if not f.can_open():
            continue
        key = f.new_bundle_key()
        name = group_names[gid]
        if gid in equipment:
            zone = program_zone or p.zones[0].id
            plan = [("prep", "setup", f"{name} 개장 전 장비 연결·점검"), ("operation", "standby", f"{name} 운영 중 장비 상태 확인·대기"),
                    ("teardown", "teardown", f"{name} 장비 철수")]
        else:
            zone = _entry_zone(req)
            plan = [("operation", "operation", f"{name} 운영 지원")]
        for phase, kind, title in plan:
            span = f.slot([t for t in draft.tasks if t["bundleKey"] == key], phase)
            if span:
                f.add(path, kind, gid, key, zone, title, span)
        draft.unresolved = [u for u in draft.unresolved if not (u.key.startswith("group-") and u.refs == [gid])]

    def by_zone(zid):
        counts = {}
        for t in draft.tasks:
            if t["zoneId"] == zid:
                counts[t["bundleKey"]] = counts.get(t["bundleKey"], 0) + 1
        order = list(dict.fromkeys(t["bundleKey"] for t in draft.tasks))
        return sorted(counts, key=lambda k: (-counts[k], order.index(k)))

    def zone_group(zid):
        mine = [t["workGroupId"] for t in draft.tasks if t["zoneId"] == zid]
        if mine:
            return max(dict.fromkeys(mine), key=mine.count)
        safety = [g.id for g in p.workGroups if devices.purpose(g.name) == "SAFETY"]
        return safety[0] if safety else (draft.workGroups[0]["id"] or draft.workGroups[0]["tempId"] if draft.workGroups else None)

    # 2. 구역별 준비·운영·정리
    kinds = {"prep": "setup", "operation": "operation", "teardown": "teardown"}
    titles = {"prep": "개장 전 준비", "operation": "운영 중 현장 확인", "teardown": "정리·철수"}
    for path in pending("coverage:zone:"):
        _, _, zid, phase = path.split(":")
        f.place(path, kinds[phase], phase, by_zone(zid), lambda b, s, z=zid: z,
                lambda g, z=zid, ph=phase: f"{zone_names[z]} {titles[ph]}", zone_group(zid))

    # 3. 개장 시각 입장 운영: 안전 직무 묶음, 없으면 입장 구역을 맡은 묶음
    if pending("coverage:entry"):
        safety = {g.id for g in p.workGroups if devices.purpose(g.name) == "SAFETY"}
        safety |= {g["id"] or g["tempId"] for g in draft.workGroups if devices.purpose(g["name"]) == "SAFETY"}
        bundles = f.bundles()
        candidates = [k for k, ts in bundles.items() if ts[0]["workGroupId"] in safety] + \
                     [k for k in by_zone(_entry_zone(req)) if bundles[k][0]["workGroupId"] not in safety]
        f.place("coverage:entry", "entry", "entry", candidates,
                lambda b, s: _nearest_zone(b, s, _entry_zone(req)), lambda g: "입장 안내·관객 응대",
                zone_group(_entry_zone(req)))

    # 4. 장비 직무의 운영 중 대기·철수
    for path in pending("coverage:group:"):
        _, _, gid, phase = path.split(":")
        if phase not in ("operation", "teardown"):
            continue
        name = group_names[gid]
        candidates = list(dict.fromkeys(t["bundleKey"] for t in draft.tasks if t["workGroupId"] == gid))
        default = program_zone or p.zones[0].id
        title = f"{name} 운영 중 장비 상태 확인·대기" if phase == "operation" else f"{name} 장비 철수"
        f.place(path, "standby" if phase == "operation" else "teardown", phase, candidates,
                lambda b, s, d=default: _nearest_zone(b, s, d), lambda g, t=title: t, gid)

    for n, (path, tid) in enumerate(f.filled, 1):
        task = next(t for t in draft.tasks if t["tempId"] == tid)
        key = f"required-fill-{n}"
        while any(a.key == key for a in draft.assumptions):
            n += 100
            key = f"required-fill-{n}"
        draft.assumptions.append(Assumption(
            key=key, refs=[tid],
            statement=f"후보에 없던 필수 업무 '{task['title']}'을 규칙으로 보완했습니다. 시각과 담당을 확인해 주세요."[:200]))
    draft.tasks.sort(key=lambda t: (_at(t["startAt"]), t["tempId"]))
    return f.filled


def _target_ref(path):
    """위반 경로가 가리키는 요청 id. 입장은 개장 시각을 가리킨다."""
    parts = path.split(":")
    return "payload.event.openAt" if parts[1] == "entry" else parts[2]


def leave_unresolved(draft, issues):
    """보완하지 못한 필수 업무 누락을 unresolved(required-n)로 남긴다. 남긴 key 목록을 반환한다.

    업무 없는 기존 그룹은 변환이 이미 같은 그룹을 가리키는 안내를 남겼으면 겹쳐 쓰지 않는다.
    """
    keys = {u.key for u in draft.unresolved}
    added, n = [], 1
    for i in issues:
        ref = _target_ref(i["path"])
        if i["path"].endswith(":tasks") and any(u.refs == [ref] for u in draft.unresolved):
            continue
        while f"required-{n}" in keys:
            n += 1
        key = f"required-{n}"
        keys.add(key)
        draft.unresolved = list(draft.unresolved) + [Unresolved(
            key=key, refs=[ref], description=f"{i['message']} 인원·시간 여유가 없어 자동으로 채우지 못했습니다. 직접 추가해 주세요."[:200])]
        added.append(key)
    return added


# ---- 조건 참조 ----

def _filled(value):
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict)):
        return bool(value)
    return value is not None


def confirmed(req):
    return {k: v for k, v in req.payload.event.confirmedInput.items() if _filled(v)}


def _target(ins):
    return "instruction:EVENT" if ins.get("scope") == "EVENT" else f"instruction:WORK_GROUP:{ins.get('targetId')}"


def recover_conditions(req, draft):
    """조건 참조를 정리·보완한다. 반환: {"dropped": 버린 참조 수, "attached": EVENT 안내에 붙인 키, "unresolved": 남긴 키}."""
    conditions = confirmed(req)
    targets = {f"task:{t['tempId']}" for t in draft.tasks} | {_target(i) for i in draft.instructions}
    kept = [(k, t) for k, t in draft.conditionRefs if k in conditions and t in targets]
    dropped = len(draft.conditionRefs) - len(kept)
    draft.conditionRefs = kept
    referenced = {k for k, _ in kept}
    attached, unresolved = [], []
    for key, value in conditions.items():
        if key in referenced:
            continue
        text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
        line = f"확인된 조건({key}): {' '.join(text.split())}"
        event = next((i for i in draft.instructions if i.get("scope") == "EVENT"), None)
        body = event["body"] if event else ""
        joined = f"{body} {line}".strip()
        if len(joined) <= BODY_MAX:
            if event:
                event["body"] = joined
            else:
                draft.instructions.insert(0, {"scope": "EVENT", "targetId": None, "body": joined})
            draft.conditionRefs.append((key, "instruction:EVENT"))
            attached.append(key)
            continue
        safe = re.sub(r"[^A-Za-z0-9_-]", "_", key)
        draft.unresolved = list(draft.unresolved) + [Unresolved(
            key=f"condition-{safe}"[:40], refs=[f"payload.event.confirmedInput.{key}"],
            description=f"확인된 조건 '{key}'을 반영한 업무·안내를 찾지 못했고 공통 안내에 붙일 자리도 없습니다. 직접 반영해 주세요."[:200])]
        unresolved.append(key)
    return {"dropped": dropped, "attached": attached, "unresolved": unresolved}

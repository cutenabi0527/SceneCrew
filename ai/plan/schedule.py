"""문서 일정표 입력 어댑터(S15P31A202-408, AI 측 제안·검증용).

대표 시연 PDF의 '준비 일정과 업무' 표(7쪽 등)는 업무 이름·시작·종료 시각이 있지만 지금 요청4에는 실리지 않는다.
이 모듈은 그 표를 한 행씩 담는 최소 입력 모양(ScheduleItem)을 정하고, 계획 생성에서 원문 시각을 지키는 규칙을 구현한다.
V9-D66(2026-11-13)로 요청4 선택 키 scheduleItems가 되었다(모양은 contract.ScheduleItem). GmsGenerator(schedule=...)는
시험 경로로 남는다.

규칙
1. 확정 항목: 시작·종료가 모두 있고, 시작 < 종료이고, 행사 준비 시작~철수 완료 안이며, 같은 직무·같은 묶음의 다른 항목과
   시간이 겹치지 않는 항목. 모델에 scheduleItems로 보내고 결과 업무 시각을 원문 그대로 둔다.
2. 미확정 항목: 시각이 없거나(원문 '미정' 등) 위 조건을 어기는 항목. 모델에 보내지 않고 업무를 만들지 않는다.
   임의 시각을 정하지 않고 unresolved(schedule-unclear·schedule-conflict 등)로 남겨 총괄이 확인하게 한다.
   계획 전체를 막는 질문(NEEDS_INFO)으로 만들지 않는 이유는 일정표 한 줄 때문에 나머지 계획까지 버리지 않기 위해서다.
3. 모델 출력 변환(apply):
   - scheduleKey가 붙은 업무는 시각을 원문으로 되돌린다(모델이 바꾼 경우).
   - scheduleKey가 없어도 같은 직무 그룹에 같은 시작·종료의 업무가 있으면 그 항목을 반영한 것으로 본다.
   - 반영되지 않은 확정 항목은 직무 그룹·구역 이름이 요청과 정확히 맞으면 원문 시각으로 업무를 추가하고, 맞지 않으면
     unresolved(schedule-missing)로 남긴다.
   - 일정표가 있는 직무 그룹에서 일정 항목이 아닌 모델 업무가 일정 항목과 시간이 겹치면, 같은 일을 원문과 다른 시각으로
     만든 것으로 보고 빼고 그 사실을 assumptions에 남긴다. 겹치는 업무를 두면 한 사람이 같은 시각에 두 일을 맡게 된다.
"""

import json
from datetime import datetime, timezone
from pathlib import Path
from pydantic import TypeAdapter

from contract import Assumption, ScheduleItem, ScheduleSource, Unresolved  # noqa: F401 (schedule.ScheduleItem 경로 유지)

ITEMS = TypeAdapter(list[ScheduleItem])


def load(path):
    """fixture 파일({"scheduleItems": [...]})을 읽는다. key가 겹치면 ValueError다."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return parse(data["scheduleItems"])


def parse(raw_items):
    items = ITEMS.validate_python(raw_items)
    keys = [i.key for i in items]
    dup = sorted({k for k in keys if keys.count(k) > 1})
    if dup:
        raise ValueError(f"일정 항목 key가 중복됩니다: {dup}")
    return items


def _ref(item):
    return [f"schedule:{item.key}", f"{item.source.documentId}#page={item.source.pageNumber}"]


def _where(item):
    return f"{item.source.pageNumber}쪽 '{item.title}'"


def review(items, req):
    """(확정 항목, 미확정 unresolved 목록). 미확정 항목은 시각을 만들지 않고 이유만 남긴다."""
    e = req.payload.event
    problems = {}
    for item in items:
        if item.startAt is None or item.endAt is None:
            problems[item.key] = Unresolved(
                key=f"schedule-unclear-{item.key}"[:40], refs=_ref(item),
                description=f"일정표 {_where(item)}의 시각({item.timeText or '표기 없음'})을 확정할 수 없어 업무를 만들지 않았습니다. 시각을 확인해 주세요.")
        elif item.startAt >= item.endAt:
            problems[item.key] = Unresolved(
                key=f"schedule-invalid-{item.key}"[:40], refs=_ref(item),
                description=f"일정표 {_where(item)}의 종료 시각이 시작 시각보다 늦지 않습니다. 시각을 확인해 주세요.")
        elif item.startAt < e.prepStartAt or item.endAt > e.teardownEndAt:
            problems[item.key] = Unresolved(
                key=f"schedule-outside-{item.key}"[:40], refs=_ref(item),
                description=f"일정표 {_where(item)}이 행사 준비 시작~철수 완료 밖에 있습니다. 행사 시각이나 일정표를 확인해 주세요.")
    timed = [i for i in items if i.key not in problems]
    for a in timed:
        if a.workGroupName is None or a.bundle is None:
            continue
        clash = [b for b in timed if b is not a and (b.workGroupName, b.bundle) == (a.workGroupName, a.bundle)
                 and a.startAt < b.endAt and b.startAt < a.endAt]
        if clash:
            problems[a.key] = Unresolved(
                key=f"schedule-conflict-{a.key}"[:40], refs=_ref(a) + [f"schedule:{b.key}" for b in clash],
                description=f"일정표 {_where(a)}이 같은 담당({a.workGroupName} {a.bundle})의 "
                            f"{', '.join(repr(b.title) for b in clash)}와 시간이 겹칩니다. 어느 시각이 맞는지 확인해 주세요.")
    return [i for i in items if i.key not in problems], [problems[i.key] for i in items if i.key in problems]


def _z(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def model_items(items):
    """모델에 보낼 확정 항목. 시각은 요청과 같은 UTC(Z)로 바꾼다. 원문 위치·표기는 보내지 않는다."""
    return [{"key": i.key, "title": i.title, "startAt": _z(i.startAt), "endAt": _z(i.endAt),
             "workGroupName": i.workGroupName, "zoneName": i.zoneName, "bundle": i.bundle,
             **({"steps": list(i.steps)} if i.steps else {}),
             **({"checklist": list(i.checklist)} if i.checklist else {})} for i in items]


PROMPT = """
문서 일정표 (입력에 scheduleItems가 있을 때)
- scheduleItems는 총괄이 낸 문서의 일정표에서 시각까지 확인한 업무다. 항목마다 업무 1건을 만들고 startAt·endAt을 항목 값 그대로 쓴다.
  개장·운영 종료 시각을 넘는 항목(예: 개장 뒤까지 이어지는 사운드 체크)도 시각을 바꾸거나 자르지 않는다. 항목을 합치거나 나누지 않는다.
- 그 업무에 "scheduleKey"로 항목 key를 적는다. 일정 항목이 아닌 업무는 scheduleKey를 쓰지 않는다.
- steps·checklist가 있으면 그 업무의 steps·checklist로 그대로 쓴다.
- workGroupName이 있으면 그 이름의 기존 직무 그룹에, zoneName이 있으면 그 이름의 구역에 둔다. title은 항목 title을 그대로 쓴다.
- 같은 workGroupName·bundle의 항목은 같은 bundleKey로 묶는다.
- 일정표가 있는 직무 그룹에는 일정 항목과 시간이 겹치는 다른 업무를 만들지 않는다. 일정표에 없는 시각을 지어내지 않는다.
- 필수 업무 1~5 가운데 일정 항목으로 채워지지 않는 것만 별도 업무로 더한다."""


def _at(value):
    return value if isinstance(value, datetime) else datetime.fromisoformat(value)


def _span(item):
    """결과 업무에 넣는 시각. 다른 업무와 같은 UTC로 맞춘다(원문 오프셋 +09:00과 같은 순간)."""
    return item.startAt.astimezone(timezone.utc), item.endAt.astimezone(timezone.utc)


def _bundle_ref(item):
    """일정표의 같은 담당 묶음을 식별한다. 담당이나 묶음이 없으면 항목끼리 임의로 합치지 않는다."""
    if item.workGroupName is None or item.bundle is None:
        return item.workGroupName, item.bundle, item.key
    return item.workGroupName, item.bundle, None


def _same_identity(item, task, groups, zones):
    """모델 업무가 일정표의 이름·직무·구역을 그대로 가리키는지 확인한다."""
    if task["title"] != item.title:
        return False
    if item.workGroupName is not None and task["workGroupId"] != groups.get(item.workGroupName):
        return False
    if item.zoneName is not None and task["zoneId"] != zones.get(item.zoneName):
        return False
    return True


def _bind(refs, bundle_keys, item, task):
    """검증된 일정 항목과 업무를 연결한다. 같은 일정 묶음(같은 담당)의 업무는 처음 연결한 업무의 bundleKey로 모은다.

    모델이 한 사람의 일정을 단계별 bundleKey로 나눠도 연결을 거부하지 않는다. 거부하면 모델 업무와 같은 일정 업무가
    한 번 더 추가되어 묶음 수가 넘치고 계획 전체가 인원 질문으로 끝난다(리뷰에서 재현).
    """
    logical = _bundle_ref(item)
    task["bundleKey"] = bundle_keys.setdefault(logical, task["bundleKey"])
    refs[item.key] = task["tempId"]
    return True


def apply(draft, req, items, data_tasks):
    """모델 출력(draft, 원본 업무 목록 data_tasks의 scheduleKey)에 일정 규칙을 적용한다. 변경 요약 dict를 반환한다.

    draft.tasks는 이미 PlanDraft 형식(dict, datetime)이다. data_tasks는 같은 순서의 모델 원본 업무다.
    """
    valid, unclear = review(items, req)
    draft.unresolved = list(draft.unresolved) + unclear
    by_key = {i.key: i for i in valid}
    groups = {g.name: g.id for g in req.payload.workGroups}
    zones = {z.name: z.id for z in req.payload.zones}
    refs, restored, bundle_keys = {}, [], {}

    for task, raw in zip(draft.tasks, data_tasks):
        key = raw.get("scheduleKey")
        item = by_key.get(key) if isinstance(key, str) else None
        if item is None or key in refs or not _same_identity(item, task, groups, zones):
            continue
        if not _bind(refs, bundle_keys, item, task):
            continue
        if (task["startAt"], task["endAt"]) != (item.startAt, item.endAt):
            restored.append(key)
        task["startAt"], task["endAt"] = _span(item)

    for item in valid:  # scheduleKey가 없어도 이름·직무·구역·시각이 모두 같아야 반영으로 본다
        if item.key in refs:
            continue
        for task in draft.tasks:
            if (task["tempId"] in refs.values()
                    or (task["startAt"], task["endAt"]) != (item.startAt, item.endAt)
                    or not _same_identity(item, task, groups, zones)):
                continue
            if _bind(refs, bundle_keys, item, task):
                break

    # 모델이 만든 일정 업무도 문서의 수행 절차·점검 항목이 있으면 그것을 쓴다(문서가 우선, #410 리뷰)
    by_tid = {t["tempId"]: t for t in draft.tasks}
    for key, tid in refs.items():
        item, task = by_key[key], by_tid.get(tid)
        if task is None:
            continue
        if item.steps:
            task["steps"] = list(item.steps)
        if item.checklist:
            task["checklist"] = [{"key": f"check-{n}", "text": text, "required": True}
                                 for n, text in enumerate(item.checklist, start=1)]

    # 일정표와 모델 양쪽 모두 한 묶음뿐인 직무만 같은 담당 묶음으로 대응한다. 여러 묶음이면 추측하지 않는다.
    logical_by_group, model_by_group = {}, {}
    for item in valid:
        gid = groups.get(item.workGroupName)
        if gid is not None:
            logical_by_group.setdefault(gid, set()).add(_bundle_ref(item))
    for task in draft.tasks:
        model_by_group.setdefault(task["workGroupId"], set()).add(task["bundleKey"])
    for gid, logicals in logical_by_group.items():
        model_keys = model_by_group.get(gid, set())
        if len(logicals) == 1 and len(model_keys) == 1:
            bundle_keys.setdefault(next(iter(logicals)), next(iter(model_keys)))

    scheduled = set(refs.values())
    # 같은 담당 묶음으로 확인된 모델 업무만 일정표 업무와 겹칠 때 대체한다. 다른 묶음의 동시 업무는 보존한다.
    placed = [i for i in valid if i.key in refs or (i.workGroupName in groups and i.zoneName in zones)]
    replaced, keep = [], []
    for t in draft.tasks:
        overlap = t["tempId"] not in scheduled and any(
            groups.get(i.workGroupName) == t["workGroupId"]
            and bundle_keys.get(_bundle_ref(i)) == t["bundleKey"]
            and t["startAt"] < i.endAt and i.startAt < t["endAt"]
            for i in placed)
        (replaced if overlap else keep).append(t)
    draft.tasks = keep

    added, missing = [], []
    used_bundle_keys = {t["bundleKey"] for t in draft.tasks} | set(bundle_keys.values())

    def new_bundle_key():
        n = 1
        while f"sched-{n}" in used_bundle_keys:
            n += 1
        key = f"sched-{n}"
        used_bundle_keys.add(key)
        return key

    # 추가 업무 tempId는 남은 업무·대체된 업무(선행 관계가 가리킬 수 있음)·그룹 tempId와 겹치지 않게 정한다
    used_ids = ({t["tempId"] for t in draft.tasks} | {t["tempId"] for t in replaced}
                | {g.get("tempId") for g in draft.workGroups if g.get("tempId")})

    def new_task_id(key):
        tid, n = f"tmp-sched-{key}", 2
        while tid in used_ids:
            tid, n = f"tmp-sched-{key}-{n}", n + 1
        used_ids.add(tid)
        return tid

    for item in valid:
        if item.key in refs:
            continue
        gid, zid = groups.get(item.workGroupName), zones.get(item.zoneName)
        if gid is None or zid is None:
            missing.append(Unresolved(
                key=f"schedule-missing-{item.key}"[:40], refs=_ref(item),
                description=f"일정표 {_where(item)}을 업무로 만들지 못했습니다. 담당 직무·구역이 요청과 맞지 않습니다. 직접 추가해 주세요."))
            continue
        logical = _bundle_ref(item)
        bundle = bundle_keys.get(logical)
        if bundle is None:
            bundle = new_bundle_key()
            bundle_keys[logical] = bundle
        tid = new_task_id(item.key)
        draft.tasks.append({"tempId": tid, "workGroupId": gid, "bundleKey": bundle, "zoneId": zid, "programId": None,
                            "title": item.title, "startAt": _span(item)[0], "endAt": _span(item)[1],
                            "steps": list(item.steps) if item.steps else
                            [f"일정표 {item.source.pageNumber}쪽의 '{item.title}' 업무를 표의 절차대로 수행한다"[:120]],
                            "checklist": [{"key": f"check-{n}", "text": text, "required": True}
                                          for n, text in enumerate(item.checklist or [], start=1)],
                            "assigneeId": None})
        refs[item.key] = tid
        added.append(item.key)
    draft.unresolved += missing
    for t in replaced:
        n = len(draft.assumptions) + 1
        draft.assumptions.append(Assumption(
            key=f"schedule-replaced-{n}", refs=[t["tempId"]],
            statement=f"'{t['title']}' 업무가 같은 담당 묶음의 문서 일정표 시각과 겹쳐 일정표 업무로 대체했습니다."[:200]))
    draft.tasks.sort(key=lambda t: (t["startAt"], t["tempId"]))
    draft.scheduleRefs = sorted(refs.items())
    return {"kept": len(refs), "valid": len(valid), "restored": restored, "added": added,
            "replaced": [t["tempId"] for t in replaced], "unresolved": [u.key for u in unclear + missing]}


def kept(items, req, tasks):
    """평가 지표: 확정 항목 가운데 이름·직무·구역·시각이 원문과 같은 업무 수와 빠진 key."""
    valid, _ = review(items, req)
    groups = {g.name: g.id for g in req.payload.workGroups}
    zones = {z.name: z.id for z in req.payload.zones}
    out = []
    for item in valid:
        if not any((_at(t["startAt"]), _at(t["endAt"])) == (item.startAt, item.endAt)
                   and _same_identity(item, t, groups, zones) for t in tasks):
            out.append(item.key)
    return {"kept": len(valid) - len(out), "total": len(valid), "missing": out}

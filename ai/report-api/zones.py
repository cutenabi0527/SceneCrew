"""보고 개체를 같은 revision의 실제 구역 후보로 바꾼다.

우선순위는 문장에 직접 나온 구역(ZONE) → 프로그램의 구역(PROGRAM) → 직무 그룹의 허용 구역(ROLE)이다(AI07).
같은 구역은 우선순위가 가장 높은 근거 하나만 남긴다. 이름은 공백·대소문자를 무시하고 대조하며,
완전히 같은 이름이 없으면 개체 표현에 들어 있는 가장 긴 이름을 쓴다(예: 'A구역 부스' → 'A구역').
일치하는 이름이 없거나 프로그램의 구역이 미정이면 후보를 만들지 않는다. 이름으로 id를 지어내지 않는다.
"""

import re

SOURCES = ("ZONE", "PROGRAM", "ROLE")
MIN_CONTAINED = 2  # 개체 표현 안에서 찾는 이름의 최소 길이(공백 제외)


def key(name):
    return re.sub(r"\s+", "", name).casefold()


def match(text, items):
    """개체 표현과 이름이 맞는 항목. 완전 일치가 없으면 표현에 포함된 가장 긴 이름의 항목."""
    k = key(text)
    exact = [x for x in items if key(x.name) == k]
    if exact:
        return exact
    contained = [x for x in items if len(key(x.name)) >= MIN_CONTAINED and key(x.name) in k]
    if not contained:
        return []
    longest = max(len(key(x.name)) for x in contained)
    return [x for x in contained if len(key(x.name)) == longest]


def candidates(payload, entities, context_revision):
    """entities: [{type, text, ...}] (문장 순서). 반환: [{zoneId, source, sourceRef, contextRevision}]."""
    out, seen = [], set()
    for source in SOURCES:
        for ent in entities:
            if ent["type"] == source:
                for zone_id, ref in _zones_of(source, ent["text"], payload):
                    if zone_id not in seen:
                        seen.add(zone_id)
                        out.append({"zoneId": zone_id, "source": source, "sourceRef": ref,
                                    "contextRevision": context_revision})
    return out


def _zones_of(source, text, payload):
    """(zoneId, sourceRef) 목록. ZONE의 sourceRef는 Zone.id, PROGRAM은 Program.id, ROLE은 WorkGroup.id다."""
    if source == "ZONE":
        return [(z.id, z.id) for z in match(text, payload.allowedZones)]
    if source == "PROGRAM":
        return [(p.zoneId, p.id) for p in match(text, payload.allowedPrograms) if p.zoneId]
    return [(z, g.id) for g in match(text, payload.allowedWorkGroups) for z in g.zoneIds]

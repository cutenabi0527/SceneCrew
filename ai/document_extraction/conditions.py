"""Conservative extraction of labeled Korean event data and cue/zone tables."""

from collections import defaultdict
from datetime import date, time
from itertools import chain
import re

# BE04 required event values. This does not apply the BE completeness rules to DRAFT.
REQUIRED_FIELDS = (
    "date", "prepStartAt", "openAt", "closeAt", "teardownEndAt",
    "locationName", "address", "totalCapacity",
)
LABELS = {
    "공연 이름": "name", "행사 이름": "name", "행사명": "name", "공연명": "name",
    "날짜": "date", "행사 날짜": "date", "공연 날짜": "date", "공연일": "date",
    "행사 일자": "date", "공연 일자": "date", "행사일": "date",
    "장소": "locationName", "공연 장소": "locationName", "주소": "address",
    "운영 인원": "totalCapacity", "총 운영 인원": "totalCapacity", "총 인원": "totalCapacity",
    "준비 시작": "prepStartAt", "관객 입장": "openAt", "개장": "openAt",
    "운영 종료": "closeAt", "철수 완료": "teardownEndAt", "정리 종료": "teardownEndAt",
    "공연 시작": "performanceStart", "공연 종료": "performanceEnd", "공연 길이": "duration",
    "구역": "zones", "공연 순서": "programOrder",
}
TIME_FIELDS = {"prepStartAt", "openAt", "closeAt", "teardownEndAt", "performanceStart", "performanceEnd"}
PROVISIONAL = re.compile(r"미정|임시|미확정|가안|잠정|예정\s*\(변경")
DATE = re.compile(r"(\d{4})[-./년]\s*(\d{1,2})[-./월]\s*(\d{1,2})(?:일)?(?!\d)")
CLOCK = re.compile(r"(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)")
ZONE = re.compile(r"^[A-Z](?:\s*구역|\b)")
CUE = re.compile(r"^(?:첫째?|두 번째|둘째|제?\s*\d+\s*번(?:째)?)\s*곡")
PAIR = re.compile(r"^([^:：]+)[:：]\s*(.+)$")
HEADCOUNT = re.compile(r"^(?:운영 인원|총 인원)은\s*(.+)$")


def _cells(line):
    if line.startswith("|"):
        return [cell.strip() for cell in line.strip("|").split("|")]
    return [cell.strip() for cell in re.split(r"\t+| {2,}", line)]


def _values(field, raw):
    if PROVISIONAL.search(raw) or raw in {"-", "—", "없음"}:
        return []
    if field == "date":
        values = []
        for match in DATE.finditer(raw):
            try:
                values.append(date(*map(int, match.groups())).isoformat())
            except ValueError:
                continue
        return values
    if field in TIME_FIELDS:
        values = []
        for match in CLOCK.finditer(raw):
            try:
                hour, minute, second = match.groups()
                values.append(time(int(hour), int(minute), int(second or 0)).isoformat())
            except ValueError:
                continue
        return values
    if field == "totalCapacity":
        summary = raw.split("(", 1)[0].strip()
        counts = re.findall(r"(?<![\d.])(\d+)\s*명", summary)
        if re.fullmatch(r"\d{1,2}", summary):
            counts = [summary]
        return [str(int(count)) for count in counts if len(count) <= 2 and 1 <= int(count) <= 31]
    return [raw]


def _candidates(lines, zones, cues):
    for line in lines:
        line = line.strip()
        if not line:
            continue
        cells = _cells(line)
        pair = PAIR.fullmatch(line.removeprefix("- ").strip())
        if pair and (pair[1].strip() in LABELS or pair[1].strip() == "운영 시간"):
            label, value = pair[1].strip(), pair[2]
        elif len(cells) >= 2:
            label, value = cells[:2]
        else:
            label, value = "", ""

        if label == "운영 시간" and not PROVISIONAL.search(value):
            for clock, role in re.findall(
                r"(\d{1,2}:\d{2}(?::\d{2})?)\s*(준비 시작|관객 입장|개장|운영 종료|철수 완료|정리 종료|공연 시작|공연 종료)",
                value,
            ):
                yield LABELS[role], clock
        elif label in LABELS and value and not re.fullmatch(r"[:\s-]+", value):
            if label != "구역" or value != "위치":
                yield LABELS[label], value
        elif len(cells) >= 3 and ZONE.match(cells[0]) and cells[1]:
            zones.append(cells[0])
        elif (
            len(cells) >= 4 and cells[0].isdigit() and CLOCK.fullmatch(cells[1])
            and CLOCK.fullmatch(cells[2]) and CUE.match(cells[3])
        ):
            try:
                index = int(cells[0])
            except ValueError:
                # Some digit characters and overlong numbers are not valid integers.
                continue
            title = re.search(r"['‘“](.+?)['’”]", cells[3])
            cues[index].append(title[1] if title else cells[3])
        else:
            count = HEADCOUNT.match(line.removeprefix("- "))
            if count:
                yield "totalCapacity", count[1]


def _table_values(zones, cues):
    if zones:
        yield "zones", ", ".join(dict.fromkeys(zones))
    if cues:
        ordered = [list(dict.fromkeys(cues[index])) for index in sorted(cues)]
        baseline = [names[0] for names in ordered]
        yield "programOrder", " → ".join(baseline)
        for index, names in enumerate(ordered):
            for alternate in names[1:]:
                yield "programOrder", " → ".join(baseline[:index] + [alternate] + baseline[index + 1:])


# 계획서 부록의 "키 값" 표(계획 생성 조건을 그대로 적은 것). 키는 영문 한 단어이고 값은 다음 키까지 이어진다.
APPENDIX_HEADER = re.compile(r"^키\s+값$")
APPENDIX_KEY = re.compile(r"^([a-z][A-Za-z0-9]{1,39})\s+(\S.*)$")
PAGE_FOOTER = re.compile(r"\d+\s*/\s*\d+\s*쪽\s*$")
CONDITION_MAX = 1000   # BE 결과3·계획 조건 값 상한(코드 포인트)


def _appendix(lines):
    """부록 표의 (키, 값). 기본정보 키(name·date 등)는 위 규칙이 맡으므로 넘기지 않는다."""
    reserved = set(LABELS.values())
    out, key, inside = [], None, False
    for line in lines:
        line = line.strip()
        if APPENDIX_HEADER.match(line):
            inside, key = True, None
            continue
        if not inside or not line:
            continue
        if PAGE_FOOTER.search(line):
            inside, key = False, None
            continue
        match = APPENDIX_KEY.match(line)
        if match and match[1] in reserved:
            key = None  # 기본정보 키 줄은 받지 않고, 앞 값에도 붙이지 않는다
        elif match:
            key = match[1]
            out.append([key, match[2]])
        elif key and out:
            prev = out[-1][1]
            out[-1][1] = prev + ("" if prev.endswith(("·", "→")) else " ") + line
    for key, value in out:
        value = " ".join(value.split())
        if len(value) > CONDITION_MAX:
            value = value[:CONDITION_MAX - 1].rstrip() + "…"
        yield key, value


def appendix_keys(document_pages):
    """부록 표에서 나온 조건 키. 서버 결과3이 기본 키 목록 밖이어도 이 키는 넘긴다(#401)."""
    keys = set()
    for pages in document_pages:
        lines = [line for page in pages for line in page.splitlines()]
        keys.update(key for key, value in _appendix(lines) if value)
    return frozenset(keys)


def build_result(document_pages, unreadable):
    candidates = defaultdict(list)
    for pages in document_pages:
        zones = []
        cues = defaultdict(list)
        lines = [line for page in pages for line in page.splitlines()]
        for field, raw in chain(_candidates(lines, zones, cues), _table_values(zones, cues)):
            for value in _values(field, raw.strip()):
                if value not in candidates[field]:
                    candidates[field].append(value)
        # 부록 값은 총괄이 조건으로 적은 글이라 '미정' 같은 낱말이 있어도 그대로 넘긴다
        for field, value in _appendix(lines):
            if value and value not in candidates[field]:
                candidates[field].append(value)

    fields = {field: values[0] for field, values in candidates.items() if len(values) == 1}
    missing = [field for field in REQUIRED_FIELDS if not candidates.get(field)]
    conflicts = [
        {"field": field, "values": values}
        for field, values in candidates.items()
        if len(values) > 1
    ]
    return {
        "fields": fields,
        "missingRequired": missing,
        "conflictingValues": conflicts,
        "unreadableFiles": unreadable,
        "resultStatus": "PARTIAL" if missing or conflicts or unreadable else "COMPLETE",
    }


# ---- 구조 결과(V9-D66): 공연 순서 시각, 업무 일정표 행, 직무 그룹 ----
# 일정표 행: "HH:MM~HH:MM | 업무 | 직무 | 구역 | 담당 | 수행 절차 | 점검 항목"(담당 뒤는 선택).
# 절차·점검은 " / "로 나눈 한 줄이다(표 칸 안 줄바꿈은 줄 단위 읽기에서 행이 쪼개진다). 시각 칸을 못 읽으면 시각 없이(null) 원문 표기만 남긴다.
SPAN = re.compile(r"^(\d{1,2}:\d{2})\s*[~\-–]\s*(\d{1,2}:\d{2})$")
SCHEDULE_HEAD = re.compile(r"^시간$")
NO_ZONE = {"-", "—", "전체", "없음", ""}
MAX_SCHEDULE_ITEMS = 50   # 요청4 scheduleItems 상한(ai/plan/contract.py)
MAX_PROGRAMS, MAX_GROUPS = 20, 31   # 계획 공연·직무 그룹 상한(BE가 넘으면 그 필드를 통째로 버린다)
NAME_MAX, ZONE_MAX, TITLE_MAX, LABEL_MAX, PROGRAM_MAX = 20, 30, 100, 40, 20
STEP_MAX, CHECK_MAX, LIST_MAX = 120, 80, 6   # 업무 절차·점검 항목 상한(요청4·028과 같다)


def _parts(raw, limit):
    """" / "로 나눈 절차·점검 칸. 상한을 넘는 칸이나 7개 이상이면 쓰지 않는다(자르면 뜻이 바뀐다)."""
    parts = [part.strip() for part in raw.split(" / ") if part.strip() and part.strip() not in NO_ZONE]
    if not parts or len(parts) > LIST_MAX or any(len(part) > limit for part in parts):
        return None
    return parts


def _at(event_date, clock):
    """행사 날짜(Asia/Seoul)에 원문 HH:MM을 붙인 ISO 시각. 없는 시각(25:00 등)이면 None"""
    hour, minute = clock.split(":")[:2]
    try:
        value = time(int(hour), int(minute))
    except ValueError:
        return None
    return f"{event_date}T{value.isoformat(timespec='minutes')}:00+09:00"


def _zone_name(raw):
    name = raw.split("(", 1)[0].strip()
    return None if name in NO_ZONE or len(name) > ZONE_MAX else name


def _schedule_row(cells):
    """일정표 행이면 (시작, 종료, 시각 표기, 업무, 직무, 구역, 담당), 아니면 None"""
    if len(cells) < 4 or not cells[1] or not cells[2]:
        return None
    rest = (cells + ["", "", ""])[1:7]   # 담당(묶음)·수행 절차·점검 항목 칸은 없어도 된다
    span = SPAN.match(cells[0])
    if span:
        return span[1], span[2], cells[0], *rest
    # 시각 칸에 '미정' 같은 글만 있는 행도 일정표 행이다(시각 없이 남긴다).
    if PROVISIONAL.search(cells[0]) and not CLOCK.search(cells[0]):
        return None, None, cells[0], *rest
    return None


def structured(pages, event_date):
    """쪽 단위 원문에서 programs·scheduleItems·workGroups. 없는 것은 키를 넣지 않는다.

    pages는 (문서 id, 쪽 번호, 글)이다. event_date(YYYY-MM-DD)가 없으면 시각을 만들 수 없어 programs는 내지 않고,
    일정표 행은 시각 없이(startAt·endAt null) 원문 표기만 남긴다. 값을 지어내지 않는다.
    """
    programs, items, groups = [], [], []
    seen_programs, seen_cues = set(), set()
    documents = list(dict.fromkeys(document_id for document_id, _, _ in pages))
    for document_id, page_number, text in pages:
        document_index = documents.index(document_id)
        row = 0
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            cells = _cells(line)
            if SCHEDULE_HEAD.match(cells[0]):
                continue
            if (
                len(cells) >= 4 and cells[0].isdigit() and CLOCK.fullmatch(cells[1])
                and CLOCK.fullmatch(cells[2]) and CUE.match(cells[3])
            ):
                if event_date is None or cells[0] in seen_cues:
                    continue
                title = re.search(r"['‘“](.+?)['’”]", cells[3])
                name = (title[1] if title else cells[3]).strip()[:PROGRAM_MAX].rstrip()
                start, end = _at(event_date, cells[1]), _at(event_date, cells[2])
                # 자정을 넘는 곡(끝 ≤ 시작)은 날짜를 정할 수 없어 내지 않는다. 계획 공연 상한 20개(#410 리뷰)
                if (name and start and end and start < end and len(programs) < MAX_PROGRAMS
                        and (name, start, end) not in seen_programs):
                    seen_cues.add(cells[0])
                    seen_programs.add((name, start, end))
                    programs.append({"title": name, "startAt": start, "endAt": end})
                continue
            parsed = _schedule_row(cells)
            if parsed is None or len(items) >= MAX_SCHEDULE_ITEMS:
                continue
            start, end, time_text, title, group, zone, bundle, steps, checks = parsed
            row += 1
            group = group.strip()
            group = group if 0 < len(group) <= NAME_MAX and group not in NO_ZONE else None
            bundle = bundle.strip()
            bundle = bundle if 0 < len(bundle) <= LABEL_MAX and bundle not in NO_ZONE else None
            timed = event_date is not None and start is not None and end is not None
            items.append({
                "key": f"d{document_index + 1}p{page_number}r{row}"[:30],
                "title": title.strip()[:TITLE_MAX].rstrip(),
                "startAt": _at(event_date, start) if timed else None,
                "endAt": _at(event_date, end) if timed else None,
                "timeText": time_text[:LABEL_MAX],
                "workGroupName": group,
                "zoneName": _zone_name(zone),
                "bundle": bundle,
                "source": {"documentId": str(document_id), "pageNumber": page_number},
            })
            steps, checks = _parts(steps, STEP_MAX), _parts(checks, CHECK_MAX)
            if steps:
                items[-1]["steps"] = steps
            if checks:
                items[-1]["checklist"] = checks
            if group and group not in groups and len(groups) < MAX_GROUPS:
                groups.append(group)
    out = {}
    if programs:
        out["programs"] = programs
    if items:
        out["scheduleItems"] = items
    if groups:
        out["workGroups"] = [{"name": name} for name in groups]
    return out

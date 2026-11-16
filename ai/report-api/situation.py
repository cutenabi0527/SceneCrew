"""보고 분석 결과4의 운영 상황 추천 situation{primary, related} (V9-D48, S15P31A202-297).

전용 모델을 학습하지 않고, 기존 모델의 category와 문장 단서 규칙으로 정한다(결정적).

- 단서: 상황마다 정한 표현을 normalizedText에서 찾는다. 단서 뒤 같은 절에 부정(없·않·아니·`정상입니다`)이 오면
  (`지연은 현재 없습니다`, `펜스 상태는 정상입니다`) 세지 않는다. 결핍 단서(`부족`)는 `않·아니`가 와야 부정이다
  (`인력은 부족하지 않습니다`). `두 업무`·`같은 시간`·`먼저 진행` 같은 중립 표현은 같은 문장에 배정·순서 문맥이
  있을 때만 센다.
- primary(대표 1개 또는 null):
  1. 업무 충돌·공연 순서 변경은 기존 category로 표현되지 않아 단서가 있으면 이것을 대표로 둔다.
  2. 아니면 category(0.65 이상)를 대응표로 옮긴 상황. 단, 그 상황이 문장에서 부정만 됐으면 쓰지 않는다.
  3. 아니면 문장에서 가장 먼저 나온 단서의 상황.
  4. 모두 없으면 null(미분류). `UNCLASSIFIED` 같은 코드는 쓰지 않는다.
- related: primary를 뺀 나머지 단서 상황을 문장 순서로 중복 없이 둔다. 대응표 상황은 문장 단서가 있을 때만
  들어간다(모델 유형만으로 연관 상황을 더하지 않는다). primary가 null이면 비운다.
- 긍정 단서 없이 `특이사항 없습니다`·`정상입니다`처럼 전체가 이상 없다는 보고는 category와 관계없이 null이다.
  단, 복구·조치 표현(`다시 켜고`·`재시험`·`고쳤`·`복구했` 등)이 함께 있으면 해결 보고로 보고 대응표 상황을 쓴다(#357).
  `교체 완료되어`·`복구 완료 후`처럼 띄어 쓴 완료형도 복구로 본다. 복구 표현 바로 앞에 `안·못`이 있거나, 뒤 같은 절에
  부정·예정·의도(`안 했`·`필요 없`·`예정`·`싶`)가 오면 조치로 보지 않는다. 해결 여부는 응답의
  reportedState(RESOLVED_MENTION)로 구분한다. 평가셋은 `지연 없습니다`·`특이사항 없습니다`에도 RESOLVED_MENTION을
  붙이므로 reportedState만으로 상황을 정하지 않는다.

대응표는 docs/planning/product/08-operating-scenarios.md '기존 보고 모델과의 관계'와 같다.
SUPPLY는 '관련 요인'이라 그 자체로 상황이 아니고, OTHER·null은 미분류다.
"""

import re

SITUATIONS = ("SCHEDULE_DELAY", "STAFF_GAP", "TASK_CONFLICT", "EQUIPMENT_ANOMALY",
              "PERFORMANCE_ORDER_CHANGE", "AUDIENCE_SAFETY")

CATEGORY_TO_SITUATION = {
    "DELAY": "SCHEDULE_DELAY",
    "STAFF_GAP": "STAFF_GAP",
    "EQUIPMENT": "EQUIPMENT_ANOMALY",
    "CONGESTION": "AUDIENCE_SAFETY",
    "SUPPLY": None,
    "OTHER": None,
    None: None,
}

# 기존 category로 표현되지 않는 상황. 단서가 있으면 대응표 상황보다 먼저 대표로 둔다.
UNMAPPED = ("TASK_CONFLICT", "PERFORMANCE_ORDER_CHANGE")

_EQUIPMENT = (r"(마이크|믹서|스피커|조명|LED|엘이디|VCR|모니터|앰프|카메라|프로젝터|인이어|무전기|장비|콘솔|"
              r"케이블|전원|화면|송출|음향|소리|영상|스크린|리시버|수신기|배터리|스캐너|전광판)")

# 중립 표현은 같은 문장에 이 문맥이 있을 때만 단서로 센다(예: `두 업무 모두 진행 중`은 충돌이 아니다).
_ASSIGNED = r"(배정|배치|일정|근무|맡|들어가|잡혀|겹|예약|쓰기로|쓰게|사용)"
_ORDER_CONTEXT = r"(순서|곡|무대|앵콜|상영|VCR|게스트|인사|멘트)"
# `밀리다`는 관객이 밀리는 경우도 있어 일정 문맥이 있을 때만 지연으로 센다.
_TIME_CONTEXT = r"(곡|공연|일정|시작|시간|순서|리허설|입장|전환|상영|\d+\s?분)"
# 계획대로 진행한다는 문장의 `먼저`·`앞으로`는 순서 변경이 아니다.
_AS_PLANNED = r"(순서|예정|계획|원래)\s?대로"

# 상황별 단서. 문자열은 단독 단서, (단서, 문맥[, 제외])는 같은 문장에 문맥이 있고 제외 표현이 없어야 하는 중립 단서다.
CUES = {
    "SCHEDULE_DELAY": [
        r"지연", r"딜레이", r"늦어", r"늦게", r"늦었", r"늦을", r"늦춰",
        (r"밀(리|렸|려|릴)", _TIME_CONTEXT),
        r"(시작|입장|전환|리허설|공연)(을|이)?\s?못\s?(하|해|했)", r"\d+\s?분\s?(정도\s?)?(더|추가|늘어)",
        r"(시간|일정|공연)(이|을)?\s?(더\s?)?(걸|늘어|연장)",
    ],
    "STAFF_GAP": [
        r"(인원|사람|담당|담당자|스태프|요원|직원|팀원|인력|크루|기사|감독)(이|가|은|는|도)?\s?"
        r"(\S+\s)?(없|부족|안\s?보|안\s?와|안\s?왔|빠졌|비어|비었|못\s?(왔|와|오)|이탈|결근|모자)",
        r"공백", r"결원", r"자리(를)?\s?비", r"(혼자|한 명)(밖에|뿐|만)", r"(교대|대체)\s?(인원|투입|필요)",
    ],
    "TASK_CONFLICT": [
        r"겹(쳐|칩|치|친|침|쳤)", r"중복\s?(배정|예약)", r"(배정|예약|일정|업무)(이|가)?\s?충돌",
        r"(같이|함께)\s?배정",
        (r"(동시에|같은 시간)", _ASSIGNED), (r"두 (곳|군데|업무)", _ASSIGNED), (r"양쪽(에|을|다)", _ASSIGNED),
    ],
    "EQUIPMENT_ANOMALY": [
        _EQUIPMENT + r"(이|가|은|는|에서|도|쪽|만)?\s?(\S+\s){0,2}(안\s?(나|켜|들|돼|되|됩|잡)|꺼|끊|고장|먹통|잡음|노이즈|"
                     r"깜빡|나갔|불량|오류|에러|튀|멈|안\s?나와|이상|문제|복구|"
                     r"(나오|켜지|들리|되)지\s?않)",
        r"하울링", r"잡음", r"먹통", r"고장", r"방전",
    ],
    "PERFORMANCE_ORDER_CHANGE": [
        r"순서(를|가|도|는)?\s?(\S+\s)?(바꾸|바꿔|바꿨|바꿀|바뀌|바뀐|변경|조정|교체)", r"셋\s?리스트",
        r"(곡|앵콜)(을|를)?\s?(한 곡\s?)?(줄이|빼|추가|바꾸|바꿀|바꿔|바꾸자)",
        (r"(앞|뒤)로\s?(당기|당겨|옮기|옮겨|빼|미루|미뤄)", _ORDER_CONTEXT, _AS_PLANNED),
        (r"앞당", _ORDER_CONTEXT, _AS_PLANNED), (r"먼저\s?(하|진행|올리|틀|넣)", _ORDER_CONTEXT, _AS_PLANNED),
    ],
    "AUDIENCE_SAFETY": [
        r"(관객|관람객|팬|사람|인파)(들)?(이|가|은|는|도)?\s?(\S+\s)?(몰|밀|넘어|쓰러|다쳤|다치|부상|붐비|엉켜|뒤엉)",
        r"압사", r"밀집", r"혼잡", r"부상", r"쓰러", r"넘어졌", r"응급", r"구급", r"실신",
        r"펜스(가|는|도)?\s?(\S+\s)?(무너|넘어|밀리|밀려|쓰러|파손|부서|흔들)",
        r"안전\s?(사고|문제|위험)", r"위험", r"싸움", r"미끄", r"줄이\s?(\S+\s)?(길|깁|늘어)",
    ],
}
_CUES = {s: [tuple(re.compile(x) if x else None for x in ((c, None, None) if isinstance(c, str) else (*c, None)[:3]))
             for c in cs] for s, cs in CUES.items()}

# 단서 뒤 같은 절(문장부호나 연결 어미 `고·서·는데·지만·면서·며` 전까지)에 부정이 오면 그 단서는 부정된 것이다.
# `지연은 현재 없습니다`, `믹서 문제는 아니고`, `펜스 상태는 정상입니다`.
_CLAUSE_END = re.compile(r"[.!?,]|(고|서|는데|지만|면서|며)\s")
_NEGATION = re.compile(r"(없|않|아니|아닙|정상(입니|이에|이고|이며|이다|임))")
# 단서 안에 이미 결핍(없·부족 등)이 있으면 `않·아니`가 와야 부정이다. `인력은 부족하지 않습니다`.
_ABSENCE = re.compile(r"(없|부족|빠졌|비어|비었|모자|결근|이탈|공백|결원)")
_ABSENCE_NEGATION = re.compile(r"(않|아니|아닙)")
_SENTENCE = re.compile(r"[^.!?]+[.!?]?")
# 긍정 단서 없이 전체가 이상 없다는 보고. 이때는 대응표 상황도 쓰지 않는다.
_ALL_CLEAR = re.compile(r"(특이\s?사항|이상|문제)(은|이|가|는)?\s?(없|아니)|잘 진행|정상(적으로)?\s?진행|정상(입니|이에|이고|이며|이다|임)")
# 복구·조치 뒤의 `정상입니다`는 전체 이상 없음이 아니라 해결 보고다. 이때는 대응표 상황(해결된 문제의 유형)을 쓴다.
# `메인 무선 마이크 전원 다시 켜고 재시험했는데 정상입니다` (S15P31A202-357 시연 문장).
# 띄어 쓰거나 조사가 붙은 완료형도 잡는다. `교체 완료되어`, `복구 완료 후`, `케이블을 교체한 뒤`.
_RECOVERY = re.compile(r"다시\s?(켜|켰|연결|꽂)|재시험|재부팅|재연결|고쳤|고친|돌아왔|"
                       r"(복구|수리|교체|해결|조치)(을|를)?\s?(했|됐|되었|하고|해서|완료|한\s?(뒤|후|다음)|후)")
# 복구 표현 뒤 같은 절에 부정·예정·의도가 오면 조치한 것이 아니다.
# `재시험은 내일 할 예정이고`, `다시 연결할 필요 없고`, `고친 건 없고`, `교체하고 싶은데`, `재시험은 안 했고`.
# `안`은 `안전`·`안내`와 구분하려고 띄어 쓴 단어나 동사가 바로 붙은 경우만 본다.
_NOT_DONE = re.compile(r"없|않|아니|아닙|못|예정|싶|필요|계획|려고|하려|해야|할\s?(게|거|것)|"
                       r"(^|\s)안(\s|했|하|해|됐|돼|되|됨|함)")
# 복구 표현 바로 앞의 부정. `안 고쳤고`, `못 고쳤지만`.
_NOT_DONE_BEFORE = re.compile(r"(^|\s)(안|못)\s?$")
# 복구 표현의 절은 `되어·돼·되니`에서도 끝난다. `교체 완료되어 현재 이상 없습니다`의 `없`은 조치 부정이 아니다.
_RECOVERY_CLAUSE_END = re.compile(_CLAUSE_END.pattern + r"|(되어|돼서|돼|되니|하여|하니)\s")


def _recovered(text):
    """전체 이상 없음 문장에 실제로 조치·복구했다는 표현이 있는지 본다."""
    for m in _RECOVERY.finditer(text):
        if _NOT_DONE_BEFORE.search(text[:m.start()]):
            continue
        rest = text[m.end():]
        end = _RECOVERY_CLAUSE_END.search(rest)
        if not _NOT_DONE.search(rest[:end.start() if end else len(rest)]):
            return True
    return False


def _negated(text, match):
    rest = text[match.end():]
    end = _CLAUSE_END.search(rest)
    clause = rest[:end.start() if end else len(rest)]
    pattern = _ABSENCE_NEGATION if _ABSENCE.search(match.group()) else _NEGATION
    return bool(pattern.search(clause))


def _sentence(text, pos):
    for m in _SENTENCE.finditer(text):
        if m.start() <= pos < m.end():
            return m.group()
    return text


def cues(text):
    """{상황: 첫 위치}(긍정 단서)와 부정만 된 상황 집합."""
    found, negated = {}, set()
    for situation, patterns in _CUES.items():
        for pattern, context, exclude in patterns:
            for m in pattern.finditer(text):
                sentence = _sentence(text, m.start())
                if context and (not context.search(sentence) or (exclude and exclude.search(sentence))):
                    continue
                if _negated(text, m):
                    negated.add(situation)
                    continue
                found[situation] = min(found.get(situation, m.start()), m.start())
    return found, negated - set(found)


def recommend(text, category):
    """normalizedText와 0.65 적용 뒤 category로 {primary, related}를 만든다."""
    found, negated = cues(text)
    mapped = CATEGORY_TO_SITUATION[category]
    # 복구 보고에서는 `복구 완료 후 정상입니다`처럼 단서 뒤 `정상`이 부정이 아니라 조치 결과다.
    recovered = _recovered(text)
    if (mapped in negated and not recovered) or (not found and _ALL_CLEAR.search(text) and not recovered):
        mapped = None
    ordered = sorted(found, key=lambda s: (found[s], SITUATIONS.index(s)))
    unmapped = [s for s in ordered if s in UNMAPPED]
    primary = unmapped[0] if unmapped else mapped or (ordered[0] if ordered else None)
    if primary is None:
        return {"primary": None, "related": []}
    return {"primary": primary, "related": [s for s in ordered if s != primary]}

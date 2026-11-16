"""GMS Planning 생성기. flow.RuleGenerator와 같은 draft(req, feedback=None) 인터페이스로 콘서트 최초 계획 후보를 만든다.

모델에는 확인된 행사 조건(행사·구역·프로그램·기존 그룹의 id/이름)만 보낸다. 모델 출력은 PlanDraft로 옮기고
선행·인원·절차·계약 검사는 flow가 규칙 생성기와 똑같이 수행한다.

호출은 draft 한 번에 GMS 1회이며 재시도하지 않는다. flow의 Staffing이 묶음 수 초과를 찾으면
draft(req, feedback=위반 목록)로 한 번 더 부르며, 이때 직전 출력과 위반 목록을 함께 보낸다(실행당 최대 2회).
연결·응답 장애는 GeneratorUnavailable(AI_UNAVAILABLE), 형식이 맞지 않는 출력은 예외로 올려
flow가 AI_REVIEW_FAILED(INVALID_ARTIFACT)로 반환한다.
설정은 공통 클라이언트(ai/labeling/generate/gms.py)가 ai/.env의 GMS_KEY·GMS_BASE_URL·GMS_PLAN_MODEL/GMS_MODEL에서 읽는다.
"""

import hashlib
import importlib.util
import json
from datetime import datetime, timezone
from pathlib import Path

from pydantic import ValidationError

import schedule as schedule_input
from contract import Assumption, Question, Unresolved
from flow import GeneratorUnavailable, PlanDraft

GMS_PATH = Path(__file__).resolve().parents[1] / "labeling" / "generate" / "gms.py"
MAX_CALL_SECONDS = 120

SYSTEM = """너는 콘서트 운영 팀의 공연 당일 최초 운영 계획을 만드는 Planning 담당이다. 팀원은 공연 운영 경험이 적은 운영 인력이다.
입력은 총괄이 확인한 행사·구역·프로그램·기존 직무 그룹과 묶음 상한(maxBundles)이다. JSON 객체 하나만 출력한다.

반드시 포함할 업무 (품질 평가 기준)
1. 구역마다(zones 전부. 로비·입장 게이트·백스테이지·부스처럼 프로그램이 없는 구역도 포함) 개장 전 준비 업무 1건 이상: endAt ≤ openAt인 설치 또는 개장 전 점검.
2. 구역마다 정리 업무 1건 이상: startAt ≥ closeAt인 정리·철수. 마지막 프로그램이 일찍 끝나도 정리는 운영 종료(closeAt) 이후에 시작한다(그 사이는 관객 퇴장 안내 구간이다).
3. 입장 운영 업무 1건 이상: programId는 null이고 startAt ≤ openAt < endAt. 티켓·명단 확인, 대기 줄 안내, 관객 동선 안내를 맡는다.
4. confirmedInput의 키마다 그 조건을 반영한 업무 또는 안내 1건 이상. 반영한 업무·안내의 conditionRefs에 그 키 이름을 적고
   (예: 입력이 {"equipment": "..."}이면 "equipment"), steps나 body에 조건 내용을 실제로 반영한다. 내용을 반영했으면 conditionRefs를 비우지 않는다.
5. 입력의 기존 직무 그룹마다 그 그룹의 업무 1건 이상. 장비·음향·영상·장애 대응처럼 장비를 맡는 그룹은 개장 전 연결·점검,
   공연(프로그램) 중 장비 상태 확인 또는 장애 대응 대기, 운영 종료 뒤 철수를 각각 업무로 둔다. 안전·안내 그룹은 맡은 구역의
   개장 전 준비, 입장 안내, 공연 중 관객 흐름 확인, 퇴장 안내를 둔다.

자료 반영
- confirmedInput에 직무별 맡는 일, 구역 담당, 업무 일정이 있으면 그 직무·구역·시각을 그대로 따른다. 자료의 업무를 빠뜨리지 않고, 자료에 없는 수치(인원 기준, 면적, 장비 모델)를 만들지 않는다.
- 한 직무가 구역별 담당으로 나뉘면(예: A구역 담당·B구역 담당) 담당마다 bundleKey를 하나씩 두고, 그 사람이 자기 구역의
  개장 전 준비·입장 안내·공연 중 관객 흐름 확인·퇴장 안내·정리를 모두 맡게 한다. 한 구역 업무만 만들고 다른 구역을 빠뜨리지 않는다.
  입장 안내도 그 구역 담당의 bundleKey에 넣고 입장 전용 묶음을 따로 만들지 않는다.
- 기존 그룹이 맡을 수 있는 설치·점검·철수는 새 그룹(예: 현장 설치)을 만들지 말고 그 기존 그룹에 배정한다. 새 그룹은 기존 그룹으로 맡을 수 없는 일이 있을 때만 만든다.
- 업무 title·steps·checklist text와 안내 body는 모두 한국어로 쓴다. 영어 문장을 쓰지 않는다(장비 모델명 같은 고유 이름은 원문대로 둔다).
- 조건에 "팀원 업무로 만들지 않는다"처럼 업무가 아닌 일(예: 총괄이 직접 하는 일)이 있으면 업무로 만들지 말고 EVENT 안내 body에 그 내용을 적고 conditionRefs에 그 키를 적는다.

구성 지침
- 구역별 설치 → 개장 전 점검 → (운영) → 정리·철수 순서로 만들고 precedences에 적는다. 개장 전 점검은 개장 시각 직전에 끝나게 둔다.
- 프로그램(공연·사인회 등)마다 그 구역의 진행 지원 업무를 두는 것을 기본으로 한다. 관객 통제·무대 주변 안전·다음 순서 전환처럼 운영에 필요한 일을 steps로 구체화한다.
- 입장 운영은 개장 직후 혼잡을 덮고, 운영 구간이 4시간을 넘으면 교대 묶음으로 나눈다. 공연 종료 직후 퇴장 안내가 필요하면 별도 업무로 둔다.
- 인원 활용: 묶음 하나는 한 사람이 전담한다. 묶음 수는 maxBundles 이하로 지키되, 인원이 남으면 혼잡 구간(입장·퇴장)과 큰 구역의 설치·정리를 나눠 맡겨 한 묶음에 업무가 몰리지 않게 한다.
- 입력에 없는 사실(출연자, 장비 모델, 사운드체크·리허설 일정, 좌석 수, 반입 금지 품목 등)을 확정 사실처럼 쓰지 않는다. 계획에 필요한 판단은 assumptions에 적는다.
- 계획을 만들 수 없을 만큼 조건이 부족하면 계획을 만들지 말고 questions만 채운다(준비·정리 시간 부족은 호출 전에 이미 걸러진다).

형식 규칙
- 모든 업무의 zoneId는 필수다(null 금지). 구역이 없는 프로그램의 진행 지원 업무는 운영 위치가 가장 가까운 구역을 고르고 그 판단을 assumptions에 적는다.
- zoneId·programId·기존 workGroup id는 입력에 있는 값만 쓴다. 새 직무 그룹은 tempId "tmp-wg-영문"으로 만든다. 그룹 이름은 20자 이하.
- 입력의 기존 직무 그룹은 모두 workGroups에 id 그대로 넣는다. 그룹 이름에 맞는 업무는 새 그룹을 만들지 말고 그 기존 그룹에 배정한다. 맞는 업무를 정할 수 없는 기존 그룹은 unresolved에 적는다.
- 사람 이름·담당자·가상 인원을 만들지 않는다.
- 업무 시각은 prepStartAt~teardownEndAt 안이다. 시각은 입력과 같은 ISO 8601 UTC(Z)로 쓴다.
- programId를 쓴 업무는 그 프로그램의 zoneId(있으면)와 같은 구역이고 프로그램 시간과 겹친다. 프로그램 전 준비·후 정리 업무는 programId를 null로 둔다.
- 프로그램 시간에 다른 구역에서 하는 일(예: 공연 중 무대 앞 객석 안전 관리, 후방 객석 카메라 운영, 입장 게이트 안내)은 실제 구역에 두고 programId를 null로 둔다. programId를 붙이려고 업무를 프로그램 구역으로 옮기지 않는다.
- bundleKey는 한 사람이다. 단계(설치·운영·정리)마다 새 bundleKey를 만들지 말고, 한 사람이 준비부터 정리까지 이어서 맡는
  업무를 같은 bundleKey로 묶는다(예: sound-1, safety-a, safety-b). 같은 묶음의 업무는 같은 그룹이고 시간이 겹치지 않는다(끝과 시작이 같은 시각은 허용).
- 출력 전에 확인한다: zones마다 endAt ≤ openAt 업무와 startAt ≥ closeAt 업무가 있는가, 기존 그룹마다 업무가 있는가, 서로 다른 bundleKey 수가 maxBundles 이하인가,
  confirmedInput 키마다 그 키를 conditionRefs에 적은 업무나 안내가 있는가.
- precedences는 [선행 tempId, 후행 tempId] 목록이다. 선행 업무가 끝난 뒤 후행 업무가 시작하도록 시각을 맞춘다.
- 업무 tempId는 "tmp-task-번호", title 100자 이하, steps 1~6개(각 120자 이하), checklist 0~6개(key는 영문·숫자·-, text 80자 이하).
- instructions는 EVENT 최대 1개(targetId null)와 그룹별 WORK_GROUP 최대 1개(targetId는 그룹 id 또는 tempId), body 1000자 이하.
- questions를 채우면 workGroups·tasks·precedences·instructions는 빈 배열로 둔다.

출력 형식
{"workGroups":[{"id":null,"tempId":"tmp-wg-setup","name":"현장 설치"}],
 "tasks":[{"tempId":"tmp-task-1","workGroupId":"tmp-wg-setup","bundleKey":"setup-1","zoneId":"...","programId":null,
           "title":"...","startAt":"...Z","endAt":"...Z","steps":["..."],
           "checklist":[{"key":"layout-done","text":"...","required":true}],"conditionRefs":[]}],
 "precedences":[["tmp-task-1","tmp-task-2"]],
 "instructions":[{"scope":"EVENT","targetId":null,"body":"...","conditionRefs":[]}],
 "assumptions":[{"key":"...","statement":"...","refs":["payload.event.openAt"]}],
 "unresolved":[{"key":"...","description":"...","refs":["기존 그룹 id"]}],
 "questions":[{"key":"...","field":"payload.event.prepStartAt","question":"..."}]}"""

REVISE = """직전 후보가 묶음 수 검사를 통과하지 못했다. 아래 항목을 모두 보완한 전체 계획 JSON을 같은 형식으로 다시 출력한다.
이미 맞는 업무는 유지하고, 시간·묶음·선행 규칙과 maxBundles를 계속 지킨다.
{issues}"""


def _load_gms():
    spec = importlib.util.spec_from_file_location("gms", GMS_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def conditions(req, schedule=None):
    """모델에 보낼 확인된 행사 조건. 요청 식별·해시·마감은 보내지 않는다.

    schedule(문서 일정표 항목, S15P31A202-408 시험 경로)이 있으면 확정 항목만 scheduleItems로 더한다.
    """
    p, e = req.payload, req.payload.event
    out = {
        "event": e.model_dump(mode="json"),
        "zones": [z.model_dump(mode="json") for z in p.zones],
        "programs": [x.model_dump(mode="json") for x in p.programs],
        "workGroups": [g.model_dump(mode="json") for g in p.workGroups],
        "maxBundles": e.totalCapacity - e.desiredReserveCount,
    }
    if schedule:
        valid, _ = schedule_input.review(schedule, req)
        out["scheduleItems"] = schedule_input.model_items(valid)
    return out


def system_prompt(schedule=None):
    """일정표가 없으면 기존 프롬프트 그대로다. 있으면 일정표 규칙을 덧붙인다."""
    return SYSTEM + schedule_input.PROMPT if schedule else SYSTEM


class GmsGenerator:
    def __init__(self, model_var="GMS_PLAN_MODEL", schedule=None):
        self.model_var = model_var
        self.schedule = list(schedule or [])   # 문서 일정표 항목(contract.ScheduleItem). 시험 경로이며, 없으면 요청4 scheduleItems를 쓴다
        self.schedule_results = []             # 회차별 일정 규칙 적용 요약(schedule.apply 반환값)
        self.model_version = "plan-gms"
        self.usage = None   # 이번 실행의 호출별 토큰 사용량을 더한 값
        self.calls = 0
        self._last = None   # 보완 요청에 붙일 직전 모델 출력
        self.raw_outputs = []   # 회차별 모델 원본 출력. 변환 실패(INVALID_ARTIFACT) 원인 확인용
        self.model_inputs = []  # 회차별 실제 모델 입력의 조건 본문·프롬프트/메시지 해시(비밀값 없음)
        self.errors = []        # 회차별 변환 예외 요약

    def draft(self, req, feedback=None):
        gms = _load_gms()
        # 요청4 scheduleItems(V9-D66)가 있으면 쓴다. 생성자로 받은 시험 경로 일정이 있으면 그쪽이 먼저다.
        schedule = self.schedule or list(req.payload.scheduleItems or [])
        remaining = (req.deadlineAt - datetime.now(timezone.utc)).total_seconds()
        gms.TIMEOUT_SECONDS = max(1, min(MAX_CALL_SECONDS, int(remaining)))  # 남은 마감보다 오래 기다리지 않는다
        prompt = system_prompt(schedule)
        condition_input = conditions(req, schedule)
        user_content = json.dumps(condition_input, ensure_ascii=False)
        messages = [{"role": "system", "content": prompt},
                    {"role": "user", "content": user_content}]
        snapshot = {
            "systemPromptSha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
            "conditions": condition_input,
            "userContentSha256": hashlib.sha256(user_content.encode("utf-8")).hexdigest(),
        }
        if feedback:
            previous = json.dumps(self._last or {}, ensure_ascii=False)
            revision = REVISE.format(issues="\n".join(f"- {i['message']}" for i in feedback))
            messages += [{"role": "assistant", "content": previous}, {"role": "user", "content": revision}]
            snapshot["revision"] = {
                "previousOutputSha256": hashlib.sha256(previous.encode("utf-8")).hexdigest(),
                "message": revision,
                "messageSha256": hashlib.sha256(revision.encode("utf-8")).hexdigest(),
            }
        self.model_inputs.append(snapshot)
        try:
            data, model, usage = gms.chat_json(messages, temperature=0.2, retries=0, model_var=self.model_var)
        except gms.GmsError as e:
            raise GeneratorUnavailable(str(e)) from None
        self.calls += 1
        self.usage = _add_usage(self.usage, usage)
        self._last = data
        self.raw_outputs.append(data)
        self.model_version = f"plan-gms-{model}"[:200]
        try:
            draft = to_draft(data, req, schedule)
            if schedule and not draft.questions:
                self.schedule_results.append(draft.scheduleSummary)
            return draft
        except ConversionError as e:  # flow는 INVALID_ARTIFACT로 바꾸므로 실패 위치를 남긴다. 원문 값·메시지는 남기지 않는다
            self.errors.append(f"{self.calls}회차 변환 실패: {e.path} ({e.kind})")
            raise
        except Exception as e:
            self.errors.append(f"{self.calls}회차 변환 실패: 위치 미상 ({type(e).__name__})")
            raise


def _add_usage(total, usage):
    total = dict(total or {})
    for key, value in (usage or {}).items():
        if isinstance(value, int):
            total[key] = total.get(key, 0) + value
    return total


def _instruction_target(ins):
    return "instruction:EVENT" if ins.get("scope") == "EVENT" else f"instruction:WORK_GROUP:{ins.get('targetId')}"


def to_draft(data, req=None, schedule=None):
    """모델 JSON을 PlanDraft로 옮긴다. 필드가 없거나 형식이 다르면 예외를 낸다.

    업무·안내의 conditionRefs는 결과 계약에 없는 필드라 떼어 내 PlanDraft.conditionRefs로 옮긴다.
    업무가 쓴 기존 직무 그룹(요청 workGroups의 id)을 모델이 workGroups에 빠뜨리면 요청 값으로 채운다.
    프로그램 구역·시간과 맞지 않는 업무의 programId는 비운다(_drop_mismatched_program_links).
    묶음이 상한을 넘거나 틀렸으면 그룹별로 시간 순으로 다시 묶는다(_repack_bundles).
    schedule(문서 일정표 항목)이 있으면 묶기 전에 일정 규칙(schedule.apply)으로 원문 시각을 지킨다.
    업무의 scheduleKey는 결과 계약에 없는 필드라 결과에 옮기지 않고 PlanDraft.scheduleRefs 근거로만 남긴다.
    """
    if not isinstance(data, dict):
        raise ConversionError("$", "not_object")
    out = PlanDraft()
    out.assumptions = [_model(Assumption, a, f"assumptions[{i}]") for i, a in enumerate(_list(data, "assumptions"))]
    out.questions = [_model(Question, q, f"questions[{i}]") for i, q in enumerate(_list(data, "questions"))]
    if out.questions:
        return out
    for i, g in enumerate(_list(data, "workGroups", required=True)):
        g = _obj(g, f"workGroups[{i}]")
        out.workGroups.append({"id": g.get("id"), "tempId": g.get("tempId"), "name": _need(g, "name", f"workGroups[{i}]")})
    tasks = _list(data, "tasks", required=True)
    for i, t in enumerate(tasks):
        path = f"tasks[{i}]"
        t = _obj(t, path)
        task = {k: _need(t, k, path) for k in ("tempId", "workGroupId", "bundleKey", "zoneId", "title")}
        task.update({"programId": t.get("programId"), "startAt": _time(t, "startAt", path), "endAt": _time(t, "endAt", path),
                     "steps": list(_list(t, "steps", path=path)), "checklist": list(_list(t, "checklist", path=path)),
                     "assigneeId": None})
        out.tasks.append({k: task[k] for k in ("tempId", "workGroupId", "bundleKey", "zoneId", "programId", "title",
                                               "startAt", "endAt", "steps", "checklist", "assigneeId")})
        out.conditionRefs += [(str(k), f"task:{task['tempId']}") for k in _list(t, "conditionRefs", path=path)]
    for i, pair in enumerate(_list(data, "precedences")):
        if not isinstance(pair, list) or len(pair) != 2:
            raise ConversionError(f"precedences[{i}]", "not_pair")
        out.precedences.append(tuple(pair))
    for i, ins in enumerate(_list(data, "instructions")):
        ins = dict(_obj(ins, f"instructions[{i}]"))
        refs = ins.pop("conditionRefs", None) or []
        if not isinstance(refs, list):
            raise ConversionError(f"instructions[{i}].conditionRefs", "not_list")
        out.conditionRefs += [(str(k), _instruction_target(ins)) for k in refs]
        out.instructions.append(ins)
    out.unresolved = [_model(Unresolved, u, f"unresolved[{i}]") for i, u in enumerate(_list(data, "unresolved"))]
    if req is not None:
        for name, step in (("schedule", lambda: schedule_input.apply(out, req, schedule, tasks) if schedule else None),
                           ("existingGroups", lambda: _keep_existing_groups(out, req)),
                           ("programLinks", lambda: _drop_mismatched_program_links(out, req)),
                           ("bundles", lambda: _repack_bundles(out, req))):
            try:
                summary = step()
            except ConversionError:
                raise
            except Exception as e:
                raise ConversionError(f"postprocess.{name}", type(e).__name__) from None
            if name == "schedule" and summary is not None:
                out.scheduleSummary = summary
    return out


class ConversionError(ValueError):
    """모델 출력 변환 실패. 원문 값이나 예외 메시지는 담지 않고 필드 경로(path)와 오류 종류(kind)만 담는다(S15P31A202-409).

    flow는 path를 실패 응답의 details.unresolvedRefs("model.<path>")로, 생성기는 errors 진단 기록으로 남긴다.
    """

    def __init__(self, path, kind):
        self.path, self.kind = path, kind
        super().__init__(f"{path}: {kind}")


def _obj(value, path):
    if not isinstance(value, dict):
        raise ConversionError(path, "not_object")
    return value


def _need(obj, key, path):
    if key not in obj:
        raise ConversionError(f"{path}.{key}", "missing")
    return obj[key]


def _list(obj, key, required=False, path=None):
    where = f"{path}.{key}" if path else key
    if key not in obj or obj[key] is None:
        if required:
            raise ConversionError(where, "missing")
        return []
    if not isinstance(obj[key], list):
        raise ConversionError(where, "not_list")
    return obj[key]


def _time(obj, key, path):
    value = _need(obj, key, path)
    try:
        return datetime.fromisoformat(value)
    except (TypeError, ValueError):
        raise ConversionError(f"{path}.{key}", "invalid_datetime") from None


def _model(cls, value, path):
    """pydantic 검증 실패를 첫 오류의 위치·종류로만 옮긴다(입력 값은 버린다)."""
    try:
        return cls(**_obj(value, path))
    except ValidationError as e:
        first = e.errors(include_url=False, include_context=False, include_input=False)[0]
        loc = "".join(f"[{x}]" if isinstance(x, int) else f".{x}" for x in first["loc"])
        raise ConversionError(f"{path}{loc}", first["type"]) from None


def _bundles_valid(tasks, limit):
    """모델 묶음이 상한 이하이고, 묶음마다 한 그룹이며 시간이 겹치지 않는가(끝과 시작이 같은 시각은 허용)."""
    by_key = {}
    for t in tasks:
        by_key.setdefault(t["bundleKey"], []).append(t)
    if len(by_key) > limit:
        return False
    for items in by_key.values():
        if len({t["workGroupId"] for t in items}) > 1:
            return False
        ordered = sorted(items, key=lambda t: t["startAt"])
        if any(b["startAt"] < a["endAt"] for a, b in zip(ordered, ordered[1:])):
            return False
    return True


def _repack_bundles(out, req):
    """모델 묶음이 틀렸거나 상한(총인원 − 희망 예비)을 넘으면 그룹별로 시간 순으로 다시 묶는다.

    2026-11-09 시연 요청 6회에서 모델은 1회차에 업무마다 묶음을 만들어 상한을 넘겼고, 보완 요청을 받으면 같은 시각의 정리
    업무를 한 묶음에 넣어 REVIEW의 TIME_OVERLAP으로 4회 실패했다. 묶음은 한 사람이 전담할 업무 모음이라 그룹 안에서
    시간이 겹치지 않게 앞에서부터 이어 붙이면 그룹별 최소 묶음 수가 된다(규칙 생성기의 묶기와 같은 방식).
    모델 묶음이 올바르면 그대로 둔다. 다시 묶어도 상한을 넘으면 STAFFING의 보완 요청·인원 질문이 그대로 처리한다.
    """
    e = req.payload.event
    if _bundles_valid(out.tasks, e.totalCapacity - e.desiredReserveCount):
        return
    groups = list(dict.fromkeys(t["workGroupId"] for t in out.tasks))
    for n, group in enumerate(groups, 1):
        ends = []
        for t in sorted((t for t in out.tasks if t["workGroupId"] == group), key=lambda t: (t["startAt"], t["endAt"])):
            slot = next((i for i, end in enumerate(ends) if end <= t["startAt"]), None)
            if slot is None:
                ends.append(t["endAt"])
                slot = len(ends) - 1
            ends[slot] = t["endAt"]
            t["bundleKey"] = f"g{n}-{slot + 1}"


def _drop_mismatched_program_links(out, req):
    """프로그램 구역·시간과 맞지 않는 업무의 programId만 비운다. 업무의 구역·시각은 모델 출력 그대로 둔다.

    모델이 공연 시간에 다른 구역에서 하는 일(무대 앞 객석 안전 관리, 후방 객석 카메라 운영)에 무대 공연의 programId를
    붙이면 REVIEW의 프로그램 연결 검사에 걸려 계획 전체가 INVALID_ARTIFACT로 실패했다(2026-11-09 운영 서버 6회 중 5회).
    programId는 선택 필드이고, 업무를 프로그램 구역으로 옮기면 실제 위치가 틀어지므로 연결만 뗀다.
    요청에 없는 programId는 그대로 두어 REVIEW가 참조 오류로 막게 한다.
    """
    programs = {p.id: p for p in req.payload.programs}
    for t in out.tasks:
        prog = programs.get(t["programId"])
        if prog is None:
            continue
        other_zone = prog.zoneId is not None and t["zoneId"] != prog.zoneId
        no_overlap = not (t["startAt"] < prog.endAt and prog.startAt < t["endAt"])
        if other_zone or no_overlap:
            t["programId"] = None


def _keep_existing_groups(out, req):
    """요청의 기존 직무 그룹을 모델이 workGroups에서 빠뜨리거나 이름을 바꿔도 요청 값 그대로 후보에 남긴다.

    후보가 DRAFT 계획을 통째로 바꾸므로 빠진 기존 그룹은 반영 때 사라지고 바뀐 이름은 그대로 덮어쓴다. 업무가 없는 기존 그룹은
    unresolved에 남겨 총괄이 직접 업무를 추가하게 한다.
    """
    names = {g.id: g.name for g in req.payload.workGroups}
    for g in out.workGroups:  # 기존 id의 이름은 모델 출력과 관계없이 요청 값으로 되돌린다
        if g["id"] in names:
            g["name"] = names[g["id"]]
    listed = {g["id"] for g in out.workGroups if g["id"]}
    used = {t["workGroupId"] for t in out.tasks}
    noted = {ref for u in out.unresolved for ref in u.refs}
    keys = {u.key for u in out.unresolved}
    for g in req.payload.workGroups:
        if g.id not in listed:
            out.workGroups.append({"id": g.id, "tempId": None, "name": g.name})
        if g.id not in used and g.id not in noted:
            n = 1
            while f"group-{n}" in keys:
                n += 1
            keys.add(f"group-{n}")
            out.unresolved.append(Unresolved(key=f"group-{n}", refs=[g.id],
                                             description=f"기존 그룹 '{g.name}'에 맞는 업무를 정하지 못했습니다. 직접 추가해 주세요."))

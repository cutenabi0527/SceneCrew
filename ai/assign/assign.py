"""최초 배정 실행 흐름 (V9-D47).

run(raw_request, now, gms)이 요청 하나로 성공 또는 실패 envelope dict를 반환하고 예외를 밖으로 내지 않는다.

- allowExternalAi=true면 GMS로 배정을 만든다. GMS 호출 실패·형식 오류·계약 검사(시간 충돌 등) 실패면
  같은 요청을 규칙으로 다시 만든다. allowExternalAi=false면 GMS를 부르지 않고 규칙으로 만든다.
- 결과의 generator(GMS|RULE)로 어느 쪽이 만들었는지 구분한다. 규칙 결과를 GMS 성공으로 적지 않는다.
- GMS에는 묶음의 업무 제목·시각과 팀원 id·이미 맡은 시간만 보낸다. 구역·업무 id와 이름 등 개인정보는 보내지 않는다.
- GMS 호출은 1회이며 재시도하지 않는다(실패하면 규칙으로 대신한다). 마감 전 RULE_RESERVE_SECONDS는 규칙 배정 몫으로
  남겨 GMS가 끝까지 걸려도 규칙으로 대신할 수 있게 한다.
- 배정할 수 있는 묶음을 미배정으로 둔 GMS 결과는 계약 검사에서 걸러 규칙으로 대신한다(GMS_INVALID_RESULT).

실행 (ai/assign에서):
    uv run python assign.py run examples/request.json --rule
"""

import importlib.util
import json
import sys
import threading
from datetime import datetime, timezone
from pathlib import Path

import contract
from contract import ContractViolation

GMS_PATH = Path(__file__).resolve().parents[1] / "labeling" / "generate" / "gms.py"
MAX_CALL_SECONDS = 120
RULE_RESERVE_SECONDS = 5  # GMS가 끝까지 걸려도 마감 전에 규칙 배정을 마칠 몫
RULE_VERSION = "assign-rule-v1"

SYSTEM = """너는 콘서트 운영 팀의 최초 업무 배정 담당이다. 같은 직무 그룹 안의 업무 묶음(bundle)을 팀원에게 나눠 준다.
입력은 묶음 목록(묶음마다 업무의 제목·시작·종료 시각)과 팀원 목록(팀원 id와 이미 맡은 시간 busy)이다. JSON 객체 하나만 출력한다.

규칙
- 묶음 하나는 팀원 한 명이 전부 맡는다. 묶음을 나누지 않는다.
- 한 팀원이 맡는 묶음들의 업무 시간과 그 팀원의 busy 시간이 서로 겹치면 안 된다. 끝과 시작이 같은 시각은 허용한다.
- 시간이 겹치지 않으면 한 팀원이 여러 묶음을 맡아도 된다. 다만 가능한 한 여러 팀원에게 고르게 나눈다.
- 맡길 수 있는 팀원이 없는 묶음은 unassigned에 넣는다.
- 입력에 있는 bundleKey·memberId만 쓴다. 모든 묶음은 assignments나 unassigned 중 한 곳에 정확히 한 번 나온다.
- reason은 그 팀원에게 맡긴 이유를 한국어 한 문장(80자 이하)으로 쓴다. 사람 이름을 지어내지 않는다.

출력 형식
{"assignments":[{"bundleKey":"...","memberId":"...","reason":"..."}],
 "unassigned":[{"bundleKey":"...","reason":"..."}]}"""


class GmsUnavailable(RuntimeError):
    pass


_gms_module = None
_gms_lock = threading.Lock()


def _load_gms():
    """gms.py를 한 번만 읽어 같은 모듈을 쓴다. 매번 새로 읽으면 동시 호출 제한(_semaphore)이 요청마다 새로 생긴다."""
    global _gms_module
    with _gms_lock:
        if _gms_module is None:
            spec = importlib.util.spec_from_file_location("gms", GMS_PATH)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            _gms_module = module
        return _gms_module


# ---- 규칙 배정 ----

def rule_result(req, note=None):
    """시작이 이른 묶음부터, 시간이 비는 팀원 중 맡은 시간이 가장 짧은 팀원에게 맡긴다(같으면 요청 순서).

    맡은 시간은 이미 맡은 시간(busy)의 합에서 시작해 이번에 맡긴 묶음 시간을 더한다.

    note가 있으면 reasons 앞에 붙여 왜 규칙으로 만들었는지 남긴다(예: GMS 실패).
    """
    p = req.payload
    taken = {m.memberId: list(m.busy) for m in p.members}
    load = {m.memberId: sum((s.endAt - s.startAt).total_seconds() for s in m.busy) for m in p.members}
    assignments, unassigned, reasons = [], [], []
    for bundle in sorted(p.bundles, key=lambda b: (min(t.startAt for t in b.tasks), b.bundleKey)):
        if not p.members:
            unassigned.append({"bundleKey": bundle.bundleKey, "code": "NO_MEMBER"})
            reasons.append({"bundleKey": bundle.bundleKey, "text": "가용 팀원이 없어 배정하지 못했습니다."})
            continue
        free = [m.memberId for m in p.members
                if not any(contract.overlaps(t, s) for t in bundle.tasks for s in taken[m.memberId])]
        if not free:
            unassigned.append({"bundleKey": bundle.bundleKey, "code": "NO_AVAILABLE_MEMBER"})
            reasons.append({"bundleKey": bundle.bundleKey, "text": "업무 시간이 비는 팀원이 없어 배정하지 못했습니다."})
            continue
        member = min(free, key=lambda m: load[m])  # min은 같은 값이면 앞의 것(요청 순서)을 고른다
        taken[member].extend(bundle.tasks)
        load[member] += sum((t.endAt - t.startAt).total_seconds() for t in bundle.tasks)
        assignments.append({"bundleKey": bundle.bundleKey, "memberId": member})
        reasons.append({"bundleKey": bundle.bundleKey, "text": "업무 시간이 겹치지 않는 팀원 중 이미 맡은 시간을 포함해 맡은 시간이 가장 적습니다."})
    result = {"assignments": assignments, "unassigned": unassigned, "reasons": reasons, "generator": "RULE"}
    if note:
        result["coordination"] = {"fallback": note}
    return result


# ---- GMS 배정 ----

def conditions(req):
    """모델에 보낼 입력. 식별·해시·마감·그룹 id는 보내지 않는다."""
    p = req.payload
    return {
        "bundles": [{"bundleKey": b.bundleKey,
                     "tasks": [{"title": t.title, "startAt": _z(t.startAt), "endAt": _z(t.endAt)} for t in b.tasks]}
                    for b in p.bundles],
        "members": [{"memberId": m.memberId, "busy": [{"startAt": _z(s.startAt), "endAt": _z(s.endAt)} for s in m.busy]}
                    for m in p.members],
    }


def _z(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def gms_result(req, gms=None):
    """GMS 1회 호출로 결과 dict와 사용 모델을 돌려준다. 연결 장애는 GmsUnavailable, 형식 오류는 ValueError."""
    gms = gms or _load_gms()
    budget = (req.deadlineAt - datetime.now(timezone.utc)).total_seconds() - RULE_RESERVE_SECONDS
    if budget < 1:
        raise GmsUnavailable("마감이 가까워 GMS를 부르지 않습니다.")
    messages = [{"role": "system", "content": SYSTEM},
                {"role": "user", "content": json.dumps(conditions(req), ensure_ascii=False)}]
    try:
        data, model, _usage = gms.chat_json(messages, temperature=0.2, retries=0, model_var="GMS_ASSIGN_MODEL",
                                            timeout=min(MAX_CALL_SECONDS, int(budget)))
    except gms.GmsError as e:
        raise GmsUnavailable(str(e)) from None
    return to_result(data), model


def to_result(data):
    """모델 출력 → 결과 dict. 미배정 사유 코드는 모델이 정하지 않고 NO_AVAILABLE_MEMBER로 둔다."""
    if not isinstance(data, dict):
        raise ValueError("모델 출력이 JSON 객체가 아닙니다.")
    assignments, unassigned, reasons = [], [], []
    for a in data.get("assignments") or []:
        assignments.append({"bundleKey": str(a["bundleKey"]), "memberId": str(a["memberId"])})
        if a.get("reason"):
            reasons.append({"bundleKey": str(a["bundleKey"]), "text": str(a["reason"])[:contract.MAX_REASON]})
    for u in data.get("unassigned") or []:
        unassigned.append({"bundleKey": str(u["bundleKey"]), "code": "NO_AVAILABLE_MEMBER"})
        if u.get("reason"):
            reasons.append({"bundleKey": str(u["bundleKey"]), "text": str(u["reason"])[:contract.MAX_REASON]})
    return {"assignments": assignments, "unassigned": unassigned, "reasons": reasons, "generator": "GMS"}


# ---- 실행 ----

def run(raw_request, now=None, gms=None, use_gms=True):
    """요청 하나를 실행한다. gms를 주면 그 모듈로 호출한다(테스트용). use_gms=False면 항상 규칙이다."""
    now = now or datetime.now(timezone.utc)
    try:
        req = contract.parse_request(raw_request)
    except ContractViolation as e:
        return contract.failure(e.request, "INVALID_REQUEST", "요청이 최초 배정 계약을 위반했습니다.",
                                {"issues": [i["path"] for i in e.issues]})
    if now >= req.deadlineAt:
        return contract.failure(req, "AI_TIMEOUT", "실행 마감이 지났습니다.")

    note = None
    if not req.payload.allowExternalAi:
        note = "EXTERNAL_AI_NOT_ALLOWED"
    elif not req.payload.members:
        note = "NO_MEMBER"  # 맡길 사람이 없으면 모델을 부르지 않는다
    elif use_gms:
        try:
            result, model = gms_result(req, gms)
            return contract.success(req, result, f"assign-gms-{model}"[:200])
        except GmsUnavailable:
            note = "GMS_UNAVAILABLE"
        except (ContractViolation, ValueError, KeyError, TypeError):
            note = "GMS_INVALID_RESULT"  # 형식 오류·시간 충돌 등 계약 위반
    else:
        note = "GMS_DISABLED"
    if datetime.now(timezone.utc) >= req.deadlineAt:
        return contract.failure(req, "AI_TIMEOUT", "실행 마감이 지났습니다.")
    return contract.success(req, rule_result(req, note), RULE_VERSION)


def main(argv):
    if len(argv) >= 2 and argv[0] == "run":
        raw = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
        print(json.dumps(run(raw, use_gms="--rule" not in argv), ensure_ascii=False, indent=2))
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

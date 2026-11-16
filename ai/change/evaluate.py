"""시연 보고로 대응안 Multi-agent와 단일 GMS 호출을 비교한다(S15P31A202-375).

같은 요청(examples/demo/*.json)을 두 방식으로 만들고, 같은 코드 기준으로 누락·충돌을 센다.
- multi : graph.run(GMS Supervisor + GMS 전문 Agent 4개)
- single: 같은 사실을 한 번에 주고 operations를 바로 받는 GMS 1회 호출(비교 기준선)

누락(missing): 조치 업무 없음, 조치 담당 없음, 절차·체크 부족(절차 3단계 미만 또는 체크 2개 미만),
              지시한 지연 미반영, 빠진 인원의 업무 미배정
충돌(conflicts): 같은 사람 시간 겹침 + 참조 오류 + 시간 범위 오류(검토 도구와 같은 판정)
형식 오류(invalid): 계약(BE 연산 형식)에 맞지 않아 버린 연산 수

실행 (ai/change에서, GMS 호출은 사전 승인 후):
    uv run python evaluate.py --dry                # 모델 없이 규칙 Supervisor·Agent로 비교 틀만 확인
    uv run python evaluate.py                      # GMS 실호출. examples/gms-runs/에 결과 저장
"""
import argparse
import json
import sys
from datetime import timedelta
from pathlib import Path

import contract
import graph
from agents import RuleSupervisor, rule_agents
from contract import Operation, parse_request
from tools import Evidence, Snapshot, ToolBox, hm, time_conflicts

HERE = Path(__file__).resolve().parent
DEMO = HERE / "examples" / "demo"
RUNS = HERE / "examples" / "gms-runs"

SINGLE_SYSTEM = """너는 콘서트 현장 운영의 대응안 작성자다. 현장 보고와 현재 계획을 보고 변경 연산 목록을 한 번에 만든다.
JSON 객체 하나만 출력한다: {"operations": [...], "rationale": "한 문장"}. 모든 글은 한국어다.
연산 형식(시각은 ISO 8601, 시간대 포함):
- ADD_TASK: {"type":"ADD_TASK","payload":{"tempId":"tmp-1","workGroupId","bundleKey":"response","zoneId","programId":null,"title",
  "startAt","endAt","steps":[수행 절차 3~6개],"checklist":[체크 항목 2~4개 {"key":"c1","text","required":true}],"assigneeId"}}
- UPDATE_PROGRAM: {"type":"UPDATE_PROGRAM","payload":{"programId","startAt","endAt"}}
- UPDATE_TASK: {"type":"UPDATE_TASK","payload":{"taskId","startAt","endAt"}}
- ASSIGN_TASK: {"type":"ASSIGN_TASK","payload":{"taskId","fromMemberId","toMemberId"}}
기준: 기준 시각(now) 뒤로만 제안한다. 같은 사람에게 시간이 겹치는 업무를 맡기지 않는다. 가용(available) 팀원만 쓴다.
지시에 지연 시간이 있으면 그대로 반영한다. 필요한 조치 업무에는 담당자와 수행 절차(3~6단계, 순서대로 한 단계에 한 동작)와
체크 항목(2~4개)을 붙인다. 빠진 인원의 남은 업무는 비어 있는 다른 팀원에게 다시 맡긴다. 입력에 없는 id를 만들지 않는다."""


def load(name):
    return json.loads((DEMO / f"{name}.json").read_text(encoding="utf-8"))


def scenarios():
    return sorted(p.stem for p in DEMO.glob("*.json"))


# ---- 같은 기준으로 세기 ----

def parse_ops(raw_ops):
    ops, invalid = [], 0
    for raw in raw_ops or []:
        try:
            ops.append(Operation.model_validate(raw))
        except Exception:
            invalid += 1
    return ops, invalid


def score(raw, expect, raw_ops):
    req = parse_request(raw)
    snap = Snapshot(req)
    ops, invalid = parse_ops(raw_ops)
    tools = ToolBox(snap, "REVIEW", Evidence())
    projected = snap.project(ops)
    conflicts = [c.describe(snap, projected) for c in time_conflicts(projected)] + tools.check_references(ops) \
        + tools.check_window(ops)
    adds = [o.payload for o in ops if o.type == "ADD_TASK"]
    missing = []
    if expect.get("response") and not adds:
        missing.append("조치 업무 없음")
    for a in adds:
        if a.assigneeId is None:
            missing.append(f"'{a.title}' 담당 없음")
        if len(a.steps) < 3 or len(a.checklist) < 2:
            missing.append(f"'{a.title}' 절차·체크 부족")
    if expect.get("delayMinutes"):
        d = timedelta(minutes=expect["delayMinutes"])
        shifted = [o.payload for o in ops if o.type == "UPDATE_PROGRAM" and o.payload.programId in snap.programs
                   and o.payload.startAt and o.payload.startAt - snap.programs[o.payload.programId].startAt == d]
        if not shifted:
            missing.append(f"지시한 {expect['delayMinutes']}분 지연 미반영")
    if expect.get("gapTasks"):
        gone = {m.id for m in snap.members.values() if not m.available}
        for t in snap.tasks.values():
            if t.assigneeId in gone and t.status == "TODO" and t.startAt >= snap.now and projected[t.id].assigneeId in gone:
                missing.append(f"'{t.title}' 미배정")
    return {"operations": len(ops), "invalid": invalid, "missing": missing, "conflicts": conflicts}


# ---- 실행 ----

class Counter:
    """GMS 호출 수·토큰을 센다."""

    def __init__(self, chat):
        self.chat, self.calls, self.tokens = chat, 0, 0

    def __call__(self, messages, **kw):
        out, model, usage = self.chat(messages, **kw)
        self.calls += 1
        self.tokens += int((usage or {}).get("total_tokens", 0))
        return out, model, usage


def run_multi(raw, dry, chat):
    if dry:
        return graph.run(raw, supervisor=RuleSupervisor(), agents=rule_agents(), clock=lambda: parse_request(raw).payload.now)
    from specialists import gms_agents
    from supervisor import GmsSupervisor
    return graph.run(raw, supervisor=GmsSupervisor(chat=chat), agents=gms_agents(chat),
                     clock=lambda: parse_request(raw).payload.now)


def single_facts(raw):
    p = raw["payload"]
    return {"now": p["now"], "nowKorea": hm(parse_request(raw).payload.now), "reports": [r["body"] for r in p["issue"]["reports"]],
            "reportZoneId": p["issue"]["zoneId"], "instruction": p["instruction"], "situation": p["situation"],
            "event": p["event"], "zones": p["zones"], "programs": p["programs"], "workGroups": p["workGroups"],
            "members": [{k: m[k] for k in ("id", "workGroupId", "available", "isReserve")} for m in p["members"]],
            "tasks": p["tasks"]}


def run_single(raw, dry, chat):
    if dry:
        return {"operations": [], "rationale": "dry"}
    out, _, _ = chat([{"role": "system", "content": SINGLE_SYSTEM},
                      {"role": "user", "content": json.dumps(single_facts(raw), ensure_ascii=False)}],
                     temperature=0.2, retries=0, model_var="GMS_CHANGE_MODEL", response_format={"type": "json_object"},
                     timeout=60)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry", action="store_true", help="모델 없이 규칙 경로로 틀만 확인")
    ap.add_argument("--only", nargs="*", help="시나리오 이름")
    args = ap.parse_args(argv)
    chat = None
    if not args.dry:
        from supervisor import _load_gms
        chat = _load_gms().chat_json
    rows = []
    for name in args.only or scenarios():
        sc = load(name)
        raw, expect = sc["request"], sc["expect"]
        multi_chat, single_chat = Counter(chat) if chat else None, Counter(chat) if chat else None
        multi = run_multi(raw, args.dry, multi_chat)
        multi_ops = (multi.get("result") or {}).get("operations", [])
        single = run_single(raw, args.dry, single_chat)
        row = {"scenario": name, "title": sc["title"],
               "multi": {"status": (multi.get("result") or {}).get("status") or multi["error"]["code"],
                         **score(raw, expect, multi_ops), "calls": getattr(multi_chat, "calls", 0),
                         "tokens": getattr(multi_chat, "tokens", 0),
                         "path": [n["role"] for n in ((multi.get("result") or {}).get("coordination") or
                                                      multi.get("error", {}).get("details", {}).get("coordination") or {}).get("graph", [])]},
               "single": {**score(raw, expect, single.get("operations")), "calls": getattr(single_chat, "calls", 0),
                          "tokens": getattr(single_chat, "tokens", 0)}}
        rows.append(row)
        if not args.dry:
            RUNS.mkdir(parents=True, exist_ok=True)
            (RUNS / f"{name}-multi.json").write_text(json.dumps(multi, ensure_ascii=False, indent=2), encoding="utf-8")
            (RUNS / f"{name}-single.json").write_text(json.dumps(single, ensure_ascii=False, indent=2), encoding="utf-8")
        m, s = row["multi"], row["single"]
        print(f"{name}: multi {m['status']} 누락 {len(m['missing'])} 충돌 {len(m['conflicts'])} 형식 {m['invalid']} "
              f"({m['calls']}회 {m['tokens']}토큰) | single 누락 {len(s['missing'])} 충돌 {len(s['conflicts'])} 형식 {s['invalid']} "
              f"({s['calls']}회 {s['tokens']}토큰)", file=sys.stderr)
    if not args.dry:
        (RUNS / "compare.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(rows, ensure_ascii=False, indent=2))
    return rows


if __name__ == "__main__":
    main()

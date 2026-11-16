"""Multi-agent 실행 구조 시험(S15P31A202-374). 실행: cd ai/change && uv run python -m unittest -v

 모델 없이 규칙 Agent·가짜 Supervisor로 경로·병렬·재위임·울타리를 본다.

시연 장면(기준 시각 18:00, 무대 구역)
- 장비 고장: Supervisor → 일정·인력(동시) → 안내 → 검토. 조치 업무를 팀원 1에게.
- 고장 + "오프닝 20분 지연": 일정이 오프닝을 18:20으로 미뤄 팀원 1의 오프닝 지원이 조치 시간(18:05~18:25)과 겹친다
  → 인력 Agent에 1회 재위임 → 팀원 2로 바뀐다.
- 인원 결원: Supervisor가 인력 Agent만 부른다.
"""
import threading
from datetime import datetime, timedelta, timezone

import unittest

import graph
from agents import ScheduleArtifact, StaffingArtifact, rule_agents
from supervisor import GmsSupervisor

KST = timezone(timedelta(hours=9))
NOW = datetime(2026, 11, 18, 18, 0, tzinfo=KST)
Z1, Z2 = "00000000-0000-4000-8000-0000000000a1", "00000000-0000-4000-8000-0000000000a2"
G1, G2 = "00000000-0000-4000-8000-0000000000b1", "00000000-0000-4000-8000-0000000000b2"
P1 = "00000000-0000-4000-8000-0000000000c1"
M = {n: f"00000000-0000-4000-8000-0000000000d{n}" for n in range(1, 5)}
T = {n: f"00000000-0000-4000-8000-0000000000e{n}" for n in range(1, 5)}
ISSUE, REPORT = "00000000-0000-4000-8000-0000000000f1", "00000000-0000-4000-8000-0000000000f2"


def at(h, m):
    return NOW.replace(hour=h, minute=m).isoformat()


def request(body="무대 프로젝터가 안 나옵니다", instruction="", situation=None, zone=Z1, members=None, deadline=60):
    return {
        "jobId": "00000000-0000-4000-8000-000000000001", "attempt": 1, "requestSchemaVersion": "4",
        "kind": "GENERATE_CHANGE", "inputHash": "a" * 64, "contextRevision": 12,
        "deadlineAt": (NOW + timedelta(seconds=deadline)).isoformat(),
        "payload": {
            "issue": {"id": ISSUE, "zoneId": zone, "reports": [{"id": REPORT, "body": body, "reportedAt": at(17, 58)}]},
            "instruction": instruction, "situation": situation, "now": NOW.isoformat(),
            "event": {"openAt": at(17, 0), "closeAt": at(21, 0), "teardownEndAt": at(22, 0)},
            "zones": [{"id": Z1, "name": "무대"}, {"id": Z2, "name": "로비"}],
            "programs": [{"id": P1, "zoneId": Z1, "name": "오프닝", "startAt": at(18, 0), "endAt": at(18, 5)}],
            "workGroups": [{"id": G1, "name": "무대 운영"}, {"id": G2, "name": "안내"}],
            "members": members or [
                {"id": M[1], "crewNo": 1, "workGroupId": G1, "available": True, "isReserve": False},
                {"id": M[2], "crewNo": 2, "workGroupId": G1, "available": True, "isReserve": False},
                {"id": M[3], "crewNo": 3, "workGroupId": G2, "available": True, "isReserve": False},
                {"id": M[4], "crewNo": 4, "workGroupId": G2, "available": False, "isReserve": False}],
            "tasks": [
                {"id": T[1], "title": "오프닝 진행 지원", "workGroupId": G1, "zoneId": Z1, "programId": P1, "assigneeId": M[1],
                 "startAt": at(18, 0), "endAt": at(18, 5), "status": "TODO"},
                {"id": T[2], "title": "로비 안내", "workGroupId": G2, "zoneId": Z2, "programId": None, "assigneeId": M[3],
                 "startAt": at(17, 30), "endAt": at(19, 0), "status": "IN_PROGRESS"},
                {"id": T[3], "title": "무대 정리", "workGroupId": G1, "zoneId": Z1, "programId": None, "assigneeId": M[2],
                 "startAt": at(19, 40), "endAt": at(20, 0), "status": "TODO"},
                {"id": T[4], "title": "퇴장 안내", "workGroupId": G2, "zoneId": Z2, "programId": None, "assigneeId": M[4],
                 "startAt": at(19, 30), "endAt": at(20, 0), "status": "TODO"}]}}


def clock():
    return NOW


def roles(res):
    return [n["role"] for n in res["result"]["coordination"]["graph"]]


def op(res, type_):
    return [o for o in res["result"]["operations"] if o["type"] == type_]


# ---- 경로 ----

class PathTest(unittest.TestCase):
    def test_equipment_fault_runs_supervisor_schedule_staffing_guide_review(self):
        res = graph.run(request(), clock=clock)
        assert res["resultSchemaVersion"] == "6" and res["result"]["status"] == "READY"
        assert roles(res) == ["SUPERVISOR", "SCHEDULE", "STAFFING", "GUIDE", "REVIEW"]
        assert all(n["status"] == "SUCCEEDED" for n in res["result"]["coordination"]["graph"])
        add = op(res, "ADD_TASK")[0]["payload"]
        assert add["title"] == "장비 조치" and add["zoneId"] == Z1 and add["assigneeId"] == M[1]
        assert add["startAt"] == "2026-11-18T09:05:00Z" and add["endAt"] == "2026-11-18T09:25:00Z"
        assert add["steps"][0].startswith("고장 장비") and [c["key"] for c in add["checklist"]] == ["checked", "reported"]
        assert res["result"]["coordination"]["redelegations"] == []
        assert op(res, "ASSIGN_TASK") == []                                     # 조치 업무만 맡기고 결원 처리는 하지 않는다


    def test_delay_conflict_is_redelegated_once_to_staffing(self):
        res = graph.run(request(instruction="오프닝 20분 지연"), clock=clock)
        assert res["result"]["status"] == "READY", res
        assert roles(res) == ["SUPERVISOR", "SCHEDULE", "STAFFING", "STAFFING", "GUIDE", "REVIEW"]
        c = res["result"]["coordination"]
        assert len(c["redelegations"]) == 1 and c["redelegations"][0]["taskId"] == "t4"
        assert c["graph"][3]["parentTaskId"] == "t1"
        assert c["resolvedConflicts"] and T[1] in c["resolvedConflicts"][0]["refs"]
        assert op(res, "ADD_TASK")[0]["payload"]["assigneeId"] == M[2]          # 팀원 1 → 팀원 2
        assert c["contributions"][3]["summary"] == "겹침을 피해 다시 선정: 팀원 2"
        assert {o["payload"].get("programId") for o in op(res, "UPDATE_PROGRAM")} == {P1}
        upd = op(res, "UPDATE_TASK")[0]["payload"]
        assert set(upd) == {"taskId", "startAt", "endAt"}                         # 바꾼 칸만 보낸다


    def test_staff_gap_calls_only_staffing(self):
        res = graph.run(request(body="안내 4번이 아파서 못 옴", zone=Z2,
                                situation={"primaryType": "STAFF_GAP", "relatedTypes": []}), clock=clock)
        assert res["result"]["status"] == "READY", res
        assert roles(res) == ["SUPERVISOR", "STAFFING", "GUIDE", "REVIEW"]
        assign = op(res, "ASSIGN_TASK")[0]["payload"]
        assert assign == {"taskId": T[4], "fromMemberId": M[4], "toMemberId": M[1]}
        assert res["result"]["situation"]["primaryType"] == "STAFF_GAP"


    def test_no_free_member_after_redelegation_ends_with_question(self):
        members = [{"id": M[1], "crewNo": 1, "workGroupId": G1, "available": True, "isReserve": False},
                   {"id": M[4], "crewNo": 4, "workGroupId": G2, "available": False, "isReserve": False}]
        req = request(instruction="오프닝 20분 지연", members=members)
        req["payload"]["tasks"] = [t for t in req["payload"]["tasks"] if t["assigneeId"] in (M[1], M[4])]
        res = graph.run(req, clock=clock)
        assert res["result"]["status"] == "NEEDS_INFO" and res["result"]["operations"] == []
        assert res["result"]["questions"][0]["key"] == "staff"
        assert roles(res).count("STAFFING") == 2                                   # 재위임은 한 번뿐
        assert res["result"]["coordination"]["graph"][0]["status"] == "NEEDS_INFO"


    def test_unknown_zone_asks_the_chief_without_delegating(self):
        res = graph.run(request(zone=None), clock=clock)
        assert res["result"]["status"] == "NEEDS_INFO"
        assert roles(res) == ["SUPERVISOR"]


# ---- 동시 실행·울타리 ----

class Waiting:
    """일정·인력이 같은 단계에서 동시에 실행되지 않으면 Barrier가 깨진다."""

    def __init__(self, inner, barrier):
        self.inner, self.barrier = inner, barrier

    def run(self, task, tools):
        if task.feedback is None:
            self.barrier.wait()
        return self.inner.run(task, tools)


class GuardTest(unittest.TestCase):
    def test_schedule_and_staffing_run_at_the_same_time(self):
        barrier = threading.Barrier(2, timeout=3)
        agents = rule_agents()
        agents["SCHEDULE"] = Waiting(agents["SCHEDULE"], barrier)
        agents["STAFFING"] = Waiting(agents["STAFFING"], barrier)
        res = graph.run(request(), agents=agents, clock=clock)
        assert res["result"]["status"] == "READY", res


    def test_tool_outside_the_role_is_blocked(self):
        class Greedy:
            def run(self, task, tools):
                tools.get_programs()   # 인력 Agent에 허용되지 않은 일정 도구
                return StaffingArtifact(summary="x")
        agents = rule_agents()
        agents["STAFFING"] = Greedy()
        res = graph.run(request(), agents=agents, clock=clock)
        assert res["error"]["code"] == "AI_REVIEW_FAILED"
        assert res["error"]["details"] == {"reason": "INVALID_ARTIFACT", "stage": "STAFFING", "cause": "허용되지 않은 도구 get_programs"}


    def test_review_rejects_and_nothing_is_returned_as_ready(self):
        class Past:
            def run(self, task, tools):
                out = rule_agents()["SCHEDULE"].run(task, tools)
                ops = [o.model_copy(update={"payload": o.payload.model_copy(update={
                    "startAt": NOW - timedelta(hours=1), "endAt": NOW - timedelta(minutes=40)})}) for o in out.operations]
                return ScheduleArtifact(operations=ops, summary="과거 시간에 조치")
        agents = rule_agents()
        agents["SCHEDULE"] = Past()
        res = graph.run(request(), agents=agents, clock=clock)
        assert res["error"]["code"] == "AI_REVIEW_FAILED" and res["error"]["details"]["reason"] == "REVIEW_REJECTED"
        assert any("기준 시각" in f for f in res["error"]["details"]["findings"])


    def test_wrong_artifact_shape_is_an_invalid_artifact(self):
        class Bad:
            def run(self, task, tools):
                return {"operations": "x"}
        agents = rule_agents()
        agents["SCHEDULE"] = Bad()
        res = graph.run(request(), agents=agents, clock=clock)
        assert res["error"]["details"]["reason"] == "INVALID_ARTIFACT" and res["error"]["details"]["stage"] == "SCHEDULE"


    def test_deadline_is_checked_between_steps(self):
        ticks = iter([NOW] * 6 + [NOW + timedelta(minutes=5)] * 50)
        res = graph.run(request(deadline=60), clock=lambda: next(ticks))
        assert res["error"]["code"] == "AI_TIMEOUT"


    def test_past_deadline_and_bad_request(self):
        assert graph.run(request(deadline=-1), clock=clock)["error"]["code"] == "AI_TIMEOUT"
        bad = request()
        bad["payload"]["tasks"][0]["zoneId"] = "00000000-0000-4000-8000-0000000000ff"
        res = graph.run(bad, clock=clock)
        assert res["error"]["code"] == "INVALID_REQUEST" and res["jobId"] is None


# ---- 결과6 ----

class Result6Test(unittest.TestCase):
    def test_result6_has_evidence_and_empty_simulation_refs(self):
        res = graph.run(request(), clock=clock)
        r = res["result"]
        assert r["simulationRefs"] == [] and r["alternatives"] == []
        kinds = {(e["kind"], e["id"]) for e in r["evidenceRefs"]}
        assert ("REPORT", REPORT) in kinds and ("MEMBER", M[1]) in kinds
        assert all(set(e) == {"kind", "id", "revision", "contentHash", "observedAt", "preconditions"} for e in r["evidenceRefs"])
        add = op(res, "ADD_TASK")[0]["payload"]
        assert set(add) == {"workGroupId", "bundleKey", "zoneId", "programId", "title", "startAt", "endAt", "steps",
                            "checklist", "tempId", "assigneeId"}
        assert "prompt" not in str(r["coordination"]) and "프로젝터" not in str(r["coordination"])   # 보고 원문은 기록에 없음


# ---- LLM Supervisor ----

def fake_chat(*answers):
    calls = []
    it = iter(answers)

    def chat(messages, **kw):
        calls.append((messages, kw))
        a = next(it)
        if isinstance(a, Exception):
            raise a
        return a, "gpt-4o", {"total_tokens": 10}
    chat.calls = calls
    return chat


class SupervisorTest(unittest.TestCase):
    def test_llm_supervisor_chooses_the_path(self):
        chat = fake_chat({"delegate": [{"role": "STAFFING", "goal": "빠진 인원 대신할 사람"}], "need": None,
                          "questions": [], "reason": "시간 변경 없이 사람만 바꾸면 된다"})
        res = graph.run(request(body="안내 4번 연락 두절", zone=Z2), supervisor=GmsSupervisor(chat=chat), clock=clock)
        assert roles(res) == ["SUPERVISOR", "STAFFING", "GUIDE", "REVIEW"]
        assert res["modelVersion"] == "change-supervisor-gpt-4o"
        sent = chat.calls[0][0][1]["content"]
        assert M[1] not in sent and "crewNo" not in sent                              # 개인·담당자 식별은 보내지 않는다
        assert chat.calls[0][1]["response_format"]["type"] == "json_schema"


    def test_llm_supervisor_need_offsets_become_times_and_redelegation_is_its_choice(self):
        chat = fake_chat(
            {"delegate": [{"role": "SCHEDULE", "goal": "오프닝 조정"}, {"role": "STAFFING", "goal": "조치 담당"}],
             "need": {"title": "프로젝터 교체", "zoneId": Z1, "startAfterMinutes": 5, "durationMinutes": 20},
             "questions": [], "reason": "장비 교체와 담당자가 필요하다"},
            {"role": "STAFFING", "goal": "팀원 1 대신 다른 사람", "reason": "담당자만 바꾸면 겹침이 풀린다"})
        res = graph.run(request(instruction="오프닝 20분 지연"), supervisor=GmsSupervisor(chat=chat), clock=clock)
        assert res["result"]["status"] == "READY", res
        assert op(res, "ADD_TASK")[0]["payload"]["title"] == "프로젝터 교체"
        assert res["result"]["coordination"]["redelegations"][0]["reason"] == "담당자만 바꾸면 겹침이 풀린다"
        assert len(chat.calls) == 2


    def test_llm_supervisor_can_decline_redelegation_then_review_rejects(self):
        chat = fake_chat(
            {"delegate": [{"role": "SCHEDULE", "goal": "a"}, {"role": "STAFFING", "goal": "b"}],
             "need": {"title": "조치", "zoneId": Z1, "startAfterMinutes": 5, "durationMinutes": 20}, "questions": [], "reason": "r"},
            {"role": None, "goal": "없음", "reason": "풀 수 없음"})
        res = graph.run(request(instruction="오프닝 20분 지연"), supervisor=GmsSupervisor(chat=chat), clock=clock)
        assert res["error"]["details"]["reason"] == "REVIEW_REJECTED"


    def test_invalid_supervisor_output_falls_back_to_the_rule_path(self):
        chat = fake_chat({"delegate": [{"role": "SCHEDULE", "goal": "a"}],
                          "need": {"title": "x", "zoneId": "00000000-0000-4000-8000-0000000000ff", "startAfterMinutes": 5,
                                   "durationMinutes": 20}, "questions": [], "reason": "r"})
        res = graph.run(request(), supervisor=GmsSupervisor(chat=chat), clock=clock)
        assert res["result"]["status"] == "READY"
        assert "기본 판단" in res["result"]["coordination"]["contributions"][0]["summary"]


    def test_supervisor_model_down_is_unavailable(self):
        class GmsError(Exception):
            pass
        res = graph.run(request(), supervisor=GmsSupervisor(chat=fake_chat(GmsError("GMS HTTP 503"))), clock=clock)
        assert res["error"]["code"] == "AI_UNAVAILABLE" and res["error"]["retryable"] is True


# ---- 리뷰 반영(!314) ----

def plan_decision(roles_, title="조치", after=5, minutes=20):
    return {"delegate": [{"role": r, "goal": "g"} for r in roles_],
            "need": {"title": title, "zoneId": Z1, "startAfterMinutes": after, "durationMinutes": minutes},
            "questions": [], "reason": "r"}


def operation(type_, payload):
    from contract import Operation
    return Operation.model_validate({"type": type_, "payload": payload})


def toolbox(role, req=None):
    from contract import parse_request
    from tools import Evidence, Snapshot, ToolBox
    return ToolBox(Snapshot(parse_request(req or request())), role, Evidence())


class ReviewFixTest(unittest.TestCase):
    def test_supervisor_prompt_asks_for_korean(self):
        chat = fake_chat(plan_decision(["SCHEDULE", "STAFFING"]))
        graph.run(request(), supervisor=GmsSupervisor(chat=chat), clock=clock)
        assert "한국어" in chat.calls[0][0][0]["content"]


    def test_equipment_steps_do_not_depend_on_the_need_title(self):
        chat = fake_chat(plan_decision(["SCHEDULE", "STAFFING"], title="Fix Projector"))
        res = graph.run(request(), supervisor=GmsSupervisor(chat=chat), clock=clock)
        assert res["result"]["status"] == "READY", res
        assert op(res, "ADD_TASK")[0]["payload"]["steps"][0].startswith("고장 장비")


    def test_huge_offsets_are_clamped_not_unavailable(self):
        chat = fake_chat(plan_decision(["SCHEDULE", "STAFFING"], after=10 ** 12, minutes=10 ** 12))
        res = graph.run(request(), supervisor=GmsSupervisor(chat=chat), clock=clock)
        assert res.get("error", {}).get("code") != "AI_UNAVAILABLE", res


    def test_in_progress_task_keeps_its_start(self):
        from agents import AgentTask, Need, RuleSchedule
        req = request(instruction="오프닝 20분 지연")
        req["payload"]["tasks"][0].update(startAt=at(17, 55), status="IN_PROGRESS")
        tools = toolbox("SCHEDULE", req)
        need = Need(title="장비 조치", zoneId=Z1, startAt=NOW + timedelta(minutes=5), endAt=NOW + timedelta(minutes=25))
        out = RuleSchedule().run(AgentTask(taskId="t2", role="SCHEDULE", goal="g", need=need), tools)
        upd = [o.payload for o in out.operations if o.type == "UPDATE_TASK"]
        assert len(upd) == 1 and upd[0].taskId == T[1]
        assert upd[0].startAt is None and upd[0].endAt == NOW.replace(minute=25)
        assert set(operation("UPDATE_TASK", upd[0].model_dump(mode="json", exclude_none=True)).dump()["payload"]) \
            == {"taskId", "endAt"}


    def test_no_work_group_asks_instead_of_failing(self):
        req = request()
        req["payload"].update(workGroups=[], members=[], tasks=[])
        for sup in (None, GmsSupervisor(chat=fake_chat(plan_decision(["SCHEDULE", "STAFFING"])))):
            res = graph.run(req, supervisor=sup, clock=clock) if sup else graph.run(req, clock=clock)
            assert res["result"]["status"] == "NEEDS_INFO", res
            assert res["result"]["questions"][0]["field"] == "workGroups"


    def test_redelegation_text_hides_crew_numbers_and_uses_korean_time(self):
        chat = fake_chat(plan_decision(["SCHEDULE", "STAFFING"]),
                         {"role": "STAFFING", "goal": "다른 사람", "reason": "담당만 바꾸면 된다"})
        res = graph.run(request(instruction="오프닝 20분 지연"), supervisor=GmsSupervisor(chat=chat), clock=clock)
        assert res["result"]["status"] == "READY", res
        sent = chat.calls[1][0][1]["content"]
        assert "담당자 A" in sent and "팀원" not in sent and "crewNo" not in sent
        assert "18:05~18:25" in sent and "09:05" not in sent


    def test_redelegation_only_to_a_role_that_was_delegated(self):
        req = request(instruction="오프닝 20분 지연")
        req["payload"]["tasks"].append({"id": "00000000-0000-4000-8000-0000000000e5", "title": "음향 점검", "workGroupId": G1,
                                        "zoneId": Z2, "programId": None, "assigneeId": M[1],
                                        "startAt": at(18, 20), "endAt": at(18, 30), "status": "TODO"})
        chat = fake_chat(plan_decision(["SCHEDULE"]), {"role": "STAFFING", "goal": "다른 사람", "reason": "r"})
        res = graph.run(req, supervisor=GmsSupervisor(chat=chat), clock=clock)
        assert len(chat.calls) == 2
        assert res["error"]["details"]["reason"] == "REVIEW_REJECTED", res


    def test_review_checks_merged_times_and_program_of_task_updates(self):
        tools = toolbox("REVIEW")
        end_only = operation("UPDATE_TASK", {"taskId": T[3], "endAt": at(19, 30)})       # 시작 19:40보다 앞선 끝
        assert any("시작이 끝보다" in f for f in tools.check_window([end_only]))
        bad_program = operation("UPDATE_TASK", {"taskId": T[3], "programId": "00000000-0000-4000-8000-0000000000c9"})
        assert any("program" in f for f in tools.check_references([bad_program]))


# ---- 운영 실패 반영(2026-11-12, S15P31A202-389) ----

def before_event(req, hours=30):
    """기준 시각을 공연 운영 시작(17:00)보다 hours만큼 앞으로 옮긴 요청과 그 시각의 clock."""
    early = NOW - timedelta(hours=hours)
    req["payload"]["now"] = early.isoformat()
    req["deadlineAt"] = (early + timedelta(seconds=60)).isoformat()
    return req, (lambda: early)


def at_time(req, when):
    """기준 시각을 when으로 옮긴 요청과 그 시각의 clock."""
    req["payload"]["now"] = when.isoformat()
    req["deadlineAt"] = (when + timedelta(seconds=60)).isoformat()
    return req, (lambda: when)


def start_of(res):
    return datetime.fromisoformat(op(res, "ADD_TASK")[0]["payload"]["startAt"].replace("Z", "+00:00"))


def end_of(res):
    return datetime.fromisoformat(op(res, "ADD_TASK")[0]["payload"]["endAt"].replace("Z", "+00:00"))


class OperationsFailureTest(unittest.TestCase):
    def test_need_without_delegate_hands_the_response_task_to_staffing(self):
        chat = fake_chat({"delegate": [], "need": {"title": "마이크 점검", "zoneId": Z1, "startAfterMinutes": 5,
                                                    "durationMinutes": 20}, "questions": [], "reason": "현장 점검이 필요하다"})
        res = graph.run(request(body="메인 마이크 소리가 나오지 않습니다"), supervisor=GmsSupervisor(chat=chat), clock=clock)
        assert res["result"]["status"] == "READY", res
        assert roles(res)[:2] == ["SUPERVISOR", "STAFFING"]
        assert op(res, "ADD_TASK")[0]["payload"]["title"] == "마이크 점검"
        assert op(res, "ADD_TASK")[0]["payload"]["assigneeId"] == M[1], res
        assert "인력 Agent" in res["result"]["coordination"]["contributions"][0]["summary"]

    def test_empty_decision_falls_back_to_the_rule_path(self):
        chat = fake_chat({"delegate": [], "need": None, "questions": [], "reason": "판단 없음"})
        res = graph.run(request(), supervisor=GmsSupervisor(chat=chat), clock=clock)
        assert res["result"]["status"] == "READY", res
        assert "기본 판단" in res["result"]["coordination"]["contributions"][0]["summary"]

    def test_report_before_the_event_starts_the_response_task_from_open(self):
        open_at = NOW.replace(hour=17, minute=0)
        req, early = before_event(request())
        rule = graph.run(req, clock=early)
        assert rule["result"]["status"] == "READY", rule
        assert start_of(rule) == open_at + timedelta(minutes=5)
        req, early = before_event(request())
        chat = fake_chat(plan_decision(["SCHEDULE", "STAFFING"], after=10))
        res = graph.run(req, supervisor=GmsSupervisor(chat=chat), clock=early)
        assert res["result"]["status"] == "READY", res
        assert start_of(res) == open_at + timedelta(minutes=10)

    def test_request_after_teardown_pulls_the_response_task_into_the_event_window(self):
        """철수가 끝난 뒤(22:00 이후) 요청해도 대응안을 만든다. 조치 업무는 철수 종료에 끝나게 운영 구간 안으로 당긴다."""
        teardown = NOW.replace(hour=22, minute=0)
        req, late = at_time(request(), teardown + timedelta(hours=3))
        rule = graph.run(req, clock=late)
        assert rule["result"]["status"] == "READY", rule
        assert start_of(rule) == teardown - timedelta(minutes=20) and end_of(rule) == teardown
        req, late = at_time(request(), teardown + timedelta(hours=3))
        chat = fake_chat(plan_decision(["SCHEDULE", "STAFFING"], after=10))
        res = graph.run(req, supervisor=GmsSupervisor(chat=chat), clock=late)
        assert res["result"]["status"] == "READY", res
        assert end_of(res) == teardown and start_of(res) < datetime.fromisoformat(req["payload"]["now"])

    def test_request_just_before_teardown_ends_the_task_at_teardown(self):
        teardown = NOW.replace(hour=22, minute=0)
        req, late = at_time(request(), teardown - timedelta(minutes=10))   # 5분 뒤 시작 20분이면 22:15에 끝난다
        res = graph.run(req, clock=late)
        assert res["result"]["status"] == "READY", res
        assert (start_of(res), end_of(res)) == (teardown - timedelta(minutes=20), teardown)

    def test_review_allows_an_earlier_start_only_when_no_room_is_left_after_now(self):
        tools = toolbox("REVIEW")                                          # 기준 시각 18:00, 철수 종료 22:00
        past = operation("UPDATE_TASK", {"taskId": T[3], "startAt": at(17, 0), "endAt": at(17, 20)})
        assert any("기준 시각" in f for f in tools.check_window([past]))     # 18:00 뒤에 자리가 있으면 그대로 불통과
        tools.snap.now = NOW.replace(hour=21, minute=50)
        assert not tools.check_window([past.model_copy(update={"payload": past.payload.model_copy(update={
            "startAt": NOW.replace(hour=21, minute=40), "endAt": NOW.replace(hour=22, minute=0)})})])
        over = operation("UPDATE_TASK", {"taskId": T[3], "startAt": at(21, 50), "endAt": at(22, 10)})
        assert any("정리 종료" in f for f in tools.check_window([over]))   # 철수 종료를 넘기는 것은 계속 불통과

    def test_review_rejects_an_earlier_start_that_does_not_end_at_teardown(self):
        """기준 시각 21:00·철수 22:00에서 90분짜리 업무·공연 순서를 과거 시작으로 옮기면, 길이로는 자리가 없어도
        철수 종료에 끝나지 않으므로 당긴 조치 업무가 아니다. 그대로 불통과다(!377 리뷰)."""
        tools = toolbox("REVIEW")
        tools.snap.now = NOW.replace(hour=21, minute=0)
        task = operation("UPDATE_TASK", {"taskId": T[3], "startAt": at(20, 0), "endAt": at(21, 30)})
        program = operation("UPDATE_PROGRAM", {"programId": P1, "startAt": at(20, 0), "endAt": at(21, 30)})
        for moved in (task, program):
            assert any("기준 시각" in f for f in tools.check_window([moved])), moved

    def test_report_during_setup_starts_now_not_at_open(self):
        """준비 중(가장 이른 업무 17:30 이후, 운영 시작 17:00 이전 아님) 보고는 기준 시각 그대로 센다."""
        req = request()
        req["payload"]["event"]["openAt"] = at(18, 30)                # 로비 안내(17:30~)보다 늦은 운영 시작
        res = graph.run(req, clock=clock)                            # 기준 시각 18:00
        assert res["result"]["status"] == "READY", res
        assert start_of(res) == NOW + timedelta(minutes=5)

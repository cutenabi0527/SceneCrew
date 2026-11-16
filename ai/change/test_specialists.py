"""GMS 전문 Agent 시험(S15P31A202-375). 모델 호출 없이 역할별 가짜 응답으로 본다.
실행: cd ai/change && uv run python -m unittest -v test_specialists

시연 장면은 test_graph와 같다(기준 18:00, 무대). 18:05~18:25 조치 시간에 비어 있는 가용 팀원은 팀원 1·2라
후보 A=팀원 1, 후보 B=팀원 2다.
"""
import json
from datetime import timedelta
import unittest

import graph
import server
from specialists import GmsGuide, GmsReview, GmsSchedule, GmsStaffing, gms_agents
from test_graph import G1, M, NOW, P1, REPORT, T, Z2, clock, op, request, roles

ROLE_OF = {"schedule_artifact": "SCHEDULE", "staffing_artifact": "STAFFING", "guide_artifact": "GUIDE",
           "review_artifact": "REVIEW"}

SCHEDULE_OK = {"responseGroup": "G1", "delays": [], "affected": [{"ref": "T1", "reason": "오프닝 진행 지원이 조치 시간 직전에 끝난다"}],
               "questions": []}
STAFFING_B = {"assignments": [{"target": "NEED", "candidate": "후보 B", "reason": "후보 B는 무대 운영 그룹이고 지금 비어 있다"}],
              "questions": []}
GUIDE_OK = {"guides": [{"ref": "N1", "steps": ["프로젝터 전원과 케이블 연결 상태를 확인한다", "예비 프로젝터를 무대 옆에 준비한다",
                                               "예비 장비로 교체하고 화면 출력을 확인한다", "총괄에게 조치 결과를 보고한다"],
                        "checklist": [{"text": "전원·케이블 안전 확인", "required": True},
                                      {"text": "화면 출력 정상 확인", "required": True},
                                      {"text": "총괄에 결과 보고", "required": True}]}]}
HANDLED = [{"problem": "보고된 문제", "operations": ["operations[0]"]}]
REVIEW_OK = {"problems": HANDLED, "findings": []}


def router(**answers):
    """역할별 응답 목록(차례로 꺼냄)으로 답하는 가짜 GMS. calls에 (역할, 메시지)를 남긴다."""
    queues = {k: list(v) if isinstance(v, list) else [v] for k, v in answers.items()}
    calls = []

    def chat(messages, **kw):
        role = ROLE_OF[kw["response_format"]["json_schema"]["name"]]
        calls.append((role, messages, kw))
        a = queues[role].pop(0)
        if isinstance(a, Exception):
            raise a
        return a, "gpt-4o", {"total_tokens": 10}
    chat.calls = calls
    return chat


def run(chat, **kw):
    return graph.run(request(**kw), agents=gms_agents(chat), clock=clock)


def sent(chat, role):
    return [json.loads(m[1]["content"]) for r, m, _ in chat.calls if r == role]


class GmsPathTest(unittest.TestCase):
    def test_equipment_fault_uses_each_agents_own_instruction_and_output(self):
        chat = router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE=GUIDE_OK, REVIEW=REVIEW_OK)
        res = run(chat)
        assert res["result"]["status"] == "READY", res
        assert roles(res) == ["SUPERVISOR", "SCHEDULE", "STAFFING", "GUIDE", "REVIEW"]
        systems = {r: m[0]["content"] for r, m, _ in chat.calls}
        assert len(set(systems.values())) == 4                                  # 역할마다 다른 지시
        assert len({kw["response_format"]["json_schema"]["name"] for _, _, kw in chat.calls}) == 4   # 다른 출력 형식
        add = op(res, "ADD_TASK")[0]["payload"]
        assert add["assigneeId"] == M[2] and add["workGroupId"] == G1
        assert add["steps"] == GUIDE_OK["guides"][0]["steps"]                   # 장비 조치에 절차·체크 항목이 붙는다
        assert [c["key"] for c in add["checklist"]] == ["check-1", "check-2", "check-3"]
        contributions = {c["role"]: c["summary"] for c in res["result"]["coordination"]["contributions"]}
        assert contributions["STAFFING"] == "담당 후보 1명 선정: 팀원 2"
        assert "절차 4단계·체크 3개" in contributions["GUIDE"]

    def test_models_never_see_member_ids_or_crew_numbers(self):
        chat = router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE=GUIDE_OK, REVIEW=REVIEW_OK)
        run(chat)
        for _, messages, _ in chat.calls:
            text = messages[1]["content"]
            assert not any(m in text for m in M.values()) and not any(t in text for t in T.values())
            assert "crewNo" not in text and "팀원 " not in text
        staffing = sent(chat, "STAFFING")[0]
        assert [c["ref"] for c in staffing["candidates"]] == ["후보 A", "후보 B"]
        assert staffing["targets"][0]["candidates"] == ["후보 A", "후보 B"]
        assert "담당자 A" in json.dumps(sent(chat, "REVIEW")[0], ensure_ascii=False)


class ScheduleTest(unittest.TestCase):
    def test_delay_without_a_stated_number_is_dropped(self):
        chat = router(SCHEDULE={**SCHEDULE_OK, "delays": [{"program": "P1", "minutes": 30, "reason": "조치 동안 미룬다"}]},
                      STAFFING=STAFFING_B, GUIDE=GUIDE_OK, REVIEW=REVIEW_OK)
        res = run(chat)
        assert res["result"]["status"] == "READY" and op(res, "UPDATE_PROGRAM") == []
        assert "근거 없는 30분 지연 제외" in res["result"]["coordination"]["contributions"][1]["summary"]

    def test_stated_delay_moves_program_then_conflict_is_redelegated_to_staffing(self):
        chat = router(SCHEDULE={**SCHEDULE_OK, "delays": [{"program": "P1", "minutes": 20, "reason": "지시대로 20분 지연"}]},
                      STAFFING=[{"assignments": [{"target": "NEED", "candidate": "후보 A", "reason": "후보 A가 가장 먼저 비어 있다"}],
                                 "questions": []}, STAFFING_B],
                      GUIDE=GUIDE_OK, REVIEW=REVIEW_OK)
        res = run(chat, instruction="오프닝 20분 지연")
        assert res["result"]["status"] == "READY", res
        assert roles(res) == ["SUPERVISOR", "SCHEDULE", "STAFFING", "STAFFING", "GUIDE", "REVIEW"]
        assert {o["payload"]["programId"] for o in op(res, "UPDATE_PROGRAM")} == {P1}
        assert op(res, "ADD_TASK")[0]["payload"]["assigneeId"] == M[2]
        again = sent(chat, "STAFFING")[1]
        assert again["feedback"]["conflicts"] and [c["ref"] for c in again["candidates"]] == ["후보 A"]   # 팀원 1은 빠짐
        assert "담당자 A" in again["feedback"]["conflicts"][0]

    def test_unknown_group_label_uses_the_zone_group(self):
        chat = router(SCHEDULE={**SCHEDULE_OK, "responseGroup": "G9"}, STAFFING=STAFFING_B, GUIDE=GUIDE_OK, REVIEW=REVIEW_OK)
        res = run(chat)
        assert op(res, "ADD_TASK")[0]["payload"]["workGroupId"] == G1


class StaffingTest(unittest.TestCase):
    def test_candidate_outside_the_list_falls_back_to_the_first_free_member(self):
        chat = router(SCHEDULE=SCHEDULE_OK, STAFFING={"assignments": [{"target": "NEED", "candidate": "후보 Z", "reason": "r"}],
                                                      "questions": []}, GUIDE=GUIDE_OK, REVIEW=REVIEW_OK)
        res = run(chat)
        assert op(res, "ADD_TASK")[0]["payload"]["assigneeId"] == M[1]
        assert "비어 있는 첫 후보로 대신함" in res["result"]["coordination"]["contributions"][2]["summary"]

    def test_staff_gap_picks_same_group_member_and_skips_guide_model(self):
        chat = router(STAFFING={"assignments": [{"target": "W1", "candidate": "후보 B", "reason": "후보 B는 같은 안내 그룹이다"}],
                                "questions": []}, REVIEW=REVIEW_OK)
        res = run(chat, body="안내 4번이 아파서 못 옴", zone=Z2, situation={"primaryType": "STAFF_GAP", "relatedTypes": []})
        assert res["result"]["status"] == "READY", res
        assert op(res, "ASSIGN_TASK")[0]["payload"] == {"taskId": T[4], "fromMemberId": M[4], "toMemberId": M[3]}
        assert [r for r, _, _ in chat.calls] == ["STAFFING", "REVIEW"]           # 새 업무가 없으면 안내 모델을 부르지 않는다
        target = sent(chat, "STAFFING")[0]["targets"][0]
        assert target["title"] == "퇴장 안내" and target["group"] == "안내"


class GuideTest(unittest.TestCase):
    def test_empty_guide_uses_the_rule_steps(self):
        chat = router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE={"guides": [{"ref": "N1", "steps": [], "checklist": []}]},
                      REVIEW=REVIEW_OK)
        res = run(chat)
        assert op(res, "ADD_TASK")[0]["payload"]["steps"][0].startswith("고장 장비")
        assert "기본 절차" in res["result"]["coordination"]["contributions"][3]["summary"]

    def test_long_text_is_cut_to_the_contract_limits(self):
        long = {"guides": [{"ref": "N1", "steps": ["가" * 300] * 9, "checklist": [{"text": "나" * 200, "required": True}] * 9}]}
        res = run(router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE=long, REVIEW=REVIEW_OK))
        add = op(res, "ADD_TASK")[0]["payload"]
        assert len(add["steps"]) == 6 and len(add["steps"][0]) == 120 and len(add["checklist"][0]["text"]) == 80


class ReviewTest(unittest.TestCase):
    def test_blocking_finding_rejects_and_notes_do_not(self):
        missing = {"problems": HANDLED, "findings": [{"rule": "INSTRUCTION_MISSING", "target": "대응안",
                                                     "detail": "안내 방송 지시가 변경에 없다", "blocking": True}]}
        res = run(router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE=GUIDE_OK, REVIEW=missing), instruction="안내 방송")
        assert res["error"]["details"]["reason"] == "REVIEW_REJECTED"
        assert res["error"]["details"]["findings"] == ["[INSTRUCTION_MISSING] 대응안: 안내 방송 지시가 변경에 없다"]
        note = {"problems": HANDLED, "findings": [{**missing["findings"][0], "blocking": False}]}
        res = run(router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE=GUIDE_OK, REVIEW=note))
        assert res["result"]["status"] == "READY"
        assert "참고 1건" in res["result"]["coordination"]["contributions"][-1]["summary"]

    def test_code_findings_reject_without_calling_the_model(self):
        class Past(GmsSchedule):
            def decide(self, task, tools):
                out = super().decide(task, tools)
                p = out.operations[0].payload.model_copy(update={"startAt": NOW.replace(hour=17), "endAt": NOW.replace(hour=17, minute=20)})
                return out.model_copy(update={"operations": [out.operations[0].model_copy(update={"payload": p})]})
        chat = router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE=GUIDE_OK, REVIEW=REVIEW_OK)
        agents = gms_agents(chat)
        agents["SCHEDULE"] = Past(chat)
        res = graph.run(request(), agents=agents, clock=clock)
        assert res["error"]["details"]["reason"] == "REVIEW_REJECTED"
        assert "REVIEW" not in [r for r, _, _ in chat.calls]


class FailureTest(unittest.TestCase):
    def test_malformed_output_falls_back_to_the_rule_agent(self):
        res = run(router(SCHEDULE={"delays": []}, STAFFING={"oops": 1}, GUIDE=GUIDE_OK, REVIEW={"findings": [{"rule": "x"}]}))
        assert res["result"]["status"] == "READY", res
        summaries = [c["summary"] for c in res["result"]["coordination"]["contributions"]]
        assert sum("규칙 판단 사용" in s for s in summaries) == 3
        assert "코드 검사만 적용" in summaries[-1]

    def test_model_down_is_unavailable(self):
        class GmsError(Exception):
            pass
        res = run(router(SCHEDULE=GmsError("GMS HTTP 503"), STAFFING=STAFFING_B))
        assert res["error"]["code"] == "AI_UNAVAILABLE" and res["error"]["retryable"] is True

    def test_evidence_keeps_report_and_chosen_member(self):
        res = run(router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE=GUIDE_OK, REVIEW=REVIEW_OK))
        kinds = {(e["kind"], e["id"]) for e in res["result"]["evidenceRefs"]}
        assert {("REPORT", REPORT), ("MEMBER", M[2]), ("PROGRAM", P1)} <= kinds


class SettingsTest(unittest.TestCase):
    def test_gms_agents_are_the_default_set(self):
        settings = server.Settings(service_token="x")
        agents = settings.new_agents()
        assert settings.agents == "gms"
        assert [type(agents[r]) for r in ("SCHEDULE", "STAFFING", "GUIDE", "REVIEW")] == [GmsSchedule, GmsStaffing, GmsGuide, GmsReview]
        assert type(server.Settings(service_token="x", agents="rule").new_agents()["GUIDE"]).__name__ == "RuleGuide"


class FirstRunFixTest(unittest.TestCase):
    """첫 GMS 비교 실행(examples/gms-runs)에서 나온 문제의 회귀 시험."""

    def test_label_variants_are_the_same_alias(self):
        chat = router(SCHEDULE={**SCHEDULE_OK, "responseGroup": "g1"},
                      STAFFING={"assignments": [{"target": "need", "candidate": "B", "reason": "B가 비어 있다"}], "questions": []},
                      GUIDE={"guides": [{**GUIDE_OK["guides"][0], "ref": "n1"}]}, REVIEW=REVIEW_OK)
        res = run(chat)
        add = op(res, "ADD_TASK")[0]["payload"]
        assert add["assigneeId"] == M[2] and add["workGroupId"] == G1 and add["steps"] == GUIDE_OK["guides"][0]["steps"]

    def test_review_gets_shift_minutes_computed_by_code(self):
        chat = router(SCHEDULE={**SCHEDULE_OK, "delays": [{"program": "P1", "minutes": 20, "reason": "지시"}]},
                      STAFFING=STAFFING_B, GUIDE=GUIDE_OK, REVIEW=REVIEW_OK)
        run(chat, instruction="오프닝 20분 지연")
        lines = sent(chat, "REVIEW")[0]["operations"]
        program = next(x for x in lines if x["type"] == "UPDATE_PROGRAM")
        assert program["startShiftMinutes"] == 20 and program["endShiftMinutes"] == 20

    def test_dropped_pick_is_named_in_the_summary(self):
        chat = router(SCHEDULE=SCHEDULE_OK, STAFFING={"assignments": [{"target": "NEED", "candidate": "팀원 9", "reason": "r"}],
                                                      "questions": []}, GUIDE=GUIDE_OK, REVIEW=REVIEW_OK)
        res = run(chat)
        assert "NEED←'팀원 9'" in res["result"]["coordination"]["contributions"][2]["summary"]

    def test_target_written_as_title_is_accepted(self):
        chat = router(SCHEDULE=SCHEDULE_OK, STAFFING={"assignments": [{"target": "장비 조치", "candidate": "후보 B", "reason": "r"}],
                                                      "questions": []}, GUIDE=GUIDE_OK, REVIEW=REVIEW_OK)
        assert op(run(chat), "ADD_TASK")[0]["payload"]["assigneeId"] == M[2]



# ---- 리뷰 반영(!375 1차) ----

def gap_request():
    """결원 팀원 4의 겹치는 업무 2건(19:30~20:00, 19:35~19:55)과 비어 있는 가용 팀원 1명뿐인 장면."""
    from test_graph import Z2 as Z
    members = [{"id": M[1], "crewNo": 1, "workGroupId": G1, "available": True, "isReserve": False},
               {"id": M[4], "crewNo": 4, "workGroupId": G1, "available": False, "isReserve": False}]
    req = request(body="4번 팀원이 아파서 못 옴", zone=Z, members=members,
                  situation={"primaryType": "STAFF_GAP", "relatedTypes": []})
    tasks = [t for t in req["payload"]["tasks"] if t["assigneeId"] in (M[1], M[4])]
    tasks.append({**tasks[-1], "id": "00000000-0000-4000-8000-0000000000e5", "title": "출구 안내",
                  "startAt": NOW.replace(hour=19, minute=35).isoformat(), "endAt": NOW.replace(hour=19, minute=55).isoformat()})
    req["payload"]["tasks"] = tasks
    return req


class ReviewRound1Test(unittest.TestCase):
    def test_second_problem_without_an_operation_rejects_even_with_a_response_task(self):
        second = {"problems": [{"problem": "프로젝터 화면이 안 나옴", "operations": ["operations[0]"]},
                               {"problem": "음향 콘솔 잡음", "operations": []}], "findings": []}
        res = run(router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE=GUIDE_OK, REVIEW=second))
        assert res["error"]["details"]["reason"] == "REVIEW_REJECTED", res
        assert res["error"]["details"]["findings"] == ["[REPORT_NOT_ADDRESSED] 음향 콘솔 잡음: 이 문제를 처리하는 변경이 없음"]

    def test_unknown_operation_numbers_or_no_problems_reject(self):
        for problems in ([{"problem": "프로젝터", "operations": ["operations[9]", "없음"]}], []):
            res = run(router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE=GUIDE_OK,
                             REVIEW={"problems": problems, "findings": []}))
            assert res["error"]["details"]["reason"] == "REVIEW_REJECTED", res

    def test_review_sees_that_a_gap_task_was_handed_over(self):
        chat = router(STAFFING={"assignments": [{"target": "W1", "candidate": "후보 B", "reason": "같은 그룹"}], "questions": []},
                      REVIEW=REVIEW_OK)
        res = run(chat, body="안내 4번이 아파서 못 옴", zone=Z2, situation={"primaryType": "STAFF_GAP", "relatedTypes": []})
        assert res["result"]["status"] == "READY", res
        line = sent(chat, "REVIEW")[0]["operations"][0]
        assert line["type"] == "ASSIGN_TASK" and "결원" in line["note"]

    def test_partly_unassigned_staff_gap_is_needs_info_not_ready(self):
        both = {"assignments": [{"target": "W1", "candidate": "후보 A", "reason": "r"},
                                {"target": "W2", "candidate": "후보 A", "reason": "r"}], "questions": []}
        for agents in (gms_agents(router(STAFFING=both, REVIEW=REVIEW_OK)), None):
            res = graph.run(gap_request(), agents=agents, clock=clock) if agents else graph.run(gap_request(), clock=clock)
            assert res["result"]["status"] == "NEEDS_INFO", res
            assert res["result"]["operations"] == [] and "출구 안내" in res["result"]["questions"][0]["question"]

    def test_staff_gap_over_the_model_target_limit_still_reassigns_every_task(self):
        from test_graph import Z2 as Z
        members = [{"id": M[1], "crewNo": 1, "workGroupId": G1, "available": True, "isReserve": False},
                   {"id": M[4], "crewNo": 4, "workGroupId": G1, "available": False, "isReserve": False}]
        req = request(body="4번 팀원이 아파서 못 옴", zone=Z, members=members,
                      situation={"primaryType": "STAFF_GAP", "relatedTypes": []})
        start = NOW.replace(minute=10)
        req["payload"]["tasks"] = [
            {"id": f"00000000-0000-4000-8000-0000000001{i:02d}", "title": f"안내 {i + 1}", "workGroupId": G1, "zoneId": Z,
             "programId": None, "assigneeId": M[4], "startAt": (start + timedelta(minutes=10 * i)).isoformat(),
             "endAt": (start + timedelta(minutes=10 * i + 5)).isoformat(), "status": "TODO"} for i in range(12)]
        picks = {"assignments": [{"target": f"W{i + 1}", "candidate": "후보 A", "reason": "같은 그룹"} for i in range(10)],
                 "questions": []}
        chat = router(STAFFING=picks, REVIEW=REVIEW_OK)
        res = graph.run(req, agents=gms_agents(chat), clock=clock)
        assert res["result"]["status"] == "READY", res
        assert len(op(res, "ASSIGN_TASK")) == 12                              # 모델에 보내지 않은 2건도 넘긴다
        assert len(sent(chat, "STAFFING")[0]["targets"]) == 10
        staffing = res["result"]["coordination"]["contributions"][1]["summary"]
        assert "대상 2건은 모델 없이" in staffing and "후보 목록 밖" not in staffing

    def test_guide_below_three_steps_or_two_checks_uses_rule_steps(self):
        short = {"guides": [{"ref": "N1", "steps": ["확인", "보고"], "checklist": [{"text": "보고함", "required": True}]}]}
        res = run(router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE=short, REVIEW=REVIEW_OK))
        add = op(res, "ADD_TASK")[0]["payload"]
        assert add["steps"][0].startswith("고장 장비") and len(add["checklist"]) == 2
        items = GmsGuide.schema["schema"]["properties"]["guides"]["items"]["properties"]
        assert (items["steps"]["minItems"], items["steps"]["maxItems"]) == (3, 6)
        assert (items["checklist"]["minItems"], items["checklist"]["maxItems"]) == (2, 4)

    def test_model_version_shows_gms_agents_in_a_mixed_setup(self):
        res = run(router(SCHEDULE=SCHEDULE_OK, STAFFING=STAFFING_B, GUIDE=GUIDE_OK, REVIEW=REVIEW_OK))
        assert res["modelVersion"] == "change-rule-supervisor-v1+agents-gpt-4o"
        assert graph.run(request(), clock=clock)["modelVersion"] == "change-rule-supervisor-v1"

    def test_single_call_baseline_is_asked_and_scored_with_the_same_guide_limits(self):
        import evaluate
        assert "3~6" in evaluate.SINGLE_SYSTEM and "2~4" in evaluate.SINGLE_SYSTEM
        add = {"type": "ADD_TASK", "payload": {
            "tempId": "tmp-1", "workGroupId": G1, "bundleKey": "response", "zoneId": request()["payload"]["zones"][0]["id"],
            "programId": None, "title": "조치", "startAt": NOW.replace(minute=5).isoformat(),
            "endAt": NOW.replace(minute=25).isoformat(), "steps": ["a", "b", "c"],
            "checklist": [{"key": "c1", "text": "확인", "required": True}], "assigneeId": M[2]}}
        assert evaluate.score(request(), {"response": True}, [add])["missing"] == ["'조치' 절차·체크 부족"]

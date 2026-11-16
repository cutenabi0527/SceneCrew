"""최초 계획 최소 실행 흐름 테스트 (모델 호출 없이 실행된다).

실행: cd ai/plan && uv run python -m unittest -v
"""

import copy
import json
import unittest
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from pathlib import Path

import contract
import flow
import gms_generator
import quality

HERE = Path(__file__).parent
EXAMPLES = HERE / "examples"
NOW = datetime(2026, 11, 1, 3, 0, tzinfo=timezone.utc)
DEMO_NOW = datetime(2026, 11, 18, 3, 30, tzinfo=timezone.utc)
BE_DEMO = HERE.parent.parent / "backend" / "src" / "test" / "resources" / "ai-contract" / "plan" / "demo"

# (요청, 저장된 응답, 실행 시각, 분기). README의 실행 사례 표와 같다.
CASES = [
    ("request.json", "flow/ready.json", NOW, "READY"),
    ("flow/request-existing-groups.json", "flow/existing-groups.json", NOW, "READY"),
    ("flow/request-short-prep.json", "flow/short-prep.json", NOW, "NEEDS_INFO"),
    ("flow/request-understaffed.json", "flow/understaffed.json", NOW, "NEEDS_INFO"),
    ("request-missing-zones.json", "flow/precheck.json", NOW, "NEEDS_INFO"),
    ("request-invalid-reserve.json", "flow/invalid-request.json", NOW, "FAILED"),
    ("request.json", "flow/timeout.json", datetime(2026, 11, 1, 3, 10, tzinfo=timezone.utc), "FAILED"),
    # S15P31A202-252 콘서트 품질 평가 사례의 규칙 기준선 (evaluate.py rule)
    ("eval/request-small-single-stage.json", "eval/small-single-stage-rule.json", NOW, "READY"),
    ("eval/request-festival-multi-zone.json", "eval/festival-multi-zone-rule.json", NOW, "READY"),
    ("eval/request-outdoor-long-existing-groups.json", "eval/outdoor-long-existing-groups-rule.json", NOW, "READY"),
    ("eval/request-short-prep.json", "eval/short-prep-rule.json", NOW, "NEEDS_INFO"),
    # S15P31A202-253 대표 시연 행사 조건. 요청은 BE PlanRequest 직렬화(PlanDemoRequestTest)로 만들었다.
    ("demo/request-ready.json", "demo/ready.json", DEMO_NOW, "READY"),
    ("demo/request-short-prep.json", "demo/short-prep.json", DEMO_NOW, "NEEDS_INFO"),
    ("demo/request-date-mismatch.json", "demo/date-mismatch.json", DEMO_NOW, "FAILED"),
    ("demo/request-ready.json", "demo/timeout.json", datetime(2026, 11, 18, 3, 40, tzinfo=timezone.utc), "FAILED"),
]


def load(name):
    return json.loads((EXAMPLES / name).read_text(encoding="utf-8"))


def at(value):
    return datetime.fromisoformat(value)


class CaseTest(unittest.TestCase):
    """저장된 실행 사례가 현재 흐름의 출력과 같고 계약 분기와 일치하는지 확인한다."""

    def test_cases(self):
        for request, response, now, branch in CASES:
            with self.subTest(response):
                out = flow.run(load(request), now)
                self.assertEqual(out, load(response),
                                 f"uv run python flow.py run examples/{request} examples/{response} --now ... 로 다시 생성하세요.")
                self.assertEqual(contract.validate_response(load(request), out), (branch, []))


class DemoCopyTest(unittest.TestCase):
    """S15P31A202-253 시연 요청·응답이 BE 테스트 자원과 같은 내용인지 확인한다(BE 폴더가 있을 때만)."""

    @unittest.skipUnless(BE_DEMO.is_dir(), "backend 폴더 없음")
    def test_same_as_backend(self):
        for path in sorted((EXAMPLES / "demo").glob("*.json")):
            with self.subTest(path.name):
                be = BE_DEMO / path.name
                self.assertTrue(be.is_file(), f"{be}에 복사하세요")
                self.assertEqual(json.loads(be.read_text(encoding="utf-8")), load(f"demo/{path.name}"))


class ReadyTest(unittest.TestCase):
    def setUp(self):
        self.request = load("request.json")
        self.result = flow.run(self.request, NOW)["result"]

    def test_uses_given_zone_and_program_ids(self):
        payload = self.request["payload"]
        self.assertEqual([z["id"] for z in self.result["zones"]], [z["id"] for z in payload["zones"]])
        self.assertEqual([x["id"] for x in self.result["programs"]], [x["id"] for x in payload["programs"]])
        programs = {x["id"]: x for x in payload["programs"]}
        linked = [t for t in self.result["tasks"] if t["programId"]]
        self.assertEqual({t["programId"] for t in linked}, set(programs))
        for t in linked:
            self.assertEqual(t["zoneId"], programs[t["programId"]]["zoneId"])

    def test_precedence_in_time_order(self):
        tasks = {t["tempId"]: t for t in self.result["tasks"]}
        pairs = [ref.removeprefix("precedence:").split(">") for ref in self.result["coordination"]["evidenceRefs"]
                 if ref.startswith("precedence:")]
        self.assertIn(["tmp-setup-z1", "tmp-check-z1"], pairs)
        self.assertIn(["tmp-check-z1", "tmp-program-1"], pairs)
        self.assertIn(["tmp-program-2", "tmp-teardown-z1"], pairs)
        for before, after in pairs:
            self.assertLessEqual(at(tasks[before]["endAt"]), at(tasks[after]["startAt"]), f"{before}>{after}")

    def test_groups_and_bundles(self):
        self.assertEqual([g["name"] for g in self.result["workGroups"]], ["현장 설치", "프로그램 운영", "입장 안내"])
        e = self.request["payload"]["event"]
        self.assertLessEqual(len(self.result["bundles"]), e["totalCapacity"] - e["desiredReserveCount"])
        self.assertTrue(all(t["assigneeId"] is None for t in self.result["tasks"]))

    def test_existing_groups_keep_id(self):
        result = flow.run(load("flow/request-existing-groups.json"), NOW)["result"]
        groups = {g["name"]: g for g in result["workGroups"]}
        self.assertEqual(groups["입장 안내"]["id"], "22222222-2222-4222-8222-222222222201")
        self.assertEqual(groups["물품 관리"]["id"], "22222222-2222-4222-8222-222222222202")
        # 이름으로 맞출 템플릿이 없는 '물품 관리'는 필수 업무 게이트가 새 묶음의 운영 업무로 채운다(S15P31A202-409)
        self.assertEqual(result["unresolved"], [])
        self.assertIn(groups["물품 관리"]["id"], {t["workGroupId"] for t in result["tasks"]})
        entry = [t for t in result["tasks"] if t["tempId"].startswith("tmp-entry")]
        self.assertTrue(all(t["workGroupId"] == groups["입장 안내"]["id"] for t in entry))


class BrokenGenerator(flow.RuleGenerator):
    """모델 생성기 자리에 끼우는 시험용 생성기. break_draft로 산출물을 망가뜨린다."""
    model_version = "plan-test"

    def __init__(self, break_draft):
        self.break_draft = break_draft

    def draft(self, req, feedback=None):
        out = super().draft(req)
        self.break_draft(out)
        return out


class SequenceGenerator(flow.RuleGenerator):
    """회차마다 다른 손상을 준다. 받은 feedback을 기록해 보완 요청을 확인한다."""
    model_version = "plan-test"

    def __init__(self, *breakers):
        self.breakers = list(breakers)
        self.feedbacks = []

    def draft(self, req, feedback=None):
        self.feedbacks.append(feedback)
        out = super().draft(req)
        self.breakers[min(len(self.feedbacks), len(self.breakers)) - 1](out)
        return out


LOBBY = "33333333-3333-4333-8333-333333333302"


def drop_lobby_setup(d):
    """로비의 설치·점검 업무를 빼서 2026-10-26 GMS 실행과 같은 누락을 만든다."""
    d.tasks = [t for t in d.tasks if t["tempId"] not in ("tmp-setup-z2", "tmp-check-z2")]
    d.precedences = [p for p in d.precedences if not set(p) & {"tmp-setup-z2", "tmp-check-z2"}]


class EvaluateTest(unittest.TestCase):
    def test_rule_baseline_meets_coverage(self):
        import evaluate
        for case, branch in evaluate.CASES:
            with self.subTest(case):
                raw = evaluate.load_request(case)
                row = evaluate.metrics(raw, load(f"eval/{case}-rule.json"))
                self.assertEqual(row["branch"], branch)
                if branch == "READY":
                    n = len(raw["payload"]["zones"])
                    self.assertEqual(row["zonesCovered"], f"{n}/{n}")
                    self.assertTrue(row["entryAtOpen"])
                    k = len(raw["payload"]["event"]["confirmedInput"])
                    self.assertEqual(row["conditionsReferenced"], f"{k}/{k}")


class RevisingGenerator(SequenceGenerator):
    """보완 요청을 반영하는 생성기(GMS 자리)."""
    revises = True


def split_entry(d):
    """업무마다 묶음을 따로 둬 묶음 수를 늘린다."""
    for i, t in enumerate(d.tasks):
        t["bundleKey"] = f"solo-{i}"


class StaffingRetryTest(unittest.TestCase):
    def setUp(self):
        self.request = load("request.json")
        self.request["payload"]["event"]["totalCapacity"] = len(flow.run(self.request, NOW)["result"]["bundles"]) +             self.request["payload"]["event"]["desiredReserveCount"]

    def test_too_many_bundles_is_revised(self):
        gen = RevisingGenerator(split_entry, lambda d: None)
        out = flow.run(self.request, NOW, gen)
        self.assertEqual(contract.validate_response(self.request, out), ("READY", []))
        self.assertEqual([i["path"] for i in gen.feedbacks[1]], ["staffing:bundles"])
        coordination = out["result"]["coordination"]
        self.assertEqual([r["reason"] for r in coordination["redelegations"]],
                         ["업무 묶음을 전담 가능 인원 이하로 줄이도록 보완 요청"])
        self.assertEqual(coordination["graph"][0]["status"], "SUCCEEDED")

    def test_mergeable_bundles_get_merge_feedback(self):
        """묶음을 더 합칠 수 있으면(최소보다 많으면) 같은 그룹 업무를 합치라고 안내한다."""
        gen = RevisingGenerator(split_entry, lambda d: None)
        flow.run(self.request, NOW, gen)
        self.assertIn("같은 bundleKey로 합쳐", gen.feedbacks[1][0]["message"])

    def test_min_bundle_count_allows_touching_times(self):
        """끝과 시작이 같은 업무는 한 묶음에 이어 붙일 수 있다. 겹치면 묶음이 하나 더 필요하다."""
        tasks = [{"workGroupId": "g", "startAt": "2026-11-18T01:00:00Z", "endAt": "2026-11-18T02:00:00Z"},
                 {"workGroupId": "g", "startAt": "2026-11-18T02:00:00Z", "endAt": "2026-11-18T03:00:00Z"},
                 {"workGroupId": "g", "startAt": "2026-11-18T02:30:00Z", "endAt": "2026-11-18T03:30:00Z"},
                 {"workGroupId": "h", "startAt": "2026-11-18T01:00:00Z", "endAt": "2026-11-18T02:00:00Z"}]
        self.assertEqual(flow.min_bundle_count(tasks), 3)

    def test_still_too_many_is_staffing_question(self):
        gen = RevisingGenerator(split_entry)
        out = flow.run(self.request, NOW, gen)
        self.assertEqual(len(gen.feedbacks), 2)
        self.assertEqual(out["result"]["status"], "NEEDS_INFO")
        self.assertEqual([q["key"] for q in out["result"]["questions"]], ["staffing"])

    def test_rule_generator_asks_without_revision(self):
        gen = SequenceGenerator(split_entry)
        out = flow.run(self.request, NOW, gen)
        self.assertEqual(len(gen.feedbacks), 1)
        self.assertEqual([q["key"] for q in out["result"]["questions"]], ["staffing"])


class TimePrecheckTest(unittest.TestCase):
    def test_short_prep_asks_before_generator(self):
        """준비 시간 부족은 어떤 생성기든 호출하기 전에 질문한다."""
        gen = RevisingGenerator(lambda d: None)
        out = flow.run(load("eval/request-short-prep.json"), NOW, gen)
        self.assertEqual(gen.feedbacks, [])
        self.assertEqual([q["key"] for q in out["result"]["questions"]], ["prep-time"])
        self.assertEqual([n["role"] for n in out["result"]["coordination"]["graph"]], ["SUPERVISOR"])


class CoverageTest(unittest.TestCase):
    """필수 업무 품질 게이트(S15P31A202-409): 결정적 보완과 보완 불가 시 검토 실패."""

    def setUp(self):
        self.request = load("request.json")
        self.req = contract.parse_request(self.request)

    def test_missing_lobby_setup_is_recovered(self):
        """로비 준비가 빠지면 로비를 맡은 묶음의 개장 전 빈 시간에 준비 업무를 더해 READY로 반환한다(재호출 없음)."""
        gen = SequenceGenerator(drop_lobby_setup)
        out = flow.run(self.request, NOW, gen)
        self.assertEqual(contract.validate_response(self.request, out), ("READY", []))
        self.assertEqual(gen.feedbacks, [None])
        result = out["result"]
        coordination = result["coordination"]
        self.assertEqual(coordination["redelegations"], [])
        self.assertIn(f"required:coverage:zone:{LOBBY}:prep>task:tmp-req-1", coordination["evidenceRefs"])
        added = next(t for t in result["tasks"] if t["tempId"] == "tmp-req-1")
        self.assertEqual((added["zoneId"], added["bundleKey"], added["programId"]), (LOBBY, "entry-1", None))  # 로비 업무가 가장 많은 입장 안내 묶음의 개장 전 빈 시간
        self.assertEqual((added["startAt"], added["endAt"]), ("2026-11-14T00:30:00Z", "2026-11-14T01:00:00Z"))  # 개장 직전 30분
        self.assertIn("required-fill-1", [a["key"] for a in result["assumptions"]])
        self.assertIn("빠진 업무 1건을 담당 묶음의 빈 시간에 보완",
                      [c["summary"] for c in coordination["contributions"] if c["role"] == "REVIEW"][0])
        self.assertEqual(quality.required_issues(self.req, result["tasks"]), [])

    def test_unrecoverable_missing_is_left_unresolved(self):
        """로비 업무가 모두 빠지고 새 묶음을 열 인원도 없으면 막지 않고 누락을 unresolved로 남긴 채 READY다(252·AUD-T09)."""
        def drop_lobby(d):
            d.tasks = [t for t in d.tasks if t["zoneId"] != LOBBY]
        raw = load("request.json")
        raw["payload"]["event"]["totalCapacity"] = 2 + raw["payload"]["event"]["desiredReserveCount"]  # 남은 묶음 2개뿐
        out = flow.run(raw, NOW, SequenceGenerator(drop_lobby))
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        required = [(u["key"], u["refs"]) for u in out["result"]["unresolved"] if u["key"].startswith("required-")]
        self.assertEqual(required, [("required-1", [LOBBY]), ("required-2", [LOBBY]), ("required-3", [LOBBY]),
                                    ("required-4", ["payload.event.openAt"])])
        self.assertIn("보완하지 못한 누락 4건을 미해결로 남김(차단하지 않음)",
                      [c["summary"] for c in out["result"]["coordination"]["contributions"] if c["role"] == "REVIEW"][0])

    def test_equipment_group_standby_and_teardown(self):
        """장비 직무는 운영 중 대기와 운영 종료 뒤 철수가 필요하다. 그 직무 묶음의 빈 시간에 채운다."""
        raw = load("demo-pdf/request-design.json")
        req = contract.parse_request(raw)
        sound = next(g.id for g in req.payload.workGroups if g.name == "음향 장비 운영")
        zone = req.payload.zones[0].id
        e = req.payload.event
        tasks = [{"tempId": "t1", "workGroupId": sound, "bundleKey": "s", "zoneId": zone, "programId": None,
                  "title": "음향 설치", "startAt": e.prepStartAt, "endAt": e.openAt}]
        paths = [i["path"] for i in quality.required_issues(req, tasks) if sound in i["path"]]
        self.assertEqual(paths, [f"coverage:group:{sound}:operation", f"coverage:group:{sound}:teardown"])
        d = flow.PlanDraft(workGroups=[{"id": g.id, "tempId": None, "name": g.name} for g in req.payload.workGroups],
                           tasks=tasks)
        filled = quality.recover(req, d)
        mine = [t for t in d.tasks if t["workGroupId"] == sound]
        self.assertEqual({t["bundleKey"] for t in mine}, {"s"})       # 같은 사람(묶음)이 맡는다
        self.assertEqual([i["path"] for i in quality.required_issues(req, d.tasks) if sound in i["path"]], [])
        down = next(t for t in mine if t["title"].endswith("장비 철수"))
        self.assertEqual((down["startAt"], down["endAt"]), (e.closeAt, e.teardownEndAt))
        self.assertIn((f"coverage:group:{sound}:teardown", down["tempId"]), filled)

    def test_condition_quality(self):
        """입력에 없는 키·없는 대상 참조는 버리고, 빠진 조건은 EVENT 안내에 원문을 붙인다. 붙일 자리가 없으면 unresolved."""
        raw = load("request.json")
        raw["payload"]["event"]["confirmedInput"] = {"entryRule": "QR 확인", "bagPolicy": "가방 검사", "long": "가" * 990}
        req = contract.parse_request(raw)
        d = flow.RuleGenerator().draft(req)
        d.instructions = [{"scope": "EVENT", "targetId": None, "body": "공통 안내"}]
        d.conditionRefs = [("entryRule", "task:tmp-entry-1"), ("leaked", "instruction:EVENT"), ("bagPolicy", "task:none")]
        out = quality.recover_conditions(req, d)
        self.assertEqual(out, {"dropped": 2, "attached": ["bagPolicy"], "unresolved": ["long"]})
        self.assertEqual(d.instructions[0]["body"], "공통 안내 확인된 조건(bagPolicy): 가방 검사")
        self.assertEqual(d.conditionRefs, [("entryRule", "task:tmp-entry-1"), ("bagPolicy", "instruction:EVENT")])
        self.assertEqual([(u.key, u.refs) for u in d.unresolved], [("condition-long", ["payload.event.confirmedInput.long"])])

    def test_rule_generator_has_no_issues(self):
        d = flow.RuleGenerator().draft(self.req)
        flow.guide(self.req, d)
        self.assertEqual(flow.coverage_issues(self.req, d.tasks, d.instructions, d.conditionRefs),
                         ([], ["condition:entryRule>instruction:EVENT"]))

    def test_entry_and_teardown_rules(self):
        d = flow.RuleGenerator().draft(self.req)
        flow.guide(self.req, d)
        tasks = [t for t in d.tasks if not t["tempId"].startswith(("tmp-entry", "tmp-teardown-z1"))]
        issues, _ = flow.coverage_issues(self.req, tasks, d.instructions, d.conditionRefs)
        self.assertEqual([i["path"] for i in issues],
                         ["coverage:zone:33333333-3333-4333-8333-333333333301:teardown", "coverage:entry"])

    def test_program_task_at_open_is_not_entry(self):
        """개장 시각에 시작하는 프로그램 업무(개회식 지원)는 입장 운영으로 세지 않는다."""
        d = flow.RuleGenerator().draft(self.req)
        tasks = [t for t in d.tasks if not t["tempId"].startswith("tmp-entry")]
        self.assertTrue(any(t["programId"] and t["startAt"] == self.req.payload.event.openAt for t in tasks))
        issues, _ = flow.coverage_issues(self.req, tasks)
        self.assertIn("coverage:entry", [i["path"] for i in issues])

    def test_condition_refs(self):
        raw = load("request.json")
        raw["payload"]["event"]["confirmedInput"] = {"entryRule": "QR 확인", "bagPolicy": "가방 검사",
                                                     "empty": " ", "none": None, "list": []}
        req = contract.parse_request(raw)
        d = flow.RuleGenerator().draft(req)
        refs = [("entryRule", "task:tmp-entry-1"), ("bagPolicy", "task:tmp-missing"), ("entryRule", "instruction:EVENT")]
        issues, evidence = flow.coverage_issues(req, d.tasks, [], refs)
        self.assertEqual([i["path"] for i in issues], ["coverage:condition:bagPolicy"])
        self.assertEqual(evidence, ["condition:entryRule>task:tmp-entry-1"])
        _, evidence = flow.coverage_issues(req, d.tasks, [], [("leaked", "task:tmp-entry-1")] + refs)
        self.assertEqual(evidence, ["condition:entryRule>task:tmp-entry-1"])   # 입력에 없는 키는 근거로 세지 않는다

    def test_guide_references_all_conditions(self):
        raw = load("request.json")
        raw["payload"]["event"]["confirmedInput"] = {"entryRule": "QR 확인", "gates": ["A", "B"]}
        out = flow.run(raw, NOW)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        self.assertIn('["A", "B"]', out["result"]["instructions"][0]["body"])

    def test_recorded_gms_run_misses(self):
        """2026-10-26 GMS 실행 기록은 계약은 통과하지만 이번 누락 검사에서 로비·입장·조건 누락으로 걸린다."""
        result = load("flow/gms-ready.json")["result"]
        issues, _ = flow.coverage_issues(self.req, result["tasks"], result["instructions"])
        self.assertEqual([i["path"] for i in issues],
                         [f"coverage:zone:{LOBBY}:prep", f"coverage:zone:{LOBBY}:teardown",
                          "coverage:entry", "coverage:condition:entryRule"])


class GeneratorTest(unittest.TestCase):
    def run_with(self, break_draft):
        return flow.run(load("request.json"), NOW, BrokenGenerator(break_draft))

    def test_precedence_against_times_is_dropped(self):
        """업무 시각과 맞지 않는 선행 관계는 실패시키지 않고 버린다. 업무와 시각은 그대로 둔다."""
        base = flow.run(load("request.json"), NOW)
        out = self.run_with(lambda d: d.precedences.append(("tmp-teardown-z1", "tmp-setup-z1")))
        self.assertEqual(contract.validate_response(load("request.json"), out), ("READY", []))
        self.assertEqual(out["result"]["tasks"], base["result"]["tasks"])
        coordination = out["result"]["coordination"]
        self.assertNotIn("precedence:tmp-teardown-z1>tmp-setup-z1", coordination["evidenceRefs"])
        schedule = [c["summary"] for c in coordination["contributions"] if c["role"] == "SCHEDULE"]
        self.assertTrue(schedule[0].endswith("업무 시각과 맞지 않는 1건 제외"))

    def test_unknown_precedence_ref_is_dropped(self):
        out = self.run_with(lambda d: d.precedences.append(("tmp-setup-z1", "tmp-missing")))
        self.assertEqual(out["result"]["status"], "READY")
        self.assertNotIn("precedence:tmp-setup-z1>tmp-missing", out["result"]["coordination"]["evidenceRefs"])

    def test_contract_violation_is_invalid_artifact(self):
        def outside_window(d):
            d.tasks[0]["startAt"] = d.tasks[0]["startAt"].replace(day=1)
        out = self.run_with(outside_window)
        self.assertEqual(out["error"]["details"]["reason"], "INVALID_ARTIFACT")
        self.assertEqual(contract.validate_response(load("request.json"), out), ("FAILED", []))

    def test_model_version_from_generator(self):
        self.assertEqual(self.run_with(lambda d: None)["modelVersion"], "plan-test")


class BoundaryTest(unittest.TestCase):
    def test_full_work_groups_ask_question(self):
        raw = load("request.json")
        raw["payload"]["workGroups"] = [{"id": f"22222222-2222-4222-8222-{i:012d}", "name": f"그룹 {i}"}
                                        for i in range(contract.MAX_WORK_GROUPS)]
        out = flow.run(raw, NOW)
        self.assertEqual([q["key"] for q in out["result"]["questions"]], ["work-groups"])
        self.assertEqual(contract.validate_response(raw, out), ("NEEDS_INFO", []))

    def test_mapped_existing_groups_do_not_count_as_added(self):
        """장비·안전 기존 그룹은 설치·입장 템플릿을 맡으므로 상한 사전 검사에서 추가로 세지 않는다(30개 + 프로그램 운영 = 31)."""
        raw = load("request.json")
        names = ["음향 장비", "관객 안전"] + [f"그룹 {i}" for i in range(contract.MAX_WORK_GROUPS - 3)]
        raw["payload"]["workGroups"] = [{"id": f"22222222-2222-4222-8222-{i:012d}", "name": n} for i, n in enumerate(names)]
        d = flow.RuleGenerator().draft(contract.parse_request(raw))
        self.assertEqual(d.questions, [])
        self.assertEqual(len(d.workGroups), contract.MAX_WORK_GROUPS)

    def test_too_many_generated_groups_is_invalid_artifact(self):
        def extra_groups(d):
            d.workGroups += [{"id": None, "tempId": f"tmp-wg-x{i}", "name": f"추가 {i}"} for i in range(30)]
        out = flow.run(load("request.json"), NOW, BrokenGenerator(extra_groups))
        self.assertEqual(out["error"]["details"]["reason"], "INVALID_ARTIFACT")
        self.assertEqual(out["error"]["details"]["stage"], "REVIEW")

    def test_same_program_names_are_ready(self):
        raw = load("request.json")
        raw["payload"]["programs"][1]["name"] = raw["payload"]["programs"][0]["name"]
        out = flow.run(raw, NOW)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))


class FailureTest(unittest.TestCase):
    def test_deadline_rechecked_after_generator(self):
        clock = SimpleNamespace(now=datetime(2026, 11, 1, 3, 9, 59, tzinfo=timezone.utc))

        def slow(d):
            clock.now += timedelta(seconds=2)  # 생성 중 마감(03:10) 경과
        out = flow.run(load("request.json"), lambda: clock.now, BrokenGenerator(slow))
        self.assertEqual(out["error"]["code"], "AI_TIMEOUT")
        self.assertEqual(contract.validate_response(load("request.json"), out), ("FAILED", []))

    def test_generator_connection_error_is_unavailable(self):
        def down(d):
            raise ConnectionError("모델 연결 끊김")
        out = flow.run(load("request.json"), NOW, BrokenGenerator(down))
        self.assertEqual((out["error"]["code"], out["error"]["retryable"]), ("AI_UNAVAILABLE", True))

    def test_generator_bad_output_is_invalid_artifact(self):
        def bad(d):
            raise KeyError("tasks")
        out = flow.run(load("request.json"), NOW, BrokenGenerator(bad))
        self.assertEqual(out["error"]["details"], {"reason": "INVALID_ARTIFACT", "stage": "PLANNING", "unresolvedRefs": []})

    def test_stage_error_is_invalid_artifact(self):
        out = flow.run(load("request.json"), NOW, BrokenGenerator(lambda d: d.precedences.append(("tmp-setup-z1",))))
        self.assertEqual(out["error"]["details"]["stage"], "SCHEDULE")


def model_output():
    """flow/ready.json을 모델 출력 형식으로 바꾼다. GMS를 부르지 않고 to_draft 변환을 시험한다."""
    result = load("flow/ready.json")["result"]
    pairs = [ref.removeprefix("precedence:").split(">") for ref in result["coordination"]["evidenceRefs"]
             if ref.startswith("precedence:")]
    instructions = [dict(i, conditionRefs=["entryRule"] if i["scope"] == "EVENT" else []) for i in result["instructions"]]
    return {"workGroups": result["workGroups"], "tasks": result["tasks"], "precedences": pairs,
            "instructions": instructions, "assumptions": result["assumptions"], "questions": []}


def demo_raw(capacity=12):
    """253 시연 요청에서 총인원만 늘린 요청. 빈 직무·구역을 새 묶음으로 채울 수 있어 프로그램 연결 등 다른 규칙을 시험한다."""
    raw = load("demo/request-ready.json")
    raw["payload"]["event"]["totalCapacity"] = capacity
    return raw


class FakeGms:
    class GmsError(RuntimeError):
        pass

    TIMEOUT_SECONDS = 120

    def __init__(self, *replies):
        self.replies = list(replies)
        self.sent = []

    def chat_json(self, messages, **kwargs):
        self.sent.append(messages)
        reply = self.replies[min(len(self.sent), len(self.replies)) - 1]
        if isinstance(reply, Exception):
            raise reply
        return copy.deepcopy(reply), "gpt-test", {"total_tokens": 1}


class GmsGeneratorTest(unittest.TestCase):
    def run_with(self, *replies, raw=None, now=NOW):
        original = gms_generator._load_gms
        self.fake = FakeGms(*replies)
        self.gen = gms_generator.GmsGenerator()
        gms_generator._load_gms = lambda: self.fake
        try:
            return flow.run(raw or load("request.json"), now, self.gen)
        finally:
            gms_generator._load_gms = original

    def test_model_output_to_ready(self):
        out = self.run_with(model_output())
        self.assertEqual(contract.validate_response(load("request.json"), out), ("READY", []))
        self.assertEqual(out["modelVersion"], "plan-gms-gpt-test")

    def test_omitted_existing_group_is_filled_from_request(self):
        """업무가 쓴 기존 직무 그룹을 모델이 workGroups에 빠뜨리면 요청의 그룹으로 채운다(2026-11-05 야외 사례)."""
        raw = load("flow/request-existing-groups.json")
        req = contract.parse_request(raw)
        result = load("flow/existing-groups.json")["result"]
        existing = {g["id"] for g in raw["payload"]["workGroups"]}
        self.assertTrue(existing & {t["workGroupId"] for t in result["tasks"]})
        data = {"workGroups": [g for g in result["workGroups"] if g["id"] not in existing],
                "tasks": result["tasks"], "instructions": [], "assumptions": [], "questions": []}
        d = gms_generator.to_draft(data, req)
        self.assertEqual({g["id"] for g in d.workGroups if g["id"]}, existing)
        unused = existing - {t["workGroupId"] for t in d.tasks}
        self.assertEqual({r for u in d.unresolved for r in u.refs}, unused)

    def test_unused_existing_groups(self):
        """업무 없는 기존 그룹을 모델이 빠뜨려도 후보에 남긴다(2026-11-05 시연 조건 GMS 실행, gms-runs/1-ready.json).

        S15P31A202-409부터 업무 없는 기존 그룹은 필수 업무 누락이다. 묶음이 이미 전담 인원(6)만큼이면 새 묶음을 열 수 없어
        채우지 못하고 group-n 안내로 남긴 채 READY이고, 인원이 남으면 새 묶음으로 채워 안내 없이 READY다.
        """
        raw = load("demo/request-ready.json")
        result = load("demo/gms-runs/1-ready.json")["result"]
        existing = {g["id"]: g["name"] for g in raw["payload"]["workGroups"]}
        self.assertEqual({g["id"] for g in result["workGroups"] if g["id"]}, {"25300000-0000-4000-8000-000000000304"})
        reply = {"workGroups": result["workGroups"], "tasks": result["tasks"], "precedences": [],
                 "instructions": result["instructions"], "assumptions": result["assumptions"], "questions": []}
        out = self.run_with(reply, raw=raw, now=DEMO_NOW)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        left = {r for u in out["result"]["unresolved"] for r in u["refs"]}
        self.assertLessEqual({g for g in existing if not g.endswith("304")}, left)
        out = self.run_with(reply, raw=demo_raw(), now=DEMO_NOW)
        self.assertEqual(contract.validate_response(demo_raw(), out), ("READY", []))
        self.assertEqual({g["id"] for g in out["result"]["workGroups"] if g["id"]}, set(existing))
        self.assertEqual(set(existing), {t["workGroupId"] for t in out["result"]["tasks"]} & set(existing))
        self.assertFalse([u for u in out["result"]["unresolved"] if u["key"].startswith("group-")])

    def test_recorded_demo_run_shows_unfilled_gaps(self):
        """3차 실행(2026-11-06)은 당시 READY(gms-ready.json)였지만 후방 객석 업무가 하나도 없고 장비 운영·장비·시스템 장애
        대응 직무가 업무 없이 남았다. 묶음 6개가 전담 인원 6명과 같아 채울 자리가 없으므로 409 게이트는 막지 않고
        후방 객석 누락을 required-n, 빈 직무를 group-n 안내로 드러낸다. 저장된 gms-ready.json은 당시 응답 기록으로 둔다.
        """
        raw = load("demo/request-ready.json")
        reply = load("demo/gms-runs/3-ready-raw.json")[0]
        self.assertEqual(load("demo/gms-ready.json")["result"]["status"], "READY")
        out = self.run_with(reply, raw=raw, now=DEMO_NOW)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        self.assertEqual([(u["key"], u["refs"]) for u in out["result"]["unresolved"]], [
            ("group-1", ["25300000-0000-4000-8000-000000000301"]), ("group-2", ["25300000-0000-4000-8000-000000000302"]),
            ("required-1", [self.REAR]), ("required-2", [self.REAR]), ("required-3", [self.REAR])])
        self.assertEqual(self.gen.calls, 1)   # 누락은 재호출 없이 판정한다

    def test_recorded_model_unresolved_is_kept(self):
        """4차 실행(2026-11-06)은 모델이 unresolved를 직접 쓴 첫 표본이다. 인원이 남는 요청에서는 빈 그룹을 채우고
        모델 항목(stage-video)은 그대로 둔다. 자동으로 적던 group-n 안내는 채운 그룹이라 남지 않는다."""
        out = self.run_with(load("demo/gms-runs/4-ready-raw.json")[0], raw=demo_raw(), now=DEMO_NOW)
        self.assertEqual(contract.validate_response(demo_raw(), out), ("READY", []))
        self.assertEqual([u["key"] for u in out["result"]["unresolved"]], ["stage-video"])
        out = self.run_with(load("demo/gms-runs/4-ready-raw.json")[0], raw=load("demo/request-ready.json"), now=DEMO_NOW)
        self.assertEqual(out["result"]["status"], "READY")   # 인원이 꽉 차면 채우지 못한 누락이 안내로 남는다
        self.assertEqual(out["result"]["unresolved"][0]["key"], "stage-video")

    STAGE_SHOW = "25300000-0000-4000-8000-000000000201"
    FRONT, REAR = "25300000-0000-4000-8000-000000000102", "25300000-0000-4000-8000-000000000103"

    def demo_reply_with(self, **changes):
        """4차 원본 출력에서 제목별로 업무 필드를 바꾼다."""
        reply = load("demo/gms-runs/4-ready-raw.json")[0]
        for t in reply["tasks"]:
            t.update(changes.get(t["title"], {}))
        return reply

    def test_other_zone_task_drops_program_link(self):
        """공연 시간에 다른 구역에서 하는 업무에 무대 공연 programId를 붙여도 실패하지 않는다(2026-11-09 운영 서버 재현).

        수정 전에는 REVIEW의 '참조한 프로그램의 구역과 다릅니다'로 INVALID_ARTIFACT였다. 구역·시각은 그대로, 연결만 뗀다.
        """
        raw = demo_raw()  # 빈 직무·구역을 채울 인원이 있는 요청
        reply = self.demo_reply_with(**{
            "공연 중 관객 안전 관리": {"zoneId": self.FRONT},
            "객석 카메라 철수": {"zoneId": self.REAR, "programId": self.STAGE_SHOW,
                            "title": "후방 객석 카메라 운영",
                            "startAt": "2026-11-18T05:10:00Z", "endAt": "2026-11-18T05:15:00Z"},
        })
        out = self.run_with(reply, raw=raw, now=DEMO_NOW)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        tasks = {t["title"]: t for t in out["result"]["tasks"]}
        for title, zone in (("공연 중 관객 안전 관리", self.FRONT), ("후방 객석 카메라 운영", self.REAR)):
            self.assertEqual((tasks[title]["zoneId"], tasks[title]["programId"]), (zone, None), title)
            self.assertEqual(tasks[title]["startAt"], "2026-11-18T05:10:00Z")

    def test_same_zone_program_link_is_kept(self):
        raw = demo_raw()  # 빈 직무·구역을 채울 인원이 있는 요청
        out = self.run_with(self.demo_reply_with(), raw=raw, now=DEMO_NOW)
        tasks = {t["title"]: t for t in out["result"]["tasks"]}
        self.assertEqual(tasks["공연 중 관객 안전 관리"]["programId"], self.STAGE_SHOW)

    def test_task_outside_program_time_drops_program_link(self):
        raw = demo_raw()  # 빈 직무·구역을 채울 인원이 있는 요청
        out = self.run_with(self.demo_reply_with(**{"무대 장비 설치": {"programId": self.STAGE_SHOW}}),
                            raw=raw, now=DEMO_NOW)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        tasks = {t["title"]: t for t in out["result"]["tasks"]}
        self.assertIsNone(tasks["무대 장비 설치"]["programId"])

    def test_unknown_program_id_still_fails_review(self):
        raw = demo_raw()  # 빈 직무·구역을 채울 인원이 있는 요청
        unknown = "25300000-0000-4000-8000-000000000299"
        out = self.run_with(self.demo_reply_with(**{"공연 중 관객 안전 관리": {"programId": unknown}}),
                            raw=raw, now=DEMO_NOW)
        self.assertEqual(out["error"]["details"]["stage"], "REVIEW")
        self.assertEqual(out["error"]["details"]["reason"], "INVALID_ARTIFACT")
        refs = out["error"]["details"]["unresolvedRefs"]
        self.assertTrue(refs and all(r.startswith("result.tasks[") and r.endswith("].programId") for r in refs), refs)

    def test_renamed_existing_group_keeps_request_name(self):
        """모델이 기존 그룹 id를 두고 이름만 바꿔도 요청의 이름으로 되돌린다(DRAFT 전체 교체로 사용자 입력이 바뀌지 않게)."""
        raw = demo_raw()  # 빈 직무·구역을 채울 인원이 있는 요청
        reply = load("demo/gms-runs/3-ready-raw.json")[0]
        video = "25300000-0000-4000-8000-000000000303"
        for g in reply["workGroups"]:
            if g.get("id") == video:
                g["name"] = "무대 영상 촬영팀"
        out = self.run_with(reply, raw=raw, now=DEMO_NOW)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        names = {g["id"]: g["name"] for g in out["result"]["workGroups"] if g["id"]}
        self.assertEqual(names, {g["id"]: g["name"] for g in raw["payload"]["workGroups"]})

    def test_conversion_error_is_recorded(self):
        """변환 예외는 INVALID_ARTIFACT로 끝나고, 실패 단계·필드 경로만 응답과 생성기 진단에 남긴다(원문 값은 남기지 않는다)."""
        reply = model_output()
        reply["unresolved"] = [{"key": "장비 그룹", "description": "업무 미정", "refs": []}]
        out = self.run_with(reply)
        self.assertEqual(out["error"]["details"], {"reason": "INVALID_ARTIFACT", "stage": "PLANNING",
                                                   "unresolvedRefs": ["model.unresolved[0].key"]})
        self.assertEqual(self.gen.raw_outputs, [reply])
        self.assertEqual(self.gen.errors, ["1회차 변환 실패: unresolved[0].key (string_pattern_mismatch)"])
        self.assertNotIn("장비 그룹", json.dumps(out, ensure_ascii=False))

    def test_conversion_error_paths_do_not_leak_values(self):
        secret = "sk-test-SECRET-1234"
        cases = [
            (lambda r: r["tasks"][2].update(startAt=f"오후 {secret}"), "tasks[2].startAt", "invalid_datetime"),
            (lambda r: r["tasks"][1].pop("zoneId"), "tasks[1].zoneId", "missing"),
            (lambda r: r.update(tasks={"x": secret}), "tasks", "not_list"),
            (lambda r: r["assumptions"].append({"key": "k", "statement": secret, "refs": secret}),
             f"assumptions[{len(model_output()['assumptions'])}].refs", "list_type"),
            (lambda r: r.update(precedences=[[secret]]), "precedences[0]", "not_pair"),
        ]
        for change, path, kind in cases:
            with self.subTest(path):
                reply = model_output()
                change(reply)
                out = self.run_with(reply)
                self.assertEqual(out["error"]["details"]["unresolvedRefs"], [f"model.{path}"])
                self.assertEqual(self.gen.errors, [f"1회차 변환 실패: {path} ({kind})"])
                self.assertNotIn(secret, json.dumps(out, ensure_ascii=False) + " ".join(self.gen.errors))

    def test_model_questions(self):
        reply = {"questions": [{"key": "zones", "field": "payload.zones", "question": "구역을 확인해 주세요."}]}
        self.assertEqual(self.run_with(reply)["result"]["status"], "NEEDS_INFO")

    def test_condition_refs_removed_from_result(self):
        reply = model_output()
        reply["tasks"][0]["conditionRefs"] = ["entryRule"]
        out = self.run_with(reply)
        self.assertEqual(contract.validate_response(load("request.json"), out), ("READY", []))
        self.assertIn(f"condition:entryRule>task:{reply['tasks'][0]['tempId']}",
                      out["result"]["coordination"]["evidenceRefs"])
        self.assertEqual(self.gen.calls, 1)

    def test_missing_lobby_is_recovered_without_revision(self):
        first = model_output()
        first["tasks"] = [t for t in first["tasks"] if t["tempId"] not in ("tmp-setup-z2", "tmp-check-z2")]
        first["precedences"] = [p for p in first["precedences"] if not set(p) & {"tmp-setup-z2", "tmp-check-z2"}]
        out = self.run_with(first, model_output())
        self.assertEqual(contract.validate_response(load("request.json"), out), ("READY", []))
        self.assertEqual(self.gen.calls, 1)
        self.assertIn(f"required:coverage:zone:{LOBBY}:prep>task:tmp-req-1", out["result"]["coordination"]["evidenceRefs"])

    def test_solo_bundles_are_repacked_without_revision(self):
        """업무마다 묶음을 둔 모델 출력은 그룹별 시간 순 묶기로 상한 안에 들면 보완 호출 없이 READY다."""
        first = model_output()
        for i, t in enumerate(first["tasks"]):
            t["bundleKey"] = f"solo-{i}"
        raw = load("request.json")
        raw["payload"]["event"]["totalCapacity"] = len(model_output()["tasks"]) - 1
        out = self.run_with(first, raw=raw)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        self.assertEqual(self.gen.calls, 1)
        self.assertLessEqual(len(out["result"]["bundles"]), len(model_output()["tasks"]) - 1 - raw["payload"]["event"]["desiredReserveCount"])

    def test_recorded_overlap_failure_is_ready_in_one_call(self):
        """2026-11-09 시연 요청 1회차 원본(묶음 7개 > 상한 6). 수정 전에는 보완 회차가 같은 시각 정리 업무를 한 묶음에 넣어
        TIME_OVERLAP으로 실패했다. 다시 묶으면 1회차만으로 묶음 6개, READY다."""
        raw = load("demo/request-ready.json")
        first, revised = load("demo/gms-runs/5-overlap-raw.json")
        self.assertEqual(len({t["bundleKey"] for t in first["tasks"]}), 7)
        out = self.run_with(first, revised, raw=raw, now=DEMO_NOW)
        # 묶음은 1회차만으로 6개가 되지만(보완 호출 없음), 장비·시스템 장애 대응·무대영상 직무가 업무 없이 남아
        # 새 묶음을 열 인원이 없으므로 409 게이트는 막지 않고 두 직무를 unresolved로 남긴다
        self.assertEqual(self.gen.calls, 1)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        self.assertEqual([u["refs"] for u in out["result"]["unresolved"]],
                         [["25300000-0000-4000-8000-000000000302"], ["25300000-0000-4000-8000-000000000303"]])
        roomy = demo_raw()
        out = self.run_with(first, revised, raw=roomy, now=DEMO_NOW)
        self.assertEqual(contract.validate_response(roomy, out), ("READY", []))
        self.assertEqual(self.gen.calls, 1)
        self.assertLessEqual({t["title"] for t in first["tasks"]}, {t["title"] for t in out["result"]["tasks"]})

    def test_overlapping_model_bundle_is_repacked(self):
        """보완 회차 원본처럼 한 묶음에 같은 시각 업무가 있으면 상한 안이어도 다시 묶는다."""
        raw = demo_raw()
        _, revised = load("demo/gms-runs/5-overlap-raw.json")
        out = self.run_with(revised, raw=raw, now=DEMO_NOW)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))

    def test_too_many_bundles_is_revised_with_feedback(self):
        """다시 묶어도 상한을 넘으면(같은 시각 업무가 두 배) 기존처럼 보완 요청한다."""
        first = model_output()
        first["tasks"] += [dict(t, tempId=t["tempId"] + "-dup") for t in first["tasks"]]
        raw = load("request.json")
        raw["payload"]["event"]["totalCapacity"] = len(model_output()["tasks"]) - 1  # 묶음 수 < 업무 수가 되게 줄인다
        ready = flow.run(raw, NOW)
        self.assertEqual(ready["result"]["status"], "READY")  # 규칙 후보는 줄인 인원 안에 든다
        out = self.run_with(first, model_output(), raw=raw)
        self.assertEqual(contract.validate_response(raw, out), ("READY", []))
        self.assertEqual((self.gen.calls, self.gen.usage), (2, {"total_tokens": 2}))
        retry = self.fake.sent[1]
        self.assertEqual([m["role"] for m in retry], ["system", "user", "assistant", "user"])
        self.assertEqual(json.loads(retry[2]["content"]), first)
        self.assertIn("묶음 수 검사", retry[3]["content"])
        # 이미 그룹별 최소로 다시 묶었으므로 합치라는 대신 동시 업무를 줄이라고 안내한다(MR !278 리뷰)
        self.assertIn("이미 모두 합쳐 최소", retry[3]["content"])
        self.assertNotIn("같은 bundleKey로 합쳐", retry[3]["content"])

    def test_expired_staffing_retry_does_not_call_gms(self):
        """첫 묶음 수 검사 중 마감이 지나면 보완 Planning 호출 없이 AI_TIMEOUT으로 끝난다(MR !182 리뷰)."""
        first = model_output()
        for i, t in enumerate(first["tasks"]):
            t["bundleKey"] = f"solo-{i}"
        raw = load("request.json")
        raw["payload"]["event"]["totalCapacity"] = len(model_output()["tasks"]) - 1
        clock = SimpleNamespace(now=NOW)
        original = flow.staffing_question

        def expiring_staffing(*args):
            clock.now = datetime(2026, 11, 1, 3, 10, tzinfo=timezone.utc)  # 요청의 deadlineAt
            return original(*args)
        flow.staffing_question = expiring_staffing
        try:
            out = self.run_with(first, model_output(), raw=raw, now=lambda: clock.now)
        finally:
            flow.staffing_question = original
        self.assertEqual(out["error"]["code"], "AI_TIMEOUT")
        self.assertEqual(len(self.fake.sent), 1)

    def test_gms_error_is_unavailable(self):
        self.assertEqual(self.run_with(FakeGms.GmsError("GMS HTTP 503"))["error"]["code"], "AI_UNAVAILABLE")

    def test_recorded_gms_run(self):
        """2026-10-26 실제 GMS 1회 실행 기록. 모델 출력은 매번 달라 출력 일치 대신 계약 분기만 확인한다."""
        self.assertEqual(contract.validate_response(load("request.json"), load("flow/gms-ready.json")), ("READY", []))

    def test_conditions_exclude_identity(self):
        sent = gms_generator.conditions(contract.parse_request(load("request.json")))
        self.assertEqual(set(sent), {"event", "zones", "programs", "workGroups", "maxBundles"})
        self.assertEqual(sent["maxBundles"], 10)


if __name__ == "__main__":
    unittest.main()

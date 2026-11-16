"""필수 업무 품질 게이트 회귀 테스트 (S15P31A202-409, 모델 호출 없음).

저장된 실제 GMS 원본 출력을 모두 현재 코드로 다시 처리해, 빠진 필수 업무가 규칙으로 채워지거나 unresolved로 드러나는지
확인한다. 필수 업무 누락은 계획을 막지 않는다(252 MVP 기준·AUD-T09).
실행: cd ai/plan && uv run python -m unittest -v test_quality
"""

import copy
import json
import unittest
from datetime import timedelta
from pathlib import Path

import contract
import flow
import gms_generator
import quality
import schedule

HERE = Path(__file__).parent
EX = HERE / "examples"


class _Fake:
    class GmsError(RuntimeError):
        pass

    TIMEOUT_SECONDS = 120

    def __init__(self, reply):
        self.reply = reply

    def chat_json(self, messages, **kwargs):
        return copy.deepcopy(self.reply), "gpt-replay", {"total_tokens": 1}


def saved_runs():
    """(이름, 요청, 모델 원본, 일정표) 목록. 보완 회차 원본(2회차)도 실제 모델 출력이라 "#2"를 붙여 따로 다시 처리한다.

    모델을 부르지 않은 기록(short-prep, 선검사 질문)은 원본이 없어 빠진다.
    """
    out = []

    def add(name, request, plans, sched=None):
        for i, reply in enumerate(plans, 1):
            out.append((name if i == 1 else f"{name}#{i}", request, reply, sched))

    for f in sorted((EX / "demo" / "gms-runs").glob("*-raw.json")):
        add(f"demo/{f.name}", EX / "demo" / "request-ready.json", json.loads(f.read_text(encoding="utf-8")))
    for f in sorted((EX / "demo-pdf" / "gms-runs").glob("*-raw.json")):
        name = f.name.removesuffix("-raw.json")
        req = f.with_name(f"{name}-request.json")
        if not req.exists():   # 6~10회차 등은 요청을 저장하지 않았다. 같은 사례의 저장 요청을 쓴다
            req = EX / "demo-pdf" / f"request-{name.split('-', 1)[1].removesuffix('-schedule')}.json"
        sched = EX / "demo-pdf" / "schedule-p7.json" if name.endswith("-schedule") else None
        add(f"demo-pdf/{f.name}", req, json.loads(f.read_text(encoding="utf-8"))["plan"], sched)
    for f in sorted((EX / "eval").glob("*-gms-raw.json")):
        req = EX / "eval" / f"request-{f.name.removesuffix('-gms-raw.json')}.json"
        add(f"eval/{f.name}", req, json.loads(f.read_text(encoding="utf-8")))
    return out


def replay(request_path, reply, schedule_path=None):
    raw = json.loads(request_path.read_text(encoding="utf-8"))
    now = contract.parse_request(raw).deadlineAt - timedelta(minutes=10)
    original = gms_generator._load_gms
    gms_generator._load_gms = lambda: _Fake(reply)
    try:
        gen = gms_generator.GmsGenerator(schedule=schedule.load(schedule_path) if schedule_path else None)
        return raw, flow.run(raw, now, gen), gen
    finally:
        gms_generator._load_gms = original


def summary(raw, out):
    """분기 요약: (상태, 게이트가 보완한 경로, 보완하지 못해 unresolved로 남긴 경로). 경로의 id는 요청의 이름으로 바꾼다."""
    p = raw["payload"]
    names = {z["id"]: z["name"] for z in p["zones"]} | {g["id"]: g["name"] for g in p["workGroups"]}

    def short(path):
        parts = path.split(":")
        return ":".join(names.get(x, x) for x in parts[1:])

    if "error" in out:
        return out["error"]["details"].get("reason"), [], []
    filled = [r.removeprefix("required:").split(">")[0] for r in out["result"]["coordination"]["evidenceRefs"]
              if r.startswith("required:")]
    left = [i["path"] for i in quality.required_issues(contract.parse_request(raw), out["result"]["tasks"])]
    return out["result"]["status"], [short(r) for r in filled], [short(r) for r in left]


# 저장된 원본 1회차를 현재 코드로 다시 처리한 결과. 바뀌면 README '필수 업무 품질 게이트' 표도 함께 고친다.
EXPECTED = {
    # 253 대표 시연 조건(2026-11-05~09). 채울 수 있는 단계는 채우고, 후방 객석 업무 전체와 업무 없는 장비 직무는
    # 묶음이 전담 인원(6)만큼이라 채울 자리가 없어 unresolved로 남긴 채 READY다
    "demo/3-ready-raw.json": ("READY", ["zone:무대 앞 객석:operation", "zone:무대 앞 객석:teardown", "zone:입장 게이트:prep",
                                        "zone:입장 게이트:teardown", "group:무대영상:teardown"],
                              ["zone:후방 객석:prep", "zone:후방 객석:operation", "zone:후방 객석:teardown",
                               "group:장비 운영:tasks", "group:장비·시스템 장애 대응:tasks"]),
    "demo/4-ready-raw.json": ("READY", ["zone:무대 앞 객석:operation", "zone:입장 게이트:prep", "zone:입장 게이트:teardown"],
                              ["zone:후방 객석:prep", "zone:후방 객석:operation", "zone:후방 객석:teardown",
                               "group:장비 운영:tasks", "group:장비·시스템 장애 대응:tasks", "group:무대영상:tasks"]),
    "demo/5-overlap-raw.json": ("READY", ["zone:무대 앞 객석:prep", "zone:무대 앞 객석:teardown", "zone:후방 객석:operation",
                                          "zone:입장 게이트:prep", "zone:입장 게이트:teardown", "group:장비 운영:teardown"],
                                ["group:장비·시스템 장애 대응:tasks", "group:무대영상:tasks"]),
    "demo/5-overlap-raw.json#2": ("READY", ["zone:무대 앞 객석:prep", "zone:무대 앞 객석:teardown", "zone:후방 객석:operation",
                                            "zone:입장 게이트:prep", "zone:입장 게이트:teardown", "group:장비 운영:teardown"],
                                  ["group:장비·시스템 장애 대응:tasks", "group:무대영상:tasks"]),
    # 298 시연 PDF(2026-11-11)
    "demo-pdf/1-floorplan-raw.json": ("READY", ["group:음향 장비 운영:operation", "group:장비 장애 대응:operation",
                                                "group:장비 장애 대응:teardown"], []),
    "demo-pdf/2-floorplan-raw.json": ("READY", ["zone:B구역 객석 뒤쪽:prep", "group:장비 장애 대응:teardown"], []),
    "demo-pdf/3-floorplan-raw.json": ("READY", ["zone:B구역 객석 뒤쪽:prep", "zone:B구역 객석 뒤쪽:teardown",
                                                "group:음향 장비 운영:operation", "group:장비 장애 대응:operation"], []),
    "demo-pdf/4-text-raw.json": ("READY", ["group:음향 장비 운영:teardown", "group:장비 장애 대응:operation"], []),
    "demo-pdf/5-floorplan-raw.json": ("READY", ["entry", "group:음향 장비 운영:teardown", "group:장비 장애 대응:operation"], []),
    # 407 대표 디자인 PDF(2026-11-12). README 표의 B구역 준비·음향 철수·장애 대응 운영 누락이 그대로 보완된다
    "demo-pdf/6-design-raw.json": ("READY", ["zone:B구역 객석 뒤쪽:prep"], []),
    "demo-pdf/7-design-raw.json": ("READY", ["group:음향 장비 운영:teardown", "group:장비 장애 대응:operation"], []),
    "demo-pdf/8-design-raw.json": ("READY", ["group:음향 장비 운영:operation", "group:장비 장애 대응:operation"], []),
    "demo-pdf/9-design-raw.json": ("READY", ["zone:B구역 객석 뒤쪽:prep", "group:음향 장비 운영:teardown",
                                             "group:장비 장애 대응:operation"], []),
    "demo-pdf/10-design-raw.json": ("READY", ["zone:B구역 객석 뒤쪽:prep", "group:음향 장비 운영:teardown",
                                              "group:장비 장애 대응:operation"], []),
    "demo-pdf/11-design-raw.json": ("READY", ["group:음향 장비 운영:operation", "group:장비 장애 대응:operation"], []),
    # 409 게이트 반영 뒤 대표 디자인 PDF 실제 GMS 재검증(2026-11-13). 모델은 여전히 빠뜨리고 게이트가 채운다
    "demo-pdf/15-design-raw.json": ("READY", ["zone:B구역 객석 뒤쪽:operation", "entry", "group:음향 장비 운영:operation",
                                              "group:장비 장애 대응:operation"], []),
    "demo-pdf/16-design-raw.json": ("READY", ["group:음향 장비 운영:teardown", "group:장비 장애 대응:operation"], []),
    "demo-pdf/17-design-raw.json": ("READY", ["zone:B구역 객석 뒤쪽:prep", "group:음향 장비 운영:teardown",
                                              "group:장비 장애 대응:operation"], []),
    # 408 일정표 함께 전달(2026-11-13)
    "demo-pdf/12-design-schedule-raw.json": ("READY", [], []),
    "demo-pdf/13-design-schedule-raw.json": ("READY", ["group:장비 장애 대응:operation", "group:장비 장애 대응:teardown"], []),
    "demo-pdf/14-design-schedule-raw.json": ("READY", [], []),
    # 252 평가 사례 3차(2026-11-05)
    "eval/festival-multi-zone-gms-raw.json": ("READY", [
        "zone:입장 게이트:prep", "zone:입장 게이트:teardown", "zone:스탠딩 객석:prep", "zone:스탠딩 객석:operation",
        "zone:스탠딩 객석:teardown", "zone:백스테이지:prep", "zone:백스테이지:operation", "zone:백스테이지:teardown"], []),
    "eval/festival-multi-zone-gms-raw.json#2": ("READY", ["zone:스탠딩 객석:operation", "zone:백스테이지:operation"], []),
    "eval/outdoor-long-existing-groups-gms-raw.json": ("READY", [
        "group:의무 지원:tasks", "zone:잔디 객석:operation", "zone:안내·의무 부스:operation"], []),
    # 2회차는 묶음이 전담 인원만큼 차서 업무 없는 '의무 지원'에 새 묶음을 열 수 없다. unresolved로 남긴다
    "eval/outdoor-long-existing-groups-gms-raw.json#2": ("READY", ["zone:잔디 객석:operation", "zone:안내·의무 부스:operation"],
                                                         ["group:의무 지원:tasks"]),
    "eval/small-single-stage-gms-raw.json": ("READY", [], []),
}


class SavedRunReplayTest(unittest.TestCase):
    def test_every_saved_run(self):
        runs = saved_runs()
        self.assertEqual(sorted(name for name, *_ in runs), sorted(EXPECTED))
        for name, request, reply, sched in runs:
            with self.subTest(name):
                raw, out, gen = replay(request, reply, sched)
                self.assertEqual(gen.calls, 1)   # 누락 판정·보완은 모델을 다시 부르지 않는다
                branch, issues = contract.validate_response(raw, out)
                self.assertEqual(issues, [])
                self.assertEqual(branch, "READY")   # 필수 업무 누락은 막지 않는다
                left = quality.required_issues(contract.parse_request(raw), out["result"]["tasks"])
                refs = {r for u in out["result"]["unresolved"] for r in u["refs"]}
                for i in left:   # 채우지 못한 누락은 모두 unresolved로 드러난다
                    self.assertIn(quality._target_ref(i["path"]), refs)
                self.assertEqual(summary(raw, out), EXPECTED[name])

    def test_unrecoverable_gaps_are_left_unresolved(self):
        """채울 자리가 없는 누락은 막지 않고 required-n 안내로 남긴다. 안내는 요청 id만 가리키고 모델 원문은 쓰지 않는다."""
        raw, out, _ = replay(EX / "demo" / "request-ready.json",
                             json.loads((EX / "demo" / "gms-runs" / "3-ready-raw.json").read_text(encoding="utf-8"))[0])
        rear = "25300000-0000-4000-8000-000000000103"
        required = [u for u in out["result"]["unresolved"] if u["key"].startswith("required-")]
        self.assertEqual([(u["key"], u["refs"]) for u in required],
                         [("required-1", [rear]), ("required-2", [rear]), ("required-3", [rear])])
        self.assertTrue(all("후방 객석" in u["description"] and "직접 추가" in u["description"] for u in required))
        # 업무 없는 장비 직무는 변환이 이미 남긴 group-n 안내가 있어 겹쳐 쓰지 않는다
        self.assertEqual([u["key"] for u in out["result"]["unresolved"] if u["key"].startswith("group-")], ["group-1", "group-2"])


class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.raw = json.loads((EX / "demo-pdf" / "request-design.json").read_text(encoding="utf-8"))
        self.req = contract.parse_request(self.raw)
        self.e = self.req.payload.event
        self.zone_a, self.zone_b = (z.id for z in self.req.payload.zones)
        self.groups = {g.name: g.id for g in self.req.payload.workGroups}

    def draft(self, tasks):
        return flow.PlanDraft(workGroups=[{"id": g.id, "tempId": None, "name": g.name} for g in self.req.payload.workGroups],
                              tasks=tasks)

    def task(self, tid, group, bundle, zone, start, end, title=None):
        return {"tempId": tid, "workGroupId": self.groups[group], "bundleKey": bundle, "zoneId": zone, "programId": None,
                "title": title or tid, "startAt": start, "endAt": end, "steps": ["x"], "checklist": [], "assigneeId": None}

    def full(self):
        """두 구역·세 직무가 모두 준비·운영·정리를 갖춘 4묶음 후보."""
        e = self.e
        return [self.task("s1", "음향 장비 운영", "sound", self.zone_a, e.prepStartAt, e.openAt),
                self.task("s2", "음향 장비 운영", "sound", self.zone_a, e.openAt, e.closeAt, "공연 중 음향 상태 확인"),
                self.task("s3", "음향 장비 운영", "sound", self.zone_a, e.closeAt, e.teardownEndAt),
                self.task("f1", "장비 장애 대응", "fix", self.zone_a, e.prepStartAt, e.openAt),
                self.task("f2", "장비 장애 대응", "fix", self.zone_a, e.openAt, e.closeAt, "장비 장애 대응 대기"),
                self.task("f3", "장비 장애 대응", "fix", self.zone_a, e.closeAt, e.teardownEndAt),
                self.task("a1", "관객 안전", "safety-a", self.zone_a, e.prepStartAt, e.openAt),
                self.task("a2", "관객 안전", "safety-a", self.zone_a, e.openAt, e.closeAt, "A구역 입장 안내"),
                self.task("a3", "관객 안전", "safety-a", self.zone_a, e.closeAt, e.teardownEndAt),
                self.task("b1", "관객 안전", "safety-b", self.zone_b, e.prepStartAt, e.openAt),
                self.task("b2", "관객 안전", "safety-b", self.zone_b, e.openAt, e.closeAt),
                self.task("b3", "관객 안전", "safety-b", self.zone_b, e.closeAt, e.teardownEndAt)]

    def test_complete_candidate_needs_nothing(self):
        d = self.draft(self.full())
        self.assertEqual(quality.required_issues(self.req, d.tasks), [])
        self.assertEqual(quality.recover(self.req, d), [])

    def test_zone_prep_is_added_before_open_in_zone_bundle(self):
        """B구역 담당이 개장 시각부터 일을 시작하면(407 6·9·10회차) 그 담당의 개장 전 빈 시간에 준비를 더한다."""
        tasks = [t for t in self.full() if t["tempId"] != "b1"]
        d = self.draft(tasks)
        self.assertEqual([i["path"] for i in quality.required_issues(self.req, d.tasks)],
                         [f"coverage:zone:{self.zone_b}:prep"])
        filled = quality.recover(self.req, d)
        added = next(t for t in d.tasks if t["tempId"] == filled[0][1])
        self.assertEqual((added["bundleKey"], added["zoneId"], added["workGroupId"]),
                         ("safety-b", self.zone_b, self.groups["관객 안전"]))
        self.assertEqual((added["startAt"], added["endAt"]), (self.e.openAt - timedelta(minutes=30), self.e.openAt))
        self.assertEqual(d.kinds[added["tempId"]], "setup")

    def test_zone_operation_and_teardown(self):
        tasks = [t for t in self.full() if t["tempId"] not in ("b2", "b3")]
        d = self.draft(tasks)
        quality.recover(self.req, d)
        self.assertEqual(quality.required_issues(self.req, d.tasks), [])
        b = [t for t in d.tasks if t["bundleKey"] == "safety-b"]
        self.assertEqual(len(b), 3)
        self.assertEqual(min(t["startAt"] for t in b if t["startAt"] >= self.e.closeAt), self.e.closeAt)

    def test_busy_bundle_opens_new_bundle_only_within_capacity(self):
        """구역 담당이 바빠 빈 시간이 없으면 전담 인원 안에서만 새 묶음을 연다. 넘으면 보완하지 않는다."""
        e = self.e
        tasks = [t for t in self.full() if t["tempId"] not in ("b1", "b2", "b3")]
        tasks.append(self.task("b0", "관객 안전", "safety-b", self.zone_b, e.openAt, e.teardownEndAt))  # 개장 뒤 내내 바쁨
        d = self.draft(tasks)   # 준비는 safety-b의 개장 전 빈 시간에 넣고, 정리는 담당이 바쁘고 인원도 다 차 보완하지 못한다
        quality.recover(self.req, d)
        self.assertEqual([i["path"] for i in quality.required_issues(self.req, d.tasks)],
                         [f"coverage:zone:{self.zone_b}:teardown"])

        tasks = [t for t in self.full() if t["zoneId"] != self.zone_b]
        d = self.draft(tasks)   # B구역을 맡은 묶음이 없다. 묶음 3개 < 전담 인원 4명이라 새 묶음을 연다
        quality.recover(self.req, d)
        self.assertEqual(quality.required_issues(self.req, d.tasks), [])
        self.assertEqual({t["bundleKey"] for t in d.tasks if t["zoneId"] == self.zone_b}, {"req-1"})

        tasks = [t for t in self.full() if t["zoneId"] != self.zone_b]
        tasks.append(self.task("x", "관객 안전", "extra", self.zone_a, e.prepStartAt, e.teardownEndAt))
        d = self.draft(tasks)   # 묶음 4개 = 전담 인원이라 새 묶음을 열 수 없다
        quality.recover(self.req, d)
        self.assertEqual([i["path"].rsplit(":", 1)[1] for i in quality.required_issues(self.req, d.tasks)],
                         ["prep", "operation", "teardown"])

    def test_empty_existing_group(self):
        """업무 없는 기존 직무는 인원 안에서 새 묶음으로 채운다. 장비 직무는 준비·대기·철수를 갖는다."""
        tasks = [t for t in self.full() if t["bundleKey"] != "fix"]
        d = self.draft(tasks)
        d.unresolved = [contract.Unresolved(key="group-1", description="업무 없음", refs=[self.groups["장비 장애 대응"]])]
        quality.recover(self.req, d)
        mine = [t for t in d.tasks if t["workGroupId"] == self.groups["장비 장애 대응"]]
        self.assertEqual(len(mine), 3)
        self.assertEqual(len({t["bundleKey"] for t in mine}), 1)
        self.assertEqual(d.unresolved, [])   # 채운 그룹의 자동 안내는 지운다
        self.assertEqual(quality.required_issues(self.req, d.tasks), [])

    def test_entry_and_standby_need_matching_titles(self):
        """개장 시각과 겹치는 아무 업무, 운영 시간과 겹치는 아무 장비 업무로는 입장·대기를 인정하지 않는다(리뷰 재현)."""
        tasks = self.full()
        for t in tasks:
            t["title"] = {"s2": "음향 장비 최종 점검", "f2": "장비 정리", "a2": "A구역 관객 흐름 관리"}.get(t["tempId"], "현장 업무")
        paths = [i["path"] for i in quality.required_issues(self.req, tasks)]
        self.assertEqual(paths, ["coverage:entry", f"coverage:group:{self.groups['음향 장비 운영']}:operation",
                                 f"coverage:group:{self.groups['장비 장애 대응']}:operation"])

        tasks = self.full()   # 프로그램에 연결된 장비 업무는 제목과 관계없이 운영 중 운용으로 인정한다
        s2 = next(t for t in tasks if t["tempId"] == "s2")
        s2["title"], s2["programId"] = "첫 번째 불빛 음향 지원", self.req.payload.programs[0].id
        self.assertEqual(quality.required_issues(self.req, tasks), [])

    def test_entry_is_given_to_free_safety_bundle(self):
        e = self.e
        tasks = [t for t in self.full() if t["tempId"] not in ("a2",)]
        tasks.append(self.task("a2", "관객 안전", "safety-a", self.zone_a, e.openAt + timedelta(minutes=10), e.closeAt))
        for t in tasks:   # 개장 시각에 진행 중인 업무를 모두 없앤다(다른 묶음은 프로그램 업무로 바꾼다)
            if t["startAt"] <= e.openAt < t["endAt"] and t["tempId"] != "a2":
                t["programId"] = self.req.payload.programs[0].id
        d = self.draft(tasks)
        self.assertIn("coverage:entry", [i["path"] for i in quality.required_issues(self.req, d.tasks)])
        filled = quality.recover(self.req, d)
        added = next(t for t in d.tasks if t["tempId"] == dict(filled)["coverage:entry"])
        self.assertEqual((added["bundleKey"], added["startAt"]), ("safety-a", e.openAt))
        self.assertEqual(quality.required_issues(self.req, d.tasks), [])


if __name__ == "__main__":
    unittest.main()

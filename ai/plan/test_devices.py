"""카메라·장비·그룹 표시 선택 필드 시험(S15P31A202-400). 실행: cd ai/plan && uv run python -m unittest -v test_devices

시연 요청(examples/demo-pdf/request-floorplan.json)의 조건 글에는 무선 수신기 QLXD4, 핸드헬드 QLXD2, 바디팩 QLXD1,
객석 카메라(웹캠) 1대가 있고, 직무 그룹은 음향 장비 운영·장비 장애 대응·관객 안전이다.
"""
import copy
import json
import os
import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock

import contract
import devices
import flow
from devices import GmsDeviceReader, auto_observation_kind, normalize, purpose
from test_floor_plan import FakeGms, demo, reader, truth_reply

NOW = datetime(2026, 11, 24, 8, 0, tzinfo=timezone.utc)

# GMS가 시연 조건 글에서 낼 법한 출력(모델명·위치는 글에 적힌 그대로)
REPLY = {
    "cameras": [{"name": "객석 카메라 1", "zoneName": "A구역 무대 앞"}],
    "assets": [
        {"name": "무선 수신기 1", "kind": "무선 수신기", "model": "QLXD4", "location": "보컬"},
        {"name": "핸드헬드 무선 마이크", "kind": "무선 마이크", "model": "QLXD2", "location": "보컬 메인"},
        {"name": "바디팩 송신기", "kind": "무선 송신기", "model": "QLXD1", "location": None},
        {"name": "디지털 믹서", "kind": "믹서", "model": None, "location": None},
        {"name": "객석 카메라", "kind": "카메라", "model": None, "location": None},
    ],
}


def devices_reader(reply=REPLY, clock=None):
    return GmsDeviceReader(gms=FakeGms(reply), clock=clock or (lambda: NOW))


def run(raw=None, device_reader=None):
    return flow.run(raw or demo(), NOW, venue_reader=reader(truth_reply()), device_reader=device_reader)


class RuleTest(unittest.TestCase):
    def test_group_purpose_from_names(self):
        self.assertEqual(purpose("관객 안전"), "SAFETY")
        self.assertEqual(purpose("음향 장비 운영"), "EQUIPMENT")
        self.assertEqual(purpose("장비 장애 대응"), "EQUIPMENT")
        self.assertEqual(purpose("관객 안내"), "SAFETY")
        self.assertIsNone(purpose("현장 설치"))
        self.assertEqual(purpose("무대 기술 지원"), "EQUIPMENT")
        self.assertIsNone(purpose("기술 리허설"))                          # '기술'만으로는 장비 그룹으로 보지 않는다
        self.assertEqual(purpose("안전 장비 점검"), "SAFETY")      # 둘 다 있으면 앞에 나온 낱말

    def test_only_qlxd4_receiver_is_auto_observed(self):
        for model in ("QLXD4", "qlxd4", "QLX-D4", "QLXD 4"):
            self.assertEqual(auto_observation_kind(model), "QLXD4", model)
        for model in ("QLXD2", "QLXD1", "QLXD4K", None, ""):
            self.assertIsNone(auto_observation_kind(model), model)

    def test_normalize_drops_unknown_zone_blank_and_duplicate(self):
        raw = {"cameras": [{"name": "객석 카메라 1", "zoneName": "A구역무대 앞"},       # 띄어쓰기만 다름 → 요청 이름
                           {"name": "객석 카메라 1", "zoneName": "B구역 객석 뒤쪽"},    # 이름 중복
                           {"name": "로비 카메라", "zoneName": "로비"},                # 구역 없음
                           {"name": " ", "zoneName": "A구역 무대 앞"}],
               "assets": [{"name": "수신기", "kind": None, "model": "QLXD4", "location": None},
                          {"name": "수신기", "kind": None, "model": None, "location": None}]}
        cameras, assets, dropped = normalize(raw, ["A구역 무대 앞", "B구역 객석 뒤쪽"])
        self.assertEqual(cameras, [{"name": "객석 카메라 1", "zoneName": "A구역 무대 앞"}])
        self.assertEqual([a["autoObservationKind"] for a in assets], ["QLXD4"])
        self.assertEqual(dropped, 4)
        self.assertEqual(normalize("not a dict", ["A"]), ([], [], 0))

    def test_names_are_cut_to_registration_limit(self):
        # 등록 API 이름 상한(40자)에 맞춘다. 잘린 뒤 같아진 이름은 겹침으로 버린다(#395)
        long = "가" * 45
        raw = {"cameras": [{"name": long, "zoneName": "A"}],
               "assets": [{"name": long + "1", "model": None}, {"name": long + "2", "model": None}]}
        cameras, assets, dropped = normalize(raw, ["A"])
        self.assertEqual(cameras[0]["name"], "가" * 40)
        self.assertEqual([a["name"] for a in assets], ["가" * 40])
        self.assertEqual(dropped, 1)


    def test_cameras_are_not_equipment(self):
        # 카메라는 카메라 칸으로만 만든다(2026-11-12 결정, #401). 이름·종류에 카메라·웹캠이 있으면 장비에서 뺀다
        raw = {"cameras": [{"name": "객석 카메라 1", "zoneName": "A"}],
               "assets": [{"name": "객석 카메라", "kind": "카메라", "model": None},
                          {"name": "운영 웹캠", "kind": None, "model": None},
                          {"name": "Stage Camera", "kind": None, "model": None},
                          {"name": "메인 믹서", "kind": "믹서", "model": None}]}
        cameras, assets, dropped = normalize(raw, ["A"])
        self.assertEqual([c["name"] for c in cameras], ["객석 카메라 1"])
        self.assertEqual([a["name"] for a in assets], ["메인 믹서"])
        self.assertEqual(dropped, 3)


class FlowTest(unittest.TestCase):
    def test_demo_pdf_produces_cameras_assets_and_purpose(self):
        reader_ = devices_reader()
        res = run(device_reader=reader_)
        self.assertEqual(res["resultSchemaVersion"], "6", res)
        r = res["result"]
        self.assertEqual(r["status"], "READY")
        self.assertEqual(r["cameras"], REPLY["cameras"])
        kinds = {a["model"]: a["autoObservationKind"] for a in r["equipmentDraft"]["assets"]}
        self.assertEqual(kinds, {"QLXD4": "QLXD4", "QLXD2": None, "QLXD1": None, None: None})
        self.assertEqual(set(r["equipmentDraft"]), {"assets"})          # connections 등은 만들지 않는다
        by_name = {g["name"]: g["purpose"] for g in r["workGroups"]}
        self.assertEqual(by_name["관객 안전"], "SAFETY")
        self.assertEqual(by_name["음향 장비 운영"], "EQUIPMENT")
        self.assertTrue(all("purpose" in g for g in r["workGroups"]))
        self.assertEqual(contract.validate_response(demo(), res)[0], "READY")
        sent = json.loads(reader_._gms.messages[1]["content"])           # 보낸 글: 조건·구역 이름만
        self.assertIn("QLXD4", sent["conditions"]["equipment"])
        self.assertEqual(set(sent), {"zoneNames", "description", "conditions"})
        self.assertEqual(reader_._gms.kwargs["response_format"]["type"], "json_schema")

    def test_without_reader_no_keys_are_sent(self):
        r = run()["result"]                                             # 설정 off: 지금 BE와 같은 결과
        self.assertFalse({"cameras", "equipmentDraft"} & set(r))
        self.assertFalse(any("purpose" in g for g in r["workGroups"]))

    def test_request_without_floor_plan_also_gets_device_fields(self):
        """V9-D61: 평면도 없는 행사(결과5)에도 카메라·장비·그룹 표시를 싣는다. 결과 버전은 그대로 5다."""
        raw = demo("text")
        res = flow.run(raw, NOW, device_reader=devices_reader())
        self.assertEqual(res["resultSchemaVersion"], "5")
        self.assertNotIn("venueDraft", res["result"])
        self.assertEqual(res["result"]["cameras"], REPLY["cameras"])
        # 모델이 장비 목록에 섞어 낸 카메라는 장비 칸으로 만들지 않는다
        self.assertEqual(len(res["result"]["equipmentDraft"]["assets"]), len(REPLY["assets"]) - 1)
        self.assertTrue(all("purpose" in g for g in res["result"]["workGroups"]))
        self.assertEqual(contract.validate_response(raw, res)[0], "READY")

    def test_no_devices_in_document_gives_empty_lists(self):
        raw = demo()
        raw["payload"]["event"]["confirmedInput"] = {}
        raw["payload"]["event"]["description"] = ""
        fake = FakeGms(REPLY)
        res = run(raw, GmsDeviceReader(gms=fake, clock=lambda: NOW))
        self.assertEqual((res["result"]["cameras"], res["result"]["equipmentDraft"]), ([], {"assets": []}))
        self.assertIsNone(fake.messages)                                  # 글이 없으면 모델을 부르지 않는다
        self.assertEqual(contract.validate_response(raw, res)[0], "READY")

    def test_model_failure_or_deadline_keeps_the_plan(self):
        for r in (devices_reader(RuntimeError("GMS HTTP 503")),
                  devices_reader(clock=lambda: datetime.fromisoformat(demo()["deadlineAt"]) - timedelta(seconds=5))):
            res = run(device_reader=r)
            self.assertEqual(res["result"]["status"], "READY")
            self.assertEqual(res["result"]["cameras"], [])

    def test_contract_rejects_unknown_camera_zone_and_duplicate_names(self):
        res = run(device_reader=devices_reader())
        bad = copy.deepcopy(res)
        bad["result"]["cameras"][0]["zoneName"] = "로비"
        self.assertEqual(contract.validate_response(demo(), bad)[0], "INVALID")
        dup = copy.deepcopy(res)
        dup["result"]["equipmentDraft"]["assets"].append(dict(dup["result"]["equipmentDraft"]["assets"][0]))
        self.assertEqual(contract.validate_response(demo(), dup)[0], "INVALID")


class SettingsTest(unittest.TestCase):
    def test_device_fields_are_off_unless_enabled(self):
        import server
        settings = server.Settings(service_token="t", generator="rule")
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("PLAN_DEVICE_FIELDS", None)
            self.assertIsNone(settings.new_device_reader())
        for value in ("true", "1", " ON "):
            with mock.patch.dict(os.environ, {"PLAN_DEVICE_FIELDS": value}):
                self.assertIsNotNone(settings.new_device_reader(), value)
        with mock.patch.dict(os.environ, {"PLAN_DEVICE_FIELDS": "on"}):
            self.assertIsInstance(settings.new_device_reader(), devices.NoDeviceReader)
            self.assertIsInstance(server.Settings(service_token="t").new_device_reader(), GmsDeviceReader)


if __name__ == "__main__":
    unittest.main()

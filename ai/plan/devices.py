"""계획 결과(결과5·6)의 카메라·장비·그룹 표시 선택 필드(V9-D58·D59·D60·D61, S15P31A202-400).

- cameras[{name, zoneName}]: 문서(확인된 행사 조건)에 적힌 객석 카메라. zoneName은 같은 결과 zones의 이름과 같다.
- equipmentDraft.assets[{name, kind, model, location, autoObservationKind}]: 문서에 적힌 장비. autoObservationKind는
  모델명이 QLXD4(무선 수신기)일 때만 "QLXD4"이고 나머지는 null이다(제품명 규칙, 모델 판단을 쓰지 않음).
- workGroups[].purpose: SAFETY·EQUIPMENT·null. 그룹 이름 낱말 규칙으로 정한다. BE가 연결 업무를 넣을 그룹을 고를 때만 쓴다.

카메라·장비 목록은 GMS가 조건 글에서 뽑는다(평면도 읽기와 같은 방식, 요청당 1회). 문서에 없으면 빈 목록이다.
"객석 카메라 연결"·"장비 자동 수집 연결" 업무는 만들지 않는다(BE 규칙).

BE는 계획 결과를 모르는 필드를 거절하는 엄격한 JSON으로 읽는다. BE가 이 필드를 받기 전(S15P31A202-395)에 내보내면
모든 계획 생성이 INVALID가 되므로, 환경변수 PLAN_DEVICE_FIELDS=on일 때만 내보낸다(기본 off).
"""
import json
import os
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone

MAX_CAMERAS = 20
MAX_ASSETS = 50
NAME_MAX = 40   # 카메라·장비 등록 API 이름 상한과 같다(#395). 넘으면 공개 때 잘려 이름이 겹칠 수 있다
MAX_CALL_SECONDS = 60
RESERVE_SECONDS = 10   # 마감 전에 남겨 둘 시간. 이보다 적게 남으면 읽지 않는다
AUTO_OBSERVATION_MODELS = {"QLXD4": "QLXD4"}   # 정규화한 모델명 → autoObservationKind

SAFETY_WORDS = ("안전", "관객", "질서", "안내", "통제", "경호", "의료")
EQUIPMENT_WORDS = ("장비", "음향", "조명", "영상", "무대 기술", "마이크", "믹서")
# 카메라는 카메라 칸(safety_camera)으로만 만든다. 장비 목록에 섞여 오면 뺀다(2026-11-12 오세진 결정, #401)
CAMERA_WORDS = ("카메라", "웹캠", "camera", "webcam")

SYSTEM = """너는 콘서트 운영 계획서의 확인된 조건 글에서 객석 카메라와 장비 목록을 뽑는다. JSON 객체 하나만 출력한다.
- cameras: 관객석이나 구역을 비추는 고정 카메라(웹캠 포함). name은 짧은 한국어 이름(예: "객석 카메라 1"),
  zoneName은 주어진 zoneNames 중 카메라가 비추는 구역 하나를 글자 그대로 쓴다. 여러 구역을 비추면 가장 먼저 적힌 구역을 쓴다.
- assets: 장비 한 대마다 한 항목. 같은 장비가 N대이면 "이름 1", "이름 2"처럼 N개로 나눈다.
  name은 짧은 한국어 이름, kind는 종류(예: 무선 수신기, 무선 마이크, 믹서, 스피커), model은 적힌 모델명(없으면 null),
  location은 적힌 위치나 용도(없으면 null). 카메라·웹캠은 cameras에만 넣고 assets에는 넣지 않는다.
- 글에 없는 장비·카메라·모델·위치는 만들지 않는다. 없으면 빈 목록이다."""

SCHEMA = {"name": "device_fields", "strict": True, "schema": {
    "type": "object", "additionalProperties": False, "required": ["cameras", "assets"],
    "properties": {
        "cameras": {"type": "array", "items": {
            "type": "object", "additionalProperties": False, "required": ["name", "zoneName"],
            "properties": {"name": {"type": "string"}, "zoneName": {"type": "string"}}}},
        "assets": {"type": "array", "items": {
            "type": "object", "additionalProperties": False, "required": ["name", "kind", "model", "location"],
            "properties": {"name": {"type": "string"}, "kind": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                           "model": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                           "location": {"anyOf": [{"type": "string"}, {"type": "null"}]}}}}}}}


def enabled():
    return os.environ.get("PLAN_DEVICE_FIELDS", "off").strip().lower() in ("on", "true", "1", "yes")


def purpose(group_name):
    """그룹 이름으로 연결 업무 대상 표시를 정한다. 두 낱말이 모두 있으면 앞에 나온 쪽, 없으면 None."""
    hits = [(group_name.find(w), kind) for kind, words in (("SAFETY", SAFETY_WORDS), ("EQUIPMENT", EQUIPMENT_WORDS))
            for w in words if w in group_name]
    return min(hits)[1] if hits else None


def auto_observation_kind(model):
    if not model:
        return None
    return AUTO_OBSERVATION_MODELS.get(re.sub(r"[\s\-_]", "", str(model)).upper())


def _text(value, limit):
    value = " ".join(str(value).split()) if value is not None else ""
    return value[:limit] or None


def _is_camera(name, kind):
    text = f"{name} {kind or ''}".lower()
    return any(word in text for word in CAMERA_WORDS)


def normalize(raw, zone_names):
    """모델 출력을 계약 모양으로 정리한다. 이름 없는 항목·주어진 구역에 없는 카메라는 버린다. 반환: (cameras, assets, 버린 수)"""
    by_norm = {"".join(n.split()): n for n in zone_names}
    cameras, assets, dropped, seen = [], [], 0, set()
    for item in (raw.get("cameras") or []) if isinstance(raw, dict) else []:
        name = _text(item.get("name") if isinstance(item, dict) else None, NAME_MAX)
        zone = by_norm.get("".join(str(item.get("zoneName", "")).split())) if isinstance(item, dict) else None
        if not name or zone is None or name in seen or len(cameras) >= MAX_CAMERAS:
            dropped += 1
            continue
        seen.add(name)
        cameras.append({"name": name, "zoneName": zone})
    seen = set()
    for item in (raw.get("assets") or []) if isinstance(raw, dict) else []:
        name = _text(item.get("name") if isinstance(item, dict) else None, NAME_MAX)
        if name and _is_camera(name, item.get("kind")):
            dropped += 1
            continue
        if not name or name in seen or len(assets) >= MAX_ASSETS:
            dropped += 1
            continue
        seen.add(name)
        model = _text(item.get("model"), 100)
        assets.append({"name": name, "kind": _text(item.get("kind"), 100), "model": model,
                       "location": _text(item.get("location"), 200), "autoObservationKind": auto_observation_kind(model)})
    return cameras, assets, dropped


def condition_text(req):
    """모델에 보낼 글: 확인된 조건과 공연 설명, 구역 이름. 요청 식별·해시는 보내지 않는다."""
    e = req.payload.event
    return {"zoneNames": [z.name for z in req.payload.zones], "description": e.description or "",
            "conditions": dict(e.confirmedInput or {})}


@dataclass
class DeviceOutcome:
    cameras: list = field(default_factory=list)
    assets: list = field(default_factory=list)
    summary: str = ""


class NoDeviceReader:
    """카메라·장비를 읽지 않는 실행(규칙 생성기). 빈 목록을 낸다."""
    calls = 0

    def read(self, req):
        return DeviceOutcome(summary="카메라·장비 읽기를 쓰지 않는 실행이라 빈 목록")


class GmsDeviceReader:
    """요청 하나의 조건 글에서 카메라·장비를 읽는다. 예외를 밖으로 내지 않고 실패하면 빈 목록이다."""

    def __init__(self, model_var="GMS_PLAN_MODEL", gms=None, clock=None):
        self.model_var = model_var
        self._gms = gms                 # 테스트: chat_json을 가진 객체
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        self.calls = 0
        self.raw_outputs = []

    def _client(self):
        if self._gms is None:
            from gms_generator import _load_gms
            self._gms = _load_gms()
        return self._gms

    def read(self, req):
        text = condition_text(req)
        if not text["conditions"] and not text["description"]:
            return DeviceOutcome(summary="조건 글이 없어 카메라·장비 없음")
        try:
            left = (req.deadlineAt - self.clock()).total_seconds() - RESERVE_SECONDS
            if left < 1:
                return DeviceOutcome(summary="마감 임박으로 카메라·장비를 읽지 않음")
            gms = self._client()
            messages = [{"role": "system", "content": SYSTEM},
                        {"role": "user", "content": json.dumps(text, ensure_ascii=False)}]
            data, _, _ = gms.chat_json(messages, temperature=0, retries=0, model_var=self.model_var,
                                       response_format={"type": "json_schema", "json_schema": SCHEMA},
                                       timeout=max(1, min(MAX_CALL_SECONDS, int(left))))
        except Exception as e:  # 카메라·장비 실패가 계획 생성을 막지 않게 한다
            return DeviceOutcome(summary=f"카메라·장비를 읽지 못함({type(e).__name__})")
        self.calls += 1
        self.raw_outputs.append(data)
        cameras, assets, dropped = normalize(data, text["zoneNames"])
        summary = f"조건 글에서 카메라 {len(cameras)}대·장비 {len(assets)}개를 읽음"
        if dropped:
            summary += f", 구역·이름이 맞지 않는 {dropped}건 제외"
        return DeviceOutcome(cameras=cameras, assets=assets, summary=summary)

"""고정 카메라 밀집도 경로(POST /internal/estimate-crowd) 계약.

기준: docs/planning/records/DECISIONS.md V9-D53, backend/docs/safety-camera.md "AI 서버 경로",
BE 호출부 backend/.../safety/collector/AiCrowdEstimator.java(응답 검사).
- 요청: schemaVersion "1", eventId·cameraId·streamSessionId(UUID), sequence(10진 문자열), capturedAt, inputHash(sha256),
  image{contentType image/jpeg, base64}, zones[{zoneId, scope FULL|PARTIAL|UNDETERMINED, roi[{x,y}]}]
- 응답 200: {modelVersion, zones[{zoneId, estimatedCount(0~100000 수 또는 null), qualityReasons[대문자 코드 0~10개]}]}
  요청한 구역을 같은 순서로 한 번씩만 돌려준다. BE는 하나라도 어긋나면 결과 전체를 버린다.
- 실패: {error{code, message, retryable}}. BE는 200이 아니면 이번 프레임의 관측을 남기지 않는다(다음 프레임이 대신한다).
- 이미지는 저장·기록하지 않는다(V9-D09). inputHash가 이미지 바이트의 sha256과 다르면 INVALID_REQUEST다.

사용 (ai/crowd에서):
    uv run python contract.py check examples/request.json examples/success.json
"""

import base64
import binascii
import hashlib
import json
import sys
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError, field_validator

SCHEMA_VERSION = "1"
MAX_ZONES = 50
MAX_ROI_POINTS = 64
MAX_IMAGE_BYTES = 2 * 1024 * 1024  # BE 프레임 수신 상한(2MB)과 같다
MAX_COUNT = 100_000
MAX_REASONS = 10

Uuid = Annotated[str, StringConstraints(pattern=r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
Reason = Annotated[str, StringConstraints(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")]

ERROR_RETRYABLE = {
    "INVALID_REQUEST": False,   # 형식·해시·이미지 디코딩 오류. 같은 입력을 다시 보내도 같다
    "AI_UNAVAILABLE": True,     # 모델 미준비·실행 오류
    "AI_BUSY": True,            # 앞선 추정이 아직 실행 중. 다음 프레임에서 다시 부른다
}


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Point(Strict):
    x: float = Field(allow_inf_nan=False)
    y: float = Field(allow_inf_nan=False)


class Image(Strict):
    contentType: Literal["image/jpeg"]
    base64: str = Field(min_length=1)


class Zone(Strict):
    zoneId: Uuid
    scope: Literal["FULL", "PARTIAL", "UNDETERMINED"]
    # UNDETERMINED는 범위를 모를 수 있어 비거나 null일 수 있다. 그 밖은 계수 시점에 확인한다.
    roi: list[Point] | None = Field(default=None, max_length=MAX_ROI_POINTS)


class Request(Strict):
    schemaVersion: Literal["1"]
    eventId: Uuid
    cameraId: Uuid
    streamSessionId: Uuid
    sequence: Annotated[str, StringConstraints(pattern=r"^[0-9]{1,19}$")]
    capturedAt: str = Field(min_length=1, max_length=40)
    inputHash: Sha256
    image: Image
    zones: list[Zone] = Field(min_length=1, max_length=MAX_ZONES)

    @field_validator("zones")
    @classmethod
    def unique_zones(cls, zones):
        ids = [z.zoneId.lower() for z in zones]
        if len(ids) != len(set(ids)):
            raise ValueError("같은 zoneId가 두 번 있습니다.")
        return zones


class ZoneResult(Strict):
    zoneId: Uuid
    estimatedCount: float | None = Field(ge=0, le=MAX_COUNT, allow_inf_nan=False)
    qualityReasons: list[Reason] = Field(max_length=MAX_REASONS)


class Success(Strict):
    modelVersion: str = Field(min_length=1, max_length=100)
    zones: list[ZoneResult]


class ContractViolation(ValueError):
    pass


def parse_request(raw):
    """요청을 검사하고 (Request, JPEG 바이트)를 돌려준다. 실패하면 ContractViolation."""
    try:
        req = Request.model_validate(raw)
    except ValidationError as e:
        raise ContractViolation(_summary(e)) from None
    try:
        data = base64.b64decode(req.image.base64, validate=True)
    except (binascii.Error, ValueError):
        raise ContractViolation("image.base64를 해독할 수 없습니다.") from None
    if not data or len(data) > MAX_IMAGE_BYTES:
        raise ContractViolation(f"이미지는 1바이트 이상 {MAX_IMAGE_BYTES}바이트 이하입니다.")
    if hashlib.sha256(data).hexdigest() != req.inputHash:
        raise ContractViolation("inputHash가 이미지 바이트의 sha256과 다릅니다.")
    return req, data


def check_response(req, raw):
    """BE AiCrowdEstimator.parse와 같은 기준으로 응답을 검사한다(테스트·예시 대조용)."""
    res = Success.model_validate(raw)
    asked = [z.zoneId for z in req.zones]
    got = [z.zoneId for z in res.zones]
    if got != asked:
        raise ContractViolation(f"요청한 구역을 같은 순서로 한 번씩 돌려줘야 합니다: {got} != {asked}")
    return res


def failure(code, message):
    return {"error": {"code": code, "message": message, "retryable": ERROR_RETRYABLE[code]}}


def _summary(error):
    first = error.errors()[0]
    where = ".".join(str(p) for p in first["loc"]) or "(본문)"
    return f"{where}: {first['msg']}"[:300]


def main(argv):
    if len(argv) != 4 or argv[1] != "check":
        print(__doc__)
        return 2
    with open(argv[2], encoding="utf-8") as f:
        req, _ = parse_request(json.load(f))
    with open(argv[3], encoding="utf-8") as f:
        check_response(req, json.load(f))
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

"""STT 전사 계약: 공통 요청4(kind=TRANSCRIBE)와 전사 결과2.

HTTP 처리와 분리해 두어 다른 전달 방식(MQ 등)에서도 같은 모델을 쓸 수 있다(STT 명세 Q1).
기준: docs/planning/records/STT-CONTRACT-TRANSITION.md §4~§7

JSON Schema 다시 생성 (ai/stt에서):
    uv run python contract.py schema schema
"""

import json
import sys
from pathlib import Path
from typing import Annotated, Literal

from pydantic import (AwareDatetime, BaseModel, ConfigDict, Field, StrictBool, StrictInt, StringConstraints,
                      TypeAdapter, ValidationError)

REQUEST_SCHEMA_VERSION = "4"
RESULT_SCHEMA_VERSION = "2"
KIND = "TRANSCRIBE"

# 행사별 힌트(§5.1): 종류별 최대 50개, 이름 최대 30자. BE가 맞춰 보내고 AI는 방어선으로 다시 검사한다.
MAX_HINTS_PER_KIND = 50
MAX_HINT_LENGTH = 30

UUID_PATTERN = r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
SHA256_PATTERN = r"^[0-9a-f]{64}$"

Uuid = Annotated[str, StringConstraints(pattern=UUID_PATTERN)]
Sha256 = Annotated[str, StringConstraints(pattern=SHA256_PATTERN)]
Attempt = Annotated[StrictInt, Field(ge=1)]
Revision = Annotated[StrictInt, Field(ge=0)]
HintName = Annotated[str, StringConstraints(min_length=1, max_length=MAX_HINT_LENGTH)]
HintList = Annotated[list[HintName], Field(max_length=MAX_HINTS_PER_KIND)]
Seconds = Annotated[float, Field(ge=0)]

Pipeline = Literal["DENOISED", "RAW"]
FallbackReason = Literal["DENOISE_FAILED", "DENOISE_TIMEOUT"]
ErrorCode = Literal["UNAUTHENTICATED", "INVALID_REQUEST", "AI_TIMEOUT", "AUDIO_INVALID", "NO_SPEECH", "AI_BUSY",
                    "AI_UNAVAILABLE"]


class Model(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Scope(Model):
    """BE가 접수 때 검증한 최소 범위(Q17). 동의 여부는 BE 접수 조건이라 값으로 보내지 않는다."""
    eventId: Uuid
    uploaderMemberId: Uuid


class Hints(Model):
    """접수 시점 같은 행사의 이름 스냅샷. 네 키 모두 필수이고 빈 배열을 허용한다."""
    zones: HintList
    equipment: HintList
    programs: HintList
    groups: HintList


class Options(Model):
    """사용자가 "원음으로 다시 전사"를 고른 Job에만 붙는다(§6.1)."""
    pipeline: Literal["RAW"]


class Payload(Model):
    audioId: Uuid
    audioUrl: str
    audioSha256: Sha256
    hints: Hints
    options: Options | None = None


class TranscribeRequest(Model):
    jobId: Uuid
    attempt: Attempt
    requestSchemaVersion: Literal["4"]
    kind: Literal["TRANSCRIBE"]
    inputHash: Sha256
    contextRevision: Revision
    deadlineAt: AwareDatetime
    scope: Scope
    payload: Payload

    @property
    def identity(self):
        return {
            "jobId": self.jobId,
            "attempt": self.attempt,
            "requestSchemaVersion": self.requestSchemaVersion,
            "inputHash": self.inputHash,
            "contextRevision": self.contextRevision,
        }

    @property
    def raw_requested(self):
        return self.payload.options is not None and self.payload.options.pipeline == "RAW"


class Segment(Model):
    startSec: Seconds
    endSec: Seconds
    text: str


class TranscriptionResult(Model):
    audioId: Uuid
    text: Annotated[str, StringConstraints(min_length=1)]
    segments: list[Segment]
    durationSec: Seconds


class ExecutionMeta(Model):
    """실제 실행 경로(§6.1). 전사문·음성·파생 파일은 넣지 않는다."""
    pipeline: Pipeline
    denoiseModel: str | None
    processingMs: Annotated[StrictInt, Field(ge=0)]
    fallbackReason: FallbackReason | None


class Success(Model):
    jobId: Uuid
    attempt: Attempt
    requestSchemaVersion: Literal["4"]
    inputHash: Sha256
    contextRevision: Revision
    resultSchemaVersion: Literal["2"]
    modelVersion: Annotated[str, StringConstraints(min_length=1)]
    result: TranscriptionResult
    executionMeta: ExecutionMeta


class Error(Model):
    code: ErrorCode
    message: Annotated[str, StringConstraints(min_length=1)]
    retryable: StrictBool
    details: dict | None = None


class Failure(Model):
    """접수 후 실패는 요청 식별 5개를 그대로, 접수 전 실패(인증·파싱)나 형식이 틀린 식별 필드는 null로 둔다(Q12).

    키는 생략하지 않는다.
    """
    jobId: Uuid | None
    attempt: Attempt | None
    requestSchemaVersion: Literal["4"] | None
    inputHash: Sha256 | None
    contextRevision: Revision | None
    error: Error


IDENTITY_ADAPTERS = {
    "jobId": TypeAdapter(Uuid),
    "attempt": TypeAdapter(Attempt),
    "requestSchemaVersion": TypeAdapter(Literal["4"]),
    "inputHash": TypeAdapter(Sha256),
    "contextRevision": TypeAdapter(Revision),
}
UNKNOWN_IDENTITY = dict.fromkeys(IDENTITY_ADAPTERS)


def known_identity(body):
    """해석된 본문에서 형식이 맞는 식별 필드만 그대로 돌려주고 나머지는 null로 둔다.

    본문의 다른 곳이 잘못돼도 믿을 수 있는 식별은 돌려줘야 BE가 어느 Job의 실패인지 안다.
    """
    if not isinstance(body, dict):
        return dict(UNKNOWN_IDENTITY)
    identity = {}
    for name, adapter in IDENTITY_ADAPTERS.items():
        try:
            identity[name] = adapter.validate_python(body.get(name))
        except ValidationError:
            identity[name] = None
    return identity


def json_schemas():
    return {
        "request.schema.json": TranscribeRequest.model_json_schema(),
        "success.schema.json": Success.model_json_schema(),
        "failure.schema.json": Failure.model_json_schema(),
    }


def write_schemas(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    for name, schema in json_schemas().items():
        (directory / name).write_text(json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv):
    if len(argv) != 2 or argv[0] != "schema":
        print("사용법: python contract.py schema <출력 폴더>", file=sys.stderr)
        return 2
    write_schemas(argv[1])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

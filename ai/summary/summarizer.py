"""GMS 한 번으로 행사 종료 요약문을 만든다. 모델에는 BE가 보낸 사실(개인 정보 없음)만 준다."""
import importlib.util
import json
from datetime import timedelta, timezone
from pathlib import Path

import contract

GMS_PATH = Path(__file__).resolve().parents[1] / "labeling" / "generate" / "gms.py"
MODEL_VAR = "GMS_SUMMARY_MODEL"
SERVICE_TZ = timezone(timedelta(hours=9), name="Asia/Seoul")
# 모델이 코드 값을 문장에 그대로 쓰지 않게 한국어로 바꿔 준다
CHANGE_MODES = {"AI": "AI 대응안을 총괄이 승인", "MANUAL": "총괄이 직접 변경"}
ISSUE_STATUSES = {"RECEIVED": "접수", "RESPONDING": "대응 중", "RESOLUTION_REPORTED": "해결 보고됨", "CLOSED": "종료"}
SITUATIONS = {"SCHEDULE_DELAY": "일정 지연", "STAFF_GAP": "인력 공백", "TASK_CONFLICT": "업무 충돌",
              "EQUIPMENT_ANOMALY": "장비 이상", "PERFORMANCE_ORDER_CHANGE": "공연 순서 변경",
              "AUDIENCE_SAFETY": "관객 안전"}

SYSTEM = """너는 공연 운영 총괄을 돕는 기록 담당이다. 공연이 끝난 뒤 총괄이 팀과 공유할 종료 요약을 쓴다.
- 모든 글은 한국어로 쓴다.
- 주어진 사실만 쓴다. 없는 숫자·사람·원인을 지어내지 않는다. 모르면 쓰지 않는다.
- 시각은 이미 한국 시간으로 바꿔 두었다. 그대로 쓰고 다시 바꾸지 않는다.
- 사람 이름이나 개인을 특정하는 표현을 쓰지 않는다.
- Markdown으로 쓴다. 순서: ## 개요(행사·운영 시간·종료 시각·장소) / ## 업무 결과(그룹별 완료·취소) / ## 현장 이슈와 대응(이슈·적용한 변경) / ## 다음에 참고할 점.
- "다음에 참고할 점"은 위 사실에서 직접 드러난 것만 1~3개 쓴다. "체계를 마련할 필요가 있다" 같은 일반적인 조언은 쓰지 않는다. 근거가 없으면 "특이 사항 없음"이라고만 쓴다.
- 전체 1500자 안쪽으로 간결하게 쓴다."""

SCHEMA = {
    "name": "event_summary",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {"text": {"type": "string"}, "format": {"type": "string", "enum": ["MARKDOWN", "TEXT"]}},
        "required": ["text", "format"],
        "additionalProperties": False,
    },
}


class SummaryUnavailable(Exception):
    pass


def _load_gms():
    spec = importlib.util.spec_from_file_location("gms", GMS_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def summarize(payload, timeout, chat=None):
    """(text, format, modelVersion). GMS 실패·형식 오류는 SummaryUnavailable. 재시도하지 않는다(같은 요청에 GMS 한 번)."""
    chat = chat or _load_gms().chat_json
    facts = json.dumps(model_facts(payload), ensure_ascii=False)
    try:
        out, model, _usage = chat([{"role": "system", "content": SYSTEM}, {"role": "user", "content": facts}],
                                  temperature=0.3, retries=0, model_var=MODEL_VAR,
                                  response_format={"type": "json_schema", "json_schema": SCHEMA}, timeout=timeout)
    except Exception as e:  # GmsError(연결·HTTP·형식)
        raise SummaryUnavailable(type(e).__name__) from None
    text, fmt = out.get("text") if isinstance(out, dict) else None, out.get("format") if isinstance(out, dict) else None
    if not isinstance(text, str) or not text.strip() or fmt not in ("MARKDOWN", "TEXT"):
        raise SummaryUnavailable("INVALID_OUTPUT")
    text = text.strip()
    if len(text) > contract.MAX_TEXT:
        text = text[:contract.MAX_TEXT].rstrip()
    return text, fmt, f"summary-{model}"


def kst(at):
    """UTC 시각 → 한국 시간 문자열(예: 2026-11-14 18:00). 모델이 UTC를 한국 시간으로 착각하지 않게 미리 바꾼다."""
    return None if at is None else at.astimezone(SERVICE_TZ).strftime("%Y-%m-%d %H:%M")


def model_facts(payload):
    """모델에 주는 사실. 시각은 한국 시간, 코드 값은 한국어 뜻으로 바꾼다. 개인 정보는 처음부터 받지 않는다."""
    e = payload.event
    return {
        "시간대": "한국 시간(KST)",
        "행사": {"이름": e.name, "날짜": e.date, "운영 시작": kst(e.openAt), "운영 끝": kst(e.closeAt),
                "종료 처리 시각": kst(e.endedAt), "장소": e.locationName, "총인원": e.totalCapacity,
                "예상 관객": e.expectedVisitors},
        "직무 그룹": [{"이름": g.name, "업무": {"전체": g.tasks.total, "완료": g.tasks.done, "진행 중": g.tasks.inProgress,
                                           "대기": g.tasks.waiting, "취소": g.tasks.canceled}} for g in payload.workGroups],
        "공연": [{"이름": p.name, "구역": p.zone, "시작": kst(p.startAt), "끝": kst(p.endAt)} for p in payload.programs],
        "현장 이슈": [{"제목": i.title, "상태": ISSUE_STATUSES.get(i.status, i.status), "구역": i.zone,
                    "상황 유형": SITUATIONS.get(i.situation, i.situation) if i.situation else None,
                    "보고 수": i.reports, "발생": kst(i.createdAt), "종료": kst(i.closedAt)} for i in payload.issues],
        "적용한 변경": [{"방식": CHANGE_MODES.get(c.mode, c.mode), "이유": c.rationale, "연산 수": c.operations,
                     "적용 시각": kst(c.appliedAt)} for c in payload.changes],
    }


def ready():
    try:
        _load_gms().config(MODEL_VAR)
    except Exception as e:  # GmsError: GMS_KEY 없음
        return False, type(e).__name__
    return True, None

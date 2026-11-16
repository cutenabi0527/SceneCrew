package com.ssafy.scenecrew.aijob.kind;

import com.ssafy.scenecrew.aijob.domain.AiJobState;

/**
 * AI 응답을 종류별 규칙으로 읽은 결과. ai_job에 그대로 기록한다.
 *
 * @param state               종료 상태(SUCCEEDED/PARTIAL/FAILED/STALE_CONTEXT)
 * @param resultSchemaVersion 성공 결과의 규격 버전
 * @param modelVersion        결과를 만든 모델
 * @param resultJson          검사를 통과한 결과 JSON. 실패면 null
 * @param error               실패 내용. 성공이면 null
 * @param executionMetaJson   AI가 알려 준 실행 경로·시간 같은 최소 메타데이터(JSON 객체). null이면 ai_job.execution_meta를 그대로 둔다
 */
public record AiJobOutcome(AiJobState state, String resultSchemaVersion, String modelVersion, String resultJson,
        AiJobError error, String executionMetaJson) {

    public AiJobOutcome {
        if (state == AiJobState.QUEUED || state == AiJobState.RUNNING) {
            throw new IllegalArgumentException("종료 상태가 아닙니다: " + state);
        }
        if ((state == AiJobState.SUCCEEDED || state == AiJobState.PARTIAL) != (resultJson != null)) {
            throw new IllegalArgumentException("성공·부분 성공에만 결과가 있습니다: " + state);
        }
        if ((state == AiJobState.FAILED || state == AiJobState.STALE_CONTEXT) != (error != null)) {
            throw new IllegalArgumentException("실패·오래된 입력에만 오류가 있습니다: " + state);
        }
    }

    public static AiJobOutcome succeeded(String resultSchemaVersion, String modelVersion, String resultJson) {
        return new AiJobOutcome(AiJobState.SUCCEEDED, resultSchemaVersion, modelVersion, resultJson, null, null);
    }

    public static AiJobOutcome partial(String resultSchemaVersion, String modelVersion, String resultJson) {
        return new AiJobOutcome(AiJobState.PARTIAL, resultSchemaVersion, modelVersion, resultJson, null, null);
    }

    public static AiJobOutcome failed(AiJobError error) {
        return new AiJobOutcome(AiJobState.FAILED, null, null, null, error, null);
    }

    /** 실행 중 행사 계획이 바뀜(READ_CONTEXT_CHANGED). 다시 시도하지 않고 명시 재생성 대상이다. */
    public static AiJobOutcome staleContext(AiJobError error) {
        return new AiJobOutcome(AiJobState.STALE_CONTEXT, null, null, null, error, null);
    }

    /** 같은 결과에 실행 메타데이터를 붙인다. */
    public AiJobOutcome withExecutionMeta(String executionMetaJson) {
        return new AiJobOutcome(state, resultSchemaVersion, modelVersion, resultJson, error, executionMetaJson);
    }

    /** 결과 JSON만 바꾼다. 반영(apply)에서 원문을 업무 테이블로 옮기고 ai_job.result에는 최소 값만 남길 때 쓴다. */
    public AiJobOutcome withResultJson(String resultJson) {
        return new AiJobOutcome(state, resultSchemaVersion, modelVersion, resultJson, error, executionMetaJson);
    }

    /** 업무에 반영할 결과가 있는지 */
    public boolean hasResult() {
        return resultJson != null;
    }

    /** 실패 중 AI가 다시 시도해도 된다고 한 것. 남은 마감·횟수 안에서만 실제로 다시 보낸다. */
    public boolean retryable() {
        return state == AiJobState.FAILED && error.retryable();
    }
}

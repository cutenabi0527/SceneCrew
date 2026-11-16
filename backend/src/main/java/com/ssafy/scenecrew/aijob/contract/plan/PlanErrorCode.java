package com.ssafy.scenecrew.aijob.contract.plan;

import com.ssafy.scenecrew.aijob.domain.AiJobState;

/**
 * AI 실패 코드. retryable은 AI가 응답에 넣어야 하는 값과 같고, BE는 AI_UNAVAILABLE만 남은 마감 안에서 다시 보낸다.
 */
public enum PlanErrorCode {
    /** 요청 형식·상한·참조·시간 순서 위반 */
    INVALID_REQUEST(false, AiJobState.FAILED),
    /** 실행 중 행사 계획(Event.revision)이 바뀜. 명시 재생성 대상 */
    READ_CONTEXT_CHANGED(false, AiJobState.STALE_CONTEXT),
    /** 마감(deadlineAt) 경과 */
    AI_TIMEOUT(false, AiJobState.FAILED),
    /** AI 내부 검토 실패. details.reason 필수 */
    AI_REVIEW_FAILED(false, AiJobState.FAILED),
    /** 모델·연결 장애 */
    AI_UNAVAILABLE(true, AiJobState.FAILED);

    private final boolean retryable;
    private final AiJobState jobState;

    PlanErrorCode(boolean retryable, AiJobState jobState) {
        this.retryable = retryable;
        this.jobState = jobState;
    }

    public boolean retryable() {
        return retryable;
    }

    public AiJobState jobState() {
        return jobState;
    }
}

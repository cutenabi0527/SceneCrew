package com.ssafy.scenecrew.aijob.pipeline;

/** AI 작업이 남기는 outbox 작업 종류. payload는 {"jobId": ...}이고 aggregateId도 jobId다. */
public final class AiJobOutboxTypes {
    /** 대기 중인 작업을 실행기에 넘긴다. 접수할 때와 재시도 대기가 끝날 때 남긴다. */
    public static final String REQUESTED = "AI_JOB_REQUESTED";
    /** 작업이 끝났다. 종류별 후속 처리(AiJobKind.afterFinished)를 부른다. */
    public static final String FINISHED = "AI_JOB_FINISHED";

    private AiJobOutboxTypes() {
    }
}

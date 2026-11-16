package com.ssafy.scenecrew.aijob.pipeline;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.kind.AiJobError;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import com.ssafy.scenecrew.global.outbox.OutboxPublisher;

/** 결과 기록과 이어서 할 일(outbox)을 한 트랜잭션에 남긴다. 호출하는 쪽이 행을 잠근 트랜잭션 안에서 부른다. */
@Component
@RequiredArgsConstructor
class AiJobFinisher {
    private final OutboxPublisher outbox;
    private final JsonMapper jsonMapper;

    /** 작업을 끝내고 AI_JOB_FINISHED를 남긴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(AiJob job, AiJobOutcome outcome, Instant now) {
        job.finish(outcome.state(), outcome.resultSchemaVersion(), outcome.modelVersion(), outcome.resultJson(),
                toJson(outcome.error()), outcome.executionMetaJson(), now);
        outbox.publish(job.getEventId(), AiJobOutboxTypes.FINISHED, job.getId(), Map.of("jobId", job.getId()));
    }

    /** 대기 상태로 돌리고 notBefore에 다시 실행기로 넘기도록 AI_JOB_REQUESTED를 남긴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retryLater(AiJob job, AiJobError error, Instant notBefore) {
        job.retryLater(toJson(error), notBefore);
        outbox.publish(job.getEventId(), AiJobOutboxTypes.REQUESTED, job.getId(), Map.of("jobId", job.getId()),
                notBefore);
    }

    private String toJson(AiJobError error) {
        return error == null ? null : jsonMapper.writeValueAsString(error);
    }
}

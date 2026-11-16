package com.ssafy.scenecrew.aijob.pipeline;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.repository.AiJobRepository;
import com.ssafy.scenecrew.global.outbox.OutboxHandler;
import com.ssafy.scenecrew.global.outbox.domain.OutboxEvent;

/** AI_JOB_FINISHED: 끝난 작업의 종류별 후속 처리(AiJobKind.afterFinished)를 부른다. 실패하면 outbox가 다시 시도한다. */
@Slf4j
@Component
@RequiredArgsConstructor
class AiJobFinishedHandler implements OutboxHandler {
    private final AiJobRepository repository;
    private final AiJobKinds kinds;

    @Override
    public String type() {
        return AiJobOutboxTypes.FINISHED;
    }

    @Override
    public void handle(OutboxEvent event) {
        AiJob job = repository.findById(event.getAggregateId()).orElse(null);
        if (job == null || !job.isFinished()) {
            log.warn("끝나지 않았거나 없는 AI 작업의 후속 처리 요청, 건너뜀: jobId={}", event.getAggregateId());
            return;
        }
        kinds.find(job.getKind()).ifPresentOrElse(kind -> kind.afterFinished(job),
                () -> log.error("등록되지 않은 AI 작업 종류의 후속 처리, 건너뜀: jobId={} kind={}", job.getId(), job.getKind()));
    }
}

package com.ssafy.scenecrew.aijob.pipeline;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import com.ssafy.scenecrew.global.outbox.OutboxHandler;
import com.ssafy.scenecrew.global.outbox.domain.OutboxEvent;

/**
 * AI_JOB_REQUESTED: 대기 중인 작업을 실행기에 넘기고 바로 반환한다.
 *
 * <p>OutboxHandler 규칙상 메모리의 실행기에만 넣고 반환하면 안 된다. 여기서는 작업이 이미 ai_job에 QUEUED로 저장돼 있고
 * (접수 트랜잭션), 실행기에서 사라지면 AiJobRecovery가 다시 넘기므로 DB에 넘긴 것과 같다.
 * 이미 다른 작업자가 맡았거나 끝난 작업이면 아무것도 하지 않는다.
 */
@Component
@RequiredArgsConstructor
class AiJobRequestedHandler implements OutboxHandler {
    private final AiJobDispatcher dispatcher;

    @Override
    public String type() {
        return AiJobOutboxTypes.REQUESTED;
    }

    @Override
    public void handle(OutboxEvent event) {
        dispatcher.dispatch(event.getAggregateId());
    }
}

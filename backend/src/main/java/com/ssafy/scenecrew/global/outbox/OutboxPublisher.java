package com.ssafy.scenecrew.global.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.global.outbox.domain.OutboxEvent;
import com.ssafy.scenecrew.global.outbox.repository.OutboxEventRepository;

/**
 * 업무 저장과 같은 트랜잭션 안에서 후속 작업 한 건을 outbox_event에 남긴다.
 * 업무가 커밋되면 작업도 함께 남고, 업무가 롤백되면 작업도 남지 않는다.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {
    private final OutboxEventRepository repository;
    private final JsonMapper jsonMapper;

    /**
     * 호출한 쪽의 트랜잭션에 참여한다. 트랜잭션 없이 부르면 업무와 따로 저장될 수 있어 예외를 던진다.
     * payload에는 처리에 필요한 id 정도만 넣고 원문(보고 내용, 음성 등)은 복제하지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(UUID eventId, String type, UUID aggregateId, Object payload) {
        return publish(eventId, type, aggregateId, payload, Instant.now());
    }

    /** availableAt 이후에 꺼내도록 남긴다. 재시도 간격처럼 바로 처리하면 안 되는 작업에 쓴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(UUID eventId, String type, UUID aggregateId, Object payload, Instant availableAt) {
        String json = jsonMapper.writeValueAsString(payload);
        return repository.save(OutboxEvent.pending(eventId, type, aggregateId, json, availableAt));
    }
}

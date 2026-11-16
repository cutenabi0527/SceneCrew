package com.ssafy.scenecrew.global.outbox.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false, columnDefinition = "text")
    private String type;

    @Column(nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String payload;

    @Column(nullable = false)
    private Instant availableAt;

    @Column(nullable = false)
    private Long attempts;

    private Instant leaseUntil;

    private Instant processedAt;

    /** 아직 처리하지 않은 작업 한 건. 업무 저장과 같은 트랜잭션에서 만들고 availableAt 이후에 꺼낸다. */
    public static OutboxEvent pending(UUID eventId, String type, UUID aggregateId, String payloadJson,
            Instant availableAt) {
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID();
        event.eventId = eventId;
        event.type = type;
        event.aggregateId = aggregateId;
        event.payload = payloadJson;
        event.availableAt = availableAt;
        event.attempts = 0L;
        return event;
    }
}

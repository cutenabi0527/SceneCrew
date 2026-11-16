package com.ssafy.scenecrew.global.idempotency.domain;

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
@Table(name = "idempotency_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID actorAccountId;

    @Column(nullable = false, columnDefinition = "text")
    private String operation;

    @Column(nullable = false, columnDefinition = "text")
    private String idempotencyKey;

    @Column(nullable = false, columnDefinition = "text")
    private String requestHash;

    private UUID eventId;

    private UUID actorMemberId;

    private Long participationRevision;

    @Column(nullable = false, columnDefinition = "text")
    private String resultType;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String resultRefs;

    @Column(nullable = false)
    private Integer httpStatus;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String receipt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant completedAt;

    @Column(nullable = false)
    private Instant replayExpiresAt;

    /** 업무가 끝난 요청의 기록. 업무 저장과 같은 트랜잭션에서 저장한다. */
    public static IdempotencyRecord completed(UUID actorAccountId, String operation, String idempotencyKey,
            String requestHash, UUID eventId, UUID actorMemberId, Long participationRevision, String resultType,
            String resultRefsJson, int httpStatus, String receiptJson, Instant createdAt, Instant completedAt,
            Instant replayExpiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = UUID.randomUUID();
        record.actorAccountId = actorAccountId;
        record.operation = operation;
        record.idempotencyKey = idempotencyKey;
        record.requestHash = requestHash;
        record.eventId = eventId;
        record.actorMemberId = actorMemberId;
        record.participationRevision = participationRevision;
        record.resultType = resultType;
        record.resultRefs = resultRefsJson;
        record.httpStatus = httpStatus;
        record.receipt = receiptJson;
        record.createdAt = createdAt;
        record.completedAt = completedAt;
        record.replayExpiresAt = replayExpiresAt;
        return record;
    }
}

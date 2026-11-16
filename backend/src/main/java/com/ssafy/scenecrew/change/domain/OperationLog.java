package com.ssafy.scenecrew.change.domain;

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
@Table(name = "operation_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperationLog {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false, columnDefinition = "text")
    private String type;

    private UUID actorMemberId;

    @Column(nullable = false, columnDefinition = "text")
    private String resourceType;

    @Column(nullable = false)
    private UUID resourceId;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String changes;

    @Column(columnDefinition = "text")
    private String reason;

    private UUID changeSetId;

    @Column(nullable = false)
    private Instant occurredAt;

    public static OperationLog memberGroupChanged(UUID eventId, UUID memberId, String changes, Instant occurredAt) {
        var log = new OperationLog();
        log.id = UUID.randomUUID();
        log.eventId = eventId;
        log.type = "MEMBER_GROUP_CHANGED";
        log.actorMemberId = memberId;
        log.resourceType = "MEMBER";
        log.resourceId = memberId;
        log.changes = changes;
        log.occurredAt = occurredAt;
        return log;
    }
}

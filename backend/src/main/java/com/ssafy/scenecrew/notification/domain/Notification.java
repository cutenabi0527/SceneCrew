package com.ssafy.scenecrew.notification.domain;

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
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID memberId;

    @Column(nullable = false)
    private Long participationRevision;

    @Column(nullable = false)
    private UUID eventId;

    private UUID changeSetId;

    private UUID taskId;

    private UUID acknowledgmentId;

    @Column(nullable = false, columnDefinition = "text")
    private String type;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String payload;

    @Column(nullable = false, columnDefinition = "text")
    private String dedupeKey;

    private Instant readAt;

    @Column(nullable = false)
    private Instant createdAt;
}

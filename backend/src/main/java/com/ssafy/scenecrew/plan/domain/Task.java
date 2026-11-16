package com.ssafy.scenecrew.plan.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID workGroupId;

    @Column(nullable = false, columnDefinition = "text")
    private String bundleKey;

    private UUID assigneeId;

    @Column(nullable = false)
    private UUID zoneId;

    private UUID programId;

    private UUID sourceIssueId;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false)
    private Instant startAt;

    @Column(nullable = false)
    private Instant endAt;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String steps;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String checklist;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TaskStatus status;

    private Instant firstAssignedAt;

    private Instant actualStartedAt;

    private Instant actualEndedAt;

    public void releaseWaitingAssignment() {
        if (status != TaskStatus.WAITING) {
            throw new IllegalStateException("Only waiting tasks can be released for a group move");
        }
        assigneeId = null;
    }
}

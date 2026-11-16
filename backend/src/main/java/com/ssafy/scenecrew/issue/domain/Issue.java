package com.ssafy.scenecrew.issue.domain;

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

@Entity
@Table(name = "issue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Issue {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID workGroupId;

    private UUID taskId;

    @Column(nullable = false)
    private UUID zoneId;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false)
    private UUID reporterMemberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IssueStatus status;

    private UUID relatedIssueId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant closedAt;
}

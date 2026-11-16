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

import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID issueId;

    @Column(nullable = false)
    private UUID authorMemberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportKind kind;

    @Column(nullable = false, length = 1000)
    private String text;

    @Column(nullable = false, columnDefinition = "text")
    private String normalizedText;

    private UUID audioId;

    @Column(columnDefinition = "text")
    private String quickTemplateKey;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String analysis;

    /** 보고 작성 당시 작성자 Member의 참여 회차. 이후 재참여해도 바꾸지 않는다. */
    @Column(nullable = false)
    private Long participationRevision;

    @Column(nullable = false)
    private Instant createdAt;
}

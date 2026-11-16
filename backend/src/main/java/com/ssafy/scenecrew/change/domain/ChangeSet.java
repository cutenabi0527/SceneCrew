package com.ssafy.scenecrew.change.domain;

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
@Table(name = "change_set")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeSet {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    private UUID issueId;

    private UUID sourceJobId;

    private UUID createdByMemberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ChangeSetMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChangeSetStatus status;

    @Column(nullable = false)
    private Long revision;

    @Column(nullable = false)
    private Long baseRevision;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String operations;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String diff;

    /** 변경안이 지금 참조하는 근거 목록(JSON 배열). 근거를 알 수 없는 기존 행은 null */
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String evidenceRefs;

    /** 변경안이 채택한 복구 계산 후보 목록(JSON 배열). 근거를 알 수 없는 기존 행은 null */
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String simulationRefs;

    @Column(columnDefinition = "text")
    private String previewHash;

    @Column(nullable = false, columnDefinition = "text")
    private String rationale;

    private UUID approvedByMemberId;

    private Instant appliedAt;
}

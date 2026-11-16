package com.ssafy.scenecrew.change.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;

/**
 * 변경안 revision별 당시 근거. 적용·폐기·미적용과 관계없이 행사 종료 후 2년 보관하고 수정하지 않는다.
 * DB 트리거도 수정을 거절한다(V5).
 */
@Entity
@Immutable
@Table(name = "change_set_evidence_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeSetEvidenceRevision {
    @EmbeddedId
    private ChangeSetEvidenceRevisionId id;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String snapshot;

    @Column(nullable = false)
    private Instant capturedAt;
}

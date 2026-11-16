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
@Table(name = "operational_situation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperationalSituation {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    private UUID issueId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private SituationType primaryType;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String relatedTypes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SituationSourceKind sourceKind;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String sourceRefs;

    private UUID confirmedByMemberId;

    private Instant confirmedAt;

    /** 총괄 정정 사유(V10, V9-D48). 확인된 행에는 반드시 있다. */
    private String reason;
}

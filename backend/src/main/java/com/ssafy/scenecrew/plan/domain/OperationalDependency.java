package com.ssafy.scenecrew.plan.domain;

import java.math.BigDecimal;
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
@Table(name = "operational_dependency")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperationalDependency {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DependencyTargetKind fromKind;

    @Column(nullable = false)
    private UUID fromId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DependencyTargetKind toKind;

    @Column(nullable = false)
    private UUID toId;

    @Column(nullable = false, columnDefinition = "numeric")
    private BigDecimal lagSec;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String evidence;

    @Column(nullable = false)
    private UUID confirmedByMemberId;

    @Column(nullable = false)
    private Instant confirmedAt;
}

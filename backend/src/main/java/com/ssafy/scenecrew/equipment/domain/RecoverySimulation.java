package com.ssafy.scenecrew.equipment.domain;

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
@Table(name = "recovery_simulation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecoverySimulation {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    private UUID issueId;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String targetAssetIds;

    @Column(nullable = false)
    private UUID createdByMemberId;

    @Column(nullable = false)
    private Long baseRevision;

    @Column(nullable = false, columnDefinition = "text")
    private String inputHash;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String evidenceRefs;

    @Column(nullable = false, columnDefinition = "text")
    private String policyVersion;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String assumptions;

    @Column(nullable = false)
    private Instant calculatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SimulationState state;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String candidates;
}

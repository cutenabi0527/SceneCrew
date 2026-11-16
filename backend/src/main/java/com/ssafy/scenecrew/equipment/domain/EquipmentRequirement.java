package com.ssafy.scenecrew.equipment.domain;

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
@Table(name = "equipment_requirement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentRequirement {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    private UUID programId;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String taskIds;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String requirements;

    @Column(nullable = false)
    private Instant readyBy;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String schedulePolicy;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String timeEvidence;

    @Column(nullable = false)
    private Long revision;
}

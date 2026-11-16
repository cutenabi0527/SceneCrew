package com.ssafy.scenecrew.safety.domain;

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
@Table(name = "safety_observation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SafetyObservation {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID cameraId;

    @Column(nullable = false)
    private Long cameraRevision;

    @Column(nullable = false)
    private UUID streamSessionId;

    @Column(nullable = false)
    private Long sequence;

    private Instant capturedAt;

    @Column(nullable = false, columnDefinition = "text")
    private String captureTimeBasis;

    @Column(nullable = false)
    private Instant receivedAt;

    @Column(nullable = false, columnDefinition = "text")
    private String inputHash;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String coverage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ObservationState state;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ObservationFreshness freshness;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String estimates;

    @Column(columnDefinition = "text")
    private String modelVersion;

    private Instant processedAt;

    @Column(nullable = false)
    private Instant metadataExpiresAt;
}

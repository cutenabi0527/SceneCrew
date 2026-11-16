package com.ssafy.scenecrew.safety.domain;

import java.math.BigDecimal;
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
@Table(name = "safety_zone_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SafetyZoneProfile {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID zoneId;

    @Column(nullable = false)
    private UUID layoutId;

    @Column(nullable = false)
    private Long layoutRevision;

    /** 기본 이름 변환은 숫자 앞을 나누지 않아(effective_aream2) 열 이름을 직접 지정한다. */
    @Column(name = "effective_area_m2", columnDefinition = "numeric")
    private BigDecimal effectiveAreaM2;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String areaEvidence;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String riskRule;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String minimumCrewRule;

    private UUID confirmedByMemberId;

    private Instant confirmedAt;

    @Column(nullable = false)
    private Long revision;
}

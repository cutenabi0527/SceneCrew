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
@Table(name = "venue_layout")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VenueLayout {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private Long revision;

    @Column(nullable = false, columnDefinition = "text")
    private String coordinateSystem;

    @Column(nullable = false, columnDefinition = "numeric")
    private BigDecimal width;

    @Column(nullable = false, columnDefinition = "numeric")
    private BigDecimal height;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String zonePolygons;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String source;

    @Column(nullable = false)
    private UUID confirmedByMemberId;

    @Column(nullable = false)
    private Instant confirmedAt;
}

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

@Entity
@Table(name = "equipment_connection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentConnection {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID fromAssetId;

    @Column(nullable = false, columnDefinition = "text")
    private String fromPort;

    @Column(nullable = false)
    private UUID toAssetId;

    @Column(nullable = false, columnDefinition = "text")
    private String toPort;

    @Column(nullable = false)
    private UUID confirmedByMemberId;

    @Column(nullable = false)
    private Instant confirmedAt;

    @Column(nullable = false)
    private Long revision;

    @Column(nullable = false)
    private Boolean active;
}

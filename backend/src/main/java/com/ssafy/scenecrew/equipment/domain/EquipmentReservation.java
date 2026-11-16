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

@Entity
@Table(name = "equipment_reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentReservation {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    private UUID programId;

    private UUID taskId;

    @Column(nullable = false)
    private Instant startAt;

    @Column(nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ReservationStatus status;

    private UUID sourceChangeSetId;

    @Column(nullable = false)
    private Long revision;
}

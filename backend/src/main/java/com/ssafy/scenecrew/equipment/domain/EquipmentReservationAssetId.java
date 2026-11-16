package com.ssafy.scenecrew.equipment.domain;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 장비 예약과 장비(구성품 포함)의 연결 키. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentReservationAssetId implements Serializable {
    @Column(nullable = false)
    private UUID reservationId;

    @Column(nullable = false)
    private UUID assetId;
}

package com.ssafy.scenecrew.equipment.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "equipment_reservation_asset")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentReservationAsset {
    @EmbeddedId
    private EquipmentReservationAssetId id;
}

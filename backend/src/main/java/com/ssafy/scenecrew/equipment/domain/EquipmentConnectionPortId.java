package com.ssafy.scenecrew.equipment.domain;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 활성 연결이 쓰는 장비 포트의 키. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentConnectionPortId implements Serializable {
    @Column(nullable = false)
    private UUID connectionId;

    @Column(nullable = false)
    private UUID assetId;

    @Column(nullable = false, columnDefinition = "text")
    private String port;
}

package com.ssafy.scenecrew.equipment.domain;

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
@Table(name = "equipment_asset")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentAsset {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TechnicalState technicalState;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String stateEvidence;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String componentAssetIds;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String ports;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String verifiedCompatibility;

    private UUID storageZoneId;

    private UUID holderMemberId;

    @Column(nullable = false)
    private Long revision;

    /** 웹 연결로 자동 수집할 수 있는 종류(QLXD4). 없으면 사람이 상태를 기록하는 장비(V9-D59, #395) */
    @Column(length = 16)
    private String autoObservationKind;

    /** 연결 담당 업무("장비 자동 수집 연결", #395) */
    private UUID linkTaskId;

    /** 웹 연결(#394): 수집기·수신기 키·채널·연결한 사람·시각. 함께 있거나 함께 없다 */
    @Column(columnDefinition = "text")
    private String linkCollectorId;

    @Column(columnDefinition = "text")
    private String linkSourceRef;

    private Integer linkChannel;

    private UUID linkMemberId;

    private java.time.Instant linkedAt;
}

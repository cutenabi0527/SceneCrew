package com.ssafy.scenecrew.equipment.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 활성 연결이 쓰는 포트. 연결 1개당 출발·도착 포트 2행을 연결과 같은 트랜잭션에 저장하고,
 * 연결을 비활성으로 바꾸면 지운다. (asset_id, port) UNIQUE로 방향과 무관하게 포트당 활성 연결 1개를 보장한다.
 */
@Entity
@Table(name = "equipment_connection_port")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentConnectionPort {
    @EmbeddedId
    private EquipmentConnectionPortId id;
}

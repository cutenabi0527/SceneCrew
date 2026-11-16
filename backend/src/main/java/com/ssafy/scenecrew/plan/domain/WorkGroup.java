package com.ssafy.scenecrew.plan.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "work_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkGroup {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String instructions;

    @Column(nullable = false)
    private Boolean active;

    /** 이 그룹의 현재 WORKER가 장비 현황을 조회할 수 있는지(V9-D13). 새 그룹은 false로 시작한다. */
    @Column(nullable = false)
    private Boolean equipmentViewAllowed = false;
}

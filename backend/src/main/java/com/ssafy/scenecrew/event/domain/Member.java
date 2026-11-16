package com.ssafy.scenecrew.event.domain;

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
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID accountId;

    private UUID workGroupId;

    @Column(nullable = false)
    private Long crewNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventRole eventRole;

    @Column(nullable = false, columnDefinition = "text")
    private String displayName;

    @Column(nullable = false)
    private Boolean available;

    @Column(nullable = false)
    private Boolean isReserve;

    @Column(nullable = false)
    private Boolean active;

    @Column(nullable = false)
    private Long participationRevision;

    public void changeAvailability(boolean available) {
        this.available = available;
    }

    public void stepDownTo(UUID groupId) {
        eventRole = EventRole.WORKER;
        workGroupId = groupId;
    }

    public void becomeChief() {
        eventRole = EventRole.CHIEF;
    }

    /** 038 그룹 이동. 대기 업무 해제·진행 중 거절은 MemberGroupMove.prepare가 먼저 끝낸다. */
    public void moveToGroup(UUID groupId) {
        workGroupId = groupId;
    }

    /** 038 예비 지정·해제. 맡은 업무는 건드리지 않는다(be/02 "예비 전환으로 업무를 암묵 해제하지 않는다"). */
    public void changeReserve(boolean reserve) {
        isReserve = reserve;
    }

    /** 040 행사 이탈. 참여 회차는 다시 참여할 때 올린다(InviteJoinRepository.rejoin). */
    public void leave() {
        active = false;
        available = false;
    }
}

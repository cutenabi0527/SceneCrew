package com.ssafy.scenecrew.equipment.service;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 장비 현황 조회 자격(V9-D13, EXT01·03·05). CHIEF 또는 장비 조회가 허용된 WorkGroup의 현재 WORKER다.
 *
 * <p>요청마다 현재 참여·그룹 소속·그룹 활성·조회 허용 표시를 다시 읽는다. 특정 업무 배정과 무관하고, 그룹 이름으로 판단하지 않는다.
 * 비참여자는 행사를 숨기려고 404, 자격이 없는 팀원은 403이다.
 */
@Component
public class EquipmentAccess {
    private final JdbcTemplate jdbc;
    private final CurrentMembers members;

    public EquipmentAccess(JdbcTemplate jdbc, CurrentMembers members) {
        this.jdbc = jdbc;
        this.members = members;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Member requireReader(UUID eventId, UUID accountId) {
        Member member = members.require(eventId, accountId);
        if (member.getEventRole() == EventRole.CHIEF) return member;
        if (member.getWorkGroupId() == null) throw new BusinessException(ErrorCode.FORBIDDEN);
        Boolean allowed = jdbc.query("""
                SELECT equipment_view_allowed FROM work_group WHERE id = ? AND event_id = ? AND active""",
                rs -> rs.next() ? rs.getBoolean(1) : null, member.getWorkGroupId(), eventId);
        if (!Boolean.TRUE.equals(allowed)) throw new BusinessException(ErrorCode.FORBIDDEN);
        return member;
    }

    /** 장비가 속한 행사. 없으면 404 */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID eventOfAsset(UUID assetId) {
        UUID eventId = jdbc.query("SELECT event_id FROM equipment_asset WHERE id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, assetId);
        if (eventId == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return eventId;
    }
}

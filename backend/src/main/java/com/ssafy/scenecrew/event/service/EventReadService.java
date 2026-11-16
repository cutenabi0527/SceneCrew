package com.ssafy.scenecrew.event.service;

import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.ssafy.scenecrew.event.dto.EventListResponse.EventItem;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * SC-API-023 행사 조회(오세진, 2026-11-05). 로그인한 계정이 지금 이 행사의 활성 Member일 때만 돌려준다.
 * Member가 아니거나, 탈퇴했거나, 행사가 없으면 모두 같은 404다(행사가 있다는 것도 알리지 않는다).
 * 행사 상태(DRAFT·RECRUITING·LIVE·ENDED)와 역할은 가리지 않는다. 응답 모양은 목록(SC-API-021)의 항목과 같다.
 */
public class EventReadService {
    private final JdbcTemplate jdbc;
    private final CurrentMembers members;

    public EventReadService(JdbcTemplate jdbc, CurrentMembers members) { this.jdbc = jdbc; this.members = members; }

    @Transactional(readOnly = true)
    public EventItem get(UUID eventId, UUID accountId) {
        try {
            var member = members.require(eventId, accountId);
            // 확인한 그 Member로 읽는다. 그사이 탈퇴했으면 활성 조건에 걸려 404다.
            var rows = jdbc.query(EventListService.SELECT + " AND m.id = ?",
                    (r, n) -> EventListService.row(r, n).item(), accountId, member.getId());
            if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
            return rows.getFirst();
        } catch (DataAccessException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}

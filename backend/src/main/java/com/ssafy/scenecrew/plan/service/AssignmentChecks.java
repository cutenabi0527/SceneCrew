package com.ssafy.scenecrew.plan.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.safety.service.SafetySupportProtection;

/** #283과 #344의 실제 담당 업무 시간 검사. 호출자는 Event 쓰기 잠금을 유지한다. */
@Component
public class AssignmentChecks {
    private final JdbcTemplate jdbc;

    private final SafetySupportProtection support;

    public AssignmentChecks(JdbcTemplate jdbc, SafetySupportProtection support) {
        this.jdbc = jdbc;
        this.support = support;
    }

    public void requireNoOverlap(UUID eventId, UUID memberId, UUID exceptTask, Instant start, Instant end) {
        var overlap = jdbc.queryForList("""
                SELECT id FROM task WHERE event_id = ? AND assignee_id = ? AND status IN ('WAITING', 'IN_PROGRESS')
                   AND (?::uuid IS NULL OR id <> ?::uuid) AND start_at < ? AND end_at > ? LIMIT 1""",
                UUID.class, eventId, memberId, exceptTask, exceptTask, Timestamp.from(end), Timestamp.from(start));
        UUID conflictingTask = overlap.stream().findFirst().orElseGet(() ->
                support.overdueAssignment(eventId, memberId, exceptTask, start));
        if (conflictingTask != null)
            throw new BusinessException(ErrorCode.TIME_OVERLAP, ErrorCode.TIME_OVERLAP.getMessage(),
                    Map.of("memberId", memberId.toString(), "taskId", conflictingTask.toString()));
    }
}

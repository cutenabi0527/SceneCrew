package com.ssafy.scenecrew.impact.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.impact.service.ImpactResult.AffectedTask;
import com.ssafy.scenecrew.impact.service.ImpactResult.MemberRef;

/** PostgreSQL JOIN으로 구한 영향 분석. 그래프 구현과 결과를 비교하는 기준이기도 하다. */
public class SqlImpactAnalyzer implements ImpactAnalyzer {

    /** 이슈가 난 구역의 업무와 이슈가 가리킨 업무 중 대기·진행 중인 것, 그리고 담당자 */
    private static final String AFFECTED_TASKS = """
            SELECT t.id, t.title, t.start_at, t.end_at, t.work_group_id,
                   a.id AS assignee_id, a.display_name AS assignee_name, a.is_reserve AS assignee_reserve
            FROM issue i
            JOIN task t ON t.event_id = i.event_id AND (t.zone_id = i.zone_id OR t.id = i.task_id)
            LEFT JOIN member a ON a.id = t.assignee_id
            WHERE i.id = :issueId
              AND t.status IN ('WAITING', 'IN_PROGRESS')
            """;

    /** 같은 직무 그룹의 투입 가능 팀원 중 담당자가 아니고 그 시간에 겹치는 대기·진행 업무가 없는 사람 */
    private static final String CANDIDATES = """
            SELECT t.id AS task_id, c.id, c.display_name, c.is_reserve
            FROM issue i
            JOIN task t ON t.event_id = i.event_id AND (t.zone_id = i.zone_id OR t.id = i.task_id)
            JOIN member c ON c.event_id = t.event_id AND c.work_group_id = t.work_group_id
            WHERE i.id = :issueId
              AND t.status IN ('WAITING', 'IN_PROGRESS')
              AND c.active AND c.available AND c.event_role = 'WORKER'
              AND c.id IS DISTINCT FROM t.assignee_id
              AND NOT EXISTS (
                  SELECT 1 FROM task o
                  WHERE o.assignee_id = c.id
                    AND o.id <> t.id
                    AND o.status IN ('WAITING', 'IN_PROGRESS')
                    AND o.start_at < t.end_at AND t.start_at < o.end_at)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SqlImpactAnalyzer(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ImpactResult analyze(UUID issueId) {
        Map<String, Object> params = Map.of("issueId", issueId);
        Integer found = jdbc.queryForObject("SELECT count(*) FROM issue WHERE id = :issueId", params, Integer.class);
        if (found == null || found == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "이슈를 찾을 수 없습니다.");
        }

        Map<UUID, List<MemberRef>> candidates = new HashMap<>();
        jdbc.query(CANDIDATES, params, (ResultSet rs) -> {
            candidates.computeIfAbsent(rs.getObject("task_id", UUID.class), k -> new ArrayList<>())
                    .add(new MemberRef(rs.getObject("id", UUID.class), rs.getString("display_name"),
                            rs.getBoolean("is_reserve")));
        });

        Map<UUID, AffectedTask> tasks = new LinkedHashMap<>();
        jdbc.query(AFFECTED_TASKS, params, (ResultSet rs) -> {
            UUID taskId = rs.getObject("id", UUID.class);
            tasks.put(taskId, new AffectedTask(taskId, rs.getString("title"),
                    rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("end_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("work_group_id", UUID.class), assignee(rs),
                    candidates.getOrDefault(taskId, List.of())));
        });
        return new ImpactResult(issueId, new ArrayList<>(tasks.values()));
    }

    private static MemberRef assignee(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("assignee_id", UUID.class);
        return id == null ? null
                : new MemberRef(id, rs.getString("assignee_name"), rs.getBoolean("assignee_reserve"));
    }
}

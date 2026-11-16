package com.ssafy.scenecrew.plan.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.plan.service.InitialAssignmentService.TaskView;
import com.ssafy.scenecrew.plan.service.TaskCursor;
import java.sql.Timestamp;
import java.util.ArrayList;

/** #281. Uses the existing Task response DTO; never loads mutable Task entities. */
@Repository
public class TaskReadQueries {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public TaskReadQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID eventId(UUID taskId) {
        return jdbc.queryForList("SELECT event_id FROM task WHERE id=?", UUID.class, taskId)
                .stream().findFirst().orElse(null);
    }

    private static final String SCOPE = """
            m.event_id=? AND m.account_id=? AND m.id=? AND m.participation_revision=? AND m.event_role=?
            AND m.active AND a.status='ACTIVE' AND a.signup_completed
            """;

    public List<TaskView> page(TaskCursor.Scope scope, TaskCursor.Position after, int limit) {
        var args = new ArrayList<Object>();
        String boundary = "";
        if (after != null) {
            if (after.startAt() == null) {
                boundary = " AND t.start_at IS NULL AND t.id > ?";
                args.add(after.id());
            } else {
                boundary = " AND (t.start_at > ? OR (t.start_at = ? AND t.id > ?) OR t.start_at IS NULL)";
                args.add(Timestamp.from(after.startAt())); args.add(Timestamp.from(after.startAt())); args.add(after.id());
            }
        }
        args.addAll(scopeArgs(scope)); args.add(limit);
        // The LEFT JOIN sentinel distinguishes an authorized empty page from lost current membership.
        return jdbc.query("""
                SELECT t.* FROM member m JOIN account a ON a.id=m.account_id
                LEFT JOIN task t ON t.event_id=m.event_id AND
                    (m.event_role='CHIEF' OR (t.assignee_id=m.id AND t.status IN ('WAITING','IN_PROGRESS')))
                """ + boundary + " WHERE " + SCOPE + " ORDER BY t.start_at ASC NULLS LAST,t.id ASC LIMIT ?",
                (r, n) -> task(r), args.toArray());
    }

    public List<TaskView> oneForMember(TaskCursor.Scope scope, UUID taskId) {
        var args = new ArrayList<Object>(scopeArgs(scope)); args.add(taskId);
        return jdbc.query("""
                SELECT t.* FROM member m JOIN account a ON a.id=m.account_id
                JOIN task t ON t.event_id=m.event_id WHERE
                """ + SCOPE + " AND t.id=? AND (m.event_role='CHIEF' OR t.assignee_id=m.id)",
                (r, n) -> task(r), args.toArray());
    }

    private static List<Object> scopeArgs(TaskCursor.Scope s) {
        return List.of(s.eventId(), s.accountId(), s.memberId(), s.round(), s.role());
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        var value = r.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public static TaskView task(ResultSet r) throws SQLException {
        UUID id = r.getObject("id", UUID.class);
        if (id == null) return null;
        return new TaskView(id, r.getObject("work_group_id", UUID.class), r.getObject("assignee_id", UUID.class),
                r.getString("title"), r.getString("status"), instant(r, "start_at"), instant(r, "end_at"),
                r.getObject("zone_id", UUID.class), r.getObject("program_id", UUID.class),
                r.getObject("source_issue_id", UUID.class), JSON.readTree(r.getString("steps")),
                JSON.readTree(r.getString("checklist")), instant(r, "first_assigned_at"),
                instant(r, "actual_started_at"), instant(r, "actual_ended_at"), r.getString("bundle_key"));
    }
}

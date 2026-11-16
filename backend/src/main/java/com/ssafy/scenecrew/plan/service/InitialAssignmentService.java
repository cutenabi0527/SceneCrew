package com.ssafy.scenecrew.plan.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.text.UnicodeText;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.notification.service.InitialAssignmentNotificationWriter;

/** API 045: AI/Join/ChangeSet를 생성하지 않는 최초 묶음 수동 배정. 받은 팀원에게 처음 맡음 알림을 묶음당 1건 만든다(V9-D47). */
@Service
public class InitialAssignmentService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final Idempotency idempotency;
    private final AssignmentChecks checks;
    private final InitialAssignmentNotificationWriter notifications;

    public InitialAssignmentService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency,
            AssignmentChecks checks, InitialAssignmentNotificationWriter notifications) {
        this.jdbc = jdbc;
        this.members = members;
        this.idempotency = idempotency;
        this.checks = checks;
        this.notifications = notifications;
    }

    public record Input(String bundleKey, UUID memberId, long expectedEventRevision) { }
    public record Applied(List<TaskView> tasks, String eventRevision) { }
    public record TaskView(UUID id, UUID workGroupId, UUID assigneeId, String title, String status,
            Instant startAt, Instant endAt, UUID zoneId, UUID programId, UUID sourceIssueId,
            JsonNode steps, JsonNode checklist, Instant firstAssignedAt, Instant actualStartedAt,
            Instant actualEndedAt, String bundleKey) { }
    private record EventRow(String state, long revision) { }

    @Transactional
    public IdempotentResult<Applied> assign(UUID eventId, UUID accountId, String key, Input input) {
        if (input == null || input.bundleKey() == null || UnicodeText.isBlank(input.bundleKey())
                || input.memberId() == null || input.expectedEventRevision() < 0)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        members.requireChief(eventId, accountId);
        var rows = jdbc.query("SELECT state, revision FROM event WHERE id = ? FOR UPDATE",
                (r, n) -> new EventRow(r.getString(1), r.getLong(2)), eventId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        var chief = members.requireChiefLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, "SC-API-045", key,
                IdempotencyRequest.hash(eventId, input.bundleKey(), input.memberId(), input.expectedEventRevision()),
                eventId, chief.getId(), chief.getParticipationRevision());
        return idempotency.run(request, Applied.class, () -> {
            EventRow event = rows.getFirst();
            if (!Set.of("RECRUITING", "LIVE").contains(event.state()))
                throw new BusinessException(ErrorCode.INVALID_STATE);
            if (event.revision() != input.expectedEventRevision())
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                        Map.of("target", "event"));
            var target = jdbc.query("""
                    SELECT work_group_id, participation_revision FROM member
                    WHERE id = ? AND event_id = ? AND active AND available AND NOT is_reserve FOR UPDATE""",
                    (r, n) -> new Target(r.getObject(1, UUID.class), r.getLong(2)), input.memberId(), eventId);
            if (target.isEmpty()) throw new BusinessException(ErrorCode.INVALID_REFERENCE);
            Target member = target.getFirst();
            if (member.group() == null || jdbc.queryForList(
                    "SELECT id FROM work_group WHERE id = ? AND event_id = ? AND active FOR SHARE",
                    UUID.class, member.group(), eventId).isEmpty())
                throw new BusinessException(ErrorCode.INVALID_REFERENCE);
            var tasks = jdbc.query("SELECT * FROM task WHERE event_id = ? AND bundle_key = ? ORDER BY id FOR UPDATE",
                    (r, n) -> task(r), eventId, input.bundleKey());
            if (tasks.isEmpty()) throw new BusinessException(ErrorCode.INVALID_REFERENCE);
            for (var task : tasks) {
                if (!member.group().equals(task.workGroupId())) throw new BusinessException(ErrorCode.INVALID_REFERENCE);
                if (!"WAITING".equals(task.status()) || task.assigneeId() != null || task.firstAssignedAt() != null
                        || task.actualStartedAt() != null || task.actualEndedAt() != null)
                    throw new BusinessException(ErrorCode.INVALID_STATE);
                checks.requireNoOverlap(eventId, input.memberId(), null, task.startAt(), task.endAt());
                for (var other : tasks) {
                    if (!task.id().equals(other.id()) && task.startAt().isBefore(other.endAt())
                            && task.endAt().isAfter(other.startAt()))
                        throw new BusinessException(ErrorCode.TIME_OVERLAP);
                }
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            for (var task : tasks)
                jdbc.update("UPDATE task SET assignee_id = ?, first_assigned_at = ? WHERE id = ?",
                        input.memberId(), Timestamp.from(now), task.id());
            long revision = Math.incrementExact(event.revision());
            jdbc.update("UPDATE event SET revision = ? WHERE id = ?", revision, eventId);
            // 현 스키마의 Task에는 담당 회차 열이 없다. 기존 JSONB 업무 로그에 배정 당시 회차를 원자 보존한다.
            var evidence = Map.of("bundleKey", input.bundleKey(), "memberId", input.memberId(),
                    "participationRevision", Long.toString(member.revision()),
                    "taskIds", tasks.stream().map(TaskView::id).toList(), "firstAssignedAt", now.toString());
            jdbc.update("""
                    INSERT INTO operation_log (id,event_id,type,actor_member_id,resource_type,resource_id,changes,occurred_at)
                    VALUES (?,?,'INITIAL_ASSIGNMENT',?,'MEMBER',?,?::jsonb,?)""",
                    UUID.randomUUID(), eventId, chief.getId(), input.memberId(), JSON.writeValueAsString(evidence), Timestamp.from(now));
            // 같은 커밋에 처음 맡음 알림을 남긴다. 멱등 재전달은 이 블록을 다시 실행하지 않으므로 알림도 다시 만들지 않는다.
            notifications.write(new InitialAssignmentNotificationWriter.Input(eventId, input.memberId(), member.revision(),
                    input.bundleKey(), now));
            var assigned = jdbc.query("SELECT * FROM task WHERE event_id = ? AND bundle_key = ? ORDER BY id",
                    (r, n) -> task(r), eventId, input.bundleKey());
            return IdempotentResult.of(200, new Applied(assigned, Long.toString(revision)), "INITIAL_ASSIGNMENT_APPLIED",
                    Map.of("eventId", eventId, "memberId", input.memberId(), "participationRevision", member.revision(),
                            "taskIds", tasks.stream().map(TaskView::id).toList()));
        });
    }

    private record Target(UUID group, long revision) { }

    private static Instant instant(ResultSet r, String name) throws SQLException {
        var value = r.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static TaskView task(ResultSet r) throws SQLException {
        return new TaskView(r.getObject("id", UUID.class), r.getObject("work_group_id", UUID.class),
                r.getObject("assignee_id", UUID.class), r.getString("title"), r.getString("status"),
                instant(r, "start_at"), instant(r, "end_at"), r.getObject("zone_id", UUID.class),
                r.getObject("program_id", UUID.class), r.getObject("source_issue_id", UUID.class),
                JSON.readTree(r.getString("steps")), JSON.readTree(r.getString("checklist")),
                instant(r, "first_assigned_at"), instant(r, "actual_started_at"), instant(r, "actual_ended_at"),
                r.getString("bundle_key"));
    }
}

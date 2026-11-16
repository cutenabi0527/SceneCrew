package com.ssafy.scenecrew.plan.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.ssafy.scenecrew.account.service.SessionAuthenticator.Principal;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.plan.repository.TaskReadQueries;
import com.ssafy.scenecrew.plan.service.InitialAssignmentService.TaskView;
import com.ssafy.scenecrew.safety.service.SafetySupportProtection;

/** Event -> Member -> Task locks, shared with assignment/group writers. No external work inside the commit. */
@Service
public class TaskExecutionService {
    public enum Command { START, COMPLETE, CHECKLIST }
    public record Check(String key, boolean checked) { }
    public record Result(TaskView task, String eventRevision) { }
    private record EventRow(String state, long revision) { }
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final TaskReadQueries tasks;
    private final TransactionTemplate transaction;
    private final SafetySupportProtection support;

    public TaskExecutionService(JdbcTemplate jdbc, CurrentMembers members, TaskReadQueries tasks, PlatformTransactionManager manager,
            SafetySupportProtection support) {
        this.jdbc = jdbc; this.members = members; this.tasks = tasks;
        this.support = support;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Result execute(UUID taskId, Principal principal, long expected, Command command, List<Check> checks) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if (expected < 0 || taskId == null || command == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (command == Command.CHECKLIST && (checks == null || checks.size() > 6
                || checks.stream().anyMatch(c -> c == null || c.key() == null)
                || checks.stream().map(Check::key).distinct().count() != checks.size()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        try {
            return transaction.execute(status -> apply(taskId, principal, expected, command, checks));
        } catch (DataAccessException | TransactionException failure) {
            // Includes deferred commit failures, not only SQL statement failures.
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private Result apply(UUID taskId, Principal principal, long expected, Command command, List<Check> checks) {
        UUID eventId = tasks.eventId(taskId);
        if (eventId == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        members.require(eventId, principal.accountId());
        var events = jdbc.query("SELECT state,revision FROM event WHERE id=? FOR UPDATE",
                (r, n) -> new EventRow(r.getString(1), r.getLong(2)), eventId);
        if (events.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        var event = events.getFirst();
        // #334 deactivation locks all participating Events before Account -> Member -> Task.
        // The Event guard serializes this participation; a current Account read adds no reverse lock edge.
        if (jdbc.queryForList("""
                SELECT id FROM account WHERE id=? AND status='ACTIVE' AND signup_completed AND token_version=?
                """, UUID.class, principal.accountId(), principal.tokenVersion()).isEmpty())
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        var me = members.requireLocked(eventId, principal.accountId());
        var rows = jdbc.query("SELECT * FROM task WHERE id=? AND event_id=? FOR UPDATE",
                (r, n) -> TaskReadQueries.task(r), taskId, eventId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        var task = rows.getFirst();
        if (!me.getId().equals(task.assigneeId())) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (event.revision() != expected) throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                ErrorCode.VERSION_CONFLICT.getMessage(), Map.of("target", "event"));
        // Historical DONE re-completion is explicitly a read-like no-op, including an ended event.
        if (command == Command.COMPLETE && "DONE".equals(task.status())) return result(taskId, event.revision());
        if (!Set.of("RECRUITING", "LIVE").contains(event.state())) throw new BusinessException(ErrorCode.INVALID_STATE);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var staffing = command == Command.CHECKLIST ? null : support.capture(eventId, now);
        switch (command) {
            case START -> {
                requireState(task, Set.of("WAITING"));
                support.requireStart(eventId, taskId, me.getId(), now, staffing);
                jdbc.update("UPDATE task SET status='IN_PROGRESS',actual_started_at=? WHERE id=?", Timestamp.from(now), taskId);
            }
            case COMPLETE -> {
                requireState(task, Set.of("IN_PROGRESS"));
                for (var item : task.checklist())
                    if (item.path("required").asBoolean(false) && !item.path("checked").asBoolean(false))
                        throw new BusinessException(ErrorCode.INVALID_STATE);
                // #267 may later append a durable trigger within this same transaction. Deferred for this MVP.
                jdbc.update("UPDATE task SET status='DONE',actual_ended_at=? WHERE id=?", Timestamp.from(now), taskId);
            }
            case CHECKLIST -> {
                requireState(task, Set.of("WAITING", "IN_PROGRESS"));
                ArrayNode items = (ArrayNode) JSON.readTree(task.checklist().toString());
                Map<String, ObjectNode> byKey = new HashMap<>();
                items.forEach(item -> byKey.put(item.get("key").asString(), (ObjectNode) item));
                boolean changed = false;
                for (Check check : checks) {
                    ObjectNode item = byKey.get(check.key());
                    if (item == null) throw new BusinessException(ErrorCode.INVALID_REFERENCE);
                    if (item.get("checked").asBoolean() == check.checked()) continue;
                    changed = true;
                    item.put("checked", check.checked());
                    if (check.checked()) {
                        item.put("checkedAt", now.toString()); item.put("checkedByMemberId", me.getId().toString());
                    } else { item.putNull("checkedAt"); item.putNull("checkedByMemberId"); }
                }
                if (!changed) return result(taskId, event.revision());
                jdbc.update("UPDATE task SET checklist=?::jsonb WHERE id=?", JSON.writeValueAsString(items), taskId);
            }
        }
        if (staffing != null) support.requirePreserved(eventId, now, staffing);
        long revision = Math.incrementExact(event.revision());
        jdbc.update("UPDATE event SET revision=? WHERE id=?", revision, eventId);
        return result(taskId, revision);
    }

    private Result result(UUID taskId, long revision) {
        return new Result(jdbc.query("SELECT * FROM task WHERE id=?", (r, n) -> TaskReadQueries.task(r), taskId).getFirst(),
                Long.toString(revision));
    }
    private static void requireState(TaskView task, Set<String> allowed) {
        if (!allowed.contains(task.status())) throw new BusinessException(ErrorCode.INVALID_STATE);
    }
}

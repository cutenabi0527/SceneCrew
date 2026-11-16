package com.ssafy.scenecrew.safety.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.plan.domain.TaskStatus;
import com.ssafy.scenecrew.safety.service.SafetySupportPreparation.Selection;
import com.ssafy.scenecrew.safety.service.SafetySupportStaffing.Assignment;
import com.ssafy.scenecrew.safety.service.SafetySupportStaffing.Minimum;
import com.ssafy.scenecrew.safety.service.SafetySupportStaffing.Window;

/** Server-only support provenance and staffing checks. Callers hold the Event write lock. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class SafetySupportProtection {
    private static final String SELECTION = "SAFETY_SUPPORT_SELECTED";
    private static final String SHORTAGE = "SAFETY_SUPPORT_SHORTAGE";
    private final JdbcTemplate jdbc;
    private final SafetySupportPreparation preparation;

    public SafetySupportProtection(JdbcTemplate jdbc, SafetySupportPreparation preparation) {
        this.jdbc = jdbc;
        this.preparation = preparation;
    }

    private record Stored(Selection selection, int minimum) { }

    private record SupportTask(UUID id, UUID memberId, UUID zoneId, Window window, String status, Stored stored) { }

    private record Origin(UUID zoneId, UUID groupId) { }

    private record Obligation(Origin origin, Window window, int minimum) { }

    public static final class Snapshot {
        private final List<SupportTask> tasks;
        private final List<Obligation> obligations;
        private final Map<UUID, List<Assignment>> staffing;

        private Snapshot(List<SupportTask> tasks, List<Obligation> obligations, Map<UUID, List<Assignment>> staffing) {
            this.tasks = tasks;
            this.obligations = obligations;
            this.staffing = staffing;
        }
    }

    public void record(UUID eventId, UUID changeSetId, UUID actorId, Selection selection) {
        var minimum = SafetySupportPreparation.minimum(profiles(eventId), selection.request());
        if (minimum == null) throw state("SUPPORT_CREW_RULE_UNAVAILABLE");
        jdbc.update("""
                INSERT INTO operation_log(id,event_id,type,actor_member_id,resource_type,resource_id,changes,change_set_id,occurred_at)
                VALUES (?,?,?,?,'CHANGE_SET',?,?::jsonb,?,?)
                """, UUID.randomUUID(), eventId, SELECTION, actorId, changeSetId,
                SafetyViews.MAPPER.writeValueAsString(new Stored(selection, minimum.count())), changeSetId,
                Timestamp.from(Instant.now()));
    }

    public Selection selection(UUID eventId, UUID changeSetId) {
        return jdbc.query("""
                SELECT changes::text FROM operation_log WHERE event_id=? AND change_set_id=? AND type=?
                """, (r, n) -> stored(r.getString(1)).selection(), eventId, changeSetId, SELECTION)
                .stream().findFirst().orElse(null);
    }

    public Snapshot capture(UUID eventId, Instant now) {
        var tasks = jdbc.query("""
                SELECT t.id,t.assignee_id,t.zone_id,t.start_at,t.end_at,t.status,s.changes::text AS selection
                FROM operation_log s
                JOIN change_set c ON c.id=s.change_set_id AND c.event_id=s.event_id AND c.status='APPLIED'
                JOIN operation_log added ON added.change_set_id=c.id AND added.event_id=c.event_id AND added.type='ADD_TASK'
                JOIN task t ON t.id=added.resource_id AND t.event_id=c.event_id
                WHERE s.event_id=? AND s.type=?
                  AND (t.status='IN_PROGRESS' OR (t.status='WAITING' AND t.assignee_id IS NOT NULL AND t.end_at>?))
                """, (r, n) -> new SupportTask(r.getObject("id", UUID.class), r.getObject("assignee_id", UUID.class),
                r.getObject("zone_id", UUID.class), new Window(SafetyViews.instant(r, "start_at"),
                SafetyViews.instant(r, "end_at")), r.getString("status"), stored(r.getString("selection"))),
                eventId, SELECTION, Timestamp.from(now));
        if (tasks.isEmpty()) return new Snapshot(tasks, List.of(), Map.of());
        var profiles = profiles(eventId);
        Map<UUID, List<Assignment>> staffing = new HashMap<>();
        var obligations = tasks.stream().map(task -> {
            var scope = task.stored().selection().request();
            Minimum configured = SafetySupportPreparation.minimum(profiles, scope);
            var origin = new Origin(scope.sourceZoneId(), scope.workGroupId());
            staffing.computeIfAbsent(origin.groupId(), group -> preparation.assignments(eventId, group));
            Instant start = now.isAfter(scope.startAt()) ? now : scope.startAt();
            // Overdue IN_PROGRESS is still occupying the worker. Evaluate current staffing after end.
            Instant end = scope.endAt().isAfter(start) ? scope.endAt() : start.plusNanos(1);
            var window = new Window(start, end);
            int minimum = task.stored().minimum();
            if (configured != null && configured.window().overlaps(window)) minimum = Math.max(minimum, configured.count());
            return new Obligation(origin, window, minimum);
        }).distinct().toList();
        return new Snapshot(tasks, obligations, staffing);
    }

    public void requireCapacity(Snapshot snapshot, UUID zoneId, UUID groupId) {
        var origin = new Origin(zoneId, groupId);
        for (var obligation : snapshot.obligations) {
            if (obligation.origin().equals(origin) && shortage(obligation, snapshot.staffing))
                throw state("SUPPORT_MINIMUM_CREW");
        }
    }

    public void requirePreserved(UUID eventId, Instant now, Snapshot before) {
        if (before.tasks.isEmpty()) return;
        var after = capture(eventId, now);
        for (var task : before.tasks) requireSupportAssignment(eventId, task);
        for (var obligation : after.obligations) {
            if (worsenedShortage(obligation, before, after)) throw state("SUPPORT_MINIMUM_CREW");
        }
    }

    public void requireMemberCanMove(UUID eventId, UUID memberId, Instant now) {
        var snapshot = capture(eventId, now);
        if (snapshot.tasks.stream().anyMatch(task -> memberId.equals(task.memberId())))
            throw state("SUPPORT_OPERATIONS_CHANGED");
        for (var obligation : snapshot.obligations) {
            var tasks = snapshot.staffing.getOrDefault(obligation.origin().groupId(), List.of());
            var remaining = tasks.stream().filter(task -> !task.memberId().equals(memberId)).toList();
            if (boundaries(tasks, obligation.window()).distinct().anyMatch(at -> {
                long count = count(obligation.origin(), remaining, at);
                return count < obligation.minimum() && count < count(obligation.origin(), tasks, at);
            })) throw state("SUPPORT_MINIMUM_CREW");
        }
    }

    public void requireProfileChange(UUID eventId, Instant now, Snapshot before) {
        if (before.obligations.isEmpty()) return;
        var profiles = profiles(eventId);
        for (var task : before.tasks) {
            var scope = task.stored().selection().request();
            var configured = SafetySupportPreparation.minimum(profiles, scope);
            if (scope.endAt().isAfter(now) && (configured == null
                    || !configured.window().covers(new Window(now, scope.endAt()))))
                throw state("SUPPORT_CREW_RULE_UNAVAILABLE");
        }
        var after = capture(eventId, now);
        for (var obligation : after.obligations) {
            var previous = before.obligations.stream().filter(old -> old.origin().equals(obligation.origin())
                    && old.window().equals(obligation.window())).findFirst().orElseThrow();
            if (obligation.minimum() > previous.minimum() && shortage(obligation, after.staffing))
                throw state("SUPPORT_MINIMUM_CREW");
        }
    }

    private void requireSupportAssignment(UUID eventId, SupportTask task) {
        var current = jdbc.query("""
                SELECT assignee_id,zone_id,start_at,end_at,status FROM task WHERE event_id=? AND id=?
                  AND checklist @> '[{"key":"arrive","required":true}]'::jsonb
                """, (r, n) -> new SupportTask(task.id(), r.getObject("assignee_id", UUID.class),
                r.getObject("zone_id", UUID.class), new Window(SafetyViews.instant(r, "start_at"),
                SafetyViews.instant(r, "end_at")), r.getString("status"), task.stored()), eventId, task.id());
        if (current.isEmpty()) throw state("SUPPORT_OPERATIONS_CHANGED");
        var after = current.getFirst();
        if ("DONE".equals(after.status()) || ("WAITING".equals(task.status()) && "CANCELED".equals(after.status()))) return;
        if (!Objects.equals(after.memberId(), task.memberId()) || !after.zoneId().equals(task.zoneId())
                || !after.window().equals(task.window()) || "CANCELED".equals(after.status()))
            throw state("SUPPORT_OPERATIONS_CHANGED");
    }

    public void requireStart(UUID eventId, UUID taskId, UUID memberId, Instant now, Snapshot snapshot) {
        for (var task : snapshot.tasks) {
            if (task.id().equals(taskId)) {
                if (!task.window().contains(now)) throw state("SUPPORT_WINDOW_NOT_CURRENT");
                var scope = task.stored().selection().request();
                if (preparation.assignments(eventId, scope.workGroupId()).stream().anyMatch(assignment ->
                        memberId.equals(assignment.memberId()) && assignment.status() == TaskStatus.IN_PROGRESS))
                    throw state("SUPPORT_ALREADY_IN_PROGRESS");
                requireCapacity(snapshot, scope.sourceZoneId(), scope.workGroupId());
            } else if (memberId.equals(task.memberId()) && "IN_PROGRESS".equals(task.status())) {
                throw state("SUPPORT_ALREADY_IN_PROGRESS");
            }
        }
        // An expired WAITING task is outside the active staffing set but may not be started.
        Boolean expired = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM operation_log added
                  JOIN operation_log s ON s.event_id=added.event_id AND s.change_set_id=added.change_set_id
                  JOIN task t ON t.event_id=added.event_id AND t.id=added.resource_id
                  WHERE added.event_id=? AND added.resource_id=? AND added.type='ADD_TASK' AND s.type=? AND t.end_at<=?)
                """, Boolean.class, eventId, taskId, SELECTION, Timestamp.from(now));
        if (Boolean.TRUE.equals(expired)) throw state("SUPPORT_WINDOW_NOT_CURRENT");
    }

    public UUID overdueAssignment(UUID eventId, UUID memberId, UUID exceptTask, Instant start) {
        return jdbc.queryForList("""
                SELECT t.id FROM operation_log added
                  JOIN operation_log s ON s.event_id=added.event_id AND s.change_set_id=added.change_set_id
                  JOIN task t ON t.event_id=added.event_id AND t.id=added.resource_id
                  WHERE added.event_id=? AND added.type='ADD_TASK' AND s.type=?
                    AND t.assignee_id=? AND t.status='IN_PROGRESS' AND t.end_at<=? AND t.end_at<=statement_timestamp()
                    AND (?::uuid IS NULL OR t.id<>?::uuid)
                  ORDER BY t.id LIMIT 1
                """, UUID.class, eventId, SELECTION, memberId, Timestamp.from(start), exceptTask, exceptTask)
                .stream().findFirst().orElse(null);
    }

    public void recordShortage(UUID eventId, UUID actorId, Instant now, Snapshot before) {
        if (before.obligations.isEmpty()) return;
        var after = capture(eventId, now);
        var zones = after.obligations.stream().filter(obligation -> worsenedShortage(obligation, before, after))
                .map(obligation -> obligation.origin().zoneId()).distinct().toList();
        if (zones.isEmpty()) return;
        UUID recordId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO operation_log(id,event_id,type,actor_member_id,resource_type,resource_id,changes,occurred_at)
                VALUES (?,?,?,?,'EVENT',?,?::jsonb,?)
                """, recordId, eventId, SHORTAGE, actorId, eventId,
                SafetyViews.MAPPER.writeValueAsString(Map.of("zoneIds", zones)), Timestamp.from(now));
        for (UUID zoneId : zones) {
            var taskId = after.tasks.stream()
                    .filter(task -> task.stored().selection().request().sourceZoneId().equals(zoneId))
                    .map(SupportTask::id).findFirst().orElseThrow();
            jdbc.update("""
                    INSERT INTO notification(id,member_id,participation_revision,event_id,task_id,type,payload,dedupe_key,created_at)
                    SELECT ?,m.id,m.participation_revision,m.event_id,?,?,'{}'::jsonb,?,?
                    FROM member m WHERE m.event_id=? AND m.active AND m.event_role='CHIEF'
                    """, UUID.randomUUID(), taskId, SHORTAGE, SHORTAGE + ":" + recordId + ":" + zoneId,
                    Timestamp.from(now), eventId);
        }
    }

    private static boolean worsenedShortage(Obligation obligation, Snapshot before, Snapshot after) {
        var previous = before.staffing.getOrDefault(obligation.origin().groupId(), List.of());
        var current = after.staffing.getOrDefault(obligation.origin().groupId(), List.of());
        return Stream.concat(boundaries(previous, obligation.window()), boundaries(current, obligation.window()))
                .distinct().anyMatch(at -> {
                    long remaining = count(obligation.origin(), current, at);
                    return remaining < obligation.minimum() && remaining < count(obligation.origin(), previous, at);
                });
    }

    private static boolean shortage(Obligation obligation, Map<UUID, List<Assignment>> staffing) {
        var tasks = staffing.getOrDefault(obligation.origin().groupId(), List.of());
        return boundaries(tasks, obligation.window()).distinct()
                .anyMatch(at -> count(obligation.origin(), tasks, at) < obligation.minimum());
    }

    private static Stream<Instant> boundaries(List<Assignment> tasks, Window window) {
        return Stream.concat(Stream.of(window.start()), tasks.stream()
                .flatMap(task -> Stream.of(task.window().start(), task.window().end())).filter(window::contains));
    }

    private static long count(Origin origin, List<Assignment> tasks, Instant at) {
        return SafetySupportStaffing.countAt(tasks, origin.zoneId(), origin.groupId(), at);
    }

    private List<SafetyViews.ProfileView> profiles(UUID eventId) {
        return jdbc.query("SELECT " + SafetyViews.PROFILE_COLUMNS + " FROM safety_zone_profile p WHERE p.event_id=?",
                SafetyViews::profile, eventId);
    }

    private static Stored stored(String json) {
        return SafetyViews.MAPPER.readValue(json, Stored.class);
    }

    public static BusinessException state(String reason) {
        return new BusinessException(ErrorCode.INVALID_STATE, ErrorCode.INVALID_STATE.getMessage(), Map.of("reason", reason));
    }
}

package com.ssafy.scenecrew.plan.ai.assign;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.notification.service.InitialAssignmentNotificationWriter;
import com.ssafy.scenecrew.plan.service.AssignmentChecks;

/**
 * AI 최초 배정(#267) 결과의 묶음 하나를 다시 검사하고 처음 배정한다(V9-D47). 수동 최초 배정(045)과 같은 조건이다:
 * 대상은 같은 그룹의 활성·가용·비예비 팀원(계정 활성·가입 완료)이고, 묶음의 모든 업무가 대기·담당 없음·최초 배정 기록 없음·수행 시각 없음이며,
 * 묶음 안과 이미 맡은 업무 사이 시간이 겹치지 않아야 한다. 통과하지 못하면 아무것도 쓰지 않고 사유 코드를 돌려준다.
 *
 * <p>호출자가 같은 트랜잭션에서 Event를 먼저 FOR UPDATE로 잠그고, 성공한 묶음이 하나라도 있으면 Event.revision을 한 번 올린다.
 * 잠금 순서는 045와 같다: Event → 대상 Member → 묶음 Task → (알림) 수신자 Account·Member 공유 잠금.
 * 트랜잭션 프록시를 쓰지 않는 일반 메서드라, 묶음 하나가 거절돼도 같은 트랜잭션의 다른 묶음 배정은 그대로 진행된다.
 */
@Component
public class FirstBundleAssigner {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final AssignmentChecks checks;
    private final InitialAssignmentNotificationWriter notifications;

    public FirstBundleAssigner(JdbcTemplate jdbc, AssignmentChecks checks, InitialAssignmentNotificationWriter notifications) {
        this.jdbc = jdbc;
        this.checks = checks;
        this.notifications = notifications;
    }

    private record Target(UUID group, long revision) { }
    private record TaskRow(UUID id, UUID group, UUID assignee, String status, Instant firstAssignedAt, Instant startedAt,
            Instant endedAt, Instant startAt, Instant endAt) { }

    /** 성공하면 빈 값, 거절하면 사유 코드. jobId는 기록의 근거로만 남긴다. */
    public Optional<String> assign(UUID eventId, UUID workGroupId, String bundleKey, UUID memberId, UUID jobId, Instant now) {
        // 알림 writer가 거절할 수신자(계정이 활성·가입 완료가 아님)도 여기서 묶음 단위로 거른다. writer 예외는 apply 전체를
        // 실패시키므로 미리 본다. 계정은 잠그지 않는다(잠금 순서상 알림 writer가 공유 잠금으로 다시 확인한다).
        var target = jdbc.query("""
                SELECT m.work_group_id, m.participation_revision FROM member m JOIN account a ON a.id = m.account_id
                WHERE m.id = ? AND m.event_id = ? AND m.active AND m.available AND NOT m.is_reserve AND m.event_role = 'WORKER'
                  AND a.status = 'ACTIVE' AND a.signup_completed
                FOR UPDATE OF m""", (r, n) -> new Target(r.getObject(1, UUID.class), r.getLong(2)), memberId, eventId);
        if (target.isEmpty()) return Optional.of("MEMBER_NOT_ELIGIBLE");
        Target member = target.getFirst();
        if (!workGroupId.equals(member.group()) || jdbc.queryForList(
                "SELECT id FROM work_group WHERE id = ? AND event_id = ? AND active FOR SHARE",
                UUID.class, member.group(), eventId).isEmpty())
            return Optional.of("MEMBER_NOT_ELIGIBLE");
        List<TaskRow> tasks = jdbc.query("""
                SELECT id, work_group_id, assignee_id, status, first_assigned_at, actual_started_at, actual_ended_at,
                       start_at, end_at
                  FROM task WHERE event_id = ? AND bundle_key = ? ORDER BY id FOR UPDATE""",
                (r, n) -> new TaskRow(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                        r.getString(4), instant(r.getTimestamp(5)), instant(r.getTimestamp(6)), instant(r.getTimestamp(7)),
                        instant(r.getTimestamp(8)), instant(r.getTimestamp(9))),
                eventId, bundleKey);
        if (tasks.isEmpty()) return Optional.of("BUNDLE_NOT_FOUND");
        for (var task : tasks) {
            if (!member.group().equals(task.group())) return Optional.of("BUNDLE_GROUP_CHANGED");
            if (!"WAITING".equals(task.status()) || task.assignee() != null || task.firstAssignedAt() != null
                    || task.startedAt() != null || task.endedAt() != null)
                return Optional.of("BUNDLE_ALREADY_ASSIGNED");
            for (var other : tasks)
                if (!task.id().equals(other.id()) && task.startAt().isBefore(other.endAt())
                        && task.endAt().isAfter(other.startAt()))
                    return Optional.of("TIME_OVERLAP");
            try {
                checks.requireNoOverlap(eventId, memberId, null, task.startAt(), task.endAt());
            } catch (BusinessException overlap) {
                return Optional.of("TIME_OVERLAP");
            }
        }
        for (var task : tasks)
            jdbc.update("UPDATE task SET assignee_id = ?, first_assigned_at = ? WHERE id = ?",
                    memberId, Timestamp.from(now), task.id());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("bundleKey", bundleKey);
        evidence.put("memberId", memberId);
        evidence.put("participationRevision", Long.toString(member.revision()));
        evidence.put("taskIds", tasks.stream().map(TaskRow::id).toList());
        evidence.put("firstAssignedAt", now.toString());
        evidence.put("source", "AI");
        evidence.put("jobId", jobId);
        // 시스템 배정이라 실행한 사람(actor)은 없다.
        jdbc.update("""
                INSERT INTO operation_log (id,event_id,type,actor_member_id,resource_type,resource_id,changes,occurred_at)
                VALUES (?,?,'INITIAL_ASSIGNMENT',NULL,'MEMBER',?,?::jsonb,?)""",
                UUID.randomUUID(), eventId, memberId, JSON.writeValueAsString(evidence), Timestamp.from(now));
        notifications.write(new InitialAssignmentNotificationWriter.Input(eventId, memberId, member.revision(), bundleKey, now));
        return Optional.empty();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

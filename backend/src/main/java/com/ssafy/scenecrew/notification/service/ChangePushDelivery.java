package com.ssafy.scenecrew.notification.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.ssafy.scenecrew.global.outbox.OutboxHandler;
import com.ssafy.scenecrew.global.outbox.OutboxPublisher;
import com.ssafy.scenecrew.global.outbox.domain.OutboxEvent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ChangePushDelivery implements OutboxHandler {
    private static final int MAX_ATTEMPTS = 3;
    // Keep the persisted outbox type so queued change acknowledgment pushes remain dispatchable.
    private static final String TYPE = "CHANGE_ACK_PUSH";
    private static final String ELIGIBLE = """
            FROM notification n
            JOIN member m ON m.id=n.member_id AND m.event_id=n.event_id AND m.active
                AND m.participation_revision=n.participation_revision
            JOIN account a ON a.id=m.account_id AND a.status='ACTIVE' AND a.signup_completed
                AND a.consent->'push'='true'::jsonb
            JOIN event e ON e.id=n.event_id AND e.state IN ('RECRUITING','LIVE')
            LEFT JOIN change_acknowledgment ca ON ca.id=n.acknowledgment_id AND ca.member_id=m.id
                AND ca.event_id=n.event_id AND ca.participation_revision=n.participation_revision
            JOIN device_token d ON d.member_id=m.id AND d.participation_revision=m.participation_revision
                AND d.active AND length(trim(d.fcm_token))>0
            JOIN device_installation di ON di.device_id::text=d.device_id AND di.account_id=a.id
                AND di.push_session_id IS NOT NULL
            WHERE n.id=? AND (
                (n.type='CHANGE_ACK' AND ca.id IS NOT NULL)
                OR (n.type='ISSUE' AND EXISTS (
                    SELECT 1 FROM issue i
                    WHERE i.id::text=n.payload#>>'{target,id}' AND i.event_id=n.event_id
                      AND (m.event_role='CHIEF' OR i.reporter_member_id=m.id
                        OR EXISTS (SELECT 1 FROM report r WHERE r.issue_id=i.id AND r.author_member_id=m.id)
                        OR EXISTS (SELECT 1 FROM task t WHERE t.source_issue_id=i.id
                            AND t.event_id=n.event_id AND t.assignee_id=m.id))
                ))
            )
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OutboxPublisher outbox;
    private final ObjectProvider<PushSender> sender;
    private final PushSessionEligibility sessions;

    ChangePushDelivery(JdbcTemplate jdbc, TransactionTemplate transaction, OutboxPublisher outbox,
            ObjectProvider<PushSender> sender, PushSessionEligibility sessions) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.outbox = outbox;
        this.sender = sender;
        this.sessions = sessions;
    }

    /** Called only on the winning notification insert, in its transaction. */
    void enqueue(UUID eventId, UUID notificationId) {
        if (sender.getIfAvailable() == null) return;

        var devices = jdbc.queryForList("SELECT d.id " + ELIGIBLE, UUID.class, notificationId);
        for (UUID device : devices) {
            UUID deliveryId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO notification_delivery(id,notification_id,device_token_id,attempt,state)
                    VALUES (?,?,?,0,'PENDING')
                    """, deliveryId, notificationId, device);
            outbox.publish(eventId, TYPE, deliveryId, null);
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        PushSender transport = sender.getIfAvailable();
        if (transport == null) throw new IllegalStateException("FCM 발송 설정이 비활성화되어 있습니다.");

        Target target = transaction.execute(status -> claim(event));
        if (target == null) return;

        boolean eligible;
        try {
            eligible = sessions.isCurrent(target.sessionId(), target.accountId(), target.tokenVersion())
                    && stillEligible(event, target);
        } catch (DataAccessException unavailable) {
            recordOutcome(event, target, PushSender.Outcome.of(PushSender.Status.RETRY));
            return;
        }
        if (!eligible) {
            int canceled = jdbc.update("""
                    UPDATE notification_delivery SET state='CANCELED',lease_until=NULL
                    WHERE id=? AND attempt=? AND state='PENDING'
                        AND lease_until=? AND lease_until>clock_timestamp()
                    """, event.getAggregateId(), target.attempt(), Timestamp.from(event.getLeaseUntil()));
            if (canceled == 0) throw new IllegalStateException("FCM 작업 담당권이 만료되었습니다.");
            return;
        }

        PushSender.Outcome outcome;
        try {
            outcome = transport.send(target.token(), target.notificationId(), event.getEventId());
        } catch (RuntimeException failure) {
            // Transport errors can include request details. Relay only receives a fixed message.
            outcome = PushSender.Outcome.of(PushSender.Status.RETRY);
        }
        recordOutcome(event, target, outcome);
    }

    private boolean stillEligible(OutboxEvent event, Target target) {
        Timestamp lease = Timestamp.from(event.getLeaseUntil());
        // Read committed eligibility again after the Redis check and before the external call.
        var results = jdbc.queryForList("SELECT EXISTS (SELECT 1 " + ELIGIBLE + """
                    AND n.event_id=? AND d.id=? AND d.fcm_token=? AND d.updated_at=?
                    AND d.participation_revision=? AND a.id=? AND a.token_version=? AND di.push_session_id=?
                )
                FROM notification_delivery nd JOIN outbox_event oe ON oe.aggregate_id=nd.id
                WHERE nd.id=? AND nd.attempt=? AND nd.state='PENDING' AND nd.lease_until=?
                    AND oe.id=? AND oe.processed_at IS NULL AND oe.lease_until=?
                    AND oe.lease_until>clock_timestamp()
                """, Boolean.class, target.notificationId(), event.getEventId(), target.deviceId(),
                target.token(), Timestamp.from(target.updatedAt()), target.revision(), target.accountId(),
                target.tokenVersion(), target.sessionId(), event.getAggregateId(), target.attempt(), lease,
                event.getId(), lease);
        if (results.isEmpty()) throw new IllegalStateException("FCM 작업 담당권이 만료되었습니다.");
        return results.getFirst();
    }

    private void recordOutcome(OutboxEvent event, Target target, PushSender.Outcome result) {
        Boolean retry = transaction.execute(status -> finish(event.getAggregateId(), target, result));
        if (Boolean.TRUE.equals(retry)) throw new IllegalStateException("푸시 처리 일시 오류로 재시도합니다.");
    }

    private Target claim(OutboxEvent event) {
        if (event.getLeaseUntil() == null || jdbc.queryForList("""
                SELECT id FROM outbox_event WHERE id=? AND lease_until=? AND lease_until>clock_timestamp()
                    AND processed_at IS NULL FOR SHARE
                """, UUID.class, event.getId(), Timestamp.from(event.getLeaseUntil())).isEmpty()) {
            // The relay can take a whole batch before running it. An expired item must not be marked processed.
            throw new IllegalStateException("FCM 작업 담당권이 만료되었습니다.");
        }

        UUID delivery = event.getAggregateId();
        var rows = jdbc.query("""
                SELECT notification_id, device_token_id, attempt FROM notification_delivery
                WHERE id=? AND state='PENDING' AND (lease_until IS NULL OR lease_until<=clock_timestamp())
                FOR UPDATE
                """, (rs, row) -> new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)),
                delivery);
        if (rows.isEmpty()) return null;
        Pending pending = rows.getFirst();
        int attempt = pending.attempt() + 1;
        var targets = jdbc.query("SELECT d.fcm_token,d.updated_at,d.participation_revision,di.push_session_id,a.id,a.token_version "
                        + ELIGIBLE + " AND d.id=? AND n.event_id=?",
                (rs, row) -> new Target(pending.notificationId(), pending.deviceId(),
                        rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getLong(3), attempt,
                        rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getLong(6)),
                pending.notificationId(), pending.deviceId(), event.getEventId());
        if (attempt > MAX_ATTEMPTS || targets.isEmpty()) {
            jdbc.update("UPDATE notification_delivery SET state=?, lease_until=NULL WHERE id=?",
                    attempt > MAX_ATTEMPTS ? "FAILED" : "CANCELED", delivery);
            return null;
        }
        jdbc.update("UPDATE notification_delivery SET attempt=?, lease_until=? WHERE id=?",
                attempt, Timestamp.from(event.getLeaseUntil()), delivery);
        return targets.getFirst();
    }

    private boolean finish(UUID delivery, Target target, PushSender.Outcome result) {
        boolean retry = result.status() == PushSender.Status.RETRY && target.attempt() < MAX_ATTEMPTS;
        String state = switch (result.status()) {
            case SENT -> "SENT";
            case UNREGISTERED, FAILED -> "FAILED";
            case RETRY -> retry ? "PENDING" : "FAILED";
        };
        int updated = jdbc.update("""
                UPDATE notification_delivery SET state=?,provider_message_id=?,last_error=?,lease_until=NULL,
                    sent_at=CASE WHEN ?='SENT' THEN statement_timestamp() ELSE NULL END
                WHERE id=? AND attempt=? AND state='PENDING' AND lease_until IS NOT NULL
                """, state, result.messageId(), result.status() == PushSender.Status.SENT ? null : result.status().name(),
                state, delivery, target.attempt());
        if (updated == 1 && result.status() == PushSender.Status.UNREGISTERED) {
            jdbc.update("""
                    UPDATE device_token SET active=false,updated_at=statement_timestamp()
                    WHERE id=? AND fcm_token=? AND updated_at=? AND participation_revision=? AND active
                    """, target.deviceId(), target.token(), Timestamp.from(target.updatedAt()), target.revision());
        }
        return updated == 1 && retry;
    }

    private record Pending(UUID notificationId, UUID deviceId, int attempt) { }

    private record Target(UUID notificationId, UUID deviceId, String token, Instant updatedAt, long revision,
            int attempt, UUID sessionId, UUID accountId, long tokenVersion) { }
}

package com.ssafy.scenecrew.notification.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

@Component
public class IssueNotificationWriter {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ChangePushDelivery push;

    public IssueNotificationWriter(JdbcTemplate jdbc, JsonMapper json, ChangePushDelivery push) {
        this.jdbc = jdbc;
        this.json = json;
        this.push = push;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reportReceived(UUID eventId, UUID issueId, UUID reportId, Instant createdAt) {
        // The caller holds the Event write lock, which serializes participation and chief changes.
        // Lock the recipient Account while checking eligibility, as in the change acknowledgment writer.
        var chiefs = jdbc.query("""
                SELECT m.id, m.participation_revision
                FROM member m JOIN account a ON a.id=m.account_id
                WHERE m.event_id=? AND m.event_role='CHIEF' AND m.active
                  AND a.status='ACTIVE' AND a.signup_completed
                FOR SHARE OF a
                """, (row, index) -> new Recipient(row.getObject(1, UUID.class), row.getLong(2)), eventId);
        if (chiefs.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "보고 알림을 받을 수 있는 총괄이 없습니다.");
        }
        String payload = json.writeValueAsString(Map.of(
                "target", Map.of("kind", "ISSUE", "id", issueId), "reportId", reportId));
        insert(eventId, chiefs.getFirst(), payload, "ISSUE:REPORT:" + reportId, createdAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void statusChanged(UUID eventId, UUID issueId, String status, long eventRevision, Instant createdAt) {
        // The caller holds the Event write lock. EXISTS yields one row per current author even if they wrote several reports.
        var authors = jdbc.query("""
                SELECT m.id, m.participation_revision
                FROM member m JOIN account a ON a.id=m.account_id
                WHERE m.event_id=? AND m.active AND a.status='ACTIVE' AND a.signup_completed
                  AND EXISTS (SELECT 1 FROM report r WHERE r.issue_id=? AND r.author_member_id=m.id
                              AND r.participation_revision=m.participation_revision)
                ORDER BY a.id
                FOR SHARE OF a
                """, (row, index) -> new Recipient(row.getObject(1, UUID.class), row.getLong(2)), eventId, issueId);
        String payload = json.writeValueAsString(Map.of(
                "target", Map.of("kind", "ISSUE", "id", issueId), "status", status));
        for (Recipient author : authors) {
            String key = "ISSUE:STATUS:" + issueId + ":" + eventRevision + ":" + author.memberId();
            insert(eventId, author, payload, key, createdAt);
        }
    }

    private void insert(UUID eventId, Recipient recipient, String payload, String key, Instant createdAt) {
        UUID notificationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification (id,event_id,member_id,participation_revision,type,payload,dedupe_key,created_at)
                VALUES (?,?,?,?,'ISSUE',?::jsonb,?,?)
                """, notificationId, eventId, recipient.memberId(), recipient.participationRevision(),
                payload, key, Timestamp.from(createdAt));
        push.enqueue(eventId, notificationId);
    }

    private record Recipient(UUID memberId, long participationRevision) { }
}

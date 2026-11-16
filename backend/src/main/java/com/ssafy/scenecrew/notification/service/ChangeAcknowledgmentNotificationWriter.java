package com.ssafy.scenecrew.notification.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/** #345 저장 경계만 제공한다. 실제 apply의 대상 선정·ack 생성·연결은 호출자 책임이다. */
@Component
public class ChangeAcknowledgmentNotificationWriter {
    private final JdbcTemplate jdbc;
    private final ChangePushDelivery push;

    public ChangeAcknowledgmentNotificationWriter(JdbcTemplate jdbc, ChangePushDelivery push) {
        this.jdbc = jdbc;
        this.push = push;
    }

    public record Input(UUID eventId, UUID changeSetId, UUID acknowledgmentId, UUID memberId,
            long participationRevision, Instant createdAt) {
        public Input {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(changeSetId);
            Objects.requireNonNull(acknowledgmentId);
            Objects.requireNonNull(memberId);
            Objects.requireNonNull(createdAt);
            if (participationRevision < 0) throw new IllegalArgumentException("참여 회차가 잘못되었습니다.");
            createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public record Result(UUID notificationId, boolean created) { }

    /** 같은 ack는 같은 생성 시각으로 재전달한다. readAt과 공식 확인 상태는 변경하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result write(Input input) {
        Objects.requireNonNull(input);
        // Writer 내부는 Event -> 수신자 Account -> 수신자 Member/ack 순서다.
        // apply는 이 호출 전에 Event와 총괄 Member 등을 잠근다.
        // 탈퇴는 모든 참여 Event를 Account보다 먼저 잠그므로 같은 Event에서 apply와 교차 대기하지 않는다.
        if (jdbc.queryForList("SELECT id FROM event WHERE id=? FOR SHARE", UUID.class, input.eventId()).isEmpty())
            throw invalidReference();
        if (jdbc.queryForList("""
                SELECT a.id FROM account a JOIN member m ON m.account_id=a.id
                WHERE m.id=? AND m.event_id=? AND a.status='ACTIVE' AND a.signup_completed
                FOR SHARE OF a
                """, UUID.class, input.memberId(), input.eventId()).isEmpty()) {
            // 실패 사유만 구분한다. 부적격 Account에는 새로운 잠금을 추가하지 않는다.
            var recipient = jdbc.queryForList("""
                    SELECT a.status='ACTIVE' AND a.signup_completed FROM account a JOIN member m ON m.account_id=a.id
                    WHERE m.id=? AND m.event_id=?
                    """, Boolean.class, input.memberId(), input.eventId());
            if (!recipient.isEmpty() && !Boolean.TRUE.equals(recipient.getFirst()))
                throw new BusinessException(ErrorCode.INVALID_STATE, "변경 확인 알림을 받을 수 없는 참여자가 있어 적용할 수 없습니다.");
            throw invalidReference();
        }
        var valid = jdbc.queryForList("""
                SELECT ack.id FROM change_acknowledgment ack
                JOIN member m ON m.id=ack.member_id AND m.event_id=ack.event_id
                JOIN change_set c ON c.id=ack.change_set_id AND c.event_id=ack.event_id
                WHERE ack.id=? AND ack.event_id=? AND ack.change_set_id=? AND ack.member_id=?
                  AND ack.participation_revision=? AND m.participation_revision=ack.participation_revision AND m.active
                FOR SHARE OF m, ack
                """, UUID.class, input.acknowledgmentId(), input.eventId(), input.changeSetId(), input.memberId(),
                input.participationRevision());
        if (valid.isEmpty()) throw invalidReference();

        String key = "CHANGE_ACK:" + input.acknowledgmentId();
        var inserted = jdbc.queryForList("""
                INSERT INTO notification (id,event_id,change_set_id,acknowledgment_id,member_id,participation_revision,
                                          type,payload,dedupe_key,created_at)
                VALUES (?,?,?,?,?,?,'CHANGE_ACK','{}'::jsonb,?,?)
                ON CONFLICT (dedupe_key) DO NOTHING RETURNING id
                """, UUID.class, UUID.randomUUID(), input.eventId(), input.changeSetId(), input.acknowledgmentId(),
                input.memberId(), input.participationRevision(), key, Timestamp.from(input.createdAt()));
        if (!inserted.isEmpty()) {
            UUID notificationId = inserted.getFirst();
            push.enqueue(input.eventId(), notificationId);
            return new Result(notificationId, true);
        }

        // PostgreSQL UNIQUE는 경쟁 INSERT를 기다린다. READ COMMITTED의 다음 문장으로 승자의 내용을 확인한다.
        var existing = jdbc.query("""
                SELECT id, event_id=? AND change_set_id=? AND acknowledgment_id=? AND member_id=?
                    AND participation_revision=? AND type='CHANGE_ACK' AND payload='{}'::jsonb
                    AND task_id IS NULL AND created_at=? AS same
                FROM notification WHERE dedupe_key=? FOR SHARE
                """, (r, n) -> new Existing(r.getObject(1, UUID.class), r.getBoolean(2)), input.eventId(),
                input.changeSetId(), input.acknowledgmentId(), input.memberId(), input.participationRevision(),
                Timestamp.from(input.createdAt()), key);
        if (existing.size() != 1 || !existing.getFirst().same())
            throw new IllegalStateException("같은 알림 키에 다른 내용이 저장되어 있습니다.");
        return new Result(existing.getFirst().id(), false);
    }

    private static IllegalArgumentException invalidReference() {
        return new IllegalArgumentException("알림의 현재 수신자와 변경 확인 참조가 일치하지 않습니다.");
    }

    private record Existing(UUID id, boolean same) { }
}

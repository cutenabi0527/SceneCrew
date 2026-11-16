package com.ssafy.scenecrew.notification.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * '처음 맡음' 알림(INITIAL_ASSIGNMENT)을 받은 사람·묶음마다 1건 저장한다(V9-D47).
 * 수동 최초 배정(045)과 AI 최초 배정(#267)이 같은 트랜잭션 안에서 호출한다.
 * 알림은 업무 하나가 아니라 묶음 전체를 가리키므로 task_id를 비우고, 알림함은 행사 단위 대상으로 보여 준다.
 * 변경 확인(ChangeAcknowledgment)은 만들지 않는다(P03).
 */
@Component
public class InitialAssignmentNotificationWriter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public InitialAssignmentNotificationWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Input(UUID eventId, UUID memberId, long participationRevision, String bundleKey, Instant createdAt) {
        public Input {
            Objects.requireNonNull(eventId);
            Objects.requireNonNull(memberId);
            Objects.requireNonNull(bundleKey);
            Objects.requireNonNull(createdAt);
            if (participationRevision < 0) throw new IllegalArgumentException("참여 회차가 잘못되었습니다.");
            createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public record Result(UUID notificationId, boolean created) { }

    /** 묶음의 최초 배정은 한 번뿐이므로 행사·묶음으로 중복을 막는다. 같은 내용의 재호출은 기존 알림을 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result write(Input input) {
        Objects.requireNonNull(input);
        // 호출자는 이미 Event를 FOR UPDATE로, 대상 Member를 잠근 상태다. 여기서는 Event -> 수신자 Account -> Member 순서로
        // 공유 잠금만 더한다. 탈퇴는 참여 Event를 Account보다 먼저 잠그므로 같은 Event에서 교차 대기하지 않는다.
        if (jdbc.queryForList("SELECT id FROM event WHERE id=? FOR SHARE", UUID.class, input.eventId()).isEmpty())
            throw invalidReference();
        if (jdbc.queryForList("""
                SELECT a.id FROM account a JOIN member m ON m.account_id=a.id
                WHERE m.id=? AND m.event_id=? AND a.status='ACTIVE' AND a.signup_completed
                FOR SHARE OF a
                """, UUID.class, input.memberId(), input.eventId()).isEmpty()) {
            var recipient = jdbc.queryForList("""
                    SELECT a.status='ACTIVE' AND a.signup_completed FROM account a JOIN member m ON m.account_id=a.id
                    WHERE m.id=? AND m.event_id=?
                    """, Boolean.class, input.memberId(), input.eventId());
            if (!recipient.isEmpty() && !Boolean.TRUE.equals(recipient.getFirst()))
                throw new BusinessException(ErrorCode.INVALID_STATE, "배정 알림을 받을 수 없는 참여자입니다.");
            throw invalidReference();
        }
        if (jdbc.queryForList("""
                SELECT id FROM member WHERE id=? AND event_id=? AND participation_revision=? AND active FOR SHARE
                """, UUID.class, input.memberId(), input.eventId(), input.participationRevision()).isEmpty())
            throw invalidReference();

        String key = "INITIAL_ASSIGNMENT:" + input.eventId() + ":" + input.bundleKey();
        String payload = JSON.writeValueAsString(Map.of("bundleKey", input.bundleKey()));
        var inserted = jdbc.queryForList("""
                INSERT INTO notification (id,event_id,member_id,participation_revision,type,payload,dedupe_key,created_at)
                VALUES (?,?,?,?,'INITIAL_ASSIGNMENT',?::jsonb,?,?)
                ON CONFLICT (dedupe_key) DO NOTHING RETURNING id
                """, UUID.class, UUID.randomUUID(), input.eventId(), input.memberId(), input.participationRevision(),
                payload, key, Timestamp.from(input.createdAt()));
        if (!inserted.isEmpty()) return new Result(inserted.getFirst(), true);

        // 경쟁 INSERT는 UNIQUE에서 기다린다. 승자의 내용이 같을 때만 같은 알림으로 인정한다.
        var existing = jdbc.query("""
                SELECT id, event_id=? AND member_id=? AND participation_revision=? AND type='INITIAL_ASSIGNMENT'
                    AND payload=?::jsonb AND task_id IS NULL AND change_set_id IS NULL AND acknowledgment_id IS NULL
                    AND created_at=? AS same
                FROM notification WHERE dedupe_key=? FOR SHARE
                """, (r, n) -> new Existing(r.getObject(1, UUID.class), r.getBoolean(2)), input.eventId(),
                input.memberId(), input.participationRevision(), payload, Timestamp.from(input.createdAt()), key);
        if (existing.size() != 1 || !existing.getFirst().same())
            throw new IllegalStateException("같은 알림 키에 다른 내용이 저장되어 있습니다.");
        return new Result(existing.getFirst().id(), false);
    }

    private static IllegalArgumentException invalidReference() {
        return new IllegalArgumentException("배정 알림의 행사·수신자·참여 회차가 현재와 일치하지 않습니다.");
    }

    private record Existing(UUID id, boolean same) { }
}

package com.ssafy.scenecrew.aijob.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.kind.ActiveLimit;
import com.ssafy.scenecrew.aijob.kind.AiJobKind;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.error.RateLimitedException;

/**
 * AI 작업 공통 관문(V9-D43). 접수 때 진행 중 작업 한도와 외부 AI 동의를 보고, 실행 중에는 동의를 다시 확인한다.
 *
 * <p><b>진행 중 1개 규칙</b>: 종류가 선언한 범위(AiJobKind.activeLimit)에 QUEUED·RUNNING이면서 마감 전인 작업이 있으면
 * 429 RATE_LIMITED와 Retry-After(그 작업 마감까지 남은 초, 최소 1)로 거절한다. 새 횟수 한도는 두지 않는다.
 * 범위마다 트랜잭션 advisory lock으로 직렬화해 동시 접수 두 건이 모두 통과하지 못하게 한다.
 *
 * <p><b>외부 AI 동의</b>: 외부 생성 AI 종류(AiJobKind.external)는 요청 계정의 externalAi 동의가 있어야 접수한다(403 FORBIDDEN,
 * details.reason=EXTERNAL_AI_CONSENT_REQUIRED). 시스템이 시작한 작업(요청 Member 없음)은 그 행사 현재 총괄의 동의를 본다.
 * 동의를 바꿔도 행사 revision은 올리지 않는다(V9-D40). 대신 AI 호출 직전과 결과 반영 직전에 다시 확인해, 철회됐으면
 * 호출·반영 없이 CONSENT_WITHDRAWN으로 끝낸다. 외부로 보낼 입력 안의 다른 사람 데이터(보고 작성자별 동의)는 종류가 입력을 만들 때 거른다.
 */
@Component
public class AiJobGate {
    static final String EXTERNAL_AI_CONSENT_REQUIRED = "EXTERNAL_AI_CONSENT_REQUIRED";
    static final String AI_JOB_ACTIVE = "AI_JOB_ACTIVE";

    private final JdbcTemplate jdbc;

    public AiJobGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** AiJobService.accept 맨 앞에서 부른다. 호출하는 업무 트랜잭션 안이어야 잠금이 커밋까지 유지된다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkAccept(UUID eventId, UUID requestedByMemberId, AiJobKind kind, Instant now) {
        if (kind.external() && !consented(eventId, requestedByMemberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "외부 AI 처리에 동의해야 이 기능을 쓸 수 있습니다.",
                    Map.of("reason", EXTERNAL_AI_CONSENT_REQUIRED));
        }
        ActiveLimit limit = kind.activeLimit();
        if (limit == ActiveLimit.NONE) return;
        OffsetDateTime busyUntil;
        if (limit == ActiveLimit.EVENT) {
            lock("event:" + eventId + ":" + kind.kind());
            busyUntil = jdbc.query("""
                    SELECT max(deadline_at) FROM ai_job
                     WHERE event_id = ? AND kind = ? AND state IN ('QUEUED', 'RUNNING') AND deadline_at > ?""",
                    rs -> rs.next() ? rs.getObject(1, OffsetDateTime.class) : null, eventId, kind.kind(), utc(now));
        } else {
            UUID accountId = accountOf(eventId, requestedByMemberId);
            if (accountId == null) return;
            lock("account:" + accountId + ":" + kind.kind());
            busyUntil = jdbc.query("""
                    SELECT max(j.deadline_at) FROM ai_job j JOIN member m ON m.id = j.requested_by_member_id
                     WHERE m.account_id = ? AND j.kind = ? AND j.state IN ('QUEUED', 'RUNNING') AND j.deadline_at > ?""",
                    rs -> rs.next() ? rs.getObject(1, OffsetDateTime.class) : null, accountId, kind.kind(), utc(now));
        }
        if (busyUntil != null) {
            long seconds = (Duration.between(now, busyUntil.toInstant()).toMillis() + 999) / 1000;
            throw new RateLimitedException("같은 AI 작업이 진행 중입니다. 끝난 뒤 다시 요청해 주세요.",
                    Map.of("reason", AI_JOB_ACTIVE, "kind", kind.kind()), seconds);
        }
    }

    /** 실행 중 재확인. 이 작업이 외부 AI로 나가지 않으면(AiJobKind.external(job)이 false) 항상 true */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean stillConsented(AiJob job, AiJobKind kind) {
        return !kind.external(job) || consented(job.getEventId(), job.getRequestedByMemberId());
    }

    /** 요청 Member(없으면 현재 총괄)의 계정이 활성 참여 중이고 externalAi 동의가 true인지 */
    private boolean consented(UUID eventId, UUID requestedByMemberId) {
        Boolean agreed = jdbc.query(requestedByMemberId != null ? """
                SELECT (a.consent -> 'externalAi') = 'true'::jsonb FROM member m JOIN account a ON a.id = m.account_id
                 WHERE m.id = ? AND m.event_id = ? AND m.active""" : """
                SELECT (a.consent -> 'externalAi') = 'true'::jsonb FROM member m JOIN account a ON a.id = m.account_id
                 WHERE m.event_id = ? AND m.event_role = 'CHIEF' AND m.active""",
                rs -> rs.next() ? rs.getBoolean(1) : null,
                requestedByMemberId != null ? new Object[] { requestedByMemberId, eventId } : new Object[] { eventId });
        return Boolean.TRUE.equals(agreed);
    }

    private UUID accountOf(UUID eventId, UUID requestedByMemberId) {
        String sql = requestedByMemberId != null
                ? "SELECT account_id FROM member WHERE id = ? AND event_id = ?"
                : "SELECT account_id FROM member WHERE event_id = ? AND event_role = 'CHIEF' AND active";
        Object[] args = requestedByMemberId != null ? new Object[] { requestedByMemberId, eventId } : new Object[] { eventId };
        return jdbc.query(sql, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, args);
    }

    private void lock(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, "ai-job-gate:" + key);
    }

    private static OffsetDateTime utc(Instant at) {
        return at.atOffset(java.time.ZoneOffset.UTC);
    }
}

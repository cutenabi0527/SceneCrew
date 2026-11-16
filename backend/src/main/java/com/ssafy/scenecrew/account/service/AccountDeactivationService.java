package com.ssafy.scenecrew.account.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import com.ssafy.scenecrew.safety.service.SafetySupportProtection;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * SC-API-018 탈퇴(#334, V9-D39). 탈퇴가 곧 개인정보 삭제 요청이다.
 * <ul>
 *   <li>본인 확인: 현재 계정의 LOCAL 비밀번호를 다시 받는다. 실패 횟수는 로그인과 같은 제한을 공유한다.
 *       비밀번호 로그인이 없는 계정은 운영 창구로 요청한다.</li>
 *   <li>종료 전 공연의 총괄이면 409 CHIEF_TRANSFER_REQUIRED, 종료 전 공연에 진행 중 업무가 있으면 409 TASK_IN_PROGRESS.</li>
 *   <li>통과하면 한 트랜잭션에서 대기 업무 담당을 풀고, 모든 참여를 끝내고, 기기 알림을 끄고,
 *       계정을 DISABLED·tokenVersion+1·deactivated_at으로 바꿔 모든 세션을 다음 요청부터 막는다.
 *       이것이 삭제 요청 접수다. 처리 기한은 deactivated_at+24시간이다.</li>
 * </ul>
 * 이름·연락 수단·기기·음성 삭제는 {@link AccountAnonymizer}가 접수 후 24시간 안에 한다.
 */
public final class AccountDeactivationService {
    public static final Duration DEADLINE = Duration.ofHours(24);
    private static final int MAX_MEMBERSHIP_ATTEMPTS = 3;
    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final LocalCredentialVerifier verifier;
    private final LoginFailureLimiter limiter;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final SafetySupportProtection support;

    public AccountDeactivationService(JdbcTemplate jdbc, LocalCredentialVerifier verifier, LoginFailureLimiter limiter,
            PlatformTransactionManager manager, Clock clock, SafetySupportProtection support) {
        this.jdbc = jdbc;
        this.verifier = verifier;
        this.limiter = limiter;
        this.clock = clock;
        this.support = support;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Result deactivate(SessionAuthenticator.Principal principal, String password) {
        confirmPassword(principal, password);
        try {
            for (int attempt = 0; attempt < MAX_MEMBERSHIP_ATTEMPTS; attempt++) {
                try {
                    return transaction.execute(status -> deactivateLocked(principal));
                } catch (MembershipsChanged retry) {
                    // 잠근 행사 밖의 참여는 Account를 잡은 채 추가로 잠그지 않는다.
                    // 현재 트랜잭션을 롤백하고 새 참여 목록으로 잠금 순서를 다시 만든다.
                }
            }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        } catch (BusinessException failure) {
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private void confirmPassword(SessionAuthenticator.Principal principal, String password) {
        List<String> emails;
        try {
            emails = jdbc.queryForList("""
                    SELECT email FROM auth_identity WHERE account_id=? AND provider='LOCAL' AND email_verified=true
                    """, String.class, principal.accountId());
        } catch (DataAccessException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (emails.size() != 1) {
            throw new BusinessException(ErrorCode.REAUTHENTICATION_REQUIRED,
                    "비밀번호 로그인이 없는 계정은 운영 창구로 탈퇴를 요청해 주세요.", Map.of("reason", "NO_LOCAL_PASSWORD"));
        }
        var keys = limiter.keys(emails.getFirst());
        limiter.check(keys);
        var verified = verifier.verify(emails.getFirst(), password);
        if (verified.isEmpty() || !verified.get().accountId().equals(principal.accountId())
                || verified.get().tokenVersion() != principal.tokenVersion()) {
            limiter.failure(keys);
            throw new BusinessException(ErrorCode.REAUTHENTICATION_REQUIRED);
        }
        limiter.success(keys);
    }

    private Result deactivateLocked(SessionAuthenticator.Principal principal) {
        UUID accountId = principal.accountId();
        var eventIds = activeEventIds(accountId);
        var states = new LinkedHashMap<UUID, String>();
        // 참여·알림 생성과 같은 순서: 모든 Event(UUID 순) → Account → Member → Task.
        for (UUID eventId : eventIds) {
            states.put(eventId, jdbc.queryForObject("SELECT state FROM event WHERE id=? FOR UPDATE", String.class, eventId));
        }
        var locked = jdbc.queryForList("""
                SELECT id FROM account
                 WHERE id=? AND status='ACTIVE' AND signup_completed=true AND token_version=?
                 FOR UPDATE
                """, UUID.class, accountId, principal.tokenVersion());
        if (locked.isEmpty()) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        // Event 잠금 전후 신규 참여가 커밋될 수 있다. Account 잠금 아래 다시 읽어 누락을 막는다.
        if (!eventIds.equals(activeEventIds(accountId))) throw new MembershipsChanged();
        var memberships = new ArrayList<Membership>();
        for (UUID eventId : eventIds) {
            String state = states.get(eventId);
            jdbc.query("""
                    SELECT id, event_role FROM member WHERE event_id=? AND account_id=? AND active=true FOR UPDATE
                    """, rs -> {
                memberships.add(new Membership(eventId, rs.getObject("id", UUID.class), rs.getString("event_role"),
                        "ENDED".equals(state)));
            }, eventId, accountId);
        }

        var chiefEvents = new LinkedHashSet<String>();
        for (var m : memberships) if (!m.ended() && "CHIEF".equals(m.role())) chiefEvents.add(m.eventId().toString());
        if (!chiefEvents.isEmpty()) {
            throw new BusinessException(ErrorCode.CHIEF_TRANSFER_REQUIRED, ErrorCode.CHIEF_TRANSFER_REQUIRED.getMessage(),
                    Map.of("eventIds", List.copyOf(chiefEvents)));
        }

        var busyEvents = new LinkedHashSet<String>();
        var released = new ArrayList<Released>();
        for (var m : memberships) {
            if (m.ended()) continue;
            jdbc.query("""
                    SELECT id, status FROM task WHERE event_id=? AND assignee_id=? AND status IN ('WAITING','IN_PROGRESS')
                     ORDER BY id FOR UPDATE
                    """, rs -> {
                if ("IN_PROGRESS".equals(rs.getString("status"))) busyEvents.add(m.eventId().toString());
                else released.add(new Released(m.eventId(), m.memberId(), rs.getObject("id", UUID.class)));
            }, m.eventId(), m.memberId());
        }
        if (!busyEvents.isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_IN_PROGRESS, ErrorCode.TASK_IN_PROGRESS.getMessage(),
                    Map.of("eventIds", List.copyOf(busyEvents)));
        }

        // PostgreSQL은 마이크로초까지 저장하므로 응답 시각도 저장값과 같게 자른다.
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var staffing = new HashMap<UUID, SafetySupportProtection.Snapshot>();
        for (var membership : memberships) {
            if (!membership.ended()) staffing.put(membership.eventId(), support.capture(membership.eventId(), now));
        }
        for (var r : released) jdbc.update("UPDATE task SET assignee_id=NULL WHERE id=?", r.taskId());
        for (var m : memberships) {
            jdbc.update("""
                    UPDATE member SET active=false, available=false, participation_revision=participation_revision+1
                     WHERE id=?
                    """, m.memberId());
            if (m.ended()) continue;
            jdbc.update("UPDATE event SET revision=revision+1 WHERE id=?", m.eventId());
            var taskIds = released.stream().filter(r -> r.memberId().equals(m.memberId())).map(r -> "\"" + r.taskId() + "\"").toList();
            jdbc.update("""
                    INSERT INTO operation_log (id,event_id,type,actor_member_id,resource_type,resource_id,changes,occurred_at)
                    VALUES (?,?,'MEMBER_LEFT_BY_ACCOUNT_DEACTIVATION',?,'MEMBER',?,?::jsonb,?)
                    """, UUID.randomUUID(), m.eventId(), m.memberId(), m.memberId(),
                    "{\"releasedTaskIds\":[" + String.join(",", taskIds) + "]}", java.sql.Timestamp.from(now));
        }
        for (var membership : memberships) {
            if (!membership.ended()) support.recordShortage(membership.eventId(), membership.memberId(), now,
                    staffing.get(membership.eventId()));
        }
        jdbc.update("""
                UPDATE device_token SET active=false, updated_at=?
                 WHERE member_id IN (SELECT id FROM member WHERE account_id=?)
                """, java.sql.Timestamp.from(now), accountId);
        jdbc.update("""
                UPDATE account SET status='DISABLED', token_version=token_version+1, deactivated_at=? WHERE id=?
                """, java.sql.Timestamp.from(now), accountId);
        return new Result("DISABLED", now, receiptNo(accountId, now), now.plus(DEADLINE));
    }

    private List<UUID> activeEventIds(UUID accountId) {
        return jdbc.queryForList("""
                SELECT DISTINCT event_id FROM member WHERE account_id=? AND active=true ORDER BY event_id
                """, UUID.class, accountId);
    }

    private static final class MembershipsChanged extends RuntimeException {}

    /** 접수 번호는 따로 저장하지 않는다. 탈퇴일(UTC)과 계정 ID 앞 8자리로 만들어 운영 창구에서 계정을 찾을 수 있게 한다. */
    public static String receiptNo(UUID accountId, Instant deactivatedAt) {
        return "DEL-" + RECEIPT_DATE.format(deactivatedAt) + "-"
                + accountId.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 접수 번호로 처리 상태를 본다(로그인 없이, V9-D40). 이름·이메일 등은 돌려주지 않는다.
     * 처리 중 = 로그인 수단이나 원래 이름이 아직 남아 있음. 형식이 틀리거나 없는 번호는 모두 404다.
     */
    public DeletionStatus status(String receiptNo) {
        var m = java.util.regex.Pattern.compile("DEL-([0-9]{8})-([0-9A-F]{8})").matcher(receiptNo == null ? "" : receiptNo);
        if (!m.matches()) throw new BusinessException(ErrorCode.NOT_FOUND);
        java.time.LocalDate day;
        try { day = java.time.LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE); }
        catch (java.time.format.DateTimeParseException e) { throw new BusinessException(ErrorCode.NOT_FOUND); }
        try {
            var rows = jdbc.queryForList("""
                    SELECT a.deactivated_at,
                           (a.display_name<>? OR EXISTS (SELECT 1 FROM auth_identity i WHERE i.account_id=a.id)) AS pending
                      FROM account a
                     WHERE a.status='DISABLED' AND a.deactivated_at IS NOT NULL
                       AND a.id::text LIKE ? AND (a.deactivated_at AT TIME ZONE 'UTC')::date=?
                     ORDER BY a.deactivated_at
                    """, AccountAnonymizer.ANONYMOUS_NAME, m.group(2).toLowerCase(java.util.Locale.ROOT) + "%", day);
            if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
            boolean pending = rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("pending")));
            Instant at = ((java.sql.Timestamp) rows.getFirst().get("deactivated_at")).toInstant();
            return new DeletionStatus(receiptNo, pending ? "PROCESSING" : "COMPLETED", at.plus(DEADLINE));
        } catch (DataAccessException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public record DeletionStatus(String receiptNo, String status, Instant deletionDueAt) {}
    public record Result(String status, Instant deactivatedAt, String receiptNo, Instant deletionDueAt) {}
    private record Membership(UUID eventId, UUID memberId, String role, boolean ended) {}
    private record Released(UUID eventId, UUID memberId, UUID taskId) {}
}

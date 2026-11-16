package com.ssafy.scenecrew.event.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.change.service.PendingAcknowledgments;
import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.EventState;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.repository.EventRepository;
import com.ssafy.scenecrew.event.repository.MemberRepository;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.plan.domain.Task;
import com.ssafy.scenecrew.plan.domain.TaskStatus;
import com.ssafy.scenecrew.plan.repository.TaskRepository;
import com.ssafy.scenecrew.safety.service.SafetySupportProtection;
import jakarta.persistence.EntityManager;

/**
 * SC-API-040 행사 이탈(POST /events/{e}/leave). 규칙은 backend/docs/member-changes.md, 결정 V9-D44.
 *
 * <p>본인·요청 키 필수. 잠금 순서는 탈퇴와 같다: Event → Account(FOR SHARE) → Member → Task.
 * 행사 종료 전 총괄은 떠날 수 없다(후임 지정 필요). 진행 중 업무가 있으면 409, 대기 업무는 담당을 비우고 해제 알림을 남긴다.
 * Member를 비활성·불가로 바꾸고 이 참여의 기기 토큰을 끈다. 참여 회차는 다시 참여할 때 올린다(영수증은 원래 회차).
 * 종료 전이면 Event revision을 1 올린다. 같은 키 재요청은 이미 비활성인 Member도 찾아 원래 영수증을 돌려준다.
 */
@Service
public class EventLeaveService {
    static final String OPERATION = "SC-API-040";

    private final EventRepository events;
    private final MemberRepository members;
    private final TaskRepository tasks;
    private final PendingAcknowledgments pending;
    private final Idempotency idempotency;
    private final EventWriteSupport writes;
    private final JdbcTemplate jdbc;
    private final SafetySupportProtection support;
    private final EntityManager entityManager;

    public EventLeaveService(EventRepository events, MemberRepository members, TaskRepository tasks,
            PendingAcknowledgments pending, Idempotency idempotency, EventWriteSupport writes, JdbcTemplate jdbc,
            SafetySupportProtection support, EntityManager entityManager) {
        this.events = events;
        this.members = members;
        this.tasks = tasks;
        this.pending = pending;
        this.idempotency = idempotency;
        this.writes = writes;
        this.jdbc = jdbc;
        this.support = support;
        this.entityManager = entityManager;
    }

    @Transactional
    public IdempotentResult<Receipt> leave(UUID eventId, SessionAuthenticator.Principal principal, String key) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        var event = events.findForUpdate(eventId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // 이벤트 대기 중 계정이 비활성화·토큰 회전되었으면 진행하지 않는다(참여·가용 변경과 같은 순서).
        if (jdbc.queryForList("""
                SELECT id FROM account WHERE id = ? AND status = 'ACTIVE' AND signup_completed = true
                  AND token_version = ? FOR SHARE""", UUID.class, principal.accountId(), principal.tokenVersion()).isEmpty())
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        // 이미 떠난 참여도 찾는다. 같은 키 재요청은 원래 회차로 영수증을 대조해야 하기 때문이다.
        UUID memberId = jdbc.query("SELECT id FROM member WHERE event_id = ? AND account_id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, eventId, principal.accountId());
        if (memberId == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        Member member = members.findInEventForUpdate(eventId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var request = new IdempotencyRequest(principal.accountId(), OPERATION, key, IdempotencyRequest.hash(eventId),
                eventId, member.getId(), member.getParticipationRevision());
        return idempotency.run(request, Receipt.class, () -> {
            if (!Boolean.TRUE.equals(member.getActive())) throw new BusinessException(ErrorCode.NOT_FOUND);
            boolean ended = event.getState() == EventState.ENDED;
            if (member.getEventRole() == EventRole.CHIEF && !ended) throw state("CHIEF_TRANSFER_REQUIRED");
            List<Task> assigned = tasks.findAssignedForUpdate(eventId, member.getId(),
                    List.of(TaskStatus.WAITING, TaskStatus.IN_PROGRESS));
            if (assigned.stream().anyMatch(t -> t.getStatus() == TaskStatus.IN_PROGRESS)) throw state("TASK_IN_PROGRESS");
            Instant now = EventWriteSupport.now();
            var staffing = ended ? null : support.capture(eventId, now);
            assigned.forEach(Task::releaseWaitingAssignment);
            var released = assigned.stream().map(Task::getId).collect(Collectors.toSet());
            pending.release(eventId, member.getId(), released, now);
            member.leave();
            jdbc.update("UPDATE device_token SET active = false, updated_at = ? WHERE member_id = ? AND active",
                    now.atOffset(ZoneOffset.UTC), member.getId());
            if (!ended) event.advanceRevision();
            if (!ended) {
                entityManager.flush();
                support.recordShortage(eventId, member.getId(), now, staffing);
            }
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("releasedTaskIds", assigned.stream().map(t -> t.getId().toString()).toList());
            writes.log(eventId, "MEMBER_LEFT", member.getId(), "MEMBER", member.getId(), changes, now);
            var receipt = new Receipt(ChiefTransferService.Participation.from(member), event.getRevision().toString());
            return IdempotentResult.of(200, receipt, "MEMBER_LEFT",
                    Map.of("eventId", eventId.toString(), "memberId", member.getId().toString()));
        });
    }

    private static BusinessException state(String reason) {
        return new BusinessException(ErrorCode.INVALID_STATE, ErrorCode.INVALID_STATE.getMessage(), Map.of("reason", reason));
    }

    /** 계약 DepartureReceipt */
    public record Receipt(ChiefTransferService.Participation participation, String eventRevision) { }
}

package com.ssafy.scenecrew.event.service;

import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.event.domain.EventState;
import com.ssafy.scenecrew.event.repository.EventRepository;
import com.ssafy.scenecrew.event.repository.MemberRepository;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.plan.ai.assign.InitialAssignmentTrigger;

/** API 039 제한 MVP. 비예비 팀원이 다시 가용해지면 같은 트랜잭션에 AI 최초 배정 트리거를 남긴다(#267). */
@Service
public class MemberAvailabilityService {
    private final EventRepository events;
    private final MemberRepository members;
    private final CurrentMembers currentMembers;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final InitialAssignmentTrigger assignments;

    public MemberAvailabilityService(EventRepository events, MemberRepository members, CurrentMembers currentMembers,
            JdbcTemplate jdbc, PlatformTransactionManager manager, InitialAssignmentTrigger assignments) {
        this.events = events;
        this.members = members;
        this.currentMembers = currentMembers;
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.assignments = assignments;
    }

    public Result change(UUID memberId, SessionAuthenticator.Principal principal, boolean available, long expectedRevision) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if (memberId == null || expectedRevision < 0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        try {
            return transaction.execute(status -> {
                UUID eventId = members.findEventId(memberId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
                var event = events.findForUpdate(eventId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
                // Event 대기 중 Account 비활성화/tokenVersion 변경도 재확인한다. Join과 같은 잠금 순서다.
                if (jdbc.queryForList("""
                        SELECT id FROM account WHERE id = ? AND status = 'ACTIVE' AND signup_completed = true
                          AND token_version = ? FOR SHARE""", UUID.class, principal.accountId(), principal.tokenVersion()).isEmpty())
                    throw new BusinessException(ErrorCode.UNAUTHENTICATED);
                var me = currentMembers.requireLocked(eventId, principal.accountId());
                if (!me.getId().equals(memberId)) throw new BusinessException(ErrorCode.FORBIDDEN);
                // DRAFT 거절은 #233 사용자 승인 잠정 동작이며 팀 정책 확정은 대기 중이다.
                if (event.getState() != EventState.RECRUITING && event.getState() != EventState.LIVE)
                    throw new BusinessException(ErrorCode.INVALID_STATE);
                if (event.getRevision() != expectedRevision)
                    throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                            Map.of("target", "event"));
                if (me.getAvailable() != available) {
                    me.changeAvailability(available);
                    event.advanceRevision();
                    // V9-D46: 다시 가용해진 팀원의 그룹에 미배정 묶음이 있으면 AI 최초 배정을 시작한다.
                    if (available && !Boolean.TRUE.equals(me.getIsReserve())) assignments.publish(eventId, me.getWorkGroupId());
                }
                return new Result(ChiefTransferService.Participation.from(me), event.getRevision().toString());
            });
        } catch (DataAccessException | TransactionException failure) {
            // 커밋 시점 실패도 성공 응답으로 내보내지 않는다.
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public record Result(ChiefTransferService.Participation participation, String eventRevision) { }
}

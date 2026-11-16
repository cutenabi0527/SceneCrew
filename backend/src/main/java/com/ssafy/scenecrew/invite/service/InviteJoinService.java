package com.ssafy.scenecrew.invite.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.invite.dto.JoinRequest;
import com.ssafy.scenecrew.invite.dto.JoinResult;
import com.ssafy.scenecrew.invite.repository.InviteJoinRepository;
import static com.ssafy.scenecrew.global.error.ErrorCode.*;
import java.util.Map;
import com.ssafy.scenecrew.global.idempotency.service.*;
import com.ssafy.scenecrew.plan.ai.assign.InitialAssignmentTrigger;

/** 참여와 최초 응답 기록, AI 최초 배정 트리거(#267)를 같은 트랜잭션에서 저장한다. 배정 접수는 outbox 처리기가 따로 한다. */
@Service
public class InviteJoinService {
    private final InviteJoinRepository repository;
    private final TransactionTemplate transaction;
    private final Idempotency idempotency;
    private final InitialAssignmentTrigger assignments;
    // POLICIES P01: 총괄·예비를 포함한 행사 참여 상한.
    private static final long MAX_PARTICIPANTS = 31;
    public InviteJoinService(InviteJoinRepository repository, PlatformTransactionManager manager, Idempotency idempotency,
            InitialAssignmentTrigger assignments) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.assignments = assignments;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public IdempotentResult<JoinResult> join(SessionAuthenticator.Principal principal, String token, String key, JoinRequest request) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if (request == null || request.workGroupId() == null || request.expectedEventRevision() < 0)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (token == null || token.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        JoinRequest.uuid(key);
        String hash = tokenHash(token);
        try {
            return transaction.execute(status -> {
                var id = repository.findEventId(hash);
                if (id == null) throw new BusinessException(ErrorCode.NOT_FOUND);
                // Event 잠금 대기 중 회전·종료가 커밋됐을 수 있으므로 현재 토큰과 상태를 다시 읽는다.
                var event = repository.lockEvent(id);
                if (event == null || !hash.equals(event.hash())) throw new BusinessException(ErrorCode.NOT_FOUND);
                String name = repository.lockAccount(principal.accountId(), principal.tokenVersion());
                if (name == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
                if (!"RECRUITING".equals(event.state()) && !"LIVE".equals(event.state()))
                    throw new BusinessException(ErrorCode.INVALID_STATE);
                var member = repository.member(id, principal.accountId());
                // 신규/재참여의 저장될 회차를 잠금 아래 정한다. null이면 공통 재전달의 회차 검사가 생략된다.
                long round = member == null ? 1 : member.active() ? member.round() : Math.addExact(member.round(), 1);
                var receipt = new IdempotencyRequest(principal.accountId(), "SC-API-035", key,
                        IdempotencyRequest.hash(hash, request.workGroupId(), request.available(), request.isReserve(),
                                request.expectedEventRevision()), event.id(), member == null ? null : member.id(), round);
                return idempotency.run(receipt, JoinResult.class, () -> {
                    var joined = participate(event, member, name, principal, request);
                    return IdempotentResult.of(200, joined, "INVITE_JOINED", Map.of("memberId", joined.participation().memberId()));
                });
            });
        } catch (DataAccessException | TransactionException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
    private JoinResult participate(InviteJoinRepository.EventRow event, InviteJoinRepository.MemberRow member,
            String name, SessionAuthenticator.Principal principal, JoinRequest request) {
        if (member != null && member.active())
            return result(member, event.revision(), repository.assignment(event.id(), member.id()));
        if (event.revision() != request.expectedEventRevision()) throw new BusinessException(VERSION_CONFLICT);
        if (!repository.lockGroup(event.id(), request.workGroupId())) throw new BusinessException(INVALID_REFERENCE);
        if (event.capacity() == null || event.capacity() < 1 || event.capacity() > MAX_PARTICIPANTS)
            throw new BusinessException(ErrorCode.INVALID_STATE);
        if (repository.activeCount(event.id()) >= event.capacity()) throw new BusinessException(EVENT_FULL);
        // 미완료 이탈의 기존 업무를 되살리지 않는다. 완료·취소 이력은 보존한다.
        if (member != null && "ASSIGNED".equals(repository.assignment(event.id(), member.id())))
            throw new BusinessException(ErrorCode.INVALID_STATE);
        member = member == null ? repository.create(event.id(), principal.accountId(), name, request)
                : repository.rejoin(member, name, request);
        long revision = Math.addExact(event.revision(), 1);
        repository.advanceRevision(event.id(), revision);
        // V9-D46: 참여한 그룹의 미배정 묶음을 AI가 배정한다. 참여는 배정 접수 결과와 상관없이 성공한다(be/04).
        if (request.available() && !request.isReserve()) assignments.publish(event.id(), request.workGroupId());
        return result(member, revision, "UNKNOWN");
    }
    private JoinResult result(InviteJoinRepository.MemberRow member, long revision, String assignment) {
        return new JoinResult(new JoinResult.Participation(member.id(), Long.toString(member.round()), member.active(),
                member.role(), member.group()), Long.toString(revision), assignment);
    }
    public static String tokenHash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

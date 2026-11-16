package com.ssafy.scenecrew.event.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.repository.MemberRepository;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 로그인한 계정이 이 행사에서 누구인지 확인한다. 공개 API의 서비스가 업무 트랜잭션 안에서 부른다(S15P31A202-341).
 *
 * <ul>
 *   <li>이 행사의 활성 Member가 아니면 404 NOT_FOUND. 행사가 있다는 것도 알리지 않는다(be/08).</li>
 *   <li>활성 Member지만 총괄 전용 기능이면 403 FORBIDDEN.</li>
 * </ul>
 * 컨트롤러에서 미리 확인하지 않는다. 확인과 업무 반영을 같은 트랜잭션에 두어 그사이 탈퇴·총괄 인계가 끼어드는 틈을 줄인다.
 */
@Component
@RequiredArgsConstructor
public class CurrentMembers {
    private final MemberRepository members;
    private final jakarta.persistence.EntityManager entityManager;

    /** 이 행사의 활성 Member(총괄·팀원). 아니면 404 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Member require(UUID eventId, UUID accountId) {
        return members.findActive(eventId, accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** 이 행사의 활성 총괄. 비멤버는 404, 팀원은 403 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Member requireChief(UUID eventId, UUID accountId) {
        Member member = require(eventId, accountId);
        if (member.getEventRole() != EventRole.CHIEF) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return member;
    }

    /** 쓰기 서비스는 Event를 먼저 잠근 뒤 호출한다. 대기 전에 읽은 JPA 상태도 잠금 후 갱신한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Member requireLocked(UUID eventId, UUID accountId) {
        Member member = require(eventId, accountId);
        entityManager.refresh(member, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!Boolean.TRUE.equals(member.getActive())) throw new BusinessException(ErrorCode.NOT_FOUND);
        return member;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Member requireChiefLocked(UUID eventId, UUID accountId) {
        Member member = requireLocked(eventId, accountId);
        if (member.getEventRole() != EventRole.CHIEF) throw new BusinessException(ErrorCode.FORBIDDEN);
        return member;
    }
}

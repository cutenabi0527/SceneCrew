package com.ssafy.scenecrew.event.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.EventState;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.repository.EventRepository;
import com.ssafy.scenecrew.event.repository.MemberRepository;
import com.ssafy.scenecrew.event.dto.EventCrewResponse;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.plan.repository.WorkGroupRepository;

/**
 * SC-API-038 팀원 그룹 이동·예비 지정(PATCH /members/{m}). 규칙은 backend/docs/member-changes.md.
 *
 * <p>CHIEF·RECRUITING/LIVE·기대 Event revision. 잠금 순서는 다른 행사 쓰기와 같다: Event → 총괄 Member → 대상 Member → 업무.
 * 그룹 이동은 MemberGroupMove가 대기 업무를 해제하고 진행 중 업무가 있으면 409로 거절한다(USER_SELECTED_D04).
 * 예비 지정은 맡은 업무를 해제하지 않는다. 실제로 바뀌면 Event revision을 1 올리고, 참여 회차는 올리지 않는다.
 */
@Service
public class MemberUpdateService {
    private final EventRepository events;
    private final MemberRepository members;
    private final CurrentMembers currentMembers;
    private final WorkGroupRepository groups;
    private final MemberGroupMove groupMove;
    private final EventWriteSupport writes;

    public MemberUpdateService(EventRepository events, MemberRepository members, CurrentMembers currentMembers,
            WorkGroupRepository groups, MemberGroupMove groupMove, EventWriteSupport writes) {
        this.events = events;
        this.members = members;
        this.currentMembers = currentMembers;
        this.groups = groups;
        this.groupMove = groupMove;
        this.writes = writes;
    }

    /** workGroupId·isReserve가 null이면 바꾸지 않는다. 둘 중 하나는 컨트롤러가 보장한다. */
    @Transactional
    public Result update(UUID memberId, UUID accountId, UUID workGroupId, Boolean isReserve, long expectedRevision) {
        UUID eventId = members.findEventId(memberId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var event = events.findForUpdate(eventId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Member chief = currentMembers.requireChiefLocked(eventId, accountId);
        if (event.getState() != EventState.RECRUITING && event.getState() != EventState.LIVE)
            throw new BusinessException(ErrorCode.INVALID_STATE);
        EventWriteSupport.requireRevision(event.getRevision(), expectedRevision, "event");
        Member target = members.findInEventForUpdate(eventId, memberId)
                .filter(m -> Boolean.TRUE.equals(m.getActive()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // 총괄의 그룹·예비는 총괄 인계(044)로만 바꾼다.
        if (target.getEventRole() == EventRole.CHIEF) {
            throw new BusinessException(ErrorCode.INVALID_STATE, ErrorCode.INVALID_STATE.getMessage(),
                    Map.of("reason", "CHIEF_TARGET"));
        }

        boolean changed = false;
        if (workGroupId != null && !Objects.equals(target.getWorkGroupId(), workGroupId)) {
            groups.findActiveForShare(eventId, workGroupId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFERENCE,
                            ErrorCode.INVALID_REFERENCE.getMessage(), Map.of("field", "workGroupId")));
            groupMove.prepare(eventId, target, workGroupId);
            target.moveToGroup(workGroupId);
            changed = true;
        }
        if (isReserve != null && !isReserve.equals(target.getIsReserve())) {
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("isReserve", isReserve);
            target.changeReserve(isReserve);
            writes.log(eventId, "MEMBER_RESERVE_CHANGED", chief.getId(), "MEMBER", target.getId(), log,
                    EventWriteSupport.now());
            changed = true;
        }
        // 같은 값만 보낸 요청은 아무것도 바꾸지 않는다(024와 같은 원칙).
        if (changed) event.advanceRevision();
        return new Result(view(target), event.getRevision().toString());
    }

    static EventCrewResponse.Member view(Member m) {
        return new EventCrewResponse.Member(m.getId(), m.getDisplayName(), m.getWorkGroupId(), m.getEventRole().name(),
                Boolean.TRUE.equals(m.getActive()), Boolean.TRUE.equals(m.getAvailable()),
                Boolean.TRUE.equals(m.getIsReserve()), m.getParticipationRevision().toString());
    }

    /** 계약 MemberChanged */
    public record Result(EventCrewResponse.Member member, String eventRevision) { }
}

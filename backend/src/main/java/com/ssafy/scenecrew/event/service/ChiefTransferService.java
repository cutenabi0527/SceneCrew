package com.ssafy.scenecrew.event.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.EventState;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.repository.EventRepository;
import com.ssafy.scenecrew.event.repository.MemberRepository;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.plan.repository.WorkGroupRepository;
import com.ssafy.scenecrew.safety.service.SafetySupportProtection;

@Service
public class ChiefTransferService {
    private final EventRepository events;
    private final MemberRepository members;
    private final WorkGroupRepository groups;
    private final MemberGroupMove groupMove;
    private final SafetySupportProtection support;

    public ChiefTransferService(EventRepository events, MemberRepository members, WorkGroupRepository groups,
            MemberGroupMove groupMove, SafetySupportProtection support) {
        this.events = events;
        this.members = members;
        this.groups = groups;
        this.groupMove = groupMove;
        this.support = support;
    }

    @Transactional
    public Result transfer(UUID eventId, UUID accountId, UUID successorId, UUID groupId, long expectedRevision) {
        var event = events.findForUpdate(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        var previous = members.findActiveForUpdate(eventId, accountId)
                .filter(member -> member.getEventRole() == EventRole.CHIEF)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        if (event.getState() == EventState.ENDED) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        if (event.getRevision() != expectedRevision) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        var successor = members.findInEventForUpdate(eventId, successorId)
                .filter(member -> !member.getId().equals(previous.getId()) && Boolean.TRUE.equals(member.getActive()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFERENCE));
        groups.findActiveForShare(eventId, groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFERENCE));

        Instant now = Instant.now();
        var staffing = support.capture(eventId, now);
        groupMove.prepare(event.getId(), previous, groupId);
        previous.stepDownTo(groupId);
        // The partial unique index checks every statement, so flush before scheduling promotion.
        members.flush();
        successor.becomeChief();
        members.flush();
        support.requirePreserved(eventId, now, staffing);
        event.advanceRevision();

        return new Result(Participation.from(previous), Participation.from(successor), event.getRevision().toString());
    }

    public record Result(Participation previousChief, Participation currentChief, String eventRevision) {}

    public record Participation(UUID memberId, String participationRevision, boolean active,
                                EventRole eventRole, UUID workGroupId) {
        static Participation from(Member member) {
            return new Participation(member.getId(), member.getParticipationRevision().toString(),
                    member.getActive(), member.getEventRole(), member.getWorkGroupId());
        }
    }
}

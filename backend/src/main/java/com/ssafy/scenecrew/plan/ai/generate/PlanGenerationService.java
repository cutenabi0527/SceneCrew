package com.ssafy.scenecrew.plan.ai.generate;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.pipeline.AiJobService;
import com.ssafy.scenecrew.event.domain.Event;
import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.EventState;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.repository.EventRepository;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.event.service.EventCompleteness;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.issue.dto.IssueResponses.JobAccepted;

/**
 * SC-API-043 POST /events/{e}/plan-generations. 현재 DRAFT로 최초 계획 생성(GENERATE_PLAN)을 접수한다(202).
 * 결과는 statusUrl(GET /ai-jobs/{j}, 063)로 확인하고, 총괄이 확인한 계획은 화면이 028로 저장한다. S15P31A202-280.
 *
 * <ol>
 *   <li>행사를 배타 잠금하고 현재 활성 Member인지 확인한다(비멤버 404). 계획 저장(028)·입력 수정(024)과 같은 잠금이다.</li>
 *   <li>요청 키 처리. 같은 키 재요청은 최소 현재 접근만 보고 최초 202를 돌려준다(#378과 같은 원칙). 아래 검사는 새로 실행할 때만 한다.</li>
 *   <li>총괄(팀원 403)·DRAFT(아니면 409 INVALID_STATE)·기대 revision(409 VERSION_CONFLICT)·기본정보 완결성
 *       (422 EVENT_INCOMPLETE, publish와 같은 EventCompleteness. 작업을 만들지 않는다).</li>
 *   <li>공통 관문(AiJobService.accept → AiJobGate): 외부 AI 동의(403 EXTERNAL_AI_CONSENT_REQUIRED), 행사당 진행 중 1개(429).</li>
 * </ol>
 */
@Service
public class PlanGenerationService {
    public static final String OPERATION = "SC-API-043";
    static final String STATUS_URL = "/api/v1/ai-jobs/";

    private final EventRepository events;
    private final CurrentMembers members;
    private final AiJobService aiJobs;
    private final Idempotency idempotency;
    private final GeneratePlanKind kind;

    public PlanGenerationService(EventRepository events, CurrentMembers members, AiJobService aiJobs,
            Idempotency idempotency, GeneratePlanKind kind) {
        this.events = events;
        this.members = members;
        this.aiJobs = aiJobs;
        this.idempotency = idempotency;
        this.kind = kind;
    }

    @Transactional
    public IdempotentResult<JobAccepted> accept(UUID eventId, UUID accountId, String key, long expectedRevision) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Member me = members.requireLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, OPERATION, key, IdempotencyRequest.hash(eventId, expectedRevision),
                eventId, me.getId(), me.getParticipationRevision());
        return idempotency.run(request, JobAccepted.class, () -> {
            if (me.getEventRole() != EventRole.CHIEF) throw new BusinessException(ErrorCode.FORBIDDEN);
            if (event.getState() != EventState.DRAFT) throw new BusinessException(ErrorCode.INVALID_STATE);
            if (event.getRevision() != expectedRevision) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                        Map.of("target", "event"));
            }
            EventCompleteness.require(new EventCompleteness.Fields(event.getDate(), event.getPrepStartAt(),
                    event.getOpenAt(), event.getCloseAt(), event.getTeardownEndAt(), event.getLocationName(),
                    event.getAddress(), event.getTotalCapacity(), event.getExpectedVisitors(), event.getPeakVisitors(),
                    event.getDesiredReserveCount() == null ? 0 : event.getDesiredReserveCount()));
            AiJob job = aiJobs.accept(eventId, me.getId(), GeneratePlanKind.KIND, event.getRevision(), kind.input(eventId));
            return IdempotentResult.of(202, new JobAccepted(job.getId(), STATUS_URL + job.getId()),
                    "PLAN_GENERATION_ACCEPTED", Map.of("jobId", job.getId().toString()));
        });
    }
}

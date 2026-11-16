package com.ssafy.scenecrew.document.ai.extraction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.pipeline.AiJobService;
import com.ssafy.scenecrew.document.domain.IntakeDocument;
import com.ssafy.scenecrew.document.repository.IntakeDocumentRepository;
import com.ssafy.scenecrew.event.domain.Event;
import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.EventState;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.repository.EventRepository;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.issue.dto.IssueResponses.JobAccepted;

/**
 * SC-API-049 POST /events/{e}/extractions. 행사의 현재 입력 문서로 문서 추출(EXTRACT_EVENT_INPUT)을 접수한다(202).
 * 결과는 statusUrl(GET /ai-jobs/{j}, 063)로 확인한다. S15P31A202-236.
 *
 * <ol>
 *   <li>행사를 배타 잠금한다. 문서 등록·삭제·만료 정리도 같은 잠금을 잡으므로 접수와 끼어들지 않는다(IntakeDocumentService).</li>
 *   <li>현재 활성 Member인지 확인하고(비멤버 404) 요청 키 처리에 들어간다. 같은 키 재요청은 최소 현재 접근만 보고 최초 202를
 *       돌려준다(#378과 같은 원칙). 아래 검사는 새로 실행할 때만 한다.</li>
 *   <li>총괄(팀원 403)·DRAFT(아니면 409 INVALID_STATE)·기대 revision(409 VERSION_CONFLICT)·남은 문서 1개 이상(없으면 409 INVALID_STATE).</li>
 *   <li>공통 관문(AiJobService.accept → AiJobGate): 외부 AI 동의(403 EXTERNAL_AI_CONSENT_REQUIRED), 계정당 진행 중 1개(429).</li>
 * </ol>
 * 접수 중인 추출이 있으면 문서를 바꾸지 못하므로(IntakeDocumentService.checkNoExtraction) 보낸 문서 목록이 실행 중 바뀌지 않는다.
 */
@Service
@EnableConfigurationProperties(ExtractionProperties.class)
public class ExtractionService {
    public static final String OPERATION = "SC-API-049";
    static final String STATUS_URL = "/api/v1/ai-jobs/";

    private final EventRepository events;
    private final CurrentMembers members;
    private final IntakeDocumentRepository documents;
    private final AiJobService aiJobs;
    private final Idempotency idempotency;
    private final ExtractionProperties properties;

    public ExtractionService(EventRepository events, CurrentMembers members, IntakeDocumentRepository documents,
            AiJobService aiJobs, Idempotency idempotency, ExtractionProperties properties) {
        this.events = events;
        this.members = members;
        this.documents = documents;
        this.aiJobs = aiJobs;
        this.idempotency = idempotency;
        this.properties = properties;
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
            List<IntakeDocument> live = documents.findLiveByEventId(eventId, Instant.now());
            if (live.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_STATE, "추출할 문서가 없습니다. 문서를 먼저 올려 주세요.");
            }
            if (!properties.configured()) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "문서 추출을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
            }
            AiJob job = aiJobs.accept(eventId, me.getId(), ExtractEventInputKind.KIND, event.getRevision(), payload(live));
            return IdempotentResult.of(202, new JobAccepted(job.getId(), STATUS_URL + job.getId()), "EXTRACTION_ACCEPTED",
                    Map.of("jobId", job.getId().toString()));
        });
    }

    /** 요청4 payload. 원문은 넣지 않고 업무 서버 내부 주소로 내려받게 한다. */
    private Map<String, Object> payload(List<IntakeDocument> live) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (IntakeDocument d : live) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("documentId", d.getId().toString());
            item.put("name", d.getOriginalName());
            item.put("mime", d.getMime());
            item.put("bytes", d.getBytes());
            item.put("pageCount", d.getPageCount());
            item.put("sha256", d.getSha256());
            item.put("url", properties.documentUrl(d.getId()));
            list.add(item);
        }
        return Map.of("documents", list);
    }
}

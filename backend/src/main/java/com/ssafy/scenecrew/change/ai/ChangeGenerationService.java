package com.ssafy.scenecrew.change.ai;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.pipeline.AiJobService;
import com.ssafy.scenecrew.event.domain.Event;
import com.ssafy.scenecrew.event.domain.EventRole;
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
 * SC-API-064 POST /events/{e}/change-generations. 이슈·보고·지시로 AI 대응안 생성(GENERATE_CHANGE)을 접수한다(202).
 * 결과는 statusUrl(063)로 확인하고, READY면 AI 변경안(DRAFT)이 이미 저장돼 있어 기존 변경안 미리보기·승인(069·070)으로 이어진다.
 * S15P31A202-343, 규칙은 backend/docs/change-generation.md.
 *
 * <ol>
 *   <li>행사를 배타 잠금하고 현재 활성 Member인지 확인한다(비멤버 404).</li>
 *   <li>요청 키 처리. 같은 키 재요청은 최소 현재 접근만 보고 최초 202를 돌려준다. 아래 검사는 새로 실행할 때만 한다.</li>
 *   <li>총괄(팀원 403)·모집 중·운영 중(아니면 409 INVALID_STATE)·기대 revision(409 VERSION_CONFLICT)·
 *       이 행사의 이슈와 그 이슈의 보고(422 INVALID_REFERENCE)·보고가 하나 이상(422).</li>
 *   <li>공통 관문(AiJobService.accept → AiJobGate): 외부 AI 동의(403), 행사당 진행 중 1개(429).</li>
 * </ol>
 */
@Service
public class ChangeGenerationService {
    public static final String OPERATION = "SC-API-064";
    static final String STATUS_URL = "/api/v1/ai-jobs/";
    static final Set<String> RUNNING = Set.of("RECRUITING", "LIVE");

    /**
     * 064 본문. issueId는 이번 범위에서 필수다(AI가 이슈의 구역·보고를 기준으로 동작). reportIds가 비면 이슈의 최근 보고.
     * safetyObservationId·evidenceRefs(원문 JSON)는 아직 쓰지 않지만 같은 요청 키 판정에는 넣는다(값만 바꾼 재요청은 409).
     */
    public record ChangeGeneration(UUID issueId, String instruction, List<UUID> reportIds, long expectedEventRevision,
            UUID safetyObservationId, String evidenceRefs) {

        public ChangeGeneration(UUID issueId, String instruction, List<UUID> reportIds, long expectedEventRevision) {
            this(issueId, instruction, reportIds, expectedEventRevision, null, null);
        }
    }

    private final EventRepository events;
    private final CurrentMembers members;
    private final AiJobService aiJobs;
    private final Idempotency idempotency;
    private final GenerateChangeKind kind;
    private final JdbcTemplate jdbc;

    public ChangeGenerationService(EventRepository events, CurrentMembers members, AiJobService aiJobs,
            Idempotency idempotency, GenerateChangeKind kind, JdbcTemplate jdbc) {
        this.events = events;
        this.members = members;
        this.aiJobs = aiJobs;
        this.idempotency = idempotency;
        this.kind = kind;
        this.jdbc = jdbc;
    }

    @Transactional
    public IdempotentResult<JobAccepted> accept(UUID eventId, UUID accountId, String key, ChangeGeneration input) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Member me = members.requireLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, OPERATION, key,
                IdempotencyRequest.hash(eventId, input.issueId(), input.instruction(), input.reportIds(),
                        input.expectedEventRevision(), input.safetyObservationId(), input.evidenceRefs()),
                eventId, me.getId(), me.getParticipationRevision());
        return idempotency.run(request, JobAccepted.class, () -> {
            if (me.getEventRole() != EventRole.CHIEF) throw new BusinessException(ErrorCode.FORBIDDEN);
            if (!RUNNING.contains(event.getState().name())) {
                throw new BusinessException(ErrorCode.INVALID_STATE, "모집 중이거나 운영 중인 행사에서만 대응안을 만들 수 있습니다.");
            }
            if (event.getRevision() != input.expectedEventRevision()) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                        Map.of("target", "event"));
            }
            if (jdbc.queryForList("SELECT 1 FROM issue WHERE id = ? AND event_id = ?", input.issueId(), eventId).isEmpty()) {
                throw reference("issueId");
            }
            List<UUID> reportIds = input.reportIds();
            if (!reportIds.isEmpty()) {
                Long found = jdbc.queryForObject("SELECT count(*) FROM report WHERE issue_id = ? AND id = ANY(?)", Long.class,
                        input.issueId(), reportIds.toArray(UUID[]::new));
                if (found == null || found != reportIds.size()) throw reference("reportIds");
            } else if (jdbc.queryForList("SELECT 1 FROM report WHERE issue_id = ? LIMIT 1", input.issueId()).isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_REFERENCE, "보고가 없는 이슈에는 대응안을 만들 수 없습니다.",
                        Map.of("field", "issueId"));
            }
            AiJob job = aiJobs.accept(eventId, me.getId(), GenerateChangeKind.KIND, event.getRevision(),
                    kind.input(eventId, input.issueId(), reportIds, input.instruction(), Instant.now()));
            return IdempotentResult.of(202, new JobAccepted(job.getId(), STATUS_URL + job.getId()),
                    "CHANGE_GENERATION_ACCEPTED", Map.of("jobId", job.getId().toString()));
        });
    }

    private static BusinessException reference(String field) {
        return new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.INVALID_REFERENCE.getMessage(),
                Map.of("field", field));
    }
}

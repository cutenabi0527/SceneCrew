package com.ssafy.scenecrew.aijob.read;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.aijob.kind.AiJobError;
import com.ssafy.scenecrew.equipment.service.RecoveryService;
import com.ssafy.scenecrew.equipment.service.RecoveryViews;
import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.document.ai.extraction.ExtractEventInputKind;
import com.ssafy.scenecrew.issue.ai.analysis.AnalyzeReportKind;
import com.ssafy.scenecrew.issue.service.AudioAssetService;
import com.ssafy.scenecrew.issue.ai.transcription.TranscribeKind;
import com.ssafy.scenecrew.issue.dto.IssueResponses.AnalysisSummary;
import com.ssafy.scenecrew.issue.dto.IssueResponses.ZoneCandidate;
import com.ssafy.scenecrew.plan.ai.generate.GeneratePlanKind;
import com.ssafy.scenecrew.change.ai.GenerateChangeKind;

/**
 * SC-API-063 GET /ai-jobs/{j}. 로그인한 계정이 지금 볼 수 있는 AI 작업의 상태·결과를 종류별 공개 모양으로 돌려준다
 * (계약 V9JobRead, BE08 V7-D17). S15P31A202-235.
 *
 * <p>누가 보나(종류별 규칙). 볼 수 없으면 존재를 숨기고 모두 404다.
 * <ul>
 *   <li>TRANSCRIBE: 녹음을 올린 본인(작업 요청 Member)만. 총괄도 보지 않는다.</li>
 *   <li>ANALYZE_REPORT: 현재 총괄만. 분석은 총괄만 본다(issue-report.md). 056으로 jobId를 받은 팀원도 404다.</li>
 *   <li>SIMULATE_RECOVERY: 현재 총괄만(V9JobSimulateRecovery). result는 결과 위치 {simulationId, calculatedAt, expiresAt}이고
 *       후보 내용은 담지 않는다(recovery.md).</li>
 *   <li>EXTRACT_EVENT_INPUT: 현재 총괄만. 일부 문서를 못 읽었으면 PARTIAL(결과 있음)이다(document-extraction.md).</li>
 *   <li>GENERATE_PLAN: 현재 총괄만. result는 {status, questions, plan}이고 plan은 028 저장 모양이다(plan-generation.md).</li>
 *   <li>GENERATE_CHANGE: 현재 총괄만. result는 V9ChangeGenerationResult와 참고 기록이다(change-generation.md).</li>
 *   <li>그 밖의 종류: 아직 공개 모양이 없어 404.</li>
 * </ul>
 * 공통으로 이 행사의 활성 Member여야 한다(CurrentMembers).
 *
 * <p>상태: 저장 STALE_CONTEXT는 공개 FAILED·STALE_CONTEXT(retryable=false)로 보인다. QUEUED(재시도 대기)·RUNNING은
 * 마지막 오류가 있어도 error=null이다. 결과가 이미 정리돼 만들 수 없으면(전사문 삭제·24시간 경과, 더 새 분석으로 교체) 404다.
 */
@Service
public class AiJobReadService {
    static final String STALE_CONTEXT = "STALE_CONTEXT";
    /** 전사 실패 중 사용자에게 그대로 보이는 코드. 나머지는 AUDIO_INVALID 또는 AI_UNAVAILABLE로 바꾼다(STT-CONTRACT-TRANSITION §7). */
    static final Set<String> TRANSCRIBE_VISIBLE = Set.of("AUDIO_INVALID", "NO_SPEECH");
    /** 반영 직전 검사에서 막힌 전사. 공개는 AUDIO_INVALID(Q12 합의) */
    static final Set<String> TRANSCRIBE_AUDIO_GONE = Set.of("AUDIO_DELETED", "UPLOADER_INACTIVE", "CONSENT_WITHDRAWN");
    /** 계획 생성 실패 중 그대로 보이는 코드(PublicJobGeneratePlan x-known-values). 나머지는 AI_UNAVAILABLE */
    static final Set<String> PLAN_VISIBLE = Set.of("INVALID_REQUEST", "AI_REVIEW_FAILED");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;

    public AiJobReadService(JdbcTemplate jdbc, CurrentMembers members) {
        this.jdbc = jdbc;
        this.members = members;
    }

    private record JobRow(UUID id, UUID eventId, UUID requestedBy, String kind, String state, long attempt,
            String result, String error, String executionMeta) { }

    @Transactional(readOnly = true)
    public AiJobRead get(UUID jobId, UUID accountId) {
        try {
            JobRow job = jdbc.query("""
                    SELECT id, event_id, requested_by_member_id, kind, state, attempt, result::text, error::text,
                           execution_meta::text FROM ai_job WHERE id = ?""",
                    (r, n) -> new JobRow(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                            r.getString(4), r.getString(5), r.getLong(6), r.getString(7), r.getString(8), r.getString(9)),
                    jobId).stream().findFirst().orElseThrow(AiJobReadService::notFound);
            Member me = members.require(job.eventId(), accountId);
            requireAccess(job, me);
            return project(job);
        } catch (DataAccessException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static void requireAccess(JobRow job, Member me) {
        boolean allowed = switch (job.kind()) {
            case TranscribeKind.KIND -> me.getId().equals(job.requestedBy());
            case AnalyzeReportKind.KIND, RecoveryService.KIND, ExtractEventInputKind.KIND, GeneratePlanKind.KIND,
                    GenerateChangeKind.KIND ->
                    me.getEventRole() == EventRole.CHIEF;
            default -> false;
        };
        if (!allowed) throw notFound();
    }

    private AiJobRead project(JobRow job) {
        String attempt = Long.toString(job.attempt());
        return switch (job.state()) {
            case "QUEUED", "RUNNING" -> new AiJobRead(job.id(), job.kind(), job.state(), attempt, null, null);
            case "SUCCEEDED" -> new AiJobRead(job.id(), job.kind(), "SUCCEEDED", attempt, result(job), null);
            case "STALE_CONTEXT" -> new AiJobRead(job.id(), job.kind(), "FAILED", attempt, null,
                    new AiJobRead.PublicError(STALE_CONTEXT, false));
            case "FAILED" -> new AiJobRead(job.id(), job.kind(), "FAILED", attempt, null, publicError(job));
            // 일부 문서를 못 읽은 추출(#236). 결과는 있고 오류는 없다. 다른 종류에는 없는 상태다.
            case "PARTIAL" -> {
                if (!ExtractEventInputKind.KIND.equals(job.kind())) throw notFound();
                yield new AiJobRead(job.id(), job.kind(), "PARTIAL", attempt, result(job), null);
            }
            default -> throw notFound();
        };
    }

    private Object result(JobRow job) {
        JsonNode stored = MAPPER.readTree(job.result() == null ? "{}" : job.result());
        return switch (job.kind()) {
            case TranscribeKind.KIND -> transcription(stored, job);
            case AnalyzeReportKind.KIND -> analysis(stored, job);
            // 저장(jsonb)이 키 순서를 바꾸므로 계약 순서의 레코드로 다시 읽는다.
            case RecoveryService.KIND -> MAPPER.treeToValue(stored, RecoveryViews.JobResult.class);
            case ExtractEventInputKind.KIND -> MAPPER.treeToValue(stored, AiJobRead.ExtractionResult.class);
            case GeneratePlanKind.KIND -> MAPPER.treeToValue(stored, AiJobRead.PlanResult.class);
            case GenerateChangeKind.KIND -> MAPPER.treeToValue(stored, AiJobRead.ChangeResult.class);
            default -> throw notFound();
        };
    }

    /** 전사문은 AudioAsset에 임시 저장돼 있다(ai_job.result에는 audioId만). 지워졌거나 24시간이 지났으면 404 */
    private AiJobRead.TranscriptionResult transcription(JsonNode stored, JobRow job) {
        UUID audioId = uuidOrNotFound(stored.path("audioId"));
        var rows = jdbc.query("""
                SELECT transcript, transcript_segments::text, duration_sec FROM audio_asset
                 WHERE id = ? AND event_id = ? AND uploader_member_id = ? AND transcript IS NOT NULL
                   AND storage_key IS NOT NULL AND created_at > ?""",
                (r, n) -> new Object[] {r.getString(1), r.getString(2), r.getBigDecimal(3)},
                audioId, job.eventId(), job.requestedBy(), Timestamp.from(Instant.now().minus(AudioAssetService.RETENTION)));
        if (rows.isEmpty()) throw notFound();
        Object[] row = rows.getFirst();
        JsonNode meta = MAPPER.readTree(job.executionMeta() == null ? "{}" : job.executionMeta());
        JsonNode fallback = meta.get("fallbackReason");
        return new AiJobRead.TranscriptionResult(audioId, (String) row[0], (BigDecimal) row[2],
                row[1] == null ? MAPPER.createArrayNode() : MAPPER.readTree((String) row[1]),
                meta.path("pipeline").asString(), fallback == null || fallback.isNull() ? null : fallback.asString());
    }

    /**
     * 분석은 Report.analysis에 있다(ai_job.result에는 reportId만). 같은 보고를 다시 분석해 더 새 분석으로 바뀌었으면 이 작업의
     * 결과가 남아 있지 않으므로 404. 유효성은 이슈 조회와 같이 분석 때 revision이 지금과 같으면 CURRENT, 다르면 STALE이다.
     */
    private AnalysisSummary analysis(JsonNode stored, JobRow job) {
        UUID reportId = uuidOrNotFound(stored.path("reportId"));
        var rows = jdbc.query("""
                SELECT r.analysis::text, e.revision FROM report r JOIN issue i ON i.id = r.issue_id
                  JOIN event e ON e.id = i.event_id
                 WHERE r.id = ? AND i.event_id = ? AND r.analysis IS NOT NULL""",
                (r, n) -> new Object[] {r.getString(1), r.getLong(2)}, reportId, job.eventId());
        if (rows.isEmpty()) throw notFound();
        JsonNode analysis = MAPPER.readTree((String) rows.getFirst()[0]);
        if (!job.id().toString().equals(analysis.path("jobId").asString())) throw notFound();
        long revision = (Long) rows.getFirst()[1];
        List<ZoneCandidate> candidates = new ArrayList<>();
        for (JsonNode c : analysis.path("zoneCandidates"))
            candidates.add(new ZoneCandidate(UUID.fromString(c.get("zoneId").asString()), c.get("source").asString(),
                    UUID.fromString(c.get("sourceRef").asString()), Long.toString(c.get("contextRevision").asLong())));
        return new AnalysisSummary(analysis.path("contextRevision").asLong() == revision ? "CURRENT" : "STALE",
                textOrNull(analysis.get("category")), textOrNull(analysis.get("reportedState")), candidates);
    }

    /**
     * 내부 코드를 공개 코드로 바꾼다. retryable은 AI_UNAVAILABLE이고 저장된 값이 true일 때만 true다(BE06).
     * FE의 자동 새 작업 허가가 아니라 "일시 장애였다"는 표시다.
     */
    static AiJobRead.PublicError publicError(String kind, String code, boolean retryable) {
        String visible;
        if (AiJobError.AI_TIMEOUT.equals(code)) visible = AiJobError.AI_TIMEOUT;
        else if (AiJobError.READ_CONTEXT_CHANGED.equals(code)) visible = STALE_CONTEXT;
        else if (TranscribeKind.KIND.equals(kind) && TRANSCRIBE_VISIBLE.contains(code)) visible = code;
        else if (TranscribeKind.KIND.equals(kind) && TRANSCRIBE_AUDIO_GONE.contains(code)) visible = "AUDIO_INVALID";
        else if ((GeneratePlanKind.KIND.equals(kind) || GenerateChangeKind.KIND.equals(kind)) && PLAN_VISIBLE.contains(code))
            visible = code;
        else visible = AiJobError.AI_UNAVAILABLE;
        boolean publicRetryable = AiJobError.AI_UNAVAILABLE.equals(visible) && AiJobError.AI_UNAVAILABLE.equals(code)
                && retryable;
        return new AiJobRead.PublicError(visible, publicRetryable);
    }

    private static AiJobRead.PublicError publicError(JobRow job) {
        JsonNode error = MAPPER.readTree(job.error() == null ? "{}" : job.error());
        return publicError(job.kind(), error.path("code").asString(""), error.path("retryable").asBoolean(false));
    }

    /** 저장된 결과의 ID가 없거나 형식이 다르면(옛 형식 등) 결과를 만들 수 없으므로 404 */
    private static UUID uuidOrNotFound(JsonNode node) {
        try {
            return UUID.fromString(node.asString(""));
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND);
    }
}

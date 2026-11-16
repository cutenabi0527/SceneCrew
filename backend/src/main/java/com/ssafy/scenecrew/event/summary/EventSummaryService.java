package com.ssafy.scenecrew.event.summary;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.aijob.client.AiClient;
import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.client.AiTransportException;
import com.ssafy.scenecrew.aijob.pipeline.AiJobGate;
import com.ssafy.scenecrew.aijob.pipeline.AiJobInputs;
import com.ssafy.scenecrew.aijob.pipeline.AiJobProperties;
import com.ssafy.scenecrew.event.domain.Event;
import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.EventState;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.repository.EventRepository;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;

/**
 * SC-API-080 POST /events/{e}/summary. 종료된 행사의 요약을 GMS 한 번으로 만들어 이 응답으로만 돌려준다(S15P31A202-269).
 * 규칙은 backend/docs/event-summary.md.
 *
 * <ol>
 *   <li>짧은 트랜잭션: 행사 배타 잠금·활성 Member(404) → 같은 요청 키 기록 확인(재요청 처리) → 총괄(403)·종료 상태(409)·
 *       기대 revision(409)·외부 AI 동의(403) → <b>외부 호출 전에</b> 시도 기록(ai_job, SUMMARIZE_EVENT, RUNNING, result 없음)을 커밋한다.</li>
 *   <li>트랜잭션 밖에서 AI 서버(/internal/summarize-event)를 한 번 부른다. 자동 재시도하지 않는다.</li>
 *   <li>짧은 트랜잭션: 성공이면 SUCCEEDED, 실패면 FAILED와 오류 코드만 기록한다. 요약문은 DB·로그 어디에도 남기지 않는다.</li>
 * </ol>
 *
 * 같은 요청 키(계정·080·키)의 재요청은 외부 AI를 다시 부르지 않는다. 성공했던 요청은 410 TEXT_NOT_RETAINED,
 * 아직 처리 중이면 409 REQUEST_IN_PROGRESS, 도착 여부를 모르는 실패(응답 시간 초과·서버 중단)는 409 IDEMPOTENCY_OUTCOME_UNKNOWN,
 * 확실히 실패한 요청은 처음과 같은 503이다. 키가 같은데 본문(기대 revision)이 다르면 409 IDEMPOTENCY_MISMATCH다.
 */
@Service
public class EventSummaryService {
    public static final String OPERATION = "SC-API-080";
    static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(120);
    static final int MAX_TEXT = 4000;
    /** AI가 스스로 보낸 실패(확실한 실패). 이 사유가 없는 AI_TIMEOUT은 도착 여부를 모르는 실패다. */
    static final String AI_REPORTED = "AI_ERROR";
    private static final Logger log = LoggerFactory.getLogger(EventSummaryService.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 080 응답(계약 Summary). 요약문은 이 객체로만 나간다. */
    @JsonPropertyOrder({"text", "format", "contextRevision"})
    public record Summary(String text, String format, String contextRevision) { }

    private record Attempt(UUID jobId, String inputHash, long contextRevision, Instant deadline, String envelope) { }

    private final EventRepository events;
    private final CurrentMembers members;
    private final AiJobGate gate;
    private final SummarizeEventKind kind;
    private final AiClient client;
    private final AiJobProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;

    public EventSummaryService(EventRepository events, CurrentMembers members, AiJobGate gate, SummarizeEventKind kind,
            AiClient client, AiJobProperties properties, JdbcTemplate jdbc, TransactionTemplate transaction,
            JsonMapper jsonMapper) {
        this.events = events;
        this.members = members;
        this.gate = gate;
        this.kind = kind;
        this.client = client;
        this.properties = properties;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.jsonMapper = jsonMapper;
    }

    public Summary summarize(UUID eventId, UUID accountId, String key, long expectedRevision) {
        Attempt attempt = transaction.execute(s -> begin(eventId, accountId, key, expectedRevision));
        AiResponse response;
        try {
            Duration left = Duration.between(Instant.now(), attempt.deadline());
            response = client.post(SummarizeEventKind.PATH, attempt.envelope(), left.isNegative() ? Duration.ofSeconds(1) : left);
        } catch (AiTransportException e) {
            // 연결조차 못 했으면 확실한 실패, 응답 시간 초과면 AI가 받았는지 모른다.
            finish(attempt.jobId(), null, error(e.isTimeout() ? "AI_TIMEOUT" : "AI_UNAVAILABLE",
                    e.isTimeout() ? "AI_RESPONSE_TIMEOUT" : "NOT_DELIVERED"));
            log.warn("종료 요약 AI 호출 실패: jobId={} timeout={}", attempt.jobId(), e.isTimeout());
            throw unavailable();
        } catch (InterruptedException e) {
            // 서버 종료 중. RUNNING으로 두면 공통 복구가 마감 뒤 AI_TIMEOUT으로 끝낸다(재요청은 OUTCOME_UNKNOWN).
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        Optional<Summary> summary = read(attempt, response);
        if (summary.isEmpty()) throw unavailable();
        return summary.get();
    }

    // ---- 1단계: 확인과 시도 기록 ------------------------------------------------------------------------------

    private Attempt begin(UUID eventId, UUID accountId, String key, long expectedRevision) {
        Event event = events.findForUpdate(eventId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Member me = members.requireLocked(eventId, accountId);
        String trigger = AiJobInputs.sha256(OPERATION + ":" + accountId + ":" + key.toLowerCase());
        String requestHash = IdempotencyRequest.hash(eventId, expectedRevision);
        replay(eventId, trigger, requestHash);

        if (me.getEventRole() != EventRole.CHIEF) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (event.getState() != EventState.ENDED)
            throw new BusinessException(ErrorCode.INVALID_STATE, "종료된 행사에서만 요약을 만들 수 있습니다.");
        if (event.getRevision() != expectedRevision)
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                    Map.of("target", "event"));
        Instant now = Instant.now();
        gate.checkAccept(eventId, me.getId(), kind, now);   // 외부 AI 동의(403)

        UUID jobId = UUID.randomUUID();
        String input = AiJobInputs.canonicalJson(jsonMapper, kind.input(eventId));
        String inputHash = AiJobInputs.sha256(input);
        Instant deadline = now.plus(properties.deadline(SummarizeEventKind.KIND).orElse(DEFAULT_DEADLINE));
        jdbc.update("""
                INSERT INTO ai_job (id, event_id, requested_by_member_id, kind, state, input_hash, context_revision,
                                    request_schema_version, input, attempt, execution_meta, trigger_key, auto_reanalysis_count,
                                    created_at, first_started_at, deadline_at, lease_until)
                VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, '4', ?::jsonb, 1, ?::jsonb, ?, 0, ?, ?, ?, ?)""",
                jobId, eventId, me.getId(), SummarizeEventKind.KIND, inputHash, event.getRevision(), input,
                MAPPER.writeValueAsString(Map.of("requestHash", requestHash)), trigger, ts(now), ts(now), ts(deadline),
                ts(deadline.plus(properties.runGrace())));

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jobId", jobId.toString());
        envelope.put("attempt", 1);
        envelope.put("requestSchemaVersion", "4");
        envelope.put("kind", SummarizeEventKind.KIND);
        envelope.put("inputHash", inputHash);
        envelope.put("contextRevision", event.getRevision());
        envelope.put("deadlineAt", deadline.toString());
        envelope.put("payload", MAPPER.readTree(input));
        return new Attempt(jobId, inputHash, event.getRevision(), deadline, MAPPER.writeValueAsString(envelope));
    }

    /** 같은 계정·키로 이미 시도했으면 외부 AI를 다시 부르지 않고 기록만 보고 답한다. */
    private void replay(UUID eventId, String trigger, String requestHash) {
        List<String[]> rows = jdbc.query("""
                SELECT state, execution_meta ->> 'requestHash', error ->> 'code', deadline_at > now(),
                       error -> 'details' ->> 'reason' FROM ai_job
                 WHERE event_id = ? AND kind = ? AND trigger_key = ?""",
                (r, n) -> new String[] {r.getString(1), r.getString(2), r.getString(3), Boolean.toString(r.getBoolean(4)),
                        r.getString(5)},
                eventId, SummarizeEventKind.KIND, trigger);
        if (rows.isEmpty()) return;
        String[] row = rows.getFirst();
        if (!requestHash.equals(row[1])) throw new BusinessException(ErrorCode.IDEMPOTENCY_MISMATCH);
        switch (row[0]) {
            case "SUCCEEDED" -> throw new BusinessException(ErrorCode.TEXT_NOT_RETAINED);
            case "RUNNING", "QUEUED" -> throw new BusinessException(Boolean.parseBoolean(row[3])
                    ? ErrorCode.REQUEST_IN_PROGRESS : ErrorCode.IDEMPOTENCY_OUTCOME_UNKNOWN);
            // 응답 시간 초과·서버 중단(공통 복구의 AI_TIMEOUT)은 AI가 요약을 만들었는지 모른다. AI가 보낸 실패·연결 실패는 확실한 실패다.
            default -> throw "AI_TIMEOUT".equals(row[2]) && !AI_REPORTED.equals(row[4])
                    ? new BusinessException(ErrorCode.IDEMPOTENCY_OUTCOME_UNKNOWN) : unavailable();
        }
    }

    // ---- 3단계: 응답 검사와 기록 ------------------------------------------------------------------------------

    /** 계약대로면 SUCCEEDED를 기록하고 요약을 돌려준다. 어기면 FAILED를 기록하고 비운다. 요약문은 기록하지 않는다. */
    private Optional<Summary> read(Attempt attempt, AiResponse response) {
        JsonNode body;
        try {
            body = MAPPER.readTree(response.body() == null ? "" : response.body());
        } catch (JacksonException e) {
            body = null;
        }
        if (body == null || !body.isObject() || !sameIdentity(attempt, body)) {
            finish(attempt.jobId(), null, error("AI_UNAVAILABLE", "INVALID_RESULT"));
            log.warn("종료 요약 AI 응답 형식 오류: jobId={} status={}", attempt.jobId(), response.status());
            return Optional.empty();
        }
        if (body.has("error")) {
            String code = body.path("error").path("code").asString("AI_UNAVAILABLE");
            finish(attempt.jobId(), null, error(code, AI_REPORTED));
            log.warn("종료 요약 AI 실패: jobId={} code={}", attempt.jobId(), code);
            return Optional.empty();
        }
        JsonNode result = body.path("result");
        String text = result.path("text").isString() ? result.get("text").asString().strip() : "";
        String format = result.path("format").asString("");
        if (!"2".equals(body.path("resultSchemaVersion").asString("")) || text.isEmpty()
                || text.codePointCount(0, text.length()) > MAX_TEXT || !(format.equals("MARKDOWN") || format.equals("TEXT"))) {
            finish(attempt.jobId(), null, error("AI_UNAVAILABLE", "INVALID_RESULT"));
            log.warn("종료 요약 AI 결과 형식 오류: jobId={}", attempt.jobId());
            return Optional.empty();
        }
        finish(attempt.jobId(), body.path("modelVersion").asString(null), null);
        return Optional.of(new Summary(text, format, Long.toString(attempt.contextRevision())));
    }

    private static boolean sameIdentity(Attempt attempt, JsonNode body) {
        return attempt.jobId().toString().equals(body.path("jobId").asString(""))
                && body.path("attempt").asLong(-1) == 1
                && "4".equals(body.path("requestSchemaVersion").asString(""))
                && attempt.inputHash().equals(body.path("inputHash").asString(""))
                && body.path("contextRevision").asLong(-1) == attempt.contextRevision();
    }

    /** 결과(요약문)는 기록하지 않는다. error가 null이면 성공이다. 이미 공통 복구가 끝낸 작업은 건드리지 않는다. */
    private void finish(UUID jobId, String modelVersion, Map<String, Object> error) {
        jdbc.update("""
                UPDATE ai_job SET state = ?, result = NULL, error = ?::jsonb, model_version = ?, result_schema_version = ?,
                                  finished_at = now(), lease_until = NULL
                 WHERE id = ? AND state = 'RUNNING'""",
                error == null ? "SUCCEEDED" : "FAILED", error == null ? null : MAPPER.writeValueAsString(error),
                modelVersion, error == null ? "2" : null, jobId);
    }

    /** 저장하는 오류. code가 AI_TIMEOUT이면 도착 여부를 모르는 실패로 본다. */
    private static Map<String, Object> error(String code, String reason) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", "종료 요약을 만들지 못했습니다.");
        e.put("retryable", false);
        e.put("details", Map.of("reason", reason));
        return e;
    }

    private static Timestamp ts(Instant at) {
        return Timestamp.from(at);
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "요약을 만들지 못했습니다. 잠시 후 새로 요청해 주세요.");
    }
}

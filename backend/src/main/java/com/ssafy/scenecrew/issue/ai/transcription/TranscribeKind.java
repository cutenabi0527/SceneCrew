package com.ssafy.scenecrew.issue.ai.transcription;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobError;
import com.ssafy.scenecrew.aijob.kind.AiJobKind;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ssafy.scenecrew.event.repository.MemberRepository;
import com.ssafy.scenecrew.issue.domain.AudioAsset;
import com.ssafy.scenecrew.issue.repository.AudioAssetRepository;
import com.ssafy.scenecrew.issue.service.AudioAssetService;

/**
 * 음성 전사(TRANSCRIBE, 요청4/결과2). 기준: docs/planning/records/STT-CONTRACT-TRANSITION.md.
 *
 * <ul>
 *   <li>scope: 서버가 검증한 eventId·uploaderMemberId(Q17). 동의 여부는 값으로 보내지 않는다.</li>
 *   <li>계획(Event.revision)이 바뀌어도 전사문을 버리지 않는다(Q7, V9-D18). 사용자가 확인·수정한 뒤 제출하기 때문이다.</li>
 *   <li>성공 결과의 text·segments는 AudioAsset.transcript·transcriptSegments에 임시 저장하고, ai_job.result에는 audioId만 남긴다(Q10·Q22).</li>
 *   <li>executionMeta(소음 처리 경로)는 ai_job.execution_meta에 저장한다. pipeline이 없으면 추측하지 않고 INVALID_RESULT다(Q19).</li>
 *   <li>AI의 실패 코드(AUDIO_INVALID·NO_SPEECH·AI_BUSY 등)는 그대로 ai_job.error에 남긴다. 공개 코드 변환은 조회 API(#235)에서 한다.</li>
 * </ul>
 */
@Component
public class TranscribeKind implements AiJobKind {
    public static final String KIND = "TRANSCRIBE";
    static final String RESULT_SCHEMA_VERSION = "2";
    private static final Set<String> PIPELINES = Set.of("DENOISED", "RAW");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final AudioAssetRepository audioAssets;
    private final MemberRepository members;
    private final JdbcTemplate jdbc;

    public TranscribeKind(AudioAssetRepository audioAssets, MemberRepository members, JdbcTemplate jdbc) {
        this.audioAssets = audioAssets;
        this.members = members;
        this.jdbc = jdbc;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String path() {
        return "/internal/transcribe";
    }

    @Override
    public String resource() {
        return "STT";
    }

    @Override
    public boolean requiresCurrentContext() {
        return false;
    }

    @Override
    public JsonNode scope(AiJob job) {
        ObjectNode scope = MAPPER.createObjectNode();
        scope.put("eventId", job.getEventId().toString());
        scope.put("uploaderMemberId", job.getRequestedByMemberId().toString());
        return scope;
    }

    @Override
    public AiJobOutcome read(AiJobEnvelope request, AiResponse response) {
        JsonNode body = MAPPER.readTree(response.body());
        Optional<String> identity = identityMismatch(request, body);
        if (identity.isPresent()) {
            return AiJobOutcome.failed(AiJobError.invalidResult("요청과 다른 작업의 응답입니다: " + identity.get()));
        }
        if (body.has("error")) {
            return AiJobOutcome.failed(aiError(body.get("error")));
        }
        if (!RESULT_SCHEMA_VERSION.equals(text(body.get("resultSchemaVersion")))) {
            return invalid("resultSchemaVersion이 2가 아닙니다.");
        }
        String modelVersion = text(body.get("modelVersion"));
        if (modelVersion == null || modelVersion.isBlank()) {
            return invalid("modelVersion이 없습니다.");
        }
        JsonNode result = body.get("result");
        if (result == null || !result.isObject()) {
            return invalid("result가 없습니다.");
        }
        String audioId = request.payload().path("audioId").asString();
        if (!audioId.equals(text(result.get("audioId")))) {
            return invalid("result.audioId가 요청과 다릅니다.");
        }
        String transcript = text(result.get("text"));
        if (transcript == null || transcript.isBlank()) {
            return invalid("result.text가 없습니다.");
        }
        ArrayNode segments = segments(result.get("segments"));
        if (segments == null) {
            return invalid("result.segments 형식이 올바르지 않습니다.");
        }
        if (!nonNegativeNumber(result.get("durationSec"))) {
            return invalid("result.durationSec 형식이 올바르지 않습니다.");
        }
        ObjectNode meta = executionMeta(body.get("executionMeta"));
        if (meta == null) {
            return invalid("executionMeta.pipeline이 없거나 형식이 올바르지 않습니다.");
        }
        // 사용자가 "원음으로 다시 전사"를 고른 작업은 결과도 원음 경로여야 한다(§6.1).
        if ("RAW".equals(request.payload().path("options").path("pipeline").asString())
                && !"RAW".equals(meta.get("pipeline").asString())) {
            return invalid("원음 전사를 요청했는데 executionMeta.pipeline이 RAW가 아닙니다.");
        }
        ObjectNode accepted = MAPPER.createObjectNode();
        accepted.put("audioId", audioId);
        accepted.put("text", transcript);
        accepted.set("segments", segments);
        return AiJobOutcome.succeeded(RESULT_SCHEMA_VERSION, modelVersion, MAPPER.writeValueAsString(accepted))
                .withExecutionMeta(MAPPER.writeValueAsString(meta));
    }

    /**
     * 반영 직전 다시 확인한다(Q6). 음성이 지워졌거나 보관 기간이 지났으면 AUDIO_DELETED, 업로더가 이 행사의 활성 참여자가 아니면
     * UPLOADER_INACTIVE, 업로더가 그사이 음성 동의를 철회했으면 CONSENT_WITHDRAWN으로 끝내고 전사문을 기록하지 않는다(#229, V9-D40).
     * 탈퇴한 계정은 참여가 끝나고 원본이 지워지므로 앞의 두 검사에서 막힌다.
     */
    @Override
    public AiJobOutcome apply(AiJob job, AiJobOutcome outcome) {
        JsonNode result = MAPPER.readTree(outcome.resultJson());
        UUID audioId = UUID.fromString(result.get("audioId").asString());
        AudioAsset asset = audioAssets.findForUpdate(audioId).orElse(null);
        if (asset == null || !asset.getEventId().equals(job.getEventId())
                || !asset.getUploaderMemberId().equals(job.getRequestedByMemberId())
                || !asset.isOriginalAvailable(Instant.now(), AudioAssetService.RETENTION)) {
            return rejected(outcome, "AUDIO_DELETED", "음성이 삭제되었거나 보관 기간이 지났습니다.");
        }
        boolean activeUploader = members.findById(asset.getUploaderMemberId())
                .filter(m -> m.getActive() && m.getEventId().equals(asset.getEventId()))
                .isPresent();
        if (!activeUploader) {
            return rejected(outcome, "UPLOADER_INACTIVE", "음성을 올린 참여자가 더 이상 이 행사에 참여하지 않습니다.");
        }
        Boolean voice = jdbc.queryForObject("""
                SELECT coalesce(a.consent -> 'voice' = 'true'::jsonb, false)
                  FROM member m JOIN account a ON a.id = m.account_id WHERE m.id = ?
                """, Boolean.class, asset.getUploaderMemberId());
        if (!Boolean.TRUE.equals(voice)) {
            return rejected(outcome, "CONSENT_WITHDRAWN", "음성을 올린 사용자가 음성 사용 동의를 철회했습니다.");
        }
        asset.recordTranscript(result.get("text").asString(), MAPPER.writeValueAsString(result.get("segments")));
        ObjectNode stored = MAPPER.createObjectNode();
        stored.put("audioId", audioId.toString());
        return outcome.withResultJson(MAPPER.writeValueAsString(stored));
    }

    private static AiJobOutcome rejected(AiJobOutcome outcome, String code, String message) {
        return AiJobOutcome.failed(new AiJobError(code, message, false, Map.of()))
                .withExecutionMeta(outcome.executionMetaJson());
    }

    /** 성공·접수 후 실패 모두 요청의 식별 필드를 그대로 돌려줘야 한다. 다르면 어느 필드인지 돌려준다. */
    private static Optional<String> identityMismatch(AiJobEnvelope request, JsonNode body) {
        if (!request.jobId().toString().equals(text(body.get("jobId")))) {
            return Optional.of("jobId");
        }
        JsonNode attempt = body.get("attempt");
        if (attempt == null || !attempt.isIntegralNumber() || attempt.asLong() != request.attempt()) {
            return Optional.of("attempt");
        }
        if (!request.requestSchemaVersion().equals(text(body.get("requestSchemaVersion")))) {
            return Optional.of("requestSchemaVersion");
        }
        if (!request.inputHash().equals(text(body.get("inputHash")))) {
            return Optional.of("inputHash");
        }
        JsonNode revision = body.get("contextRevision");
        if (revision == null || !revision.isIntegralNumber() || revision.asLong() != request.contextRevision()) {
            return Optional.of("contextRevision");
        }
        return Optional.empty();
    }

    /** AI가 보낸 error{code,message,retryable,details}를 그대로 옮긴다. 형식이 틀리면 계약 위반이다. */
    private static AiJobError aiError(JsonNode error) {
        String code = text(error == null ? null : error.get("code"));
        JsonNode retryable = error == null ? null : error.get("retryable");
        if (code == null || code.isBlank() || retryable == null || !retryable.isBoolean()) {
            return AiJobError.invalidResult("AI 오류 응답 형식이 올바르지 않습니다.");
        }
        String message = text(error.get("message"));
        Map<String, Object> details = new LinkedHashMap<>();
        JsonNode detailsNode = error.get("details");
        if (detailsNode != null && detailsNode.isObject()) {
            for (var entry : detailsNode.properties()) {
                details.put(entry.getKey(), MAPPER.convertValue(entry.getValue(), Object.class));
            }
        }
        return new AiJobError(code, message == null ? "" : message, retryable.asBoolean(), details);
    }

    /** [{startSec, endSec, text}]. 시간은 0 이상이고 startSec ≤ endSec. 저장할 세 필드만 남긴다. */
    private static ArrayNode segments(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        ArrayNode result = MAPPER.createArrayNode();
        for (int i = 0; i < node.size(); i++) {
            JsonNode segment = node.get(i);
            if (!segment.isObject() || !nonNegativeNumber(segment.get("startSec"))
                    || !nonNegativeNumber(segment.get("endSec")) || text(segment.get("text")) == null
                    || segment.get("startSec").asDouble() > segment.get("endSec").asDouble()) {
                return null;
            }
            ObjectNode copy = result.addObject();
            copy.set("startSec", segment.get("startSec"));
            copy.set("endSec", segment.get("endSec"));
            copy.put("text", segment.get("text").asString());
        }
        return result;
    }

    /** {pipeline, denoiseModel, processingMs, fallbackReason}. 전사문·음성은 넣지 않는다. */
    private static ObjectNode executionMeta(JsonNode node) {
        String pipeline = node == null || !node.isObject() ? null : text(node.get("pipeline"));
        if (pipeline == null || !PIPELINES.contains(pipeline)) {
            return null;
        }
        JsonNode denoiseModel = node.get("denoiseModel");
        JsonNode processingMs = node.get("processingMs");
        JsonNode fallbackReason = node.get("fallbackReason");
        if (!nullOrText(denoiseModel) || !nullOrText(fallbackReason)
                || processingMs == null || !processingMs.isIntegralNumber() || processingMs.asLong() < 0) {
            return null;
        }
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("pipeline", pipeline);
        putTextOrNull(meta, "denoiseModel", denoiseModel);
        meta.put("processingMs", processingMs.asLong());
        putTextOrNull(meta, "fallbackReason", fallbackReason);
        return meta;
    }

    private static void putTextOrNull(ObjectNode target, String field, JsonNode value) {
        if (value == null || value.isNull()) {
            target.putNull(field);
        } else {
            target.put(field, value.asString());
        }
    }

    private static AiJobOutcome invalid(String message) {
        return AiJobOutcome.failed(AiJobError.invalidResult(message));
    }

    private static boolean nonNegativeNumber(JsonNode node) {
        return node != null && node.isNumber() && node.asDouble() >= 0;
    }

    private static boolean nullOrText(JsonNode node) {
        return node == null || node.isNull() || node.isString();
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }
}

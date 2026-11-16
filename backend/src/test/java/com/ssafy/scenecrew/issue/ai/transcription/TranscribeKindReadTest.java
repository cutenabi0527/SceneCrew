package com.ssafy.scenecrew.issue.ai.transcription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.domain.AiJobState;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;

/** 결과2·실패 응답 해석(STT 명세 §4·§6·§7). DB 없이 TranscribeKind.read만 확인한다. */
class TranscribeKindReadTest {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final UUID JOB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID AUDIO = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final String HASH = "a".repeat(64);

    private final TranscribeKind kind = new TranscribeKind(null, null, null);
    private final AiJobEnvelope request = new AiJobEnvelope(JOB, 1, "4", "TRANSCRIBE", HASH, 12,
            Instant.parse("2026-11-05T12:01:30Z"), null, MAPPER.readTree("{\"audioId\":\"" + AUDIO + "\"}"));

    @Test
    void successKeepsTextAndSegmentsForApplyAndExecutionMeta() {
        AiJobOutcome outcome = kind.read(request, response(success()));

        assertThat(outcome.state()).isEqualTo(AiJobState.SUCCEEDED);
        assertThat(outcome.resultSchemaVersion()).isEqualTo("2");
        assertThat(outcome.modelVersion()).isEqualTo("faster-whisper-large-v3-int8");
        JsonNode result = MAPPER.readTree(outcome.resultJson());
        assertThat(result.get("text").asString()).isEqualTo("프로젝터가 꺼졌습니다.");
        assertThat(result.get("segments").size()).isEqualTo(1);
        JsonNode meta = MAPPER.readTree(outcome.executionMetaJson());
        assertThat(meta.get("pipeline").asString()).isEqualTo("RAW");
        assertThat(meta.get("fallbackReason").asString()).isEqualTo("DENOISE_FAILED");
        assertThat(meta.get("denoiseModel").isNull()).isTrue();
    }

    @Test
    void missingPipelineIsInvalidResultInsteadOfGuessing() {
        ObjectNode body = success();
        ((ObjectNode) body.get("executionMeta")).remove("pipeline");

        assertInvalid(kind.read(request, response(body)));
        ObjectNode noMeta = success();
        noMeta.remove("executionMeta");
        assertInvalid(kind.read(request, response(noMeta)));
    }

    @Test
    void rawRequestMustBeAnsweredWithRawPipeline() {
        AiJobEnvelope rawRequest = new AiJobEnvelope(JOB, 1, "4", "TRANSCRIBE", HASH, 12,
                Instant.parse("2026-11-05T12:01:30Z"), null,
                MAPPER.readTree("{\"audioId\":\"" + AUDIO + "\",\"options\":{\"pipeline\":\"RAW\"}}"));
        ObjectNode denoised = success();
        ((ObjectNode) denoised.get("executionMeta")).put("pipeline", "DENOISED");
        ((ObjectNode) denoised.get("executionMeta")).putNull("fallbackReason");

        assertInvalid(kind.read(rawRequest, response(denoised)));
        assertThat(kind.read(rawRequest, response(success())).state()).isEqualTo(AiJobState.SUCCEEDED);
        // 원음을 요청하지 않은 작업은 소음 처리 경로 결과도 정상이다.
        assertThat(kind.read(request, response(denoised)).state()).isEqualTo(AiJobState.SUCCEEDED);
    }

    @Test
    void identityOrAudioMismatchIsInvalidResult() {
        for (String field : new String[] {"jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision"}) {
            ObjectNode body = success();
            if (field.equals("attempt") || field.equals("contextRevision")) {
                body.put(field, 99);
            } else {
                body.put(field, "other");
            }
            assertInvalid(kind.read(request, response(body)));
        }
        ObjectNode otherAudio = success();
        ((ObjectNode) otherAudio.get("result")).put("audioId", UUID.randomUUID().toString());
        assertInvalid(kind.read(request, response(otherAudio)));
    }

    @Test
    void malformedResultIsInvalid() {
        ObjectNode emptyText = success();
        ((ObjectNode) emptyText.get("result")).put("text", " ");
        assertInvalid(kind.read(request, response(emptyText)));

        ObjectNode reversed = success();
        ((ObjectNode) reversed.get("result").get("segments").get(0)).put("startSec", 3.0);
        assertInvalid(kind.read(request, response(reversed)));

        ObjectNode oldSchema = success();
        oldSchema.put("resultSchemaVersion", "1");
        assertInvalid(kind.read(request, response(oldSchema)));
    }

    @Test
    void aiFailureCodesAreKeptAsIs() {
        AiJobOutcome audioInvalid = kind.read(request, response(failure(
                "{\"code\":\"AUDIO_INVALID\",\"message\":\"음성 길이가 최대 30초를 넘습니다.\",\"retryable\":false,"
                        + "\"details\":{\"reason\":\"TOO_LONG\",\"maxSeconds\":30}}")));
        assertThat(audioInvalid.state()).isEqualTo(AiJobState.FAILED);
        assertThat(audioInvalid.error().code()).isEqualTo("AUDIO_INVALID");
        assertThat(audioInvalid.error().details()).containsEntry("reason", "TOO_LONG");
        assertThat(audioInvalid.retryable()).isFalse();

        AiJobOutcome busy = kind.read(request, response(failure(
                "{\"code\":\"AI_BUSY\",\"message\":\"다른 전사 처리 중\",\"retryable\":true}")));
        assertThat(busy.error().code()).isEqualTo("AI_BUSY");
        assertThat(busy.retryable()).isTrue();

        AiJobOutcome noRetryable = kind.read(request, response(failure("{\"code\":\"NO_SPEECH\",\"message\":\"\"}")));
        assertInvalid(noRetryable);
    }

    @Test
    void scopeIsNotPartOfRequestIdentity() {
        // scope는 요청에만 있고 응답에서 비교하지 않는다(AI는 식별 5필드만 돌려준다).
        assertThat(kind.read(request, response(success())).state()).isEqualTo(AiJobState.SUCCEEDED);
    }

    static ObjectNode success() {
        return (ObjectNode) MAPPER.readTree("""
                {"jobId": "%s", "attempt": 1, "requestSchemaVersion": "4", "inputHash": "%s", "contextRevision": 12,
                 "resultSchemaVersion": "2", "modelVersion": "faster-whisper-large-v3-int8",
                 "result": {"audioId": "%s", "text": "프로젝터가 꺼졌습니다.",
                            "segments": [{"startSec": 0.0, "endSec": 2.0, "text": "프로젝터가 꺼졌습니다."}],
                            "durationSec": 2.4},
                 "executionMeta": {"pipeline": "RAW", "denoiseModel": null, "processingMs": 8400,
                                   "fallbackReason": "DENOISE_FAILED"}}
                """.formatted(JOB, HASH, AUDIO));
    }

    static ObjectNode failure(String error) {
        return (ObjectNode) MAPPER.readTree("""
                {"jobId": "%s", "attempt": 1, "requestSchemaVersion": "4", "inputHash": "%s", "contextRevision": 12,
                 "error": %s}
                """.formatted(JOB, HASH, error));
    }

    static AiResponse response(JsonNode body) {
        return new AiResponse(200, MAPPER.writeValueAsString(body));
    }

    static void assertInvalid(AiJobOutcome outcome) {
        assertThat(outcome.state()).isEqualTo(AiJobState.FAILED);
        assertThat(outcome.error().details()).containsEntry("reason", "INVALID_RESULT");
    }
}

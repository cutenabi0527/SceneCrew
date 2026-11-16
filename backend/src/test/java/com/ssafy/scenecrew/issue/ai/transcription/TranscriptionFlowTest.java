package com.ssafy.scenecrew.issue.ai.transcription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.aijob.client.AiClient;
import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.outbox.OutboxRelay;
import com.ssafy.scenecrew.support.FakeAudioProbe;
import com.ssafy.scenecrew.support.FakeFileStorage;
import com.ssafy.scenecrew.support.FileTestBeans;
import com.ssafy.scenecrew.support.TestRows;

/**
 * 전사 접수 → 요청4 전송 → 결과2 반영과 AI 서버용 음성 다운로드. 실제 AI 대신 FakeStt가 답한다.
 * 실제 STT 서버와의 연동은 #270에서 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "scenecrew.ai.transcription.audio-base-url=https://app.example.invalid/",
        "scenecrew.ai.server.service-token=test-service-token"})
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import({FileTestBeans.class, TranscriptionFlowTest.TestBeans.class})
class TranscriptionFlowTest {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2, 3, 4};
    static final String TOKEN = "test-service-token";

    @LocalServerPort
    private int port;

    @Autowired
    private TranscriptionService service;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private com.ssafy.scenecrew.issue.service.AudioAssetService audioAssets;

    @Autowired
    private FakeStt ai;

    @Autowired
    private FakeFileStorage storage;

    @Autowired
    private FakeAudioProbe probe;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID eventId;
    private UUID memberId;

    @BeforeEach
    void setUp() {
        ai.reset();
        storage.reset();
        probe.reset();
        TestRows rows = new TestRows(jdbc);
        eventId = rows.event("LIVE");
        UUID accountId = rows.account();
        // 전사 결과 반영은 업로더의 현재 음성 동의를 다시 본다(#229).
        jdbc.update("update account set consent = '{\"voice\":true}'::jsonb where id = ?", accountId);
        memberId = rows.member(eventId, accountId, "WORKER", true);
        jdbc.update("insert into zone (id, event_id, name, description, aliases) values (?, ?, 'A구역', '', '[]'::jsonb)",
                UUID.randomUUID(), eventId);
        jdbc.update("insert into work_group (id, event_id, name, instructions, active) values (?, ?, '음향팀', '', true)",
                UUID.randomUUID(), eventId);
        jdbc.update("insert into work_group (id, event_id, name, instructions, active) values (?, ?, '해산한 그룹', '', false)",
                UUID.randomUUID(), eventId);
        jdbc.update("insert into work_group (id, event_id, name, instructions, active) values (?, ?, ?, '', true)",
                UUID.randomUUID(), eventId, "가".repeat(31));
    }

    @Test
    void submitStoresAudioAndQueuesJobWithPayloadHintsAndHash() {
        var submitted = service.submit(eventId, memberId, WEBM, false);

        Map<String, Object> job = job(submitted.jobId());
        assertThat(job).containsEntry("kind", "TRANSCRIBE").containsEntry("state", "QUEUED")
                .containsEntry("requested_by_member_id", memberId).containsEntry("context_revision", 1L);
        JsonNode input = MAPPER.readTree(job.get("input").toString());
        assertThat(input.get("audioId").asString()).isEqualTo(submitted.audioId().toString());
        assertThat(input.get("audioUrl").asString())
                .isEqualTo("https://app.example.invalid/internal/audio-assets/" + submitted.audioId());
        assertThat(input.get("audioSha256").asString()).isEqualTo(FakeFileStorage.sha256(WEBM));
        assertThat(input.get("hints").get("zones").get(0).asString()).isEqualTo("A구역");
        assertThat(input.get("hints").get("groups").size()).isEqualTo(1);
        assertThat(input.get("hints").get("equipment").size()).isZero();
        assertThat(input.has("options")).isFalse();

        var raw = service.submit(eventId, memberId, WEBM, true);
        assertThat(MAPPER.readTree(job(raw.jobId()).get("input").toString()).get("options").get("pipeline").asString())
                .isEqualTo("RAW");
    }

    @Test
    void successRecordsTranscriptAndSegmentsAndKeepsOnlyAudioIdInJob() {
        var submitted = service.submit(eventId, memberId, WEBM, false);
        ai.answer(request -> success(request, "DENOISED"));

        relay.relayOnce();
        awaitState(submitted.jobId(), "SUCCEEDED");

        // 앞 시험에서 접수만 한 작업도 같이 보내질 수 있어 이 작업의 요청을 jobId로 찾는다.
        JsonNode sent = ai.calls.stream()
                .filter(call -> submitted.jobId().toString().equals(call.get("jobId").asString()))
                .findFirst().orElseThrow();
        assertThat(sent.get("requestSchemaVersion").asString()).isEqualTo("4");
        assertThat(sent.get("kind").asString()).isEqualTo("TRANSCRIBE");
        assertThat(sent.get("scope").get("eventId").asString()).isEqualTo(eventId.toString());
        assertThat(sent.get("scope").get("uploaderMemberId").asString()).isEqualTo(memberId.toString());
        Map<String, Object> job = job(submitted.jobId());
        assertThat(MAPPER.readTree(job.get("result").toString()).propertyNames())
                .containsExactly("audioId");
        assertThat(MAPPER.readTree(job.get("execution_meta").toString()).get("pipeline").asString())
                .isEqualTo("DENOISED");
        Map<String, Object> audio = audio(submitted.audioId());
        assertThat(audio).containsEntry("transcript", "프로젝터가 꺼졌습니다.");
        assertThat(MAPPER.readTree(audio.get("transcript_segments").toString()).get(0).get("endSec").asDouble())
                .isEqualTo(2.0);
    }

    @Test
    void planChangeDuringTranscriptionDoesNotDiscardTranscript() {
        var submitted = service.submit(eventId, memberId, WEBM, false);
        ai.answer(request -> {
            jdbc.update("update event set revision = revision + 1 where id = ?", eventId);
            return success(request, "DENOISED");
        });

        relay.relayOnce();
        awaitState(submitted.jobId(), "SUCCEEDED");

        assertThat(audio(submitted.audioId()).get("transcript")).isNotNull();
    }

    @Test
    void audioDeletedBeforeResultIsNotRecorded() {
        var submitted = service.submit(eventId, memberId, WEBM, false);
        ai.answer(request -> {
            jdbc.update("update audio_asset set storage_key = null where id = ?", submitted.audioId());
            return success(request, "DENOISED");
        });

        relay.relayOnce();
        awaitState(submitted.jobId(), "FAILED");

        assertThat(job(submitted.jobId()).get("error").toString()).contains("AUDIO_DELETED");
        assertThat(audio(submitted.audioId()).get("transcript")).isNull();
    }

    @Test
    void uploaderWhoLeftEventDoesNotGetTranscript() {
        var submitted = service.submit(eventId, memberId, WEBM, false);
        ai.answer(request -> {
            jdbc.update("update member set active = false where id = ?", memberId);
            return success(request, "DENOISED");
        });

        relay.relayOnce();
        awaitState(submitted.jobId(), "FAILED");

        assertThat(job(submitted.jobId()).get("error").toString()).contains("UPLOADER_INACTIVE");
        assertThat(audio(submitted.audioId()).get("transcript")).isNull();
    }

    @Test
    void voiceConsentWithdrawnBeforeResultIsNotRecorded() {
        var submitted = service.submit(eventId, memberId, WEBM, false);
        ai.answer(request -> {
            jdbc.update("""
                    update account set consent = consent || '{"voice":false}'::jsonb
                     where id = (select account_id from member where id = ?)
                    """, memberId);
            return success(request, "DENOISED");
        });

        relay.relayOnce();
        awaitState(submitted.jobId(), "FAILED");

        assertThat(job(submitted.jobId()).get("error").toString()).contains("CONSENT_WITHDRAWN");
        assertThat(audio(submitted.audioId()).get("transcript")).isNull();
    }

    @Test
    void lateResultAfterAccountDeactivationIsNotRecorded() {
        // 탈퇴(#334)는 참여를 끝내고 계정을 막는다. 그 뒤 도착한 결과가 전사문(개인정보)을 되살리지 않는다.
        var submitted = service.submit(eventId, memberId, WEBM, false);
        ai.answer(request -> {
            jdbc.update("update member set active = false, available = false where id = ?", memberId);
            jdbc.update("""
                    update account set status = 'DISABLED', token_version = token_version + 1, deactivated_at = now()
                     where id = (select account_id from member where id = ?)
                    """, memberId);
            return success(request, "DENOISED");
        });

        relay.relayOnce();
        awaitState(submitted.jobId(), "FAILED");

        assertThat(job(submitted.jobId()).get("error").toString()).contains("UPLOADER_INACTIVE");
        assertThat(audio(submitted.audioId())).containsEntry("transcript", null).containsEntry("transcript_segments", null);
    }

    @Test
    void uploaderDeletingDuringTranscriptionEndsAsAudioDeleted() {
        var submitted = service.submit(eventId, memberId, WEBM, false);
        ai.answer(request -> {
            audioAssets.delete(submitted.audioId(), memberId);
            return success(request, "DENOISED");
        });

        relay.relayOnce();
        awaitState(submitted.jobId(), "FAILED");

        assertThat(job(submitted.jobId()).get("error").toString()).contains("AUDIO_DELETED");
        assertThat(audio(submitted.audioId())).containsEntry("storage_key", null).containsEntry("transcript", null)
                .containsEntry("transcript_segments", null);
        assertThat(storage.objects()).doesNotContainKey(
                com.ssafy.scenecrew.global.storage.StorageKeys.audio(eventId, submitted.audioId()));
    }

    @Test
    void successWithoutPipelineFailsAsInvalidResult() {
        var submitted = service.submit(eventId, memberId, WEBM, false);
        ai.answer(request -> success(request, null));

        relay.relayOnce();
        awaitState(submitted.jobId(), "FAILED");

        assertThat(job(submitted.jobId()).get("error").toString()).contains("INVALID_RESULT");
        assertThat(audio(submitted.audioId()).get("transcript")).isNull();
    }

    @Test
    void uploadValidationStillAppliesBeforeQueueing() {
        assertThatThrownBy(() -> service.submit(eventId, memberId, "ID3 mp3".getBytes(), false))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
        assertThat(jdbc.queryForObject("select count(*) from ai_job where event_id = ?", Long.class, eventId)).isZero();
    }

    @Test
    void aiServerDownloadsOnlyWithTokenWhileJobIsActive() throws Exception {
        var submitted = service.submit(eventId, memberId, WEBM, false);

        HttpResponse<byte[]> ok = download(submitted.audioId().toString(), "Bearer " + TOKEN);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo(WEBM);
        assertThat(ok.headers().firstValue("Content-Type")).hasValue("audio/webm");
        assertThat(ok.headers().firstValue("Cache-Control")).hasValue("no-store");

        assertThat(download(submitted.audioId().toString(), "Bearer wrong").statusCode()).isEqualTo(401);
        assertThat(download(submitted.audioId().toString(), null).statusCode()).isEqualTo(401);
        assertThat(download(UUID.randomUUID().toString(), "Bearer " + TOKEN).statusCode()).isEqualTo(404);
        assertThat(download("not-a-uuid", "Bearer " + TOKEN).statusCode()).isEqualTo(404);

        // 작업이 끝나면 같은 주소로도 내려받을 수 없다.
        jdbc.update("update ai_job set state = 'SUCCEEDED' where id = ?", submitted.jobId());
        assertThat(download(submitted.audioId().toString(), "Bearer " + TOKEN).statusCode()).isEqualTo(404);
    }

    @Test
    void deletedOrExpiredAudioIsNotDownloadable() throws Exception {
        var submitted = service.submit(eventId, memberId, WEBM, false);
        jdbc.update("update audio_asset set created_at = now() - interval '25 hours' where id = ?", submitted.audioId());

        assertThat(download(submitted.audioId().toString(), "Bearer " + TOKEN).statusCode()).isEqualTo(404);
    }

    private HttpResponse<byte[]> download(String audioId, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/audio-assets/" + audioId));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static AiResponse success(JsonNode request, String pipeline) {
        String meta = pipeline == null
                ? "{\"denoiseModel\": null, \"processingMs\": 10, \"fallbackReason\": null}"
                : "{\"pipeline\": \"" + pipeline + "\", \"denoiseModel\": null, \"processingMs\": 10, \"fallbackReason\": null}";
        return new AiResponse(200, """
                {"jobId": "%s", "attempt": %d, "requestSchemaVersion": "4", "inputHash": "%s", "contextRevision": %d,
                 "resultSchemaVersion": "2", "modelVersion": "fake-stt",
                 "result": {"audioId": "%s", "text": "프로젝터가 꺼졌습니다.",
                            "segments": [{"startSec": 0.0, "endSec": 2.0, "text": "프로젝터가 꺼졌습니다."}],
                            "durationSec": 2.4},
                 "executionMeta": %s}
                """.formatted(request.get("jobId").asString(), request.get("attempt").asInt(),
                request.get("inputHash").asString(), request.get("contextRevision").asLong(),
                request.get("payload").get("audioId").asString(), meta));
    }

    private Map<String, Object> job(UUID id) {
        return jdbc.queryForMap("select * from ai_job where id = ?", id);
    }

    private Map<String, Object> audio(UUID id) {
        return jdbc.queryForMap("select * from audio_asset where id = ?", id);
    }

    private void awaitState(UUID id, String state) {
        await(() -> state.equals(job(id).get("state")));
    }

    private static void await(BooleanSupplier condition) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("10초 안에 기대한 상태가 되지 않았습니다.");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        FakeStt fakeStt() {
            return new FakeStt();
        }
    }

    /** 받은 요청을 남기고, 정해 둔 답을 돌려주는 가짜 STT 서버 */
    static final class FakeStt implements AiClient {
        final List<JsonNode> calls = new CopyOnWriteArrayList<>();
        private volatile Function<JsonNode, AiResponse> answer;

        void answer(Function<JsonNode, AiResponse> answer) {
            this.answer = answer;
        }

        void reset() {
            calls.clear();
            answer = null;
        }

        @Override
        public AiResponse post(String path, String jsonBody, Duration timeout) {
            JsonNode request = MAPPER.readTree(jsonBody);
            calls.add(request);
            Function<JsonNode, AiResponse> current = answer;
            return current == null ? new AiResponse(503, "busy") : current.apply(request);
        }
    }
}

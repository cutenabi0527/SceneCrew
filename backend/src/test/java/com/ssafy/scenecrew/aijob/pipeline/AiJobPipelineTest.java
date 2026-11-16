package com.ssafy.scenecrew.aijob.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.aijob.client.AiClient;
import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.client.AiTransportException;
import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobError;
import com.ssafy.scenecrew.aijob.kind.AiJobKind;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import com.ssafy.scenecrew.global.outbox.OutboxPublisher;
import com.ssafy.scenecrew.global.outbox.OutboxRelay;

/**
 * AI 작업 공통 처리(접수 → outbox → 실행기 → AI 호출 → 기록·재시도·복구)를 실제 PostgreSQL로 확인한다.
 * AI 서버는 가짜(FakeAiClient)로 바꾸고, 주기 실행(outbox relay·복구)은 꺼 두고 직접 부른다.
 */
@SpringBootTest(properties = {
        "scenecrew.ai.job.deadlines.[TEST_JOB]=60s",
        "scenecrew.ai.job.deadlines.[TEST_OTHER]=60s",
        "scenecrew.ai.job.deadlines.[TEST_TWO_STAGE]=60s",
        "scenecrew.ai.job.run-deadlines.[TEST_TWO_STAGE]=300s",
        "scenecrew.ai.job.slots.[TEST]=1",
        "scenecrew.ai.job.slots.[TEST_OTHER]=1"})
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import(AiJobPipelineTest.TestBeans.class)
class AiJobPipelineTest {
    static final String KIND = "TEST_JOB";
    static final String KIND_WITHOUT_DEADLINE = "TEST_NO_DEADLINE";
    /** 다른 AI 자원(TEST_OTHER)을 쓰는 종류 */
    static final String OTHER_KIND = "TEST_OTHER";
    /** 접수 후 60초 안 첫 실행, 첫 실행부터 300초(문서 추출의 300초/600초를 줄인 시험값) */
    static final String TWO_STAGE_KIND = "TEST_TWO_STAGE";
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private AiJobService service;

    @Autowired
    private AiJobDispatcher dispatcher;

    @Autowired
    private AiJobRunner runner;

    @Autowired
    private AiJobRecovery recovery;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FakeAiClient ai;

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private AiJobSlots slots;

    private UUID eventId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from ai_job");
        ai.reset();
        TestKind.finished.clear();
        TestKind.applied.clear();
        TestKind.applyGate.reset();
        eventId = UUID.randomUUID();
        jdbc.update("insert into event (id, name, state, revision, last_input_modified_at) values (?, '테스트 행사', 'DRAFT', 3, now())",
                eventId);
    }

    @AfterEach
    void tearDown() {
        ai.releaseAll();
        TestKind.applyGate.release();
    }

    @Test
    void acceptedJobIsCalledOnceAndResultIsRecorded() {
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", "무대 뒤 케이블 정리"));

        assertThat(job(id)).containsEntry("state", "QUEUED").containsEntry("attempt", 0L);
        assertThat(outboxTypes()).containsExactly(AiJobOutboxTypes.REQUESTED);

        relay.relayOnce();
        awaitState(id, "SUCCEEDED");

        Map<String, Object> row = job(id);
        assertThat(row).containsEntry("attempt", 1L).containsEntry("model_version", "test-model")
                .containsEntry("result_schema_version", "1");
        assertThat(row.get("result").toString()).contains("무대 뒤 케이블 정리");
        assertThat(row.get("error")).isNull();
        assertThat(row.get("lease_until")).isNull();
        assertThat(row.get("first_started_at")).isNotNull();
        assertThat(row.get("finished_at")).isNotNull();

        FakeAiClient.Call call = ai.calls.getFirst();
        assertThat(call.path()).isEqualTo("/internal/test");
        assertThat(call.body().get("jobId").asString()).isEqualTo(id.toString());
        assertThat(call.body().get("attempt").asLong()).isEqualTo(1);
        assertThat(call.body().get("requestSchemaVersion").asString()).isEqualTo("4");
        assertThat(call.body().get("kind").asString()).isEqualTo(KIND);
        assertThat(call.body().get("inputHash").asString()).matches("[0-9a-f]{64}");
        assertThat(call.body().get("contextRevision").asLong()).isEqualTo(3);
        assertThat(call.body().get("deadlineAt").asString()).isNotBlank();
        assertThat(call.body().get("payload").get("text").asString()).isEqualTo("무대 뒤 케이블 정리");
        // 응답 대기는 남은 마감까지다.
        assertThat(call.timeout()).isLessThanOrEqualTo(Duration.ofSeconds(60)).isGreaterThan(Duration.ofSeconds(50));

        assertThat(TestKind.applied).containsExactly(id);

        // 끝나면 AI_JOB_FINISHED로 종류별 후속 처리를 부른다.
        relay.relayOnce();
        assertThat(TestKind.finished).containsExactly(id);
    }

    /** 같은 실행 요청이 두 번 전달돼도(최소 한 번 전달) AI 호출과 결과 반영은 한 번이다. */
    @Test
    void duplicateDeliveryCallsAiAndAppliesOnce() {
        ai.blockNextCall();
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", "x"));
        transaction.executeWithoutResult(status -> outbox.publish(eventId, AiJobOutboxTypes.REQUESTED, id,
                Map.of("jobId", id)));

        assertThat(relay.relayOnce()).isEqualTo(2);
        ai.awaitBlocked();
        ai.releaseAll();
        awaitState(id, "SUCCEEDED");
        // 끝난 뒤 늦게 한 번 더 와도 아무것도 하지 않는다.
        transaction.executeWithoutResult(status -> outbox.publish(eventId, AiJobOutboxTypes.REQUESTED, id,
                Map.of("jobId", id)));
        relay.relayOnce();

        assertThat(ai.calls).hasSize(1);
        assertThat(TestKind.applied).containsExactly(id);
        assertThat(job(id)).containsEntry("attempt", 1L);
    }

    /** AI가 계산하는 동안 행사 계획이 바뀌면 옛 입력으로 만든 결과를 반영하지 않는다. */
    @Test
    void resultIsNotAppliedWhenEventChangedDuringCall() {
        ai.blockNextCall();
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        ai.awaitBlocked();
        jdbc.update("update event set revision = 4 where id = ?", eventId);
        ai.releaseAll();
        awaitState(id, "STALE_CONTEXT");

        assertThat(job(id).get("result")).isNull();
        assertThat(job(id).get("error").toString()).contains("READ_CONTEXT_CHANGED");
        assertThat(TestKind.applied).isEmpty();
    }

    @Test
    void failedApplyFinishesAsFailed() {
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", TestKind.FAIL_APPLY));

        relay.relayOnce();
        awaitState(id, "FAILED");

        assertThat(job(id).get("error").toString()).contains("APPLY_FAILED");
        assertThat(job(id).get("result")).isNull();
    }

    @Test
    void sameInputGetsSameHashWhateverTheKeyOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 1);
        a.put("a", Map.of("y", 2, "x", 3));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", Map.of("x", 3, "y", 2));
        b.put("b", 1);

        assertThat(job(accept(a)).get("input_hash")).isEqualTo(job(accept(b)).get("input_hash"));
    }

    @Test
    void unknownKindOrKindWithoutDeadlineIsNotAccepted() {
        assertThatThrownBy(() -> service.accept(eventId, null, "NO_SUCH_KIND", 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept(eventId, null, KIND_WITHOUT_DEADLINE, 0, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from ai_job", Integer.class)).isZero();
    }

    @Test
    void retryableFailureIsRetriedAfterDelayWithSameJobId() {
        ai.answer(req -> FakeAiClient.error(req, 503, "AI_UNAVAILABLE", true));
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        awaitRetryWaiting(id, 1);

        Map<String, Object> row = job(id);
        assertThat(row.get("error").toString()).contains("AI_UNAVAILABLE");
        // 첫 실패 뒤 5초 대기. 그 전에는 실행기에 넘기지 않는다.
        assertThat(instant(row.get("lease_until"))).isBetween(Instant.now().plusSeconds(3), Instant.now().plusSeconds(6));
        assertThat(relay.relayOnce()).isZero();
        assertThat(dispatcher.dispatch(id)).isFalse();

        skipRetryWait(id);
        relay.relayOnce();
        awaitState(id, "SUCCEEDED");

        assertThat(job(id)).containsEntry("attempt", 2L);
        assertThat(job(id).get("error")).isNull();
        assertThat(ai.calls).extracting(c -> c.body().get("jobId").asString()).containsOnly(id.toString());
        assertThat(ai.calls).extracting(c -> c.body().get("attempt").asLong()).containsExactly(1L, 2L);
    }

    @Test
    void retryStopsAfterThreeCalls() {
        for (int i = 0; i < 3; i++) {
            ai.answer(req -> {
                throw new AiTransportException("연결 거부", false, null);
            });
        }
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        awaitRetryWaiting(id, 1);
        skipRetryWait(id);
        relay.relayOnce();
        awaitRetryWaiting(id, 2);
        skipRetryWait(id);
        relay.relayOnce();
        awaitState(id, "FAILED");

        assertThat(job(id)).containsEntry("attempt", 3L);
        assertThat(job(id).get("error").toString()).contains("AI_UNAVAILABLE").contains("CONNECTION_FAILED");
        assertThat(ai.calls).hasSize(3);
    }

    @Test
    void retryIsNotScheduledPastTheDeadline() {
        ai.answer(req -> FakeAiClient.error(req, 503, "AI_UNAVAILABLE", true));
        UUID id = accept(Map.of("text", "x"));
        // 남은 마감(3초)이 재시도 대기(5초)보다 짧다.
        jdbc.update("update ai_job set deadline_at = ? where id = ?", at(Duration.ofSeconds(3)), id);

        relay.relayOnce();
        awaitState(id, "FAILED");

        assertThat(job(id)).containsEntry("attempt", 1L);
        assertThat(ai.calls).hasSize(1);
    }

    @Test
    void nonRetryableFailureFinishesAtOnce() {
        ai.answer(req -> FakeAiClient.error(req, 400, "INVALID_REQUEST", false));
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        awaitState(id, "FAILED");

        assertThat(job(id)).containsEntry("attempt", 1L);
        assertThat(job(id).get("error").toString()).contains("INVALID_REQUEST");
    }

    @Test
    void rejectedServiceTokenIsNotRetried() {
        ai.answer(req -> new AiResponse(401, "{\"code\":\"UNAUTHENTICATED\"}"));
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        awaitState(id, "FAILED");

        assertThat(job(id).get("error").toString()).contains("UNAUTHENTICATED");
        assertThat(ai.calls).hasSize(1);
    }

    @Test
    void gatewayErrorWithoutJsonIsRetried() {
        ai.answer(req -> new AiResponse(502, "<html>Bad Gateway</html>"));
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        awaitRetryWaiting(id, 1);
        assertThat(job(id).get("error").toString()).contains("HTTP_502");
    }

    @Test
    void changedContextBecomesStale() {
        ai.answer(req -> FakeAiClient.error(req, 409, "READ_CONTEXT_CHANGED", false));
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        awaitState(id, "STALE_CONTEXT");
    }

    @Test
    void responseForAnotherAttemptIsRejected() {
        ai.answer(req -> {
            ((ObjectNode) req).put("attempt", 99);
            return FakeAiClient.success(req);
        });
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        awaitState(id, "FAILED");

        assertThat(job(id).get("error").toString()).contains("INVALID_RESULT");
        assertThat(job(id).get("result")).isNull();
    }

    @Test
    void secondDispatchWhileHeldDoesNothing() {
        ai.blockNextCall();
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", "x"));

        assertThat(dispatcher.dispatch(id)).isTrue();
        ai.awaitBlocked();
        assertThat(dispatcher.dispatch(id)).isFalse();
        ai.releaseAll();
        awaitState(id, "SUCCEEDED");

        assertThat(ai.calls).hasSize(1);
    }

    /**
     * MR !119 리뷰(P2-2): outbox handler는 실행기에 넘기고 반환했지만, 실행 전에 서버가 꺼져 메모리의 작업이 사라진 경우.
     * outbox 작업은 이미 처리 완료라 다시 오지 않는다. ai_job은 QUEUED로 남아 있으므로 복구가 다시 넘겨 끝까지 실행한다.
     */
    @Test
    void jobLostFromMemoryAfterHandoffIsRunByRecovery() {
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", "x"));
        // 넘긴 서버가 꺼짐: outbox는 완료, 담당 표시만 남고 만료, 실행은 시작되지 않음
        jdbc.update("update outbox_event set processed_at = ? where aggregate_id = ?", at(Duration.ZERO), id);
        jdbc.update("update ai_job set lease_until = ?, created_at = ? where id = ?",
                at(Duration.ofSeconds(-1)), at(Duration.ofMinutes(-1)), id);
        assertThat(relay.relayOnce()).isZero();
        assertThat(job(id)).containsEntry("state", "QUEUED").containsEntry("attempt", 0L);

        assertThat(recovery.recoverOnce()).isEqualTo(1);
        awaitState(id, "SUCCEEDED");

        assertThat(job(id)).containsEntry("attempt", 1L);
        assertThat(ai.calls).hasSize(1);
    }

    @Test
    void justAcceptedJobIsLeftToOutbox() {
        accept(Map.of("text", "x"));

        assertThat(recovery.recoverOnce()).isZero();
        assertThat(ai.calls).isEmpty();
    }

    @Test
    void recoveryFailsJobsPastTheirDeadline() {
        UUID waiting = accept(Map.of("text", "대기 중 마감"));
        jdbc.update("update ai_job set deadline_at = ? where id = ?", at(Duration.ofSeconds(-1)), waiting);
        UUID running = accept(Map.of("text", "실행 중 서버 종료"));
        jdbc.update("update ai_job set state = 'RUNNING', attempt = 1, lease_until = ? where id = ?",
                at(Duration.ofSeconds(-1)), running);

        assertThat(recovery.recoverOnce()).isEqualTo(2);

        for (UUID id : List.of(waiting, running)) {
            assertThat(job(id)).containsEntry("state", "FAILED");
            assertThat(job(id).get("error").toString()).contains("AI_TIMEOUT");
            assertThat(job(id).get("finished_at")).isNotNull();
        }
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where type = ?", Integer.class,
                AiJobOutboxTypes.FINISHED)).isEqualTo(2);
        assertThat(ai.calls).isEmpty();
    }

    /** 실행 담당 증표가 만료돼 마감 처리된 뒤에 늦게 온 성공 응답은 기록하지 않는다. */
    @Test
    void lateResultAfterExpiryIsNotRecorded() throws Exception {
        ai.blockNextCall();
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", "x"));
        Instant dispatchLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("update ai_job set lease_until = ? where id = ?", Timestamp.from(dispatchLease), id);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> late = pool.submit(() -> runner.run(id, dispatchLease));
            ai.awaitBlocked();
            assertThat(job(id)).containsEntry("state", "RUNNING");

            jdbc.update("update ai_job set lease_until = ? where id = ?", at(Duration.ofSeconds(-1)), id);
            assertThat(recovery.recoverOnce()).isEqualTo(1);

            ai.releaseAll();
            late.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(job(id)).containsEntry("state", "FAILED");
        assertThat(job(id).get("error").toString()).contains("AI_TIMEOUT");
        assertThat(job(id).get("result")).isNull();
    }

    @Test
    void runnerWithAnotherDispatchLeaseDoesNothing() {
        UUID id = accept(Map.of("text", "x"));
        jdbc.update("update ai_job set lease_until = ? where id = ?", at(Duration.ofSeconds(30)), id);

        runner.run(id, Instant.now().truncatedTo(ChronoUnit.MICROS));

        assertThat(job(id)).containsEntry("state", "QUEUED").containsEntry("attempt", 0L);
        assertThat(ai.calls).isEmpty();
    }

    /**
     * MR !126 리뷰 1: 서버 종료로 AI 응답 대기가 중단되면 연결 실패로 보고 재시도하면 안 된다.
     * 요청이 이미 AI에 도착했을 수 있으므로 아무것도 기록하지 않고 RUNNING으로 남기고, 복구가 마감 처리한다.
     */
    @Test
    void interruptedCallIsNotRecordedAndNotSentAgain() throws Exception {
        ai.blockNextCall();
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", "x"));
        Instant dispatchLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("update ai_job set lease_until = ? where id = ?", Timestamp.from(dispatchLease), id);

        Thread worker = new Thread(() -> runner.run(id, dispatchLease));
        worker.start();
        ai.awaitBlocked();
        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(job(id)).containsEntry("state", "RUNNING").containsEntry("attempt", 1L);
        assertThat(job(id).get("error")).isNull();
        assertThat(slots.available("TEST")).isEqualTo(1);

        jdbc.update("update ai_job set lease_until = ? where id = ?", at(Duration.ofSeconds(-1)), id);
        assertThat(recovery.recoverOnce()).isEqualTo(1);
        assertThat(job(id)).containsEntry("state", "FAILED");
        assertThat(job(id).get("error").toString()).contains("AI_TIMEOUT");
        assertThat(ai.calls).hasSize(1);
    }

    /** MR !126 리뷰 2: 504는 요청이 AI 앱에 도착해 처리 중일 수 있어 다시 보내지 않는다. */
    @Test
    void gatewayTimeoutIsNotRetried() {
        ai.answer(req -> new AiResponse(504, "<html>504 Gateway Time-out</html>"));
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        awaitState(id, "FAILED");

        assertThat(job(id).get("error").toString()).contains("GATEWAY_TIMEOUT");
        assertThat(ai.calls).hasSize(1);
    }

    /** AI 게이트웨이가 하위 앱을 기다리다 포기한 SERVICE_TIMEOUT은 retryable=true로 와도 다시 보내지 않는다. */
    @Test
    void aiGatewayServiceTimeoutIsNotRetried() {
        ai.answer(req -> FakeAiClient.gatewayError("SERVICE_TIMEOUT"));
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        awaitState(id, "FAILED");

        assertThat(job(id).get("error").toString()).contains("SERVICE_TIMEOUT");
        assertThat(ai.calls).hasSize(1);
    }

    /** 하위 앱 프로세스가 내려가 전달하지 못한 SERVICE_DOWN은 다시 보낸다. */
    @Test
    void aiGatewayServiceDownIsRetried() {
        ai.answer(req -> FakeAiClient.gatewayError("SERVICE_DOWN"));
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        awaitRetryWaiting(id, 1);

        assertThat(job(id).get("error").toString()).contains("SERVICE_DOWN");
    }

    /** MR !126 리뷰 3: revision을 확인한 뒤 반영이 끝날 때까지 다른 트랜잭션이 행사 계획을 바꾸지 못한다. */
    @Test
    void eventChangeWaitsUntilResultIsApplied() throws Exception {
        TestKind.applyGate.block();
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", "x"));

        relay.relayOnce();
        TestKind.applyGate.awaitEntered();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> change = pool.submit(() -> jdbc.update("update event set revision = 4 where id = ?", eventId));
            Thread.sleep(300);
            assertThat(change.isDone()).as("반영 중에는 계획을 바꾸지 못하고 기다린다").isFalse();

            TestKind.applyGate.release();
            assertThat(change.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        awaitState(id, "SUCCEEDED");
        assertThat(TestKind.applied).containsExactly(id);
    }

    /** MR !126 리뷰 4: 한 자원의 슬롯을 다 써도 다른 자원을 쓰는 작업은 기다리지 않는다. */
    @Test
    void jobOnAnotherResourceIsNotBlocked() {
        ai.blockNextCall();
        ai.answer(FakeAiClient::success);
        ai.answer(FakeAiClient::success);
        UUID busy = accept(Map.of("text", "오래 걸리는 작업"));
        relay.relayOnce();
        ai.awaitBlocked();

        UUID other = accept(OTHER_KIND, Map.of("text", "다른 자원"));
        relay.relayOnce();
        awaitState(other, "SUCCEEDED");
        assertThat(job(busy)).containsEntry("state", "RUNNING");

        ai.releaseAll();
        awaitState(busy, "SUCCEEDED");
    }

    /** 같은 자원의 슬롯이 비지 않으면 마감까지 기다렸다가 호출 없이 AI_TIMEOUT으로 끝난다. 중복으로 다시 넣지 않는다. */
    @Test
    void jobWaitingForBusyResourceTimesOutWithoutCalling() {
        ai.blockNextCall();
        ai.answer(FakeAiClient::success);
        UUID busy = accept(Map.of("text", "슬롯을 잡은 작업"));
        relay.relayOnce();
        ai.awaitBlocked();

        UUID waiting = accept(Map.of("text", "기다리는 작업"));
        jdbc.update("update ai_job set deadline_at = ? where id = ?", at(Duration.ofSeconds(1)), waiting);
        relay.relayOnce();
        awaitState(waiting, "FAILED");

        assertThat(job(waiting)).containsEntry("attempt", 1L);
        assertThat(job(waiting).get("error").toString()).contains("AI_TIMEOUT");
        assertThat(recovery.recoverOnce()).isZero();
        ai.releaseAll();
        awaitState(busy, "SUCCEEDED");
        assertThat(ai.calls).hasSize(1);
        assertThat(slots.available("TEST")).isEqualTo(1);
    }

    /** MR !126 리뷰 5: 반영이 아닌 기록 실패(DB 오류)는 APPLY_FAILED로 확정하지 않고 RUNNING으로 남겨 복구에 맡긴다. */
    @Test
    void singleDeadlineKindKeepsDeadlineFromAcceptance() {
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", "x"));
        Instant accepted = deadline(id);

        relay.relayOnce();
        awaitState(id, "SUCCEEDED");

        assertThat(deadline(id)).isEqualTo(accepted);
    }

    /** S15P31A202-339: 두 단계 마감 종류는 첫 실행이 시작될 때 마감을 첫 실행 + 실행 마감으로 한 번 다시 정한다. */
    @Test
    void twoStageKindSetsDeadlineFromFirstStart() {
        ai.answer(FakeAiClient::success);
        Instant before = Instant.now();
        UUID id = accept(TWO_STAGE_KIND, Map.of("text", "x"));
        // 접수 때는 첫 실행까지의 대기 마감(60초)이다.
        assertThat(deadline(id)).isBetween(before.plusSeconds(59), Instant.now().plusSeconds(61));

        relay.relayOnce();
        awaitState(id, "SUCCEEDED");

        Instant firstStarted = instant(job(id).get("first_started_at"));
        assertThat(deadline(id)).isCloseTo(firstStarted.plusSeconds(300), within(1, ChronoUnit.MILLIS));
        // 요청4의 deadlineAt과 응답 대기도 다시 정한 마감 기준이다.
        FakeAiClient.Call call = ai.calls.getFirst();
        assertThat(Instant.parse(call.body().get("deadlineAt").asString())).isEqualTo(deadline(id));
        assertThat(call.timeout()).isGreaterThan(Duration.ofSeconds(290));
    }

    @Test
    void twoStageDeadlineIsNotExtendedAgainOnRetry() {
        ai.answer(req -> FakeAiClient.error(req, 503, "AI_UNAVAILABLE", true));
        ai.answer(FakeAiClient::success);
        UUID id = accept(TWO_STAGE_KIND, Map.of("text", "x"));

        relay.relayOnce();
        awaitRetryWaiting(id, 1);
        Instant afterFirstStart = deadline(id);
        assertThat(afterFirstStart).isAfter(Instant.now().plusSeconds(290));

        skipRetryWait(id);
        relay.relayOnce();
        awaitState(id, "SUCCEEDED");

        assertThat(job(id)).containsEntry("attempt", 2L);
        assertThat(deadline(id)).isEqualTo(afterFirstStart);
    }

    /** 대기 마감 직전에 넘겨받았지만 실행 시작이 마감을 넘긴 경우. 마감을 늘리지 않고 호출 없이 시간 초과로 끝난다. */
    @Test
    void twoStageJobStartedAfterWaitDeadlineIsNotExtended() {
        ai.answer(FakeAiClient::success);
        UUID id = accept(TWO_STAGE_KIND, Map.of("text", "x"));
        Instant passed = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        Instant dispatchLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("update ai_job set deadline_at = ?, lease_until = ? where id = ?",
                Timestamp.from(passed), Timestamp.from(dispatchLease), id);

        runner.run(id, dispatchLease);

        assertThat(job(id)).containsEntry("state", "FAILED");
        assertThat(job(id).get("error").toString()).contains("AI_TIMEOUT");
        assertThat(deadline(id)).isEqualTo(passed);
        assertThat(ai.calls).isEmpty();
    }

    @Test
    void recordFailureOutsideApplyIsLeftToRecovery() {
        ai.answer(FakeAiClient::success);
        UUID id = accept(Map.of("text", TestKind.BROKEN_RESULT));
        Instant dispatchLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("update ai_job set lease_until = ? where id = ?", Timestamp.from(dispatchLease), id);

        runner.run(id, dispatchLease);

        assertThat(job(id)).containsEntry("state", "RUNNING");
        assertThat(job(id).get("error")).isNull();
        assertThat(ai.calls).hasSize(1);
    }

    private UUID accept(Map<String, Object> payload) {
        return accept(KIND, payload);
    }

    private UUID accept(String kind, Map<String, Object> payload) {
        AiJob job = transaction.execute(status -> service.accept(eventId, null, kind, 3, payload));
        return job.getId();
    }

    private Map<String, Object> job(UUID id) {
        return jdbc.queryForMap("""
                select state, attempt, input_hash, result::text as result, error::text as error, model_version,
                       result_schema_version, lease_until, first_started_at, finished_at
                  from ai_job where id = ?
                """, id);
    }

    private Instant deadline(UUID id) {
        return jdbc.queryForObject("select deadline_at from ai_job where id = ?", Timestamp.class, id).toInstant();
    }

    private List<String> outboxTypes() {
        return jdbc.queryForList("select type from outbox_event where processed_at is null order by available_at",
                String.class);
    }

    /** 재시도 대기를 건너뛴다. 대기 시각과 outbox 작업 시각을 1초 전으로 당긴다. */
    private void skipRetryWait(UUID id) {
        jdbc.update("update ai_job set lease_until = ? where id = ?", at(Duration.ofSeconds(-1)), id);
        jdbc.update("update outbox_event set available_at = ? where aggregate_id = ? and processed_at is null",
                at(Duration.ofSeconds(-1)), id);
    }

    /**
     * 지금(JVM 시계) + offset. 코드는 Instant.now()로 시각을 비교하므로 DB의 now()를 쓰지 않는다.
     * Docker의 DB 시계가 PC 시계와 조금 어긋나면 now()로 당긴 시각이 아직 미래로 보일 수 있다.
     */
    private static Timestamp at(Duration offset) {
        return Timestamp.from(Instant.now().plus(offset));
    }

    private void awaitRetryWaiting(UUID id, long attempt) {
        await(() -> {
            Map<String, Object> row = job(id);
            return "QUEUED".equals(row.get("state")) && Long.valueOf(attempt).equals(row.get("attempt"))
                    && row.get("error") != null;
        });
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

    private static Instant instant(Object value) {
        return ((Timestamp) value).toInstant();
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        FakeAiClient fakeAiClient() {
            return new FakeAiClient();
        }

        @Bean
        AiJobKind testKind() {
            return new TestKind(KIND, "TEST");
        }

        @Bean
        AiJobKind testKindWithoutDeadline() {
            return new TestKind(KIND_WITHOUT_DEADLINE, "TEST");
        }

        @Bean
        AiJobKind otherResourceKind() {
            return new TestKind(OTHER_KIND, "TEST_OTHER");
        }

        @Bean
        AiJobKind twoStageKind() {
            return new TestKind(TWO_STAGE_KIND, "TEST");
        }
    }

    /** 응답 본문이 error를 가지면 그대로 실패로, 아니면 식별 필드를 대조해 성공으로 읽는 시험용 종류 */
    static final class TestKind implements AiJobKind {
        /** 이 문구가 결과에 있으면 반영 중 예외를 던진다. */
        static final String FAIL_APPLY = "반영 실패";
        /** 이 문구가 결과에 있으면 JSON이 아닌 결과를 돌려줘 기록(jsonb 저장)이 DB에서 실패한다. */
        static final String BROKEN_RESULT = "깨진 결과";
        static final List<UUID> finished = new CopyOnWriteArrayList<>();
        static final List<UUID> applied = new CopyOnWriteArrayList<>();
        /** block()이면 다음 반영을 release() 전까지 붙잡는다. */
        static final Gate applyGate = new Gate();
        private final String kind;
        private final String resource;

        TestKind(String kind, String resource) {
            this.kind = kind;
            this.resource = resource;
        }

        @Override
        public String resource() {
            return resource;
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public String path() {
            return "/internal/test";
        }

        @Override
        public AiJobOutcome read(AiJobEnvelope request, AiResponse response) {
            JsonNode body = MAPPER.readTree(response.body());
            if (!request.jobId().toString().equals(body.get("jobId").asString())
                    || request.attempt() != body.get("attempt").asLong()) {
                return AiJobOutcome.failed(AiJobError.invalidResult("요청과 다른 작업의 응답입니다."));
            }
            if (body.has("error")) {
                JsonNode error = body.get("error");
                AiJobError e = new AiJobError(error.get("code").asString(), error.get("message").asString(),
                        error.get("retryable").asBoolean(), Map.of());
                return "READ_CONTEXT_CHANGED".equals(e.code()) ? AiJobOutcome.staleContext(e) : AiJobOutcome.failed(e);
            }
            String result = body.get("result").toString();
            return AiJobOutcome.succeeded(body.get("resultSchemaVersion").asString(),
                    body.get("modelVersion").asString(), result.contains(BROKEN_RESULT) ? "{깨진" : result);
        }

        @Override
        public AiJobOutcome apply(AiJob job, AiJobOutcome outcome) {
            if (outcome.resultJson().contains(FAIL_APPLY)) {
                throw new IllegalStateException("반영 실패 시험");
            }
            applyGate.pass();
            applied.add(job.getId());
            return outcome;
        }

        @Override
        public void afterFinished(AiJob job) {
            finished.add(job.getId());
        }
    }

    /** block() 뒤 처음 지나가는 스레드를 release() 전까지 붙잡는다. */
    static final class Gate {
        private volatile CountDownLatch gate;
        private volatile CountDownLatch entered = new CountDownLatch(1);

        void block() {
            entered = new CountDownLatch(1);
            gate = new CountDownLatch(1);
        }

        void pass() {
            CountDownLatch g = gate;
            if (g == null) {
                return;
            }
            entered.countDown();
            try {
                g.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("붙잡힌 스레드").isTrue();
        }

        void release() {
            CountDownLatch g = gate;
            gate = null;
            if (g != null) {
                g.countDown();
            }
        }

        void reset() {
            release();
        }
    }

    /** 미리 넣어 둔 순서대로 답하는 가짜 AI 서버. blockNextCall이면 releaseAll 전까지 답을 미룬다. */
    static final class FakeAiClient implements AiClient {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        private final ConcurrentLinkedDeque<Step> steps = new ConcurrentLinkedDeque<>();
        private volatile CountDownLatch blocked = new CountDownLatch(1);
        /** 다음 호출을 붙잡을 문. 호출이 오면 waiting으로 옮긴다. */
        private volatile CountDownLatch gate;
        private volatile CountDownLatch waiting;

        record Call(String path, JsonNode body, Duration timeout) {
        }

        @FunctionalInterface
        interface Step {
            AiResponse answer(JsonNode request) throws AiTransportException;
        }

        void answer(Step step) {
            steps.add(step);
        }

        void blockNextCall() {
            blocked = new CountDownLatch(1);
            gate = new CountDownLatch(1);
        }

        void awaitBlocked() {
            try {
                assertThat(blocked.await(10, TimeUnit.SECONDS)).as("AI 호출 시작").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        void releaseAll() {
            for (CountDownLatch latch : new CountDownLatch[] {gate, waiting}) {
                if (latch != null) {
                    latch.countDown();
                }
            }
        }

        void reset() {
            releaseAll();
            calls.clear();
            steps.clear();
            gate = null;
            waiting = null;
        }

        @Override
        public AiResponse post(String path, String jsonBody, Duration timeout)
                throws AiTransportException, InterruptedException {
            JsonNode request = MAPPER.readTree(jsonBody);
            calls.add(new Call(path, request, timeout));
            CountDownLatch g = gate;
            if (g != null) {
                gate = null;
                waiting = g;
                blocked.countDown();
                g.await(10, TimeUnit.SECONDS);
            }
            Step step = steps.poll();
            if (step == null) {
                throw new IllegalStateException("준비한 AI 응답이 없습니다.");
            }
            return step.answer(request.deepCopy());
        }

        static AiResponse success(JsonNode request) {
            ObjectNode body = identity(request);
            body.put("resultSchemaVersion", "1");
            body.put("modelVersion", "test-model");
            body.putObject("result").set("echo", request.get("payload").get("text"));
            return new AiResponse(200, body.toString());
        }

        static AiResponse error(JsonNode request, int status, String code, boolean retryable) {
            ObjectNode body = identity(request);
            ObjectNode error = body.putObject("error");
            error.put("code", code);
            error.put("message", "시험 실패");
            error.put("retryable", retryable);
            return new AiResponse(status, body.toString());
        }

        /** AI 게이트웨이(ai/runtime/gateway.py)가 하위 앱 대신 보내는 503 */
        static AiResponse gatewayError(String reason) {
            return new AiResponse(503, """
                    {"jobId":null,"attempt":null,"error":{"code":"AI_UNAVAILABLE","message":"AI 서비스를 사용할 수 없습니다.",
                     "retryable":true,"details":{"reason":"%s","service":"test"}}}
                    """.formatted(reason));
        }

        private static ObjectNode identity(JsonNode request) {
            ObjectNode body = MAPPER.createObjectNode();
            for (String field : List.of("jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision")) {
                body.set(field, request.get(field));
            }
            return body;
        }
    }
}

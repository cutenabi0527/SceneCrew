package com.ssafy.scenecrew.aijob.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.kind.ActiveLimit;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobKind;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import com.ssafy.scenecrew.aijob.pipeline.AiJobPipelineTest.FakeAiClient;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.error.RateLimitedException;
import com.ssafy.scenecrew.global.outbox.OutboxRelay;

/**
 * AI 작업 공통 관문(V9-D43): 진행 중 1개 규칙(429·Retry-After)과 외부 AI 동의(접수 403, 호출 직전·반영 직전 철회 시
 * CONSENT_WITHDRAWN). 실제 외부 종류(계획 생성·추출)는 아직 없어 시험 전용 종류로 확인한다. AI 서버는 가짜다.
 */
@SpringBootTest(properties = {
        "scenecrew.ai.job.deadlines.[TEST_EXTERNAL]=60s",
        "scenecrew.ai.job.deadlines.[TEST_ACCOUNT]=60s",
        "scenecrew.ai.job.slots.[TEST]=1"})
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import(AiJobGateTest.TestBeans.class)
class AiJobGateTest {
    static final String EXTERNAL = "TEST_EXTERNAL";
    static final String PER_ACCOUNT = "TEST_ACCOUNT";
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired AiJobService service;
    @Autowired OutboxRelay relay;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeAiClient ai;

    final List<UUID> events = new ArrayList<>();
    final List<UUID> accounts = new ArrayList<>();
    UUID eventId, chiefAccount, chief, worker;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from ai_job");
        ai.reset();
        Kind.applied.clear();
        eventId = event();
        chiefAccount = account(true);
        chief = member(eventId, chiefAccount, "CHIEF");
        worker = member(eventId, account(false), "WORKER");
    }

    @AfterEach
    void tearDown() {
        ai.releaseAll();
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from ai_job");
        for (UUID e : events) {
            jdbc.update("delete from member where event_id = ?", e);
            jdbc.update("delete from event where id = ?", e);
        }
        for (UUID a : accounts) jdbc.update("delete from account where id = ?", a);
        events.clear();
        accounts.clear();
    }

    // ---- 외부 AI 동의 ----------------------------------------------------------------------------------------

    @Test
    void externalKindNeedsRequesterConsentAtAccept() {
        // 동의하지 않은 팀원: 403 FORBIDDEN(EXTERNAL_AI_CONSENT_REQUIRED), 작업이 생기지 않는다.
        BusinessException denied = (BusinessException) catchThrowable(() -> accept(eventId, worker, EXTERNAL));
        assertThat(denied.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(denied.getDetails()).containsEntry("reason", "EXTERNAL_AI_CONSENT_REQUIRED");
        assertThat(jdbc.queryForObject("select count(*) from ai_job", Integer.class)).isZero();
        // 동의한 총괄은 접수된다. 요청 Member가 없는(시스템) 작업은 현재 총괄의 동의를 본다.
        assertThat(accept(eventId, chief, EXTERNAL)).isNotNull();
        jdbc.update("delete from ai_job");
        assertThat(accept(eventId, null, EXTERNAL)).isNotNull();
        jdbc.update("delete from ai_job");
        // 참여를 끝낸 Member는 동의가 있어도 접수하지 않는다.
        jdbc.update("update member set active = false where id = ?", chief);
        assertThatThrownBy(() -> accept(eventId, chief, EXTERNAL)).isInstanceOf(BusinessException.class);
    }

    @Test
    void withdrawnBeforeCallSkipsAi() {
        UUID id = accept(eventId, chief, EXTERNAL);
        consent(chiefAccount, false);
        relay.relayOnce();
        awaitState(id, "FAILED");
        assertThat(ai.calls).isEmpty();
        assertThat(errorCode(id)).isEqualTo("CONSENT_WITHDRAWN");
        // 동의는 계정 단위라 행사 revision을 올리지 않는다(V9-D40).
        assertThat(jdbc.queryForObject("select revision from event where id = ?", Long.class, eventId)).isEqualTo(3L);
    }

    @Test
    void withdrawnBeforeApplyDropsResult() {
        ai.answer(FakeAiClient::success);
        ai.blockNextCall();
        UUID id = accept(eventId, chief, EXTERNAL);
        relay.relayOnce();
        ai.awaitBlocked();
        consent(chiefAccount, false);
        ai.releaseAll();
        awaitState(id, "FAILED");
        assertThat(ai.calls).hasSize(1);
        assertThat(errorCode(id)).isEqualTo("CONSENT_WITHDRAWN");
        assertThat(Kind.applied).isEmpty();
        assertThat(jdbc.queryForObject("select result::text from ai_job where id = ?", String.class, id)).isNull();
    }

    @Test
    void consentedJobSucceeds() {
        ai.answer(FakeAiClient::success);
        UUID id = accept(eventId, chief, EXTERNAL);
        relay.relayOnce();
        awaitState(id, "SUCCEEDED");
        assertThat(Kind.applied).containsExactly(id);
    }

    // ---- 진행 중 1개 규칙 ------------------------------------------------------------------------------------

    @Test
    void eventScopeAllowsOneActiveJob() {
        UUID first = accept(eventId, chief, EXTERNAL);
        RateLimitedException limited = (RateLimitedException) catchThrowable(() -> accept(eventId, chief, EXTERNAL));
        assertThat(limited.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(limited.getDetails()).containsEntry("reason", "AI_JOB_ACTIVE").containsEntry("kind", EXTERNAL);
        // 남은 마감(60초) 안에서 다시 보내라고 알린다.
        assertThat(limited.getRetryAfterSeconds()).isBetween(1L, 60L);
        assertThat(limited.getMessage()).doesNotContain("로그인");
        // 다른 행사는 따로 센다.
        UUID other = event();
        member(other, chiefAccount, "CHIEF");
        assertThat(accept(other, null, EXTERNAL)).isNotNull();
        // 끝났거나 마감이 지난 작업은 진행 중으로 세지 않는다.
        jdbc.update("update ai_job set deadline_at = now() - interval '1 second' where id = ?", first);
        assertThat(accept(eventId, chief, EXTERNAL)).isNotNull();
    }

    @Test
    void accountScopeCountsAcrossEvents() {
        UUID other = event();
        UUID chiefInOther = member(other, chiefAccount, "CHIEF");
        accept(eventId, chief, PER_ACCOUNT);
        // 같은 계정은 다른 행사에서도 진행 중 1개
        assertThatThrownBy(() -> accept(other, chiefInOther, PER_ACCOUNT)).isInstanceOf(RateLimitedException.class);
        // 다른 계정은 통과
        assertThat(accept(eventId, worker, PER_ACCOUNT)).isNotNull();
    }

    @Test
    void concurrentAcceptsLetOnlyOneThrough() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        accept(eventId, chief, EXTERNAL);
                        return true;
                    } catch (RateLimitedException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isOne();
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from ai_job where kind = ?", Integer.class, EXTERNAL)).isOne();
    }

    // ---- 도우미 ----------------------------------------------------------------------------------------------

    private UUID accept(UUID event, UUID member, String kind) {
        AiJob job = transaction.execute(status -> service.accept(event, member, kind, 3, Map.of("text", "시험 입력")));
        return job.getId();
    }

    private UUID event() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into event (id, name, state, revision, last_input_modified_at) values (?, '관문 시험', 'DRAFT', 3, now())", id);
        events.add(id);
        return id;
    }

    private UUID account(boolean externalAi) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into account values (?, 'fixture', 'ACTIVE', true, 1, ?::jsonb, now())", id,
                "{\"service\":true,\"voice\":false,\"push\":false,\"externalAi\":" + externalAi + "}");
        accounts.add(id);
        return id;
    }

    private void consent(UUID account, boolean externalAi) {
        jdbc.update("update account set consent = jsonb_set(consent, '{externalAi}', ?::jsonb) where id = ?",
                Boolean.toString(externalAi), account);
    }

    private UUID member(UUID event, UUID account, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into member (id, event_id, account_id, work_group_id, crew_no, event_role, display_name, available, is_reserve,
                                    active, participation_revision)
                values (?, ?, ?, null, (select coalesce(max(crew_no), 0) + 1 from member where event_id = ?), ?, '시험', true, false, true, 1)""",
                id, event, account, event, role);
        return id;
    }

    private String errorCode(UUID id) {
        String error = jdbc.queryForObject("select error::text from ai_job where id = ?", String.class, id);
        return MAPPER.readTree(error).get("code").asString();
    }

    private void awaitState(UUID id, String state) {
        await(() -> state.equals(jdbc.queryForObject("select state from ai_job where id = ?", String.class, id)));
    }

    private static void await(BooleanSupplier condition) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("10초 안에 기대한 상태가 되지 않았습니다.");
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
        FakeAiClient fakeAiClient() {
            return new FakeAiClient();
        }

        @Bean
        AiJobKind externalKind() {
            return new Kind(EXTERNAL, true, ActiveLimit.EVENT);
        }

        @Bean
        AiJobKind accountKind() {
            return new Kind(PER_ACCOUNT, false, ActiveLimit.ACCOUNT);
        }
    }

    /** 외부 여부·한도만 다른 시험용 종류. 응답 식별 필드가 맞으면 성공으로 읽는다. */
    static final class Kind implements AiJobKind {
        static final List<UUID> applied = new CopyOnWriteArrayList<>();
        private final String kind;
        private final boolean external;
        private final ActiveLimit limit;

        Kind(String kind, boolean external, ActiveLimit limit) {
            this.kind = kind;
            this.external = external;
            this.limit = limit;
        }

        @Override public String kind() { return kind; }
        @Override public String path() { return "/internal/test"; }
        @Override public String resource() { return "TEST"; }
        @Override public boolean external() { return external; }
        @Override public ActiveLimit activeLimit() { return limit; }
        @Override public boolean requiresCurrentContext() { return false; }

        @Override
        public AiJobOutcome read(AiJobEnvelope request, AiResponse response) {
            JsonNode body = MAPPER.readTree(response.body());
            return AiJobOutcome.succeeded(body.get("resultSchemaVersion").asString(), body.get("modelVersion").asString(),
                    body.get("result").toString());
        }

        @Override
        public AiJobOutcome apply(AiJob job, AiJobOutcome outcome) {
            applied.add(job.getId());
            return outcome;
        }
    }
}

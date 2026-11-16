package com.ssafy.scenecrew.global.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.support.FileTestBeans;
import com.ssafy.scenecrew.support.TestRows;

/** S15P31A202-341: 같은 요청 키 재요청에 업무를 다시 하지 않고 최초 응답을 돌려준다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import(FileTestBeans.class)
class IdempotencyTest {
    private static final String OPERATION = "TEST-OP";

    record Accepted(UUID itemId, String note) {
    }

    @Autowired
    private Idempotency idempotency;

    @Autowired
    private IdempotencyCleanup cleanup;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicInteger runs = new AtomicInteger();
    private UUID account;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        TestRows rows = new TestRows(jdbc);
        account = rows.account();
        eventId = rows.event("DRAFT");
        runs.set(0);
    }

    @Test
    void firstRequestRunsWorkAndRecordsResult() {
        String key = UUID.randomUUID().toString();

        IdempotentResult<Accepted> result = run(request(key, "body-1", 1L));

        assertThat(result.replayed()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(runs).hasValue(1);
        Map<String, Object> row = jdbc.queryForMap("""
                select operation, request_hash, event_id, participation_revision, result_type, result_refs::text as refs,
                       http_status, receipt::text as receipt, created_at, replay_expires_at
                  from idempotency_record where actor_account_id = ? and idempotency_key = ?
                """, account, key);
        assertThat(row).containsEntry("operation", OPERATION).containsEntry("event_id", eventId)
                .containsEntry("participation_revision", 1L).containsEntry("result_type", "TEST_ACCEPTED")
                .containsEntry("http_status", 202);
        assertThat(row.get("receipt").toString()).contains(result.body().itemId().toString());
        assertThat(row.get("refs").toString()).contains(result.body().itemId().toString());
        Instant created = ((Timestamp) row.get("created_at")).toInstant();
        assertThat(((Timestamp) row.get("replay_expires_at")).toInstant()).isEqualTo(created.plus(Idempotency.REPLAY_WINDOW));
    }

    @Test
    void sameKeyAndBodyReturnsFirstResultWithoutRunningAgain() {
        String key = UUID.randomUUID().toString();
        IdempotentResult<Accepted> first = run(request(key, "body-1", 1L));

        IdempotentResult<Accepted> again = run(request(key.toUpperCase(), "body-1", 1L));

        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(again.httpStatus()).isEqualTo(202);
        assertThat(again.resultType()).isEqualTo("TEST_ACCEPTED");
        assertThat(again.resultRefs()).containsEntry("itemId", first.body().itemId().toString());
        assertThat(runs).hasValue(1);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyOrTargetIsMismatch() {
        String key = UUID.randomUUID().toString();
        run(request(key, "body-1", 1L));

        assertCode(() -> run(request(key, "body-2", 1L)), ErrorCode.IDEMPOTENCY_MISMATCH);
        UUID otherEvent = new TestRows(jdbc).event("DRAFT");
        assertCode(() -> run(new IdempotencyRequest(account, OPERATION, key, "body-1", otherEvent, null, 1L)),
                ErrorCode.IDEMPOTENCY_MISMATCH);
        assertThat(runs).hasValue(1);
    }

    /** 탈퇴 뒤 다시 참여한 회차에는 옛 회차의 결과를 돌려주지 않는다. */
    @Test
    void anotherParticipationRevisionIsForbidden() {
        String key = UUID.randomUUID().toString();
        run(request(key, "body-1", 1L));

        assertCode(() -> run(request(key, "body-1", 2L)), ErrorCode.FORBIDDEN);
        assertThat(runs).hasValue(1);
    }

    @Test
    void replayAfterWindowIsExpiredAndNotRunAgain() {
        String key = UUID.randomUUID().toString();
        run(request(key, "body-1", 1L));
        jdbc.update("update idempotency_record set replay_expires_at = ? where actor_account_id = ? and idempotency_key = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), account, key);

        assertCode(() -> run(request(key, "body-1", 1L)), ErrorCode.IDEMPOTENCY_RESULT_EXPIRED);
        assertThat(runs).hasValue(1);
    }

    @Test
    void failedWorkLeavesNoRecordSoSameKeyCanRetry() {
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> transaction.execute(status -> idempotency.run(request(key, "body-1", 1L), Accepted.class,
                () -> {
                    runs.incrementAndGet();
                    throw new IllegalStateException("업무 실패");
                }))).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();

        IdempotentResult<Accepted> retried = run(request(key, "body-1", 1L));

        assertThat(retried.replayed()).isFalse();
        assertThat(runs).hasValue(2);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void sameKeyOfAnotherAccountOrOperationIsIndependent() {
        String key = UUID.randomUUID().toString();
        run(request(key, "body-1", 1L));
        UUID other = new TestRows(jdbc).account();

        assertThat(run(new IdempotencyRequest(other, OPERATION, key, "body-1", eventId, null, 1L)).replayed()).isFalse();
        assertThat(run(new IdempotencyRequest(account, "OTHER-OP", key, "body-1", eventId, null, 1L)).replayed()).isFalse();
        assertThat(runs).hasValue(3);
    }

    /** 첫 요청이 대기 시간(시험 500ms) 안에 끝나지 않으면 같은 키 요청은 409다. 업무는 한 번만 실행된다. */
    @Test
    void concurrentSameKeyIsInProgress() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<IdempotentResult<Accepted>> first = pool.submit(() -> transaction.execute(status ->
                    idempotency.run(request(key, "body-1", 1L), Accepted.class, () -> {
                        entered.countDown();
                        await(release);
                        return accepted();
                    })));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            assertCode(() -> run(request(key, "body-1", 1L)), ErrorCode.REQUEST_IN_PROGRESS);

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).replayed()).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(run(request(key, "body-1", 1L)).replayed()).isTrue();
        assertThat(runs).hasValue(1);
    }

    /** 첫 요청이 대기 시간 안에 끝나면 기다리던 같은 키 요청은 409 대신 그 결과를 받는다(be/11 "제한 대기 뒤"). */
    @Test
    void waitingSameKeyGetsFirstResultWhenItFinishes() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IdempotentResult<Accepted>> first = pool.submit(() -> transaction.execute(status ->
                    idempotency.run(request(key, "body-1", 1L), Accepted.class, () -> {
                        entered.countDown();
                        await(release);
                        return accepted();
                    })));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<IdempotentResult<Accepted>> second = pool.submit(() -> run(request(key, "body-1", 1L)));
            Thread.sleep(100);
            release.countDown();

            IdempotentResult<Accepted> firstResult = first.get(10, TimeUnit.SECONDS);
            IdempotentResult<Accepted> secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(secondResult.replayed()).isTrue();
            assertThat(secondResult.body()).isEqualTo(firstResult.body());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(runs).hasValue(1);
    }

    /** 24시간 지난 응답 본문은 비우고 완료 증거는 남긴다. 비운 뒤에도 같은 키는 만료 오류이고 업무를 다시 하지 않는다. */
    @Test
    void cleanupClearsExpiredReceiptButKeepsEvidence() {
        String expiredKey = UUID.randomUUID().toString();
        String liveKey = UUID.randomUUID().toString();
        IdempotentResult<Accepted> expired = run(request(expiredKey, "body-1", 1L));
        run(request(liveKey, "body-1", 1L));
        jdbc.update("update idempotency_record set replay_expires_at = ? where actor_account_id = ? and idempotency_key = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), account, expiredKey);

        assertThat(cleanup.clearExpiredReceipts()).isGreaterThanOrEqualTo(1);
        assertThat(cleanup.clearExpiredReceipts()).isZero();

        Map<String, Object> cleared = record(expiredKey);
        assertThat(cleared.get("receipt").toString()).isEqualTo(IdempotencyCleanup.CLEARED_RECEIPT);
        assertThat(cleared).containsEntry("request_hash", "body-1").containsEntry("result_type", "TEST_ACCEPTED")
                .containsEntry("http_status", 202);
        assertThat(cleared.get("refs").toString()).contains(expired.body().itemId().toString());
        assertThat(record(liveKey).get("receipt").toString()).contains("itemId");

        assertCode(() -> run(request(expiredKey, "body-1", 1L)), ErrorCode.IDEMPOTENCY_RESULT_EXPIRED);
        assertThat(run(request(liveKey, "body-1", 1L)).replayed()).isTrue();
        assertThat(runs).hasValue(2);
    }

    @Test
    void mustBeCalledInsideTransaction() {
        assertThatThrownBy(() -> idempotency.run(request(UUID.randomUUID().toString(), "body-1", 1L), Accepted.class,
                this::accepted)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(runs).hasValue(0);
    }

    private IdempotencyRequest request(String key, String hash, Long participationRevision) {
        return new IdempotencyRequest(account, OPERATION, key, hash, eventId, null, participationRevision);
    }

    private IdempotentResult<Accepted> run(IdempotencyRequest request) {
        return transaction.execute(status -> idempotency.run(request, Accepted.class, this::accepted));
    }

    private IdempotentResult<Accepted> accepted() {
        runs.incrementAndGet();
        UUID itemId = UUID.randomUUID();
        return IdempotentResult.of(202, new Accepted(itemId, "접수"), "TEST_ACCEPTED", Map.of("itemId", itemId));
    }

    private Map<String, Object> record(String key) {
        return jdbc.queryForMap("""
                select receipt::text as receipt, result_refs::text as refs, request_hash, result_type, http_status
                  from idempotency_record where actor_account_id = ? and idempotency_key = ?
                """, account, key);
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from idempotency_record where actor_account_id = ?", Integer.class,
                account);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}

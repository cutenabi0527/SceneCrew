package com.ssafy.scenecrew.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.global.outbox.domain.OutboxEvent;

/**
 * outbox 작업 처리 규칙을 실제 PostgreSQL(Testcontainers)로 확인한다.
 * 주기 실행은 테스트 설정에서 꺼져 있어 relayOnce()를 직접 부른다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import(OutboxRelayTest.TestHandlers.class)
class OutboxRelayTest {
    static final String OK = "TEST_OK";
    static final String SLOW = "TEST_SLOW";
    static final String FAIL = "TEST_FAIL";
    static final String GATED = "TEST_GATED";

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRelayProperties properties;

    private UUID eventId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox_event");
        TestHandlers.handled.clear();
        TestHandlers.failCalls.set(0);
        TestHandlers.gate.reset();
        eventId = UUID.randomUUID();
        jdbc.update("insert into event (id, name, state, revision, last_input_modified_at) values (?, '테스트 행사', 'DRAFT', 0, now())",
                eventId);
    }

    @Test
    void publishedWorkIsHandledOnceAndMarkedProcessed() {
        UUID id = publish(OK);

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(relay.relayOnce()).isZero();

        assertThat(TestHandlers.handled).containsExactly(id);
        assertThat(row(id)).containsEntry("attempts", 0L);
        assertThat(row(id).get("processed_at")).isNotNull();
        assertThat(row(id).get("lease_until")).isNull();
    }

    @Test
    void publishOutsideTransactionIsRejected() {
        assertThatThrownBy(() -> publisher.publish(eventId, OK, UUID.randomUUID(), Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rolledBackBusinessLeavesNoWork() {
        transaction.executeWithoutResult(status -> {
            publisher.publish(eventId, OK, UUID.randomUUID(), Map.of());
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isZero();
    }

    @Test
    void twoRelaysRunningTogetherHandleEachWorkOnce() throws Exception {
        int total = properties.batchSize() * 2;
        for (int i = 0; i < total; i++) {
            publish(SLOW);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return relay.relayOnce();
            }));
        }
        start.countDown();
        int claimed = 0;
        for (Future<Integer> result : results) {
            claimed += result.get();
        }
        pool.shutdown();

        assertThat(claimed).isEqualTo(total);
        assertThat(TestHandlers.handled).hasSize(total).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where processed_at is null", Integer.class))
                .isZero();
    }

    @Test
    void failedWorkWaitsThenRetriesUntilMaxAttempts() {
        UUID id = publish(FAIL);

        Instant before = Instant.now();
        relay.relayOnce();
        Map<String, Object> afterFirst = row(id);
        assertThat(afterFirst).containsEntry("attempts", 1L);
        assertThat(afterFirst.get("processed_at")).isNull();
        assertThat(afterFirst.get("lease_until")).isNull();
        assertThat(availableAt(id)).isAfterOrEqualTo(before.plus(properties.backoffAfter(1)));

        // 대기 시간이 지나기 전에는 다시 꺼내지 않는다.
        assertThat(relay.relayOnce()).isZero();

        for (int attempt = 2; attempt <= properties.maxAttempts(); attempt++) {
            makeAvailableNow(id);
            relay.relayOnce();
        }
        assertThat(row(id)).containsEntry("attempts", (long) properties.maxAttempts());
        assertThat(TestHandlers.failCalls.get()).isEqualTo(properties.maxAttempts());

        // 한도에 닿으면 대기 시간이 지나도 더 꺼내지 않는다.
        makeAvailableNow(id);
        assertThat(relay.relayOnce()).isZero();
        assertThat(TestHandlers.failCalls.get()).isEqualTo(properties.maxAttempts());
    }

    @Test
    void workHeldByAnotherServerIsSkippedUntilLeaseEnds() {
        UUID id = publish(OK);
        jdbc.update("update outbox_event set lease_until = now() + interval '1 hour' where id = ?", id);

        assertThat(relay.relayOnce()).isZero();

        // 처리하던 서버가 죽어 lease가 끝난 상황
        jdbc.update("update outbox_event set lease_until = now() - interval '1 second' where id = ?", id);
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(TestHandlers.handled).containsExactly(id);
    }

    /**
     * A가 처리하는 동안 lease가 만료돼 B가 이어받은 뒤, A가 늦게 실패해도 B의 담당 표시는 그대로 남아야 한다.
     * 그렇지 않으면 B가 처리 중인 작업을 C가 또 꺼낸다.
     */
    @Test
    void lateResultFromPreviousOwnerDoesNotTouchNewOwner() throws Exception {
        UUID id = publish(GATED);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A: 꺼낸 뒤 handler 안에서 멈춘다.
            Future<Integer> a = pool.submit(() -> relay.relayOnce());
            TestHandlers.gate.awaitEntered(1);

            // A의 lease가 끝난 상황을 만든 뒤 B가 같은 작업을 꺼낸다.
            jdbc.update("update outbox_event set lease_until = now() - interval '1 second' where id = ?", id);
            Future<Integer> b = pool.submit(() -> relay.relayOnce());
            TestHandlers.gate.awaitEntered(2);
            Timestamp leaseOfB = jdbc.queryForObject("select lease_until from outbox_event where id = ?", Timestamp.class, id);

            // A가 늦게 실패로 끝난다. B의 담당 표시와 시도 횟수는 그대로여야 한다.
            TestHandlers.gate.release(1, false);
            assertThat(a.get()).isEqualTo(1);
            assertThat(row(id)).containsEntry("attempts", 0L);
            assertThat(row(id).get("lease_until")).isEqualTo(leaseOfB);
            assertThat(row(id).get("processed_at")).isNull();

            // B가 붙잡고 있는 동안 C는 꺼내지 못한다.
            assertThat(relay.relayOnce()).isZero();

            // B가 성공하면 완료로 기록된다.
            TestHandlers.gate.release(2, true);
            assertThat(b.get()).isEqualTo(1);
            assertThat(row(id).get("processed_at")).isNotNull();
        } finally {
            TestHandlers.gate.releaseAll();
            pool.shutdownNow();
        }
    }

    /** 이전 작업자가 늦게 성공해도, 이어받은 작업자가 있으면 완료로 기록하지 않는다. */
    @Test
    void lateSuccessFromPreviousOwnerIsNotRecorded() throws Exception {
        UUID id = publish(GATED);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> relay.relayOnce());
            TestHandlers.gate.awaitEntered(1);
            jdbc.update("update outbox_event set lease_until = now() - interval '1 second' where id = ?", id);
            Future<Integer> b = pool.submit(() -> relay.relayOnce());
            TestHandlers.gate.awaitEntered(2);

            TestHandlers.gate.release(1, true);
            a.get();
            assertThat(row(id).get("processed_at")).isNull();
            assertThat(row(id).get("lease_until")).isNotNull();

            TestHandlers.gate.release(2, true);
            b.get();
            assertThat(row(id).get("processed_at")).isNotNull();
        } finally {
            TestHandlers.gate.releaseAll();
            pool.shutdownNow();
        }
    }

    @Test
    void workWithoutHandlerStopsImmediately() {
        UUID id = publish("NO_SUCH_TYPE");

        relay.relayOnce();

        assertThat(row(id)).containsEntry("attempts", (long) properties.maxAttempts());
        assertThat(row(id).get("processed_at")).isNull();
        makeAvailableNow(id);
        assertThat(relay.relayOnce()).isZero();
    }

    private UUID publish(String type) {
        OutboxEvent event = transaction.execute(status -> publisher.publish(eventId, type, UUID.randomUUID(),
                Map.of("note", "test")));
        return event.getId();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("select attempts, processed_at, lease_until from outbox_event where id = ?", id);
    }

    private Instant availableAt(UUID id) {
        return jdbc.queryForObject("select available_at from outbox_event where id = ?", Timestamp.class, id).toInstant();
    }

    private void makeAvailableNow(UUID id) {
        jdbc.update("update outbox_event set available_at = now() - interval '1 second' where id = ?", id);
    }

    @TestConfiguration
    static class TestHandlers {
        static final List<UUID> handled = new CopyOnWriteArrayList<>();
        static final AtomicInteger failCalls = new AtomicInteger();

        @Bean
        OutboxHandler okHandler() {
            return handler(OK, event -> handled.add(event.getId()));
        }

        @Bean
        OutboxHandler slowHandler() {
            return handler(SLOW, event -> {
                sleep(50);
                handled.add(event.getId());
            });
        }

        /** n번째 호출마다 멈춰 있다가 release(n, 성공여부)로 끝나는 handler */
        static final Gate gate = new Gate();

        @Bean
        OutboxHandler gatedHandler() {
            return handler(GATED, event -> gate.enterAndWait());
        }

        @Bean
        OutboxHandler failHandler() {
            return handler(FAIL, event -> {
                failCalls.incrementAndGet();
                throw new IllegalStateException("의도한 실패");
            });
        }

        private static OutboxHandler handler(String type, Consumer<OutboxEvent> body) {
            return new OutboxHandler() {
                @Override
                public String type() {
                    return type;
                }

                @Override
                public void handle(OutboxEvent event) {
                    body.accept(event);
                }
            };
        }

        static final class Gate {
            private final AtomicInteger calls = new AtomicInteger();
            private final Map<Integer, CountDownLatch> entered = new ConcurrentHashMap<>();
            private final Map<Integer, CountDownLatch> released = new ConcurrentHashMap<>();
            private final Map<Integer, Boolean> succeed = new ConcurrentHashMap<>();

            void reset() {
                releaseAll();
                calls.set(0);
                entered.clear();
                released.clear();
                succeed.clear();
            }

            void enterAndWait() {
                int call = calls.incrementAndGet();
                entered(call).countDown();
                try {
                    if (!released(call).await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("테스트 대기 시간 초과");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                if (!succeed.getOrDefault(call, true)) {
                    throw new IllegalStateException("의도한 늦은 실패");
                }
            }

            void awaitEntered(int call) throws InterruptedException {
                assertThat(entered(call).await(10, TimeUnit.SECONDS)).isTrue();
            }

            void release(int call, boolean success) {
                succeed.put(call, success);
                released(call).countDown();
            }

            void releaseAll() {
                released.values().forEach(CountDownLatch::countDown);
            }

            private CountDownLatch entered(int call) {
                return entered.computeIfAbsent(call, k -> new CountDownLatch(1));
            }

            private CountDownLatch released(int call) {
                return released.computeIfAbsent(call, k -> new CountDownLatch(1));
            }
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

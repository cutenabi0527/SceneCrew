package com.ssafy.scenecrew.account;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 PostgreSQL에서 Event → Account 경쟁과 잠금 전 참여 목록 변경을 재현한다. #345 전체 적용 시험과는 별개다. */
@SpringBootTest
@ActiveProfiles("test")
class AccountDeactivationConcurrencyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    final List<UUID> events = new ArrayList<>();
    UUID account, identity, event;
    SessionAuthenticator.Principal principal;
    LocalCredentialVerifier verifier;
    LoginFailureLimiter limiter;

    @BeforeEach void fixture() {
        account = UUID.randomUUID(); identity = UUID.randomUUID();
        jdbc.update("INSERT INTO account (id,display_name,status,signup_completed,token_version,consent,created_at) VALUES (?,'fixture','ACTIVE',true,1,'{}',now())", account);
        jdbc.update("INSERT INTO auth_identity VALUES (?,?,'LOCAL',?,'synthetic-hash',?,true)",
                identity, account, account.toString(), account + "@example.invalid");
        principal = new SessionAuthenticator.Principal(account, UUID.randomUUID(), identity, 1L, "WEB");
        verifier = mock(LocalCredentialVerifier.class); limiter = mock(LoginFailureLimiter.class);
        when(verifier.verify(account + "@example.invalid", "SyntheticPass8!"))
                .thenReturn(Optional.of(new LocalCredentialVerifier.VerifiedAccount(account, "fixture", account + "@example.invalid", 1L, identity)));
        event = event(); member(event, "WORKER");
    }

    @AfterEach void cleanup() {
        for (UUID e : events) {
            jdbc.update("DELETE FROM operation_log WHERE event_id=?", e);
            jdbc.update("DELETE FROM member WHERE event_id=?", e);
            jdbc.update("DELETE FROM work_group WHERE event_id=?", e);
            jdbc.update("DELETE FROM event WHERE id=?", e);
        }
        jdbc.update("DELETE FROM auth_identity WHERE account_id=?", account);
        jdbc.update("DELETE FROM account WHERE id=?", account);
    }

    @Test void eventThenAccountWriterAndDeactivationBothCommitWithoutDeadlock() throws Exception {
        var held = new CountDownLatch(1); var takeAccount = new CountDownLatch(1);
        var ownerPid = new AtomicInteger();
        var observed = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                try { return super.queryForObject(sql, type, args); }
                catch (DataAccessException failure) {
                    // 수정 전 재현 결과에서 실제 PostgreSQL 데드락(40P01)인지 구분한다. SQL·값은 출력하지 않는다.
                    if (failure.getMostSpecificCause() instanceof java.sql.SQLException cause)
                        System.out.println("탈퇴 경합 SQLSTATE=" + cause.getSQLState());
                    throw failure;
                }
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> transaction().execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout='3s'");
                ownerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                jdbc.queryForObject("SELECT id FROM event WHERE id=? FOR UPDATE", UUID.class, event);
                held.countDown(); await(takeAccount);
                // 알림 대상 계정을 재확인하는 #345와 같은 잠금 순서다.
                return jdbc.queryForObject("SELECT id FROM account WHERE id=? FOR UPDATE", UUID.class, account);
            }));
            try {
                assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                var deactivation = pool.submit(() -> service(observed).deactivate(principal, "SyntheticPass8!"));
                awaitBlockedBy(ownerPid.get());
                takeAccount.countDown();
                assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo(account);
                assertThat(deactivation.get(10, TimeUnit.SECONDS).status()).isEqualTo("DISABLED");
                assertThat(activeMemberships()).isZero();
            } finally { takeAccount.countDown(); }
        }
    }

    @Test void participationAddedBeforeAccountLockIsIncludedAfterNewTransaction() throws Exception {
        raceNewMembership("WORKER");
    }

    @Test void chiefAddedBeforeAccountLockStillBlocksDeactivation() throws Exception {
        raceNewMembership("CHIEF");
    }

    private void raceNewMembership(String role) throws Exception {
        UUID addedEvent = event();
        var snapshotRead = new CountDownLatch(1); var joined = new CountDownLatch(1);
        var observed = new SnapshotJdbc(at -> {
            if (at == 1) { snapshotRead.countDown(); await(joined); }
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var deactivation = pool.submit(() -> service(observed).deactivate(principal, "SyntheticPass8!"));
            try {
                assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
                join(addedEvent, role); joined.countDown();
                if ("CHIEF".equals(role)) {
                    assertThatThrownBy(() -> deactivation.get(10, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(BusinessException.class)
                            .satisfies(f -> assertThat(((BusinessException) f.getCause()).getErrorCode())
                                    .isEqualTo(ErrorCode.CHIEF_TRANSFER_REQUIRED));
                    assertUntouched(2);
                } else {
                    assertThat(deactivation.get(10, TimeUnit.SECONDS).status()).isEqualTo("DISABLED");
                    assertThat(activeMemberships()).isZero();
                    assertThat(jdbc.queryForList("SELECT revision FROM event WHERE id IN (?,?)", Long.class, event, addedEvent))
                            .containsExactly(2L, 2L);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_log WHERE event_id IN (?,?)", Long.class, event, addedEvent))
                            .isEqualTo(2);
                }
                assertThat(new HashSet<>(observed.transactionIds)).hasSize(2);
                verify(verifier, times(1)).verify(account + "@example.invalid", "SyntheticPass8!");
            } finally { joined.countDown(); }
        }
    }

    @Test void repeatedMembershipChangesStopAfterThreeAttemptsWithoutPartialDeactivation() {
        List<UUID> additions = List.of(event(), event(), event());
        var observed = new SnapshotJdbc(at -> {
            if (at % 2 == 1) join(additions.get((at - 1) / 2), "WORKER");
        });
        assertThatThrownBy(() -> service(observed).deactivate(principal, "SyntheticPass8!"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(observed.reads.get()).isEqualTo(6);
        assertThat(new HashSet<>(observed.transactionIds)).hasSize(3);
        assertUntouched(4);
        verify(verifier, times(1)).verify(account + "@example.invalid", "SyntheticPass8!");
    }

    private void join(UUID eventId, String role) {
        transaction().executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM event WHERE id=? FOR UPDATE", UUID.class, eventId);
            jdbc.queryForObject("SELECT id FROM account WHERE id=? FOR UPDATE", UUID.class, account);
            member(eventId, role);
        });
    }

    private AccountDeactivationService service(JdbcTemplate sql) {
        return new AccountDeactivationService(sql, verifier, limiter, manager, Clock.systemUTC(),
                org.mockito.Mockito.mock(com.ssafy.scenecrew.safety.service.SafetySupportProtection.class));
    }

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private UUID event() {
        UUID id = UUID.randomUUID(); events.add(id);
        jdbc.update("INSERT INTO event (id,name,state,revision,last_input_modified_at) VALUES (?,'fixture','LIVE',1,now())", id);
        return id;
    }

    private void member(UUID eventId, String role) {
        UUID group = UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'fixture','',true)", group, eventId);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,1,?,'fixture',true,false,true,1)
                """, UUID.randomUUID(), eventId, account, "CHIEF".equals(role) ? null : group, role);
    }

    private long activeMemberships() {
        return jdbc.queryForObject("SELECT count(*) FROM member WHERE account_id=? AND active=true", Long.class, account);
    }

    private void assertUntouched(long count) {
        assertThat(jdbc.queryForMap("SELECT status,token_version,deactivated_at FROM account WHERE id=?", account))
                .containsEntry("status", "ACTIVE").containsEntry("token_version", 1L).containsEntry("deactivated_at", null);
        assertThat(activeMemberships()).isEqualTo(count);
        for (UUID e : events) {
            assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?", Long.class, e)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_log WHERE event_id=?", Long.class, e)).isZero();
        }
    }

    private void awaitBlockedBy(int ownerPid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
                    AND ? = ANY(pg_blocking_pids(pid)) AND query LIKE '%SELECT state FROM event%')
                    """, Boolean.class, ownerPid))) return;
            Thread.sleep(20);
        }
        fail("탈퇴 요청의 실제 Event 행 잠금 대기를 확인하지 못했습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("동시성 시험 동기화 시간 초과");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new AssertionError(failure);
        }
    }

    private final class SnapshotJdbc extends JdbcTemplate {
        final AtomicInteger reads = new AtomicInteger();
        final List<Long> transactionIds = new CopyOnWriteArrayList<>();
        final IntConsumer afterSnapshot;
        SnapshotJdbc(IntConsumer afterSnapshot) { super(jdbc.getDataSource()); this.afterSnapshot = afterSnapshot; }
        @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            List<T> result = super.queryForList(sql, type, args);
            if (sql.contains("SELECT DISTINCT event_id FROM member")) {
                transactionIds.add(queryForObject("SELECT txid_current()", Long.class));
                afterSnapshot.accept(reads.incrementAndGet());
            }
            return result;
        }
    }
}

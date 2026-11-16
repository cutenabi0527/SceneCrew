package com.ssafy.scenecrew.account;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL transactions, not repository mock assertions. */
@SpringBootTest
@ActiveProfiles("test")
class AccountProfileDatabaseTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    UUID id, other, event, member;
    SessionAuthenticator.Principal principal;
    AccountProfileService service;

    @BeforeEach void fixture() {
        id = UUID.randomUUID(); other = UUID.randomUUID(); event = UUID.randomUUID(); member = UUID.randomUUID();
        for (UUID account : new UUID[]{id, other})
            jdbc.update("INSERT INTO account VALUES (?, 'original', 'ACTIVE', true, 3, '{}'::jsonb, now())", account);
        jdbc.update("INSERT INTO event (id,name,state,revision,last_input_modified_at) VALUES (?, 'fixture', 'DRAFT', 0, now())", event);
        jdbc.update("""
                INSERT INTO member VALUES (?, ?, ?, NULL, 1, 'CHIEF', 'member-original', true, false, true, 1)
                """, member, event, id);
        principal = new SessionAuthenticator.Principal(id, UUID.randomUUID(), UUID.randomUUID(), 3, "WEB");
        service = new AccountProfileService(jdbc, new AccountSummaryReader(jdbc), manager);
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM member WHERE id=?", member);
        jdbc.update("DELETE FROM event WHERE id=?", event);
        jdbc.update("DELETE FROM account WHERE id IN (?,?)", id, other);
    }

    @Test void trimsCountsCodePointsAndRepeatedRequestsPreserveOtherState() {
        String name = "😀".repeat(20);
        assertThat(service.update(principal, "  " + name + "  ").displayName()).isEqualTo(name);
        assertThat(service.update(principal, name).displayName()).isEqualTo(name);
        assertThat(name(other)).isEqualTo("original");
        assertThat(jdbc.queryForObject("SELECT display_name FROM member WHERE id=?", String.class, member)).isEqualTo("member-original");
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?", Long.class, event)).isZero();
        assertThat(jdbc.queryForObject("SELECT token_version FROM account WHERE id=?", Long.class, id)).isEqualTo(3);
        assertThat(new AccountSummaryReader(jdbc).read(principal).displayName()).isEqualTo(name);
    }

    @Test void invalidNamesNeverWrite() {
        for (String invalid : new String[]{null, "", "   ", "a".repeat(21), "😀".repeat(21),
                "a\nb", "\tname", "name\r", "a\u0000b", "a\u0085b", "a\u2028b", "a\u2029b", "\uD800"}) {
            assertThatThrownBy(() -> service.update(principal, invalid)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        assertThat(name(id)).isEqualTo("original");
    }

    @Test void staleOrInactiveAccountsCannotWrite() {
        for (String change : new String[]{"token_version=4", "status='DISABLED'", "signup_completed=false"}) {
            jdbc.update("UPDATE account SET token_version=3, status='ACTIVE', signup_completed=true WHERE id=?", id);
            jdbc.update("UPDATE account SET " + change + " WHERE id=?", id);
            assertThatThrownBy(() -> service.update(principal, "denied")).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
            assertThat(name(id)).isEqualTo("original");
        }
    }

    @Test void databaseFailureAfterUpdateRollsBack() {
        var reader = mock(AccountSummaryReader.class);
        when(reader.read(principal)).thenAnswer(call -> jdbc.queryForObject("SELECT 1/0", Integer.class));
        var failing = new AccountProfileService(jdbc, reader, manager);
        assertThatThrownBy(() -> failing.update(principal, "rollback")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(name(id)).isEqualTo("original");
    }

    @Test void deferredDatabaseFailureAtCommitRollsBackBeforeSuccessReturns() {
        String fixture = "profile_failure_" + id.toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + fixture + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic commit failure'; END $$");
        try {
            jdbc.execute("CREATE CONSTRAINT TRIGGER " + fixture + " AFTER UPDATE ON account "
                    + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.id='" + id + "'::uuid) "
                    + "EXECUTE FUNCTION " + fixture + "()");
            try {
                assertThatThrownBy(() -> service.update(principal, "commit-failure"))
                        .isInstanceOfSatisfying(BusinessException.class,
                                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
                assertThat(name(id)).isEqualTo("original");
            } finally { jdbc.execute("DROP TRIGGER " + fixture + " ON account"); }
        } finally { jdbc.execute("DROP FUNCTION " + fixture + "()"); }
    }

    @Test void concurrentUpdatesSerializeAndReturnTheirOwnCommittedName() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var reader = mock(AccountSummaryReader.class);
        when(reader.read(principal)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return new AccountSummaryReader(jdbc).read(principal);
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new AccountProfileService(jdbc, reader, manager).update(principal, "first"));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> service.update(principal, "second"));
                awaitBlockedUpdate();
                release.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS).displayName()).isEqualTo("first");
                assertThat(second.get(10, TimeUnit.SECONDS).displayName()).isEqualTo("second");
                assertThat(name(id)).isEqualTo("second");
            } finally { release.countDown(); }
        }
    }

    @Test void revocationCommittedWhileUpdateWaitsIsRechecked() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var revoke = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.update("UPDATE account SET token_version=4 WHERE id=?", id);
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                return null;
            }));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var update = pool.submit(() -> service.update(principal, "denied"));
                awaitBlockedUpdate(); release.countDown(); revoke.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class)
                        .satisfies(e -> assertThat(((BusinessException)e.getCause()).getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
                assertThat(name(id)).isEqualTo("original");
            } finally { release.countDown(); }
        }
    }

    private String name(UUID account) { return jdbc.queryForObject("SELECT display_name FROM account WHERE id=?", String.class, account); }
    private void awaitBlockedUpdate() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
                    AND wait_event_type='Lock' AND query LIKE 'UPDATE account SET display_name=%')
                    """, Boolean.class))) return;
            Thread.sleep(20);
        }
        fail("Expected a real PostgreSQL row-lock wait");
    }
}

package com.ssafy.scenecrew.account;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** SQL fixture for #224 feasibility, NOT a production verify-email implementation. */
@SpringBootTest
@ActiveProfiles("test")
class RegistrationDatabaseBoundaryTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    final List<UUID> ids = new ArrayList<>();
    @AfterEach void cleanup() {
        for (UUID id:ids) {
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",id);
            jdbc.update("DELETE FROM account WHERE id=?",id);
        }
    }
    void account(UUID id) {
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 0, '{}'::jsonb, now())",id);
    }
    void identity(UUID account,String email) {
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, 'synthetic-hash', ?, true)",
                UUID.randomUUID(),account,account.toString(),email);
    }
    @Test void normalizedUniqueRejectsOneConcurrentCompletionAndRollsBackItsAccount() throws Exception {
        var a=UUID.randomUUID(); var b=UUID.randomUUID(); ids.addAll(List.of(a,b));
        String email=UUID.randomUUID()+"@example.invalid";
        var bothAccountsInserted=new CyclicBarrier(2);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->complete(a,email,bothAccountsInserted));
            var second=pool.submit(()->complete(b," "+email.toUpperCase(Locale.ROOT)+" ",bothAccountsInserted));
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMMITTED","UNIQUE_REJECTED");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE id IN (?,?)",Integer.class,a,b)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id IN (?,?)",Integer.class,a,b)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity i JOIN account a ON a.id=i.account_id"
                +" WHERE lower(btrim(i.email))=? AND a.signup_completed AND i.email_verified",Integer.class,email)).isEqualTo(1);
    }
    String complete(UUID id,String email,CyclicBarrier barrier) {
        try {
            new TransactionTemplate(manager).executeWithoutResult(status->{
                account(id);
                try { barrier.await(10,TimeUnit.SECONDS); }
                catch (Exception e) { throw new IllegalStateException("fixture barrier failed",e); }
                identity(id,email);
            });
            return "COMMITTED";
        } catch (DataIntegrityViolationException e) {
            assertThat(e.getMostSpecificCause()).isInstanceOfSatisfying(java.sql.SQLException.class,
                    sql -> assertThat(sql.getSQLState()).isEqualTo("23505"));
            assertThat(e.getMostSpecificCause().getMessage()).contains("uq_local_email_normalized");
            return "UNIQUE_REJECTED";
        }
    }
    @Test void laterIdentityFailureRollsBackAccountAndAllowsCleanRetry() {
        UUID id=UUID.randomUUID(); ids.add(id);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{
            account(id); identity(id,null);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id=?",Integer.class,id)).isZero();
        new TransactionTemplate(manager).executeWithoutResult(status->{ account(id); identity(id,id+"@example.invalid"); });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void socialReferenceEmailDoesNotParticipateInLocalUniqueness() {
        UUID social=UUID.randomUUID(),local=UUID.randomUUID(); ids.addAll(List.of(social,local));
        String email=local+"@example.invalid";
        new TransactionTemplate(manager).executeWithoutResult(status->{
            account(social); account(local);
            jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'GOOGLE', ?, NULL, ?, true)",
                    UUID.randomUUID(),social,social.toString(),email);
            identity(local,email);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE email=?",Integer.class,email)).isEqualTo(2);
    }
}

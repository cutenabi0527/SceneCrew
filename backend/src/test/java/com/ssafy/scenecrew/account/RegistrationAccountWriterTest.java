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
import com.ssafy.scenecrew.account.dto.RegisterRequest;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.BusinessException;
import static org.assertj.core.api.Assertions.*;

/** Calls the production writer against PostgreSQL 17; no mocked transaction or INSERT fixture. */
@SpringBootTest
@ActiveProfiles("test")
class RegistrationAccountWriterTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    String email;
    RegistrationAccountWriter writer;
    static final RegisterRequest.Consent CONSENT = new RegisterRequest.Consent(true,false,false,false);
    @BeforeEach void setup() {
        email = UUID.randomUUID()+"@example.invalid";
        writer = new RegistrationAccountWriter(jdbc,manager);
    }
    @AfterEach void cleanup() {
        var ids=jdbc.queryForList("SELECT account_id FROM auth_identity WHERE email=?",UUID.class,email);
        jdbc.update("DELETE FROM auth_identity WHERE email=?",email);
        for (var id:ids) jdbc.update("DELETE FROM account WHERE id=?",id);
    }
    @Test void createsActiveVerifiedLocalAndConsentInOneCommit() {
        var result=writer.create(email,"fixture","synthetic-hash",CONSENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account a JOIN auth_identity i ON i.account_id=a.id"
                +" WHERE a.id=? AND a.status='ACTIVE' AND a.signup_completed AND i.email_verified"
                +" AND i.provider='LOCAL' AND a.consent->>'service'='true'",Integer.class,result.accountId())).isEqualTo(1);
        // 가입 때 동의한 약관 버전·시각을 이력으로 남긴다(#334, V9-D40).
        assertThat(jdbc.queryForMap("SELECT consent->>'version' AS v, consent->'history'->0->>'source' AS s,"
                +" consent->'history'->0->>'at' AS at FROM account WHERE id=?",result.accountId()))
                .containsEntry("v",com.ssafy.scenecrew.account.service.ConsentRecords.VERSION).containsEntry("s","SIGNUP")
                .extractingByKey("at").isNotNull();
    }
    @Test void identityCheckFailureRollsBackAccountThenRetrySucceeds() {
        int before=jdbc.queryForObject("SELECT count(*) FROM account",Integer.class);
        assertThatThrownBy(()->writer.create(email,"fixture",null,CONSENT)).isInstanceOfSatisfying(RegistrationAccountWriter.CreationFailure.class,
                failure -> assertThat(failure.rolledBack()).isTrue());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account",Integer.class)).isEqualTo(before);
        writer.create(email,"fixture","synthetic-hash",CONSENT);
    }
    @Test void competingWritersCommitOnlyOneAccount() throws Exception {
        int before=jdbc.queryForObject("SELECT count(*) FROM account",Integer.class);
        var bothPrechecks=new CyclicBarrier(2);
        var traced=org.mockito.Mockito.spy(jdbc);
        org.mockito.Mockito.doAnswer(call -> {
            Object result=call.callRealMethod();
            bothPrechecks.await(10,TimeUnit.SECONDS);
            return result;
        }).when(traced).queryForList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Boolean.class),org.mockito.ArgumentMatchers.eq(email));
        writer=new RegistrationAccountWriter(traced,manager);
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->attempt(gate)); var b=pool.submit(()->attempt(gate));
            gate.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account",Integer.class)).isEqualTo(before+1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE email=?",Integer.class,email)).isEqualTo(1);
    }
    @Test void lostCommitAcknowledgementIsUncertainAndAccountRemainsCommitted() {
        var lostAck=new PlatformTransactionManager() {
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition d) {
                return manager.getTransaction(d);
            }
            public void commit(org.springframework.transaction.TransactionStatus status) {
                manager.commit(status);
                throw new org.springframework.transaction.TransactionSystemException("synthetic lost commit acknowledgement");
            }
            public void rollback(org.springframework.transaction.TransactionStatus status) { manager.rollback(status); }
        };
        var uncertain=new RegistrationAccountWriter(jdbc,lostAck);
        assertThatThrownBy(()->uncertain.create(email,"fixture","synthetic-hash",CONSENT))
                .isInstanceOfSatisfying(RegistrationAccountWriter.CreationFailure.class,
                        failure -> assertThat(failure.rolledBack()).isFalse());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE email=?",Integer.class,email)).isEqualTo(1);
    }
    boolean attempt(CountDownLatch gate) throws Exception {
        gate.await(10,TimeUnit.SECONDS);
        try { writer.create(email,"fixture","synthetic-hash",CONSENT); return true; }
        catch (RegistrationAccountWriter.CreationFailure e) {
            assertThat(e.rolledBack()).isTrue();
            assertThat(e.code()).isEqualTo(com.ssafy.scenecrew.global.error.ErrorCode.EMAIL_ALREADY_REGISTERED);
            return false;
        }
    }
}

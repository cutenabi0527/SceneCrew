package com.ssafy.scenecrew.account;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.oauth.*;
import com.ssafy.scenecrew.account.repository.*;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** Real PostgreSQL 17 and private Redis. DISABLED writes simulate the future writer, not an API. */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SuppressWarnings("unchecked")
class AccountIssuanceIntegrationTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.datasource.url", () -> "jdbc:tc:postgresql:17-alpine:///issuance_228");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.data.redis.timeout", () -> "500ms");
        r.add("spring.data.redis.connect-timeout", () -> "500ms");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AccountRepository accounts;
    @Autowired AuthIdentityRepository identities;
    @MockitoSpyBean StringRedisTemplate redis;
    final Clock clock=Clock.systemUTC();
    final SecretKeySpec key=new SecretKeySpec(new byte[32],"HmacSHA256");
    final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(4);
    final Set<UUID> owned=ConcurrentHashMap.newKeySet();
    volatile UUID id;
    boolean incrementVersion=true;
    UUID local, social;
    String email;
    LoginTokenIssuer tokens;
    RedisLoginSessions sessions;
    RedisOAuthAttempts attempts;
    SessionAuthenticator authentication;
    final AtomicReference<LoginTokens> attempted=new AtomicReference<>();
    final AtomicReference<String> ticketKey=new AtomicReference<>();

    @BeforeEach void fixture() {
        reset(redis); attempted.set(null); ticketKey.set(null);
        id=account(); local=UUID.randomUUID(); social=UUID.randomUUID(); email=id+"@example.invalid";
        jdbc.update("INSERT INTO auth_identity VALUES (?,?,'LOCAL',?,?,?,true)",local,id,id.toString(),encoder.encode("SyntheticPass8!"),email);
        jdbc.update("INSERT INTO auth_identity VALUES (?,?,'GOOGLE',?,NULL,NULL,false)",social,id,"social-"+id);
        tokens=spy(new LoginTokenIssuer(key,clock,"issuance-test","issuance-test"));
        sessions=spy(new RedisLoginSessions(redis,clock)); attempts=new RedisOAuthAttempts(redis,clock);
        authentication=new SessionAuthenticator(new AccessJwtVerifier(key,clock,"issuance-test","issuance-test"),
                redis,new CurrentAccountReader(jdbc,manager),clock);
    }
    UUID account() {
        UUID value=UUID.randomUUID(); owned.add(value);
        jdbc.update("INSERT INTO account VALUES (?,'fixture','ACTIVE',true,3,'{}'::jsonb,now())",value);
        return value;
    }
    LocalCredentialVerifier.VerifiedAccount verified() {
        return new LocalCredentialVerifier.VerifiedAccount(id,"fixture",email,3,local);
    }
    @AfterEach void cleanup() {
        reset(redis);
        // Only this test's private Redis container is used; no shared/default Redis is touched.
        var keys=redis.keys("auth:*"); if(keys!=null && !keys.isEmpty()) redis.delete(keys);
        for(UUID value:owned) {
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",value);
            jdbc.update("DELETE FROM account WHERE id=?",value);
        }
        owned.clear();
    }
    void error(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(expected)).hasNoCause();
    }
    static void signalWait(CountDownLatch latch) {
        try { assertThat(latch.await(15,TimeUnit.SECONDS)).isTrue(); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    <T> T before(T value, Runnable hook) { hook.run(); return value; }

    Supplier<LoginTokens> operation(String mode, Runnable beforeGuard) {
        if(mode.equals("LOCAL")) {
            var verifier=spy(new LocalCredentialVerifier(identities,accounts,encoder));
            doAnswer(call -> before(call.callRealMethod(),beforeGuard)).when(verifier).verify(anyString(),anyString());
            var login=new EmailLoginService(verifier,accounts,identities,tokens,sessions,new AccountIssuanceGuard(jdbc,manager));
            return () -> login.login(email,"SyntheticPass8!","WEB").tokens();
        }
        if(mode.equals("REFRESH")) {
            var initial=tokens.issue(verified()); sessions.register(verified(),initial,"WEB"); attempted.set(initial);
            var reader=spy(new CurrentAccountReader(jdbc,manager));
            doAnswer(call -> before(call.callRealMethod(),beforeGuard)).when(reader).isActive(any());
            var refresh=new RefreshService(redis,reader,tokens,clock);
            return () -> refresh.rotate(initial.refreshToken(),"WEB","AAAAAAAAAAAAAAAAAAAAAA");
        }
        String initiator=attempts.secret(); String subject="social-"+id;
        String purpose=mode.equals("SIGNUP")?"COMPLETE_SIGNUP":"LOGIN";
        var proof=new RedisOAuthAttempts.Attempt(SocialProvider.GOOGLE,purpose,"WEB",UUID.randomUUID().toString(),
                RedisOAuthAttempts.hash(initiator),"https://web.example.invalid/callback",clock.instant().plusSeconds(90),subject,null);
        String kind=mode.equals("SIGNUP")?"signup":"ticket";
        if(mode.equals("SIGNUP")) {
            // The new signup must use a different subject from the existing linked identity.
            proof=new RedisOAuthAttempts.Attempt(proof.provider(),proof.purpose(),proof.channel(),proof.attemptId(),
                    proof.initiatorHash(),proof.returnUri(),proof.expiresAt(),"new-"+id,null);
        }
        String secret=attempts.save(kind,proof,Duration.ofSeconds(90)); ticketKey.set(RedisOAuthAttempts.key(kind,secret));
        if(mode.equals("OAUTH")) {
            var reader=spy(new OAuthAccountReader(jdbc,manager));
            doAnswer(call -> before(call.callRealMethod(),beforeGuard)).when(reader).linked(any(),anyString());
            var login=new OAuthLoginService(List.of(),Map.of("WEB:HOME",URI.create("https://web.example.invalid/callback")),
                    attempts,reader,tokens,sessions,clock,Duration.ofSeconds(90));
            return () -> login.exchange(secret,"LOGIN","WEB",initiator).tokens();
        }
        var writer=spy(new SocialSignupAccounts(jdbc,manager));
        doAnswer(call -> {
            var created=(LocalCredentialVerifier.VerifiedAccount)call.callRealMethod();
            id=created.accountId(); owned.add(id); beforeGuard.run(); return created;
        }).when(writer).create(any(),anyString(),any());
        var signup=new SocialSignupService(attempts,writer,tokens,sessions,clock,Duration.ofSeconds(90));
        return () -> signup.complete(secret,"WEB",initiator,"new fixture",new SocialSignupService.Consent(true,false,false,false)).tokens();
    }

    /** Observe an actual Redis write while its caller still owns the PostgreSQL shared lock. */
    void afterRedis(Runnable hook) {
        doAnswer(call -> { attempted.set(call.getArgument(1)); call.callRealMethod(); hook.run(); return null; })
                .when(sessions).register(any(),any(),anyString());
        doAnswer(call -> { attempted.set(call.getArgument(1)); call.callRealMethod(); hook.run(); return null; })
                .when(sessions).registerConsumingOAuthTicket(any(),any(),anyString(),anyString(),anyString(),anyString());
        doAnswer(call -> {
            Object result=call.callRealMethod();
            List<?> keys=call.getArgument(1);
            if(keys.size()==3) hook.run();
            return result;
        }).when(redis).execute(any(RedisScript.class),anyList(),any(Object[].class));
    }
    void assertWaiting(int pid) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT cardinality(pg_blocking_pids(?))",Integer.class,pid)).isPositive());
    }
    void disable() {
        jdbc.update(incrementVersion
                ? "UPDATE account SET status='DISABLED',token_version=token_version+1 WHERE id=?"
                : "UPDATE account SET status='DISABLED' WHERE id=?",id);
    }
    String value(LoginTokens issued) { return redis.opsForValue().get(RedisLoginSessions.key(issued.sessionId())); }

    @ParameterizedTest @ValueSource(strings={"LOCAL","OAUTH","REFRESH","SIGNUP"})
    void issuanceFirstHoldsRealLockThroughRedisAndLaterCredentialsAreRejected(String mode) throws Exception {
        UUID otherId=account(); UUID otherIdentity=UUID.randomUUID(); String otherEmail=otherId+"@example.invalid";
        jdbc.update("INSERT INTO auth_identity VALUES (?,?,'LOCAL',?,?,?,true)",otherIdentity,otherId,
                otherId.toString(),encoder.encode("SyntheticPass8!"),otherEmail);
        var other=new LocalCredentialVerifier.VerifiedAccount(otherId,"other",otherEmail,3,otherIdentity);
        var otherTokens=tokens.issue(other); sessions.register(other,otherTokens,"ANDROID"); String otherValue=value(otherTokens);
        var action=operation(mode,()->{});
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        afterRedis(()->{
            assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("off");
            entered.countDown();signalWait(release);
        });
        try(var pool=Executors.newFixedThreadPool(2)) {
            var issuing=pool.submit(action::get);
            try {
                signalWait(entered);
                assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id=?",String.class,id)).isEqualTo("ACTIVE");
                var started=new CountDownLatch(1); var pid=new AtomicInteger();
                var disabling=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                    pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class)); started.countDown(); disable(); return true;
                }));
                signalWait(started); assertWaiting(pid.get()); assertThat(disabling.isDone()).isFalse();
                release.countDown(); var issued=issuing.get(15,TimeUnit.SECONDS); assertThat(disabling.get(15,TimeUnit.SECONDS)).isTrue();
                assertThat(value(issued)).isNotNull();
                error(()->authentication.authenticate(issued.accessToken()),ErrorCode.UNAUTHENTICATED);
                error(()->new RefreshService(redis,new CurrentAccountReader(jdbc,manager),tokens,clock)
                        .rotate(issued.refreshToken(),"WEB","BBBBBBBBBBBBBBBBBBBBBB"),ErrorCode.UNAUTHENTICATED);
                assertThat(value(otherTokens)).isEqualTo(otherValue);
                assertThat(authentication.authenticate(otherTokens.accessToken()).accountId()).isEqualTo(otherId);
                assertThat(jdbc.queryForObject("SELECT token_version FROM account WHERE id=?",Long.class,id)).isEqualTo((mode.equals("SIGNUP")?0L:3L)+(incrementVersion?1L:0L));
            } finally { release.countDown(); }
        }
    }

    @Test void statusOnlyDeactivationAlsoWaitsForRefreshAndRevokesOldVersionCredentials() throws Exception {
        incrementVersion=false;
        issuanceFirstHoldsRealLockThroughRedisAndLaterCredentialsAreRejected("REFRESH");
    }

    @ParameterizedTest @ValueSource(strings={"LOCAL","OAUTH","REFRESH","SIGNUP"})
    void deactivationFirstWaitsThenRejectsBeforeTokenCreationOrRedisMutation(String mode) throws Exception {
        var atGuard=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        var action=operation(mode,()->{atGuard.countDown();signalWait(proceed);});
        try(var pool=Executors.newFixedThreadPool(2)) {
            var issuing=pool.submit(()->{error(action::get,mode.equals("LOCAL")?ErrorCode.INVALID_CREDENTIALS:ErrorCode.UNAUTHENTICATED);return true;});
            try {
                signalWait(atGuard);
                clearInvocations(tokens);
                var before=new HashMap<String,String>();
                for(String k:redis.keys("auth:session:*")) before.put(k,redis.opsForValue().get(k));
                var locked=new CountDownLatch(1); var commit=new CountDownLatch(1);
                var disabling=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                    disable(); locked.countDown(); signalWait(commit); return true;
                }));
                try {
                    signalWait(locked); proceed.countDown();
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(jdbc.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
                            AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM account%FOR SHARE%')
                            """,Boolean.class)).isTrue());
                    commit.countDown(); assertThat(disabling.get(15,TimeUnit.SECONDS)).isTrue();
                    assertThat(issuing.get(15,TimeUnit.SECONDS)).isTrue();
                    var after=new HashMap<String,String>();
                    for(String k:redis.keys("auth:session:*")) after.put(k,redis.opsForValue().get(k));
                    assertThat(after).isEqualTo(before);
                    verify(tokens,never()).issue(any());
                    if(mode.equals("OAUTH")) assertThat(redis.hasKey(ticketKey.get())).isTrue();
                    if(mode.equals("SIGNUP")) assertThat(redis.hasKey(ticketKey.get())).isFalse();
                } finally { commit.countDown(); }
            } finally { proceed.countDown(); }
        }
    }

    @ParameterizedTest @ValueSource(strings={"status","signup","version","missingIdentity","owner","provider","emailVerified","socialSubject"})
    void guardRejectsFreshStateChangesWithoutInvokingIssuance(String change) {
        UUID identity=local; String provider="LOCAL";
        switch(change) {
            case "status" -> jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",id);
            case "signup" -> jdbc.update("UPDATE account SET signup_completed=false WHERE id=?",id);
            case "version" -> jdbc.update("UPDATE account SET token_version=4 WHERE id=?",id);
            case "missingIdentity" -> jdbc.update("DELETE FROM auth_identity WHERE id=?",local);
            case "owner" -> jdbc.update("UPDATE auth_identity SET account_id=? WHERE id=?",account(),local);
            case "provider" -> provider="NAVER";
            case "emailVerified" -> jdbc.update("UPDATE auth_identity SET email_verified=false WHERE id=?",local);
            case "socialSubject" -> {identity=social;provider="GOOGLE";jdbc.update("UPDATE auth_identity SET subject='' WHERE id=?",social);}
        }
        var ran=new AtomicBoolean(); UUID target=identity; String selected=provider;
        error(()->new AccountIssuanceGuard(jdbc,manager).issue(id,target,3,selected,ErrorCode.UNAUTHENTICATED,
                ()->{ran.set(true);return true;}),ErrorCode.UNAUTHENTICATED);
        assertThat(ran).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"LOCAL","OAUTH","REFRESH","SIGNUP"})
    void realDeferredCommitFailureAfterRedisDoesNotReturnOrRestoreCredentials(String mode) {
        String name="issuance_failure_"+UUID.randomUUID().toString().replace("-","");
        var action=operation(mode,()->{});
        jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic commit failure'; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER "+name+" AFTER UPDATE ON account DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "+name+"()");
        afterRedis(()->jdbc.update("UPDATE account SET display_name='rolled back' WHERE id=?",id));
        try {
            error(action::get,ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id=?",String.class,id)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT display_name FROM account WHERE id=?",String.class,id)).isNotEqualTo("rolled back");
            assertThat(redis.keys("auth:session:*")).hasSize(1);
            // Partial-failure observation: ACTIVE does not logically revoke this retained session.
            // LOCAL/OAuth/signup credentials are captured only inside the server-side test spy;
            // Refresh uses the client's previously issued Access. No successful result was returned above.
            assertThat(authentication.authenticate(attempted.get().accessToken()).accountId())
                    .as("retained session remains authenticatable while Account is ACTIVE").isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id=?",Integer.class,id))
                    .isEqualTo(mode.equals("SIGNUP")?1:2);
            if(mode.equals("REFRESH")) {
                assertThat(redis.hasKey(RefreshService.receiptKey(attempted.get().sessionId()))).isTrue();
                // Previous proof stays consumed even though PostgreSQL commit failed.
                reset(redis);
                error(action::get,ErrorCode.REFRESH_RECOVERY_UNAVAILABLE);
            }
            if(mode.equals("OAUTH") || mode.equals("SIGNUP")) assertThat(redis.hasKey(ticketKey.get())).isFalse();
        } finally {
            reset(redis); jdbc.execute("DROP TRIGGER "+name+" ON account"); jdbc.execute("DROP FUNCTION "+name+"()");
        }
    }

    @Test void lostDatabaseCommitAcknowledgementReturnsFailureWithoutUndoingRedisOrCommittedDb() {
        // Commit through the real resource manager, then inject only the missing acknowledgement.
        PlatformTransactionManager uncertain=new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                throw new TransactionSystemException("synthetic lost commit acknowledgement");
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        error(()->new AccountIssuanceGuard(jdbc,uncertain).issue(id,local,3,"LOCAL",ErrorCode.UNAUTHENTICATED,()->{
            var issued=tokens.issue(verified()); sessions.register(verified(),issued,"WEB"); attempted.set(issued);
            jdbc.update("UPDATE account SET display_name='server committed' WHERE id=?",id);
            return issued;
        }),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT display_name FROM account WHERE id=?",String.class,id))
                .isEqualTo("server committed");
        assertThat(value(attempted.get())).isNotNull();
        disable(); error(()->authentication.authenticate(attempted.get().accessToken()),ErrorCode.UNAUTHENTICATED);
    }

    @Test void redisFailureBeforeWriteLeavesStateAndExistingSessionUntouched() {
        var initial=tokens.issue(verified()); sessions.register(verified(),initial,"WEB"); String snapshot=value(initial);
        var action=operation("LOCAL",()->{});
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(sessions).register(any(),any(),anyString());
        error(action::get,ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(value(initial)).isEqualTo(snapshot); assertThat(redis.keys("auth:session:*")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id=?",String.class,id)).isEqualTo("ACTIVE");
        // A later real UPDATE completes, proving the failure released the shared lock.
        disable();
    }

    @Test void realRotationThenInjectedLostAcknowledgementNeverRestoresOldProof() {
        var action=operation("REFRESH",()->{}); LoginTokens initial=attempted.get(); String before=value(initial);
        afterRedis(()->{throw new org.springframework.data.redis.RedisConnectionFailureException("synthetic lost acknowledgement");});
        error(action::get,ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(value(initial)).isNotNull().isNotEqualTo(before);
        assertThat(redis.hasKey(RefreshService.receiptKey(initial.sessionId()))).isTrue();
        reset(redis); error(action::get,ErrorCode.REFRESH_RECOVERY_UNAVAILABLE);
        disable(); error(()->authentication.authenticate(initial.accessToken()),ErrorCode.UNAUTHENTICATED);
    }
}

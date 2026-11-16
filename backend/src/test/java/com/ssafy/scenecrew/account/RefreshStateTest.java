package com.ssafy.scenecrew.account;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.*;
import org.testcontainers.containers.GenericContainer;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual standalone Redis; DB is stubbed here, real DB plus /me HTTP is covered separately. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("unchecked")
class RefreshStateTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    final Clock clock = Clock.systemUTC();
    final String attempt = "AAAAAAAAAAAAAAAAAAAAAA", otherAttempt = "BBBBBBBBBBBBBBBBBBBBBB";
    final LocalCredentialVerifier.VerifiedAccount account = new LocalCredentialVerifier.VerifiedAccount(
            UUID.randomUUID(), "fixture", "fixture@example.invalid", 3, UUID.randomUUID());
    final LoginTokenIssuer issuer = issuer(clock);
    CurrentAccountReader accounts;
    RedisLoginSessions store;
    RefreshService refresh;
    LogoutService logout;
    @BeforeAll static void start() {
        REDIS.start(); factory = new LettuceConnectionFactory(
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
                org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(500)).shutdownTimeout(Duration.ZERO).build());
        factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
    }
    @AfterAll static void stop() { if (factory != null) factory.destroy(); REDIS.stop(); }
    // Redis-only suite: DB checks/locking are covered by AccountIssuanceIntegrationTest.
    static CurrentAccountReader mockReader() {
        return mock(CurrentAccountReader.class, call -> {
            if (call.getMethod().getName().equals("withIssuanceLock"))
                return ((java.util.function.Supplier<?>)call.getArgument(1)).get();
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
    }
    @BeforeEach void setup() {
        accounts = mockReader(); when(accounts.isActive(any())).thenReturn(true);
        store = new RedisLoginSessions(redis, clock); refresh = new RefreshService(redis, accounts, issuer, clock);
        logout = new LogoutService(redis, accounts, clock);
    }
    LoginTokenIssuer issuer(Clock source) { return new LoginTokenIssuer(new SecretKeySpec(new byte[32], "HmacSHA256"), source, "test", "test"); }
    LoginTokens session(String channel) { var t = issuer.issue(account); store.register(account, t, channel); return t; }
    LoginTokens session(String channel, String provider) {
        if ("LOCAL".equals(provider)) return session(channel);
        var t = session(channel);
        String key = RedisLoginSessions.key(t.sessionId());
        String local = value(t);
        redis.opsForValue().set(key, "v2" + local.substring(2) + "|" + provider,
                Duration.between(clock.instant(), t.refreshExpiresAt()));
        assertThat(StoredLoginSession.parse(value(t),clock).provider()).isEqualTo(provider);
        return t;
    }
    String value(LoginTokens t) { return redis.opsForValue().get(RedisLoginSessions.key(t.sessionId())); }
    SessionAuthenticator auth(Clock source) { return new SessionAuthenticator(new AccessJwtVerifier(
            new SecretKeySpec(new byte[32], "HmacSHA256"), source, "test", "test"), redis, accounts, source); }
    void denied(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(code); assertThat(e.getCause()).isNull();
        });
    }

    @Test @Order(1) void rotationConsumesOldRefreshRetainsSidAndOldAccessAndOtherDevices() {
        var old = session("WEB"); var other = session("ANDROID"); String otherValue = value(other);
        var next = refresh.rotate(old.refreshToken(), "WEB", attempt);
        assertThat(next.sessionId()).isEqualTo(old.sessionId());
        assertThat(next.refreshToken()).isNotEqualTo(old.refreshToken());
        assertThat(next.accessToken()).isNotEqualTo(old.accessToken());
        assertThat(next.accessExpiresAt()).isBetween(clock.instant().plusSeconds(3590), clock.instant().plusSeconds(3600));
        assertThat(next.refreshExpiresAt()).isBetween(clock.instant().plusSeconds(1209590), clock.instant().plusSeconds(1209600));
        assertThat(redis.getExpire(RedisLoginSessions.key(old.sessionId()))).isBetween(1209590L,1209600L);
        assertThat(value(other)).isEqualTo(otherValue);
        for (var t : List.of(old, next, other)) assertThat(auth(clock).authenticate(t.accessToken()).accountId()).isEqualTo(account.accountId());
        String receipt = redis.opsForValue().get(RefreshService.receiptKey(old.sessionId()));
        assertThat(receipt).doesNotContain(old.refreshToken(),next.refreshToken(),next.accessToken(),old.accessToken());
        assertThat(value(next)).doesNotContain(next.refreshToken(),next.accessToken());
        denied(() -> refresh.rotate(old.refreshToken(), "WEB", otherAttempt), ErrorCode.REFRESH_ATTEMPT_MISMATCH);
    }
    @Test @Order(1) void sameSecondRotationsProduceDistinctAccessTokensWithoutChangingClaimsPolicy() {
        Clock fixed = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        var fixedIssuer = issuer(fixed); var old = fixedIssuer.issue(account); store.register(account,old,"WEB");
        var core = new RefreshService(redis, accounts, fixedIssuer, fixed);
        var b = core.rotate(old.refreshToken(),"WEB",attempt); var c = core.rotate(b.refreshToken(),"WEB",otherAttempt);
        assertThat(new HashSet<>(List.of(old.accessToken(),b.accessToken(),c.accessToken()))).hasSize(3);
        assertThat(b.accessExpiresAt()).isEqualTo(c.accessExpiresAt());
        for(var t:List.of(old,b,c)) assertThat(auth(fixed).authenticate(t.accessToken()).sessionId()).isEqualTo(old.sessionId());
    }
    @Test @Order(1) void expiredAccessDoesNotPreventValidRefreshRotation() {
        var old = issuer(Clock.offset(clock,Duration.ofHours(-2))).issue(account); store.register(account,old,"WEB");
        denied(() -> auth(clock).authenticate(old.accessToken()),ErrorCode.UNAUTHENTICATED);
        var next = refresh.rotate(old.refreshToken(),"WEB",attempt);
        assertThat(auth(clock).authenticate(next.accessToken()).sessionId()).isEqualTo(old.sessionId());
    }
    @Test @Order(1) void forgedSplicedMissingProofWrongChannelAndInvalidAttemptDoNotMutate() {
        var old=session("WEB"); var other=session("WEB"); String before=value(old);
        for(String invalid:List.of("forged",old.sessionId()+other.refreshToken().substring(36),old.sessionId()+"."+"x".repeat(43)))
            denied(() -> refresh.rotate(invalid,"WEB",attempt),ErrorCode.UNAUTHENTICATED);
        denied(() -> refresh.rotate(null,"WEB",attempt),ErrorCode.UNAUTHENTICATED);
        denied(() -> refresh.rotate(old.refreshToken(),"ANDROID",attempt),ErrorCode.AUTH_CHANNEL_MISMATCH);
        denied(() -> refresh.rotate(old.refreshToken(),"WEB","bad"),ErrorCode.INVALID_REQUEST);
        denied(() -> refresh.rotate(old.refreshToken(),"other",attempt),ErrorCode.INVALID_AUTH_CHANNEL);
        assertThat(value(old)).isEqualTo(before); assertThat(value(other)).isNotNull();
        assertThat(redis.hasKey(RefreshService.receiptKey(old.sessionId()))).isFalse();
    }
    @Test @Order(1) void inactiveAccountAndExpiredRefreshCannotRotateOrUseCompletionMetadata() {
        var old=session("WEB"); String before=value(old);
        when(accounts.isActive(any())).thenReturn(false);
        denied(() -> refresh.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.UNAUTHENTICATED);
        assertThat(value(old)).isEqualTo(before);
        when(accounts.isActive(any())).thenReturn(true);
        Clock future=Clock.offset(clock,Duration.ofDays(15));
        denied(() -> new RefreshService(redis,accounts,issuer(future),future).rotate(old.refreshToken(),"WEB",attempt),ErrorCode.UNAUTHENTICATED);
        assertThat(value(old)).isEqualTo(before);
        refresh.rotate(old.refreshToken(),"WEB",attempt); when(accounts.isActive(any())).thenReturn(false);
        denied(() -> refresh.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.UNAUTHENTICATED);
    }
    @Test @Order(1) void completionMetadataNeverPretendsToRecoverTokensOrExtendsEitherLifetime() {
        var old=session("WEB"); var next=refresh.rotate(old.refreshToken(),"WEB",attempt); String current=value(next);
        String key=RefreshService.receiptKey(old.sessionId()), receipt=redis.opsForValue().get(key);
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(115000L,120000L);
        redis.expire(key,Duration.ofSeconds(10));
        for(int i=0;i<5;i++) denied(() -> refresh.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.REFRESH_RECOVERY_UNAVAILABLE);
        assertThat(value(next)).isEqualTo(current); assertThat(redis.opsForValue().get(key)).isEqualTo(receipt);
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(1L,10000L);
        assertThat(redis.getExpire(RedisLoginSessions.key(next.sessionId()))).isBetween(1209590L,1209600L);
    }
    @Test @Order(1) void expiredEvidenceDoesNotAllowOldProofToBecomeNewRotation() {
        var old=session("WEB"); var next=refresh.rotate(old.refreshToken(),"WEB",attempt); String current=value(next);
        String key=RefreshService.receiptKey(old.sessionId());redis.expire(key,Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        denied(() -> refresh.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.UNAUTHENTICATED);
        assertThat(value(next)).isEqualTo(current);
    }
    @ParameterizedTest @ValueSource(strings = {"LOCAL", "GOOGLE"}) @Order(1)
    void currentProofCanRotateWhenPreviousReceiptExpiresAfterSnapshot(String provider) {
        var old = session("WEB", provider);
        var current = refresh.rotate(old.refreshToken(), "WEB", attempt);
        String key = RefreshService.receiptKey(current.sessionId());
        assertThat(redis.hasKey(key)).isTrue();

        var reader = mockReader();
        when(reader.isActive(any())).thenAnswer(call -> {
            assertThat(redis.expire(key, Duration.ZERO)).isTrue();
            assertThat(redis.hasKey(key)).isFalse();
            return true;
        });
        var core = new RefreshService(redis, reader, issuer, clock);
        var next = core.rotate(current.refreshToken(), "WEB", otherAttempt);

        assertThat(next.sessionId()).isEqualTo(current.sessionId());
        assertThat(next.refreshToken()).isNotEqualTo(current.refreshToken());
        assertThat(auth(clock).authenticate(next.accessToken()).accountId()).isEqualTo(account.accountId());
        denied(() -> refresh.rotate(current.refreshToken(), "WEB", otherAttempt),
                ErrorCode.REFRESH_RECOVERY_UNAVAILABLE);
    }

    @Test @Order(1) void currentProofCanRotateAgainButPriorCompletionCannotReturnSupersededCredentials() {
        var old=session("WEB"); var b=refresh.rotate(old.refreshToken(),"WEB",attempt);
        var c=refresh.rotate(b.refreshToken(),"WEB",otherAttempt);String current=value(c);
        denied(() -> refresh.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.UNAUTHENTICATED);
        denied(() -> refresh.rotate(b.refreshToken(),"WEB",otherAttempt),ErrorCode.REFRESH_RECOVERY_UNAVAILABLE);
        assertThat(value(c)).isEqualTo(current); assertThat(auth(clock).authenticate(c.accessToken()).sessionId()).isEqualTo(old.sessionId());
    }
    @Test @Order(1) void sameAttemptWithSuccessorProofCannotAccidentallyRotateAgain() {
        var old=session("WEB");var next=refresh.rotate(old.refreshToken(),"WEB",attempt);String current=value(next);
        denied(() -> refresh.rotate(next.refreshToken(),"WEB",attempt),ErrorCode.REFRESH_ATTEMPT_MISMATCH);
        assertThat(value(next)).isEqualTo(current);
    }
    @ParameterizedTest @ValueSource(strings={"LOCAL","GOOGLE"}) @Order(1)
    void sameAttemptConcurrentRequestsHaveOnlyOneSuccessfulEffect(String provider) throws Exception {
        concurrent(attempt,ErrorCode.REFRESH_RECOVERY_UNAVAILABLE,provider);
    }
    @ParameterizedTest @ValueSource(strings={"LOCAL","GOOGLE"}) @Order(1)
    void differentAttemptConcurrentRequestsHaveOnlyOneSuccessfulEffect(String provider) throws Exception {
        concurrent(otherAttempt,ErrorCode.REFRESH_ATTEMPT_MISMATCH,provider);
    }
    void concurrent(String secondAttempt,ErrorCode expectedLoser,String provider) throws Exception {
        var old=session("WEB",provider);var bothVerified=new CountDownLatch(2);
        when(accounts.isActive(any())).thenAnswer(call -> {bothVerified.countDown();assertThat(bothVerified.await(10,TimeUnit.SECONDS)).isTrue();return true;});
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> outcome(old,attempt));var b=pool.submit(() -> outcome(old,secondAttempt));
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK",expectedLoser.name());
        }
        assertThat(value(old)).isNotNull(); assertThat(redis.opsForValue().get(RefreshService.receiptKey(old.sessionId()))).isNotNull();
    }
    String outcome(LoginTokens old,String key) {
        try {refresh.rotate(old.refreshToken(),"WEB",key);return "OK";}
        catch(BusinessException e){return e.getErrorCode().name();}
    }
    @ParameterizedTest @ValueSource(strings={"LOCAL","GOOGLE"}) @Order(1)
    void logoutCommitWhileRefreshIsPausedAfterSnapshotNeverResurrectsSession(String provider) throws Exception {
        var old=session("WEB",provider);var other=session("ANDROID");var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var reader=pausedReader(entered,resume);var core=new RefreshService(redis,reader,issuer,clock);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(() -> {denied(() -> core.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.UNAUTHENTICATED);return true;});
            try {assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();logout.logout(old.refreshToken(),"WEB",attempt);}
            finally {resume.countDown();}
            assertThat(result.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(value(old)).isNull();assertThat(value(other)).isNotNull();
        assertThat(redis.hasKey(RefreshService.receiptKey(old.sessionId()))).isFalse();
        assertThat(redis.hasKey(LogoutService.receiptKey(old.sessionId()))).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"LOCAL","GOOGLE"}) @Order(1)
    void refreshCommitWhileLogoutIsPausedRejectsOldLogoutThenNewProofRevokesAllAccess(String provider) throws Exception {
        var old=session("WEB",provider);var other=session("ANDROID");var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var pendingLogout=new LogoutService(redis,pausedReader(entered,resume),clock);LoginTokens next;
        try(var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(() -> {denied(() -> pendingLogout.logout(old.refreshToken(),"WEB",attempt),ErrorCode.UNAUTHENTICATED);return true;});
            try {assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();next=refresh.rotate(old.refreshToken(),"WEB",attempt);}
            finally {resume.countDown();}
            assertThat(result.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(value(next)).isNotNull();assertThat(redis.hasKey(LogoutService.receiptKey(old.sessionId()))).isFalse();
        logout.logout(next.refreshToken(),"WEB",attempt);
        denied(() -> refresh.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.UNAUTHENTICATED);
        denied(() -> refresh.rotate(next.refreshToken(),"WEB",otherAttempt),ErrorCode.UNAUTHENTICATED);
        denied(() -> auth(clock).authenticate(old.accessToken()),ErrorCode.UNAUTHENTICATED);
        denied(() -> auth(clock).authenticate(next.accessToken()),ErrorCode.UNAUTHENTICATED);
        assertThat(value(other)).isNotNull();assertThat(redis.hasKey(RefreshService.receiptKey(old.sessionId()))).isTrue();
    }
    CurrentAccountReader pausedReader(CountDownLatch entered,CountDownLatch resume) {
        var reader=mockReader();
        when(reader.isActive(any())).thenAnswer(call -> {entered.countDown();assertThat(resume.await(10,TimeUnit.SECONDS)).isTrue();return true;});
        return reader;
    }
    @Test @Order(1) void loginReplacementWhileRefreshIsPausedCannotBeOverwrittenOrRecreated() throws Exception {
        var old=session("WEB");String expected=value(old);var next=issuer.issue(account);
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var core=new RefreshService(redis,pausedReader(entered,resume),issuer,clock);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var task=pool.submit(() -> {denied(() -> core.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.UNAUTHENTICATED);return true;});
            try {assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();store.registerReplacing(account,next,"WEB",old.sessionId(),expected);}
            finally {resume.countDown();}
            assertThat(task.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(value(old)).isNull();assertThat(value(next)).isNotNull();
    }
    @Test @Order(1) void refreshBeforeLoginReplacementRejectsStaleSnapshotWithoutRevokingRotatedSession() {
        var old=session("WEB");String expected=value(old);var independent=issuer.issue(account);
        var next=refresh.rotate(old.refreshToken(),"WEB",attempt);String current=value(next);
        denied(() -> store.registerReplacing(account,independent,"WEB",old.sessionId(),expected),ErrorCode.AUTH_CONTEXT_CONFLICT);
        assertThat(value(next)).isEqualTo(current);assertThat(value(independent)).isNull();
    }
    @Test @Order(1) void changedSameSidSnapshotCannotBeOverwritten() {
        var old=session("WEB");String changed=value(old).replace("|3|","|4|");
        when(accounts.isActive(any())).thenAnswer(call -> {redis.opsForValue().set(RedisLoginSessions.key(old.sessionId()),changed);return true;});
        denied(() -> refresh.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.UNAUTHENTICATED);
        assertThat(value(old)).isEqualTo(changed);assertThat(redis.hasKey(RefreshService.receiptKey(old.sessionId()))).isFalse();
    }
    @Test @Order(1) void receiptAndLogoutWrongTypesAreCheckedBeforeAnyMutation() {
        for(boolean receipt:List.of(true,false)) {
            var old=session("WEB");String before=value(old);
            String key=receipt?RefreshService.receiptKey(old.sessionId()):LogoutService.receiptKey(old.sessionId());
            redis.opsForList().leftPush(key,"synthetic");
            denied(() -> refresh.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(value(old)).isEqualTo(before);
            if(!receipt)assertThat(redis.hasKey(RefreshService.receiptKey(old.sessionId()))).isFalse();
        }
    }
    @ParameterizedTest @ValueSource(strings={"LOCAL","GOOGLE"}) @Order(1)
    void lostReplyAfterActualCommitIs503AndNeverRetriesOrRestoresOldProof(String provider) {
        var old=session("WEB",provider);var other=session("ANDROID");var client=spy(redis);
        doAnswer(call -> {call.callRealMethod();throw new org.springframework.dao.QueryTimeoutException("synthetic reply loss");})
                .when(client).execute(any(RedisScript.class),anyList(),any(Object[].class));
        denied(() -> new RefreshService(client,accounts,issuer,clock).rotate(old.refreshToken(),"WEB",attempt),ErrorCode.SERVICE_UNAVAILABLE);
        verify(client,times(1)).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertThat(value(old)).isNotNull();assertThat(value(other)).isNotNull();
        denied(() -> refresh.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.REFRESH_RECOVERY_UNAVAILABLE);
        denied(() -> logout.logout(old.refreshToken(),"WEB",attempt),ErrorCode.UNAUTHENTICATED);
    }
    @Test @Order(1) void accountReadFailureDoesNotConsumeOrRestoreRefreshProof() {
        var old = session("WEB");
        String sessionKey = RedisLoginSessions.key(old.sessionId());
        String receiptKey = RefreshService.receiptKey(old.sessionId());
        String before = value(old);
        var failure = new org.springframework.dao.DataAccessResourceFailureException("synthetic database outage");
        when(accounts.isActive(any())).thenThrow(failure);
        denied(() -> refresh.rotate(old.refreshToken(), "WEB", attempt), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(redis.opsForValue().get(sessionKey)).isEqualTo(before);
        assertThat(redis.hasKey(receiptKey)).isFalse();

        doReturn(true).when(accounts).isActive(any());
        var next = refresh.rotate(old.refreshToken(), "WEB", attempt);
        String current = value(next);
        String receipt = redis.opsForValue().get(receiptKey);
        doThrow(failure).when(accounts).isActive(any());
        denied(() -> refresh.rotate(old.refreshToken(), "WEB", attempt), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(value(next)).isEqualTo(current);
        assertThat(redis.opsForValue().get(receiptKey)).isEqualTo(receipt);
        doReturn(true).when(accounts).isActive(any());
        denied(() -> refresh.rotate(old.refreshToken(), "WEB", attempt), ErrorCode.REFRESH_RECOVERY_UNAVAILABLE);
    }

    @Test @Order(1) void failureBeforeExecutionLeavesOldProofUsableWithoutReceipt() {
        var old=session("WEB");String before=value(old);var client=spy(redis);
        doThrow(new org.springframework.dao.QueryTimeoutException("synthetic before execution"))
                .when(client).execute(any(RedisScript.class),anyList(),any(Object[].class));
        denied(() -> new RefreshService(client,accounts,issuer,clock).rotate(old.refreshToken(),"WEB",attempt),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(value(old)).isEqualTo(before);assertThat(redis.hasKey(RefreshService.receiptKey(old.sessionId()))).isFalse();
        assertThat(refresh.rotate(old.refreshToken(),"WEB",attempt)).isNotNull();
    }
    @ParameterizedTest @ValueSource(strings={"LOCAL","GOOGLE"}) @Order(1)
    void injectedLuaFailureAfterReceiptDoesNotPretendThatRotationCompleted(String provider) {
        var old=session("WEB",provider);String before=value(old);var client=spy(redis);
        doAnswer(call -> {
            RedisScript<Long> original=call.getArgument(0);
            String faulty=original.getScriptAsString().replace("redis.call('SET', KEYS[1], ARGV[2], 'XX', 'PX', ARGV[3])",
                    "error('synthetic failure before replacement')");
            assertThat(faulty).isNotEqualTo(original.getScriptAsString());
            return redis.execute(new DefaultRedisScript<>(faulty,Long.class),call.getArgument(1),(Object[])call.getRawArguments()[2]);
        }).when(client).execute(any(RedisScript.class),anyList(),any(Object[].class));
        denied(() -> new RefreshService(client,accounts,issuer,clock).rotate(old.refreshToken(),"WEB",attempt),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(value(old)).isEqualTo(before);assertThat(redis.hasKey(RefreshService.receiptKey(old.sessionId()))).isTrue();
        denied(() -> refresh.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(value(old)).isEqualTo(before);
    }
    @Test @Order(1) void realRedisSetPermissionDenialIsDetectedBeforeReceiptOrSessionChanges() {
        var old=session("WEB");String before=value(old);String user="fixture-"+UUID.randomUUID(),password=UUID.randomUUID().toString();
        redis.execute((RedisCallback<Object>) c -> c.execute("ACL",bytes("SETUSER"),bytes(user),bytes("on"),bytes(">"+password),bytes("~*"),bytes("+@all"),bytes("-set")));
        var config=new org.springframework.data.redis.connection.RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379));
        config.setUsername(user);config.setPassword(password);var restricted=new LettuceConnectionFactory(config);
        try {
            restricted.afterPropertiesSet();restricted.start();var client=new StringRedisTemplate(restricted);client.afterPropertiesSet();
            denied(() -> new RefreshService(client,accounts,issuer,clock).rotate(old.refreshToken(),"WEB",attempt),ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(value(old)).isEqualTo(before);assertThat(redis.hasKey(RefreshService.receiptKey(old.sessionId()))).isFalse();
        } finally {restricted.destroy();redis.execute((RedisCallback<Object>) c -> c.execute("ACL",bytes("SETUSER"),bytes(user),bytes("reset")));}
    }
    @Test @Order(99) void actualRedisOutageNeverReturnsTokens() {
        var old=session("WEB");REDIS.stop();
        denied(() -> refresh.rotate(old.refreshToken(),"WEB",attempt),ErrorCode.SERVICE_UNAVAILABLE);
    }
    static byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
}

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
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Redis coordination; account reads are a stub here, and real DB/HTTP is tested separately. */
class LogoutStateTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    final Clock clock = Clock.systemUTC();
    final LocalCredentialVerifier.VerifiedAccount account = new LocalCredentialVerifier.VerifiedAccount(
            UUID.randomUUID(), "fixture", "fixture@example.invalid", 3, UUID.randomUUID());
    final String attempt = "AAAAAAAAAAAAAAAAAAAAAA";
    final LoginTokenIssuer issuer = new LoginTokenIssuer(new SecretKeySpec(new byte[32], "HmacSHA256"), clock, "test", "test");
    CurrentAccountReader accounts;
    RedisLoginSessions store;
    LogoutService logout;
    @BeforeAll static void start() {
        REDIS.start(); factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
    }
    @AfterAll static void stop() { if (factory != null) factory.destroy(); REDIS.stop(); }
    @BeforeEach void setup() {
        accounts = mock(CurrentAccountReader.class); when(accounts.isActive(any())).thenReturn(true);
        store = new RedisLoginSessions(redis, clock); logout = new LogoutService(redis, accounts, clock);
    }
    LoginTokens session(String channel) { var t = issuer.issue(account); store.register(account, t, channel); return t; }
    void register(LoginTokens tokens, String provider) {
        if ("LOCAL".equals(provider)) { store.register(account,tokens,"WEB"); return; }
        store.register(account, tokens, "WEB");
        String key = RedisLoginSessions.key(tokens.sessionId());
        String local = redis.opsForValue().get(key);
        redis.opsForValue().set(key, "v2" + local.substring(2) + "|" + provider,
                Duration.between(clock.instant(), tokens.refreshExpiresAt()));
    }
    String value(LoginTokens t) { return redis.opsForValue().get(RedisLoginSessions.key(t.sessionId())); }
    void denied(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
    @Test void revocationRejectsAccessAndPreservesIndependentDevice() {
        var a = session("WEB"); var b = session("ANDROID"); String other = value(b);
        var auth = new SessionAuthenticator(new AccessJwtVerifier(new SecretKeySpec(new byte[32], "HmacSHA256"),
                clock, "test", "test"), redis, accounts, clock);
        logout.logout(a.refreshToken(), "WEB", attempt);
        assertThat(value(a)).isNull(); assertThat(value(b)).isEqualTo(other);
        denied(() -> auth.authenticate(a.accessToken()), ErrorCode.UNAUTHENTICATED);
        assertThat(auth.authenticate(b.accessToken()).sessionId()).isEqualTo(b.sessionId());
        assertThat(redis.opsForValue().get(LogoutService.receiptKey(a.sessionId()))).doesNotContain(a.refreshToken());
    }
    @Test void forgedSplicedAndWrongChannelProofNeverMutate() {
        var a = session("WEB"); var b = session("WEB"); String before = value(a);
        denied(() -> logout.logout(a.sessionId()+b.refreshToken().substring(36), "WEB", attempt), ErrorCode.UNAUTHENTICATED);
        denied(() -> logout.logout(a.refreshToken(), "ANDROID", attempt), ErrorCode.AUTH_CHANNEL_MISMATCH);
        denied(() -> logout.logout("forged", "WEB", attempt), ErrorCode.UNAUTHENTICATED);
        assertThat(value(a)).isEqualTo(before); assertThat(value(b)).isNotNull();
        assertThat(redis.hasKey(LogoutService.receiptKey(a.sessionId()))).isFalse();
    }
    @Test void invalidAttemptAndMissingProofNeverWrite() {
        var t = session("WEB"); String before = value(t);
        denied(() -> logout.logout(t.refreshToken(), "WEB", "bad"), ErrorCode.INVALID_REQUEST);
        denied(() -> logout.logout(null, "WEB", attempt), ErrorCode.UNAUTHENTICATED);
        assertThat(value(t)).isEqualTo(before);
    }
    @Test void inactiveAccountAndVersionChangeRejectBothInitialAndReplay() {
        var t = session("WEB"); String before = value(t);
        when(accounts.isActive(any())).thenReturn(false);
        denied(() -> logout.logout(t.refreshToken(), "WEB", attempt), ErrorCode.UNAUTHENTICATED);
        assertThat(value(t)).isEqualTo(before);
        when(accounts.isActive(any())).thenReturn(true); logout.logout(t.refreshToken(), "WEB", attempt);
        String receipt = redis.opsForValue().get(LogoutService.receiptKey(t.sessionId()));
        when(accounts.isActive(any())).thenReturn(false);
        denied(() -> logout.logout(t.refreshToken(), "WEB", attempt), ErrorCode.UNAUTHENTICATED);
        assertThat(redis.opsForValue().get(LogoutService.receiptKey(t.sessionId()))).isEqualTo(receipt);
    }
    @Test void fixedWindowAllowsOnlyTwoAdditionalSameAttemptProofs() {
        var t = session("WEB"); logout.logout(t.refreshToken(), "WEB", attempt);
        String key = LogoutService.receiptKey(t.sessionId());
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(85000L, 90000L);
        redis.expire(key, Duration.ofSeconds(10)); // advance expiry without waiting or extending on retry
        denied(() -> logout.logout(t.refreshToken(), "WEB", "BBBBBBBBBBBBBBBBBBBBBB"), ErrorCode.UNAUTHENTICATED);
        denied(() -> logout.logout(t.sessionId()+"."+"x".repeat(43), "WEB", attempt), ErrorCode.UNAUTHENTICATED);
        logout.logout(t.refreshToken(), "WEB", attempt); logout.logout(t.refreshToken(), "WEB", attempt);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 10000L);
        denied(() -> logout.logout(t.refreshToken(), "WEB", attempt), ErrorCode.UNAUTHENTICATED);
    }
    @Test void expiredReceiptAndAlreadyMissingSessionAreNotSuccess() {
        var t = session("WEB"); logout.logout(t.refreshToken(), "WEB", attempt);
        redis.expire(LogoutService.receiptKey(t.sessionId()), Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(LogoutService.receiptKey(t.sessionId()))));
        denied(() -> logout.logout(t.refreshToken(), "WEB", attempt), ErrorCode.UNAUTHENTICATED);
        var missing = session("WEB"); redis.delete(RedisLoginSessions.key(missing.sessionId()));
        denied(() -> logout.logout(missing.refreshToken(), "WEB", attempt), ErrorCode.UNAUTHENTICATED);
    }
    @ParameterizedTest @ValueSource(strings={"LOCAL","GOOGLE"})
    void originalRefreshExpiryDoesNotShortenLiveCompletionRecoveryWindow(String provider) {
        var issued = issuer.issue(account);
        var t = new LoginTokens(issued.sessionId(), issued.accessToken(), issued.refreshToken(),
                issued.accessExpiresAt(), clock.instant().plusSeconds(2));
        register(t,provider); logout.logout(t.refreshToken(), "WEB", attempt);
        new LogoutService(redis, accounts, Clock.offset(clock, Duration.ofSeconds(3)))
                .logout(t.refreshToken(), "WEB", attempt);
        assertThat(value(t)).isNull();
        assertThat(redis.opsForValue().get(LogoutService.receiptKey(t.sessionId()))).endsWith("|1");
    }
    @Test void expiredRefreshCannotStartRevocationEvenIfRedisKeyStillExists() {
        var t = session("WEB"); String before = value(t);
        denied(() -> new LogoutService(redis, accounts, Clock.offset(clock, Duration.ofDays(15)))
                .logout(t.refreshToken(), "WEB", attempt), ErrorCode.UNAUTHENTICATED);
        assertThat(value(t)).isEqualTo(before);
        assertThat(redis.hasKey(LogoutService.receiptKey(t.sessionId()))).isFalse();
    }
    @Test void concurrentSameAttemptHasOneRevocationAndAtMostTwoReplays() throws Exception {
        var t = session("WEB"); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Future<String>>();
            for (int i=0; i<6; i++) tasks.add(pool.submit(() -> { gate.await(); return outcome(t, attempt); }));
            gate.countDown(); var results = new ArrayList<String>();
            for (var task : tasks) results.add(task.get(10, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("OK", "OK", "OK", "UNAUTHENTICATED", "UNAUTHENTICATED", "UNAUTHENTICATED");
        }
        assertThat(value(t)).isNull();
        assertThat(redis.opsForValue().get(LogoutService.receiptKey(t.sessionId()))).endsWith("|2");
    }
    @Test void concurrentDifferentAttemptsHaveOnlyOneWinner() throws Exception {
        var t = session("WEB"); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return outcome(t, attempt); });
            var b = pool.submit(() -> { gate.await(); return outcome(t, "BBBBBBBBBBBBBBBBBBBBBB"); });
            gate.countDown(); assertThat(List.of(a.get(10,TimeUnit.SECONDS), b.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "UNAUTHENTICATED");
        }
    }
    String outcome(LoginTokens t, String key) {
        try { logout.logout(t.refreshToken(), "WEB", key); return "OK"; }
        catch (BusinessException e) { return e.getErrorCode().name(); }
    }
    @Test void reloginCommitBeforeRevocationPreservesNewSessionAndNeverCreatesReceipt() {
        var old = session("WEB"); var next = issuer.issue(account); String expected = value(old);
        when(accounts.isActive(any())).thenAnswer(call -> {
            store.registerReplacing(account, next, "WEB", old.sessionId(), expected); return true;
        });
        denied(() -> logout.logout(old.refreshToken(), "WEB", attempt), ErrorCode.UNAUTHENTICATED);
        assertThat(value(old)).isNull(); assertThat(value(next)).isNotNull();
        assertThat(redis.hasKey(LogoutService.receiptKey(old.sessionId()))).isFalse();
    }
    @Test void revocationCommitBeforeReloginRejectsStaleReplacementWithoutResurrection() {
        var old = session("WEB"); var next = issuer.issue(account); String expected = value(old);
        logout.logout(old.refreshToken(), "WEB", attempt);
        denied(() -> store.registerReplacing(account, next, "WEB", old.sessionId(), expected), ErrorCode.AUTH_CONTEXT_CONFLICT);
        assertThat(value(old)).isNull(); assertThat(value(next)).isNull();
    }
    @Test void sameSidSnapshotChangeDuringVerificationCannotBeDeletedOrReplayed() {
        var t = session("WEB"); String changed = value(t).replace("|3|", "|4|");
        when(accounts.isActive(any())).thenAnswer(call -> {
            redis.opsForValue().set(RedisLoginSessions.key(t.sessionId()), changed); return true;
        });
        denied(() -> logout.logout(t.refreshToken(), "WEB", attempt), ErrorCode.UNAUTHENTICATED);
        assertThat(value(t)).isEqualTo(changed);
        assertThat(redis.hasKey(LogoutService.receiptKey(t.sessionId()))).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"LOCAL","GOOGLE"})
    void lostReplyReturns503WithoutRetryAndReceiptRecoversConfirmedCommit(String provider) {
        var t = issuer.issue(account); register(t,provider); var other = session("ANDROID"); var failing = spy(redis);
        doAnswer(call -> { call.callRealMethod(); throw new org.springframework.dao.QueryTimeoutException("synthetic reply loss"); })
                .when(failing).execute(any(RedisScript.class), anyList(), any(Object[].class));
        denied(() -> new LogoutService(failing, accounts, clock).logout(t.refreshToken(), "WEB", attempt), ErrorCode.SERVICE_UNAVAILABLE);
        verify(failing, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertThat(value(t)).isNull(); assertThat(value(other)).isNotNull();
        assertThat(redis.opsForValue().get(LogoutService.receiptKey(t.sessionId()))).endsWith("|0");
        logout.logout(t.refreshToken(), "WEB", attempt);
        assertThat(redis.opsForValue().get(LogoutService.receiptKey(t.sessionId()))).endsWith("|1");
    }
    @Test void failureBeforeExecutionLeavesSessionAndReceiptUnchanged() {
        var t = session("WEB"); String before = value(t); var failing = spy(redis);
        doThrow(new org.springframework.dao.QueryTimeoutException("synthetic before execution"))
                .when(failing).execute(any(RedisScript.class), anyList(), any(Object[].class));
        denied(() -> new LogoutService(failing, accounts, clock).logout(t.refreshToken(), "WEB", attempt), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(value(t)).isEqualTo(before); assertThat(redis.hasKey(LogoutService.receiptKey(t.sessionId()))).isFalse();
    }
    @Test void receiptWrongTypeFailsBeforeDeletion() {
        var t = session("WEB"); String before = value(t);
        redis.opsForList().leftPush(LogoutService.receiptKey(t.sessionId()), "synthetic");
        denied(() -> logout.logout(t.refreshToken(), "WEB", attempt), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(value(t)).isEqualTo(before);
    }
    @Test void realRedisDeniedDeleteFailsBeforeWritingCompletionEvidence() {
        var t = session("WEB"); String before = value(t);
        String user = "fixture-"+UUID.randomUUID(), password = UUID.randomUUID().toString();
        redis.execute((RedisCallback<Object>) c -> c.execute("ACL", bytes("SETUSER"), bytes(user), bytes("on"),
                bytes(">"+password), bytes("~*"), bytes("+@all"), bytes("-del")));
        var config = new org.springframework.data.redis.connection.RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        config.setUsername(user); config.setPassword(password); var restricted = new LettuceConnectionFactory(config);
        try {
            restricted.afterPropertiesSet(); restricted.start(); var client = new StringRedisTemplate(restricted); client.afterPropertiesSet();
            denied(() -> new LogoutService(client, accounts, clock).logout(t.refreshToken(), "WEB", attempt), ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(value(t)).isEqualTo(before); assertThat(redis.hasKey(LogoutService.receiptKey(t.sessionId()))).isFalse();
        } finally {
            restricted.destroy(); redis.execute((RedisCallback<Object>) c -> c.execute("ACL", bytes("SETUSER"), bytes(user), bytes("reset")));
        }
    }
    static byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
}

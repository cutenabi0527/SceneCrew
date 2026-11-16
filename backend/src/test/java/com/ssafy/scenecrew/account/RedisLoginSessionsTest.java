package com.ssafy.scenecrew.account;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisLoginSessionsTest {
    static GenericContainer<?> container;
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    final Clock clock = Clock.systemUTC();
    final LocalCredentialVerifier.VerifiedAccount account = new LocalCredentialVerifier.VerifiedAccount(
            UUID.randomUUID(), "합성계정", "fixture@example.invalid", 0, UUID.randomUUID());

    @BeforeAll static void start() {
        container = new GenericContainer<>(DockerImageName.parse("redis:8.10.2-alpine")).withExposedPorts(6379);
        container.start();
        factory = new LettuceConnectionFactory(container.getHost(), container.getMappedPort(6379));
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }
    @AfterAll static void stop() {
        if (factory != null) factory.destroy();
        if (container != null) container.stop();
    }
    LoginTokens tokens(Duration ttl) {
        return new LoginTokens(UUID.randomUUID(), "synthetic-access", UUID.randomUUID().toString(),
                clock.instant().plusSeconds(3600), clock.instant().plus(ttl));
    }
    @Test void independentConcurrentSessionsHaveTtlAndNoRawRefresh() throws Exception {
        var store = new RedisLoginSessions(redis, clock);
        var first = tokens(Duration.ofDays(14)); var second = tokens(Duration.ofDays(14));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> { gate.await(); store.register(account, first, "WEB"); return true; });
            var b = pool.submit(() -> { gate.await(); store.register(account, second, "ANDROID"); return true; });
            gate.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        for (var token : List.of(first, second)) {
            var key = RedisLoginSessions.key(token.sessionId());
            assertThat(redis.opsForValue().get(key)).contains(account.accountId().toString())
                    .doesNotContain(token.refreshToken());
            assertThat(redis.getExpire(key)).isBetween(1209500L, 1209600L);
        }
    }
    @Test void collisionNeverDeletesAnotherRequestsSession() {
        var token = tokens(Duration.ofMinutes(1));
        var key = RedisLoginSessions.key(token.sessionId());
        redis.opsForValue().set(key, "other-owner", Duration.ofMinutes(1));
        assertThatThrownBy(() -> new RedisLoginSessions(redis, clock).register(account, token, "WEB"))
                .isInstanceOf(BusinessException.class);
        assertThat(redis.opsForValue().get(key)).isEqualTo("other-owner");
    }
    @SuppressWarnings("unchecked")
    @Test void lostAcknowledgementCleansOnlyOwnedSession() {
        var transport = spy(redis);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        doReturn(operations).when(transport).opsForValue();
        when(operations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call -> {
            redis.opsForValue().setIfAbsent(call.getArgument(0), call.getArgument(1), (Duration)call.getArgument(2));
            throw new org.springframework.dao.QueryTimeoutException("synthetic lost acknowledgement");
        });
        var token = tokens(Duration.ofMinutes(1));
        assertThatThrownBy(() -> new RedisLoginSessions(transport, clock).register(account, token, "WEB"))
                .isInstanceOf(BusinessException.class);
        assertThat(redis.hasKey(RedisLoginSessions.key(token.sessionId()))).isFalse();
    }
    @SuppressWarnings("unchecked")
    @Test void failedCleanupLeavesOnlyBoundedTtl() throws Exception {
        var transport = spy(redis);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        doReturn(operations).when(transport).opsForValue();
        when(operations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call -> {
            redis.opsForValue().setIfAbsent(call.getArgument(0), call.getArgument(1), (Duration)call.getArgument(2));
            throw new org.springframework.dao.QueryTimeoutException("synthetic lost acknowledgement");
        });
        doThrow(new org.springframework.dao.QueryTimeoutException("synthetic cleanup failure"))
                .when(transport).execute(any(RedisScript.class), anyList(), any(Object[].class));
        var token = tokens(Duration.ofSeconds(2));
        assertThatThrownBy(() -> new RedisLoginSessions(transport, clock).register(account, token, "WEB"))
                .isInstanceOf(BusinessException.class);
        var key = RedisLoginSessions.key(token.sessionId());
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 2000L);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
    }

    @SuppressWarnings("unchecked")
    @Test void lostAcknowledgementDoesNotDeleteAReplacementWrittenBeforeCleanup() {
        var transport = spy(redis);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        doReturn(operations).when(transport).opsForValue();
        when(operations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call -> {
            String key = call.getArgument(0);
            redis.opsForValue().setIfAbsent(key, call.getArgument(1), (Duration) call.getArgument(2));
            // 원래 세션 만료/교체가 정리보다 먼저 끝난 순서를 강제로 만든다.
            redis.opsForValue().set(key, "replacement-owner", Duration.ofMinutes(1));
            throw new org.springframework.dao.QueryTimeoutException("synthetic-sensitive-cause");
        });
        var token = tokens(Duration.ofMinutes(1));
        assertThatThrownBy(() -> new RedisLoginSessions(transport, clock).register(account, token, "WEB"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasNoCause().hasMessageNotContaining("synthetic-sensitive-cause");
        assertThat(redis.opsForValue().get(RedisLoginSessions.key(token.sessionId())))
                .isEqualTo("replacement-owner");
        assertThat(redis.getExpire(RedisLoginSessions.key(token.sessionId()))).isBetween(1L, 60L);
    }

    @Test void expiredSessionAndMissingChannelNeverWrite() {
        var transport = mock(StringRedisTemplate.class);
        var store = new RedisLoginSessions(transport, clock);
        assertThatThrownBy(() -> store.register(account, tokens(Duration.ZERO), "WEB"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> store.register(account, tokens(Duration.ofMinutes(1)), null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transport);
    }
}

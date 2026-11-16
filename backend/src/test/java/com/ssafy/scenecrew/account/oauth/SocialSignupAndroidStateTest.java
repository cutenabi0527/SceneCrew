package com.ssafy.scenecrew.account.oauth;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ssafy.scenecrew.account.dto.AndroidLoginResult;
import com.ssafy.scenecrew.account.dto.LoginResponses;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.error.BusinessException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Reuses every shared signup race/recovery test with real stores through the inactive Android adapter. */
class SocialSignupAndroidStateTest extends SocialSignupStateTest {
    @Override String channel() { return "ANDROID"; }

    private OAuthAndroidContinuation adapter() { return new OAuthAndroidContinuation(login, signup); }

    @Override OAuthLoginService.Continuation continueLogin(String ticket, String proof) {
        return adapter().exchange(ticket, proof);
    }

    @Override EmailLoginService.Result complete(String ticket, String proof, String name,
            SocialSignupService.Consent consent) {
        return adapter().complete(ticket, proof, name, consent);
    }

    @Test void signupIssuesUsableAndroidSessionWithoutCookies() {
        var result = complete(pending());
        var response = LoginResponses.android(result);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().get("Set-Cookie")).isNull();
        assertThat(response.getBody()).isInstanceOfSatisfying(AndroidLoginResult.class, body -> {
            assertThat(body.channel()).isEqualTo("ANDROID");
            assertThat(body.accessToken()).isEqualTo(result.tokens().accessToken());
            assertThat(body.refreshToken()).isEqualTo(result.tokens().refreshToken());
            assertThat(body.account().id()).isEqualTo(result.account().accountId());
        });
        var stored = StoredLoginSession.parse(
                redis.opsForValue().get(RedisLoginSessions.key(result.tokens().sessionId())), clock);
        assertThat(stored.channel()).isEqualTo("ANDROID");
        var key = new javax.crypto.spec.SecretKeySpec(new byte[32], "HmacSHA256");
        var authentication = new SessionAuthenticator(new AccessJwtVerifier(key, clock, "test", "test"),
                redis, new CurrentAccountReader(jdbc, manager), clock);
        var principal = authentication.authenticate(result.tokens().accessToken());
        assertThat(principal.channel()).isEqualTo("ANDROID");
        assertThat(principal.accountId()).isEqualTo(result.account().accountId());
    }

    @Test void callbackTicketRequiresOriginalAppAndCannotCrossChannelsOrReplay() {
        String proof = attempts.secret();
        String ticket = callback(SocialProvider.GOOGLE, UUID.randomUUID().toString(), proof);
        error(() -> adapter().exchange(ticket, attempts.secret()), ErrorCode.UNAUTHENTICATED);
        error(() -> adapter().exchange(ticket, null), ErrorCode.UNAUTHENTICATED);
        error(() -> login.continueLogin(ticket, "WEB", proof, signup), ErrorCode.UNAUTHENTICATED);
        assertThat(redis.hasKey(RedisOAuthAttempts.key("ticket", ticket))).isTrue();
        assertThat(redis.keys("auth:session:*")).isEmpty();
        assertThat(adapter().exchange(ticket, proof)).isInstanceOf(OAuthLoginService.OAuthPending.class);
        error(() -> adapter().exchange(ticket, proof), ErrorCode.UNAUTHENTICATED);

        String expired = callback(SocialProvider.NAVER, UUID.randomUUID().toString(), proof);
        String expiredKey = RedisOAuthAttempts.key("ticket", expired);
        redis.expire(expiredKey, Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(expiredKey)));
        error(() -> adapter().exchange(expired, proof), ErrorCode.UNAUTHENTICATED);
        error(() -> adapter().exchange(attempts.secret(), proof), ErrorCode.UNAUTHENTICATED);
        assertThat(redis.keys("auth:session:*")).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentExchangeHasOneWinnerForNewAndLinkedIdentity(boolean linked) {
        String subject = UUID.randomUUID().toString();
        if (linked) complete(pending(SocialProvider.GOOGLE, subject));
        long before = countAccounts();
        String proof = attempts.secret();
        String ticket = callback(SocialProvider.GOOGLE, subject, proof);
        // Force both callers to read the same live ticket before either can consume it.
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            var saved = invocation.callRealMethod();
            if (reads.getAndIncrement() < 2) barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return saved;
        }).when(attempts).read(eq("ticket"), eq(ticket), eq(ErrorCode.UNAUTHENTICATED));

        var results = concurrently(() -> adapter().exchange(ticket, proof), () -> adapter().exchange(ticket, proof));
        var success = linked ? OAuthLoginService.Authenticated.class : OAuthLoginService.OAuthPending.class;
        assertThat(results.stream().filter(success::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(BusinessException.class::isInstance).map(BusinessException.class::cast)
                .map(BusinessException::getErrorCode)).containsExactly(ErrorCode.UNAUTHENTICATED);
        assertThat(redis.hasKey(RedisOAuthAttempts.key("ticket", ticket))).isFalse();
        assertThat(redis.keys("auth:oauth:signup:*")).hasSize(linked ? 0 : 1);
        assertThat(redis.keys("auth:session:*")).hasSize(linked ? 2 : 0);
        assertThat(countAccounts()).isEqualTo(before);
        error(() -> adapter().exchange(ticket, proof), ErrorCode.UNAUTHENTICATED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void linkedExchangeFailurePreservesSessionAndFreshOAuthRecovers(boolean effectApplied) {
        String subject = UUID.randomUUID().toString();
        var healthy = complete(pending(SocialProvider.GOOGLE, subject));
        long before = countAccounts();
        String proof = attempts.secret(), ticket = callback(SocialProvider.GOOGLE, subject, proof);
        doAnswer(invocation -> {
            if (effectApplied) invocation.callRealMethod();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }).when(sessions).registerConsumingOAuthTicket(any(), any(), anyString(), anyString(), anyString(), anyString());

        error(() -> adapter().exchange(ticket, proof), ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(redis.hasKey(RedisOAuthAttempts.key("ticket", ticket))).isEqualTo(!effectApplied);
        assertThat(redis.keys("auth:session:*")).hasSize(effectApplied ? 2 : 1)
                .contains(RedisLoginSessions.key(healthy.tokens().sessionId()));
        assertThat(countAccounts()).isEqualTo(before);
        doCallRealMethod().when(sessions)
                .registerConsumingOAuthTicket(any(), any(), anyString(), anyString(), anyString(), anyString());

        // No automatic replay or credential recovery cache: a fresh OAuth proves the same identity again.
        String nextProof = attempts.secret(), nextTicket = callback(SocialProvider.GOOGLE, subject, nextProof);
        var recovered = (OAuthLoginService.Authenticated) adapter().exchange(nextTicket, nextProof);
        assertThat(recovered.result().account().accountId()).isEqualTo(healthy.account().accountId());
        assertThat(recovered.result().tokens().sessionId()).isNotEqualTo(healthy.tokens().sessionId());
        assertThat(redis.keys("auth:session:*")).hasSize(effectApplied ? 3 : 2)
                .contains(RedisLoginSessions.key(healthy.tokens().sessionId()),
                        RedisLoginSessions.key(recovered.result().tokens().sessionId()));
        assertThat(countAccounts()).isEqualTo(before);
        if (effectApplied) error(() -> adapter().exchange(ticket, proof), ErrorCode.UNAUTHENTICATED);
    }
}

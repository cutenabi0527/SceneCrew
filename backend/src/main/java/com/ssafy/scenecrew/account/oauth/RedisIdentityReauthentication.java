package com.ssafy.scenecrew.account.oauth;

import java.time.*;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.service.LocalCredentialVerifier;
import com.ssafy.scenecrew.account.service.LoginFailureLimiter;
import com.ssafy.scenecrew.account.service.SessionAuthenticator.Principal;
import com.ssafy.scenecrew.global.error.*;

/**
 * 014 본인 재확인(#227, 오세진 결정 2026-11-05): 현재 계정의 LOCAL 비밀번호를 다시 확인하면
 * 5분짜리 증명을 발급한다. 증명은 Account·Session·tokenVersion·channel·목적·제공자에 묶이고,
 * Account 잠금 안에서 DB 커밋 전에 일회 소비한다. 실패 횟수는 로그인과 같은 이메일 기준 제한을 공유한다.
 * 소셜 전용 계정의 재확인 수단은 아직 없다(LOCAL이 없으면 INVALID_CREDENTIALS).
 */
public final class RedisIdentityReauthentication implements IdentityReauthentication {
    public static final Duration TTL = Duration.ofMinutes(5);
    private static final DefaultRedisScript<Long> TAKE = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end return redis.call('DEL',KEYS[1])", Long.class);
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final LocalCredentialVerifier verifier;
    private final LoginFailureLimiter limiter;
    private final RedisOAuthAttempts secrets;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public RedisIdentityReauthentication(StringRedisTemplate redis, JdbcTemplate jdbc, LocalCredentialVerifier verifier,
            LoginFailureLimiter limiter, RedisOAuthAttempts secrets, Clock clock) {
        this.redis = redis; this.jdbc = jdbc; this.verifier = verifier; this.limiter = limiter;
        this.secrets = secrets; this.clock = clock;
    }

    public Issued issue(Principal principal, Operation operation, SocialProvider provider, String password) {
        Objects.requireNonNull(operation); Objects.requireNonNull(provider);
        List<String> emails;
        try {
            emails = jdbc.queryForList("SELECT email FROM auth_identity WHERE account_id=? AND provider='LOCAL' AND email_verified=true",
                    String.class, principal.accountId());
        } catch (org.springframework.dao.DataAccessException e) { throw unavailable(); }
        if (emails.size() != 1) throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        var keys = limiter.keys(emails.getFirst());
        limiter.check(keys);
        var verified = verifier.verify(emails.getFirst(), password);
        if (verified.isEmpty() || !verified.get().accountId().equals(principal.accountId())
                || verified.get().tokenVersion() != principal.tokenVersion()) {
            limiter.failure(keys);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        limiter.success(keys);
        String token = secrets.secret();
        var record = new Proof(principal.accountId(), principal.sessionId(), principal.tokenVersion(), principal.channel(),
                operation, provider);
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(token), json.writeValueAsString(record), TTL)))
                throw unavailable();
        } catch (org.springframework.dao.DataAccessException e) { throw unavailable(); }
        return new Issued(token, clock.instant().plus(TTL));
    }

    @Override
    public void require(Principal principal, Operation operation, SocialProvider provider, String proof) {
        verified(principal, operation, provider, proof);
    }

    private String verified(Principal principal, Operation operation, SocialProvider provider, String proof) {
        if (principal == null || proof == null || !proof.matches("[A-Za-z0-9_-]{43}")) throw required();
        String value;
        try { value = redis.opsForValue().get(key(proof)); }
        catch (org.springframework.dao.DataAccessException e) { throw unavailable(); }
        if (value == null) throw required();
        Proof saved;
        try { saved = json.readValue(value, Proof.class); } catch (RuntimeException e) { throw required(); }
        if (!saved.accountId().equals(principal.accountId()) || !saved.sessionId().equals(principal.sessionId())
                || saved.tokenVersion() != principal.tokenVersion() || !saved.channel().equals(principal.channel())
                || saved.operation() != operation || saved.provider() != provider) throw required();
        return value;
    }

    /** Consume before releasing the Account lock; Redis failure must roll back the identity change. */
    @Override
    public void consume(Principal principal, Operation operation, SocialProvider provider, String proof) {
        String value = verified(principal, operation, provider, proof);
        try {
            if (!Long.valueOf(1).equals(redis.execute(TAKE, List.of(key(proof)), value))) throw required();
        } catch (org.springframework.dao.DataAccessException e) { throw unavailable(); }
    }

    static String key(String proof) { return "auth:reauth:" + RedisOAuthAttempts.hash(proof); }
    private static IdentityChangeException required() {
        return new IdentityChangeException(IdentityChangeException.Reason.REAUTHENTICATION_REQUIRED);
    }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }

    public record Issued(String token, Instant expiresAt) {
        @Override public String toString() { return "Reauthentication[redacted]"; }
    }
    record Proof(UUID accountId, UUID sessionId, long tokenVersion, String channel, Operation operation, SocialProvider provider) { }
}

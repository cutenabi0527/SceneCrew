package com.ssafy.scenecrew.account.service;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.global.error.*;

/** USER_SELECTED candidate: fifth failure=401; retained window can block again after expiry.
 * Redis terminal-operation order defines concurrent success/reset vs failure. Not team-approved.
 */
public final class LoginFailureLimiter {
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>(
            "return redis.call('EXISTS', KEYS[2])", Long.class);
    private static final DefaultRedisScript<Long> SUCCESS = new DefaultRedisScript<>(
            "return redis.call('DEL', KEYS[1], KEYS[2])", Long.class);
    private static final DefaultRedisScript<Long> FAILURE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 600000)
            redis.call('ZADD', KEYS[1], now, ARGV[1])
            redis.call('PEXPIRE', KEYS[1], 900000)
            if redis.call('ZCARD', KEYS[1]) >= 5 then
              redis.call('SET', KEYS[2], '1', 'PX', 60000, 'NX')
            end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final SecretKeySpec key;

    public LoginFailureLimiter(StringRedisTemplate redis, byte[] secret) {
        if (secret.length < 32) throw new IllegalArgumentException("Missing failure-key secret");
        this.redis = redis;
        this.key = new SecretKeySpec(secret.clone(), "HmacSHA256");
    }
    public List<String> keys(String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(key);
            String digest = HexFormat.of().formatHex(mac.doFinal(LocalCredentialVerifier.normalizeEmail(email)
                    .getBytes(StandardCharsets.UTF_8)));
            return List.of("auth:failure:{" + digest + "}:attempts", "auth:failure:{" + digest + "}:blocked");
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Failure-key configuration unavailable");
        }
    }
    public void check(List<String> keys) {
        try {
            Long blocked = redis.execute(CHECK, keys);
            if (blocked == null) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            if (blocked != 0) throw new BusinessException(ErrorCode.RATE_LIMITED);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
    public void failure(List<String> keys) {
        try {
            if (redis.execute(FAILURE, keys, UUID.randomUUID().toString()) == null)
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
    /** Standalone state-test helper; public login resets inside RedisLoginSessions commit instead. */
    public void success(List<String> keys) {
        try {
            if (redis.execute(SUCCESS, keys) == null) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}

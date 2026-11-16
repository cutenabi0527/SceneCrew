package com.ssafy.scenecrew.account.infra;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.dto.RegisterRequest;
import com.ssafy.scenecrew.account.service.LocalCredentialVerifier;
import com.ssafy.scenecrew.account.service.RegistrationCodes;
import com.ssafy.scenecrew.account.service.RegistrationDelivery;
import com.ssafy.scenecrew.global.error.*;

/** Standalone Redis pending data and send budget. Codes are derived only in memory for delivery. */
public final class RedisPendingRegistrations {
    private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>("""
            local kind = redis.call('TYPE', KEYS[2]).ok
            if kind ~= 'none' and kind ~= 'zset' then return -2 end
            if redis.call('EXISTS', KEYS[1]) == 1 then return -2 end
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local recent = redis.call('ZREVRANGEBYSCORE', KEYS[2], '+inf', now - 600000 + 1, 'WITHSCORES')
            if #recent >= 10 or (#recent > 0 and now - tonumber(recent[2]) < 60000) then return -1 end
            local payload = cjson.decode(ARGV[1])
            payload.createdAtEpochMilli = now
            payload.expiresAtEpochMilli = now + 600000
            local value = cjson.encode(payload)
            -- Check permissions before writing: Lua errors do not roll back earlier commands.
            if not redis.acl_check_cmd('SET', KEYS[1], value, 'PX', '600000', 'NX')
              or not redis.acl_check_cmd('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - 600000)
              or not redis.acl_check_cmd('ZADD', KEYS[2], now, ARGV[2])
              or not redis.acl_check_cmd('PEXPIRE', KEYS[2], '600000') then return -2 end
            redis.call('SET', KEYS[1], value, 'PX', 600000, 'NX')
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - 600000)
            redis.call('ZADD', KEYS[2], now, ARGV[2])
            redis.call('PEXPIRE', KEYS[2], 600000)
            return now + 600000
            """, Long.class);
    private final StringRedisTemplate redis;
    private final SecretKeySpec secret;
    private final JsonMapper json = JsonMapper.builder().build();

    public RedisPendingRegistrations(StringRedisTemplate redis, byte[] secret) {
        if (secret.length < 32) throw new IllegalArgumentException("Missing registration-key secret");
        this.redis = redis;
        this.secret = new SecretKeySpec(secret.clone(), "HmacSHA256");
    }

    public Accepted save(RegisterRequest input, String passwordHash) {
        UUID requestId = UUID.randomUUID();
        var pending = new Pending(requestId, input.email(), input.displayName(), passwordHash, input.consent(), new RegistrationCodes(secret.getEncoded()).keyId());
        try {
            Long expiry = redis.execute(SAVE, List.of(key(requestId), sendBudgetKey(input.email())),
                    json.writeValueAsString(pending), requestId.toString());
            if (expiry != null && expiry == -1)
                throw new BusinessException(ErrorCode.RATE_LIMITED, "잠시 후 다시 요청해 주세요.");
            if (expiry == null || expiry < 0) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            return new Accepted(requestId, Instant.ofEpochMilli(expiry));
        } catch (org.springframework.dao.DataAccessException e) {
            // A lost reply may mean the pending request and reservation already exist. Never retry here.
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** Code exists only in memory while crossing the delivery port. Key mismatch fails closed. */
    public RegistrationDelivery.Message message(UUID requestId) {
        try {
            String value=redis.opsForValue().get(key(requestId));
            if (value==null) throw new BusinessException(ErrorCode.INVALID_STATE);
            var p=json.readTree(value);
            var codes=new RegistrationCodes(secret.getEncoded());
            if (!codes.keyId().equals(p.path("proofKeyId").asString()))
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            return new RegistrationDelivery.Message(requestId,p.get("email").asString(),codes.derive(requestId),
                    Instant.ofEpochMilli(p.get("expiresAtEpochMilli").asLong()));
        } catch (BusinessException e) { throw e; }
        catch (RuntimeException e) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }

    public static String key(UUID requestId) { return "auth:registration:pending:" + requestId; }

    /** Shared budget namespace for #224 resend. HMAC prevents raw email in Redis keys. */
    public String sendBudgetKey(String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(secret);
            String digest = HexFormat.of().formatHex(mac.doFinal(("registration:" +
                    LocalCredentialVerifier.normalizeEmail(email)).getBytes(StandardCharsets.UTF_8)));
            return "auth:registration:send:{" + digest + "}";
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Registration-key configuration unavailable");
        }
    }

    private record Pending(UUID requestId, String email, String displayName, String passwordHash,
            RegisterRequest.Consent consent, String proofKeyId) {
        @Override public String toString() { return "PendingRegistration[redacted]"; }
    }
    public record Accepted(UUID requestId, Instant expiresAt) {}
}

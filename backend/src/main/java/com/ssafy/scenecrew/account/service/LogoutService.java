package com.ssafy.scenecrew.account.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.global.error.*;

/** Standalone Redis candidate. No Access precondition, token rotation or account-wide revocation. */
public final class LogoutService {
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>("""
            -- Read/type-check both keys and deletion permission before the first write.
            local current = redis.call('GET', KEYS[1])
            local receipt = redis.call('GET', KEYS[2])
            if not redis.acl_check_cmd('DEL', KEYS[1]) then
              return redis.error_reply('Session revocation permission unavailable')
            end
            local prefix = ARGV[1] .. '|' .. ARGV[2] .. '|'
            if receipt then
              if current then return 0 end
              for retry = 0, 1 do
                if receipt == prefix .. retry then
                  redis.call('SET', KEYS[2], prefix .. (retry + 1), 'XX', 'KEEPTTL')
                  return 1
                end
              end
              return 0
            end
            if current ~= ARGV[1] then return 0 end
            -- Receipt has only the proven snapshot, attempt and retry count; never the raw token.
            redis.call('SET', KEYS[2], prefix .. '0', 'NX', 'PX', 90000)
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final CurrentAccountReader accounts;
    private final Clock clock;

    public LogoutService(StringRedisTemplate redis, CurrentAccountReader accounts, Clock clock) {
        this.redis = redis; this.accounts = accounts; this.clock = clock;
    }

    public void logout(String refresh, String channel, String attempt) {
        if (attempt == null || !attempt.matches("[A-Za-z0-9_-]{22}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (!("WEB".equals(channel) || "ANDROID".equals(channel)))
            throw new BusinessException(ErrorCode.INVALID_AUTH_CHANNEL);
        // SID is only a lookup hint. The entire opaque credential must match the stored hash.
        if (refresh == null || !refresh.matches("[0-9a-fA-F-]{36}\\.[A-Za-z0-9_-]{43}"))
            throw AccessJwtVerifier.denied();
        UUID sid = AccessJwtVerifier.uuid(refresh.substring(0, 36));
        try {
            String expected = redis.opsForValue().get(RedisLoginSessions.key(sid));
            boolean completed = expected == null;
            if (expected == null) {
                String receipt = redis.opsForValue().get(receiptKey(sid));
                if (receipt == null) throw AccessJwtVerifier.denied();
                String[] fields = receipt.split("\\|", -1);
                int size = fields.length > 0 && "v2".equals(fields[0]) ? 9 : 8;
                if (fields.length != size + 2 || !attempt.equals(fields[size]) || !fields[size + 1].matches("[012]"))
                    throw AccessJwtVerifier.denied();
                expected = String.join("|", java.util.Arrays.copyOf(fields, size));
            }
            var session = completed ? StoredLoginSession.completedLogout(expected, clock)
                    : StoredLoginSession.parse(expected, clock);
            String storedHash = expected.split("\\|", -1)[6];
            if (!MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII),
                    hash(refresh).getBytes(StandardCharsets.US_ASCII))) throw AccessJwtVerifier.denied();
            if (!session.channel().equals(channel)) throw new BusinessException(ErrorCode.AUTH_CHANNEL_MISMATCH);
            if (!accounts.isActive(session)) throw AccessJwtVerifier.denied();
            Long result = redis.execute(REVOKE, List.of(RedisLoginSessions.key(sid), receiptKey(sid)), expected, attempt);
            if (Long.valueOf(0).equals(result)) throw AccessJwtVerifier.denied();
            if (!Long.valueOf(1).equals(result)) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // No automatic retry/cleanup: deletion may already have committed with its receipt.
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public static String receiptKey(UUID sid) { return "auth:logout:" + sid; }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}

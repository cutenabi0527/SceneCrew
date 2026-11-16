package com.ssafy.scenecrew.account.infra;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.ssafy.scenecrew.account.service.LocalCredentialVerifier;
import com.ssafy.scenecrew.account.service.LoginTokens;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/** 독립 저장 및 검증된 기존 세션의 조건부 교체. 공개 로그인은 성공 초기화까지 같은 연산으로 처리한다. */
public final class RedisLoginSessions {
    private static final DefaultRedisScript<Long> REPLACE = new DefaultRedisScript<>("""
            -- Redis scripts do not roll back runtime errors. Preflight deletion ACL before SET/DEL.
            if not redis.acl_check_cmd('DEL', unpack(KEYS)) then
              return redis.error_reply('Session commit deletion permission unavailable')
            end
            if ARGV[3] ~= '' and redis.call('GET', KEYS[2]) ~= ARGV[3] then return -1 end
            if not redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 0 end
            if ARGV[3] ~= '' then redis.call('DEL', KEYS[2]) end
            if #KEYS == 4 then redis.call('DEL', KEYS[3], KEYS[4]) end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CLEANUP = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisLoginSessions(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public void register(LocalCredentialVerifier.VerifiedAccount account, LoginTokens tokens, String channel) {
        if (!"WEB".equals(channel) && !"ANDROID".equals(channel)) {
            throw new IllegalArgumentException("Invalid channel");
        }
        String key = key(tokens.sessionId());
        // v1 포맷: 요청 소유 표식·계정·수단·버전·채널·refresh hash·절대 만료.
        String value = String.join("|", "v1", UUID.randomUUID().toString(),
                account.accountId().toString(), account.identityId().toString(),
                Long.toString(account.tokenVersion()), channel, hash(tokens.refreshToken()),
                tokens.refreshExpiresAt().toString());
        Duration ttl = Duration.between(clock.instant(), tokens.refreshExpiresAt());
        if (ttl.isNegative() || ttl.isZero()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        try {
            // SET NX PX: 세션과 만료를 함께 저장한다. 충돌 시 다른 요청 값을 덮어쓰지 않는다.
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, ttl))) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }
        } catch (RuntimeException failure) {
            try {
                redis.execute(CLEANUP, List.of(key), value);
            } catch (RuntimeException cleanupFailure) {
                // 저장됐을 수 있는 세션은 최초 SET의 TTL로 만료된다. 원문 예외/자격은 로그에 남기지 않는다.
            }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public static String key(UUID sessionId) { return "auth:session:" + sessionId; }

    /** Standalone Redis candidate. Register+replace have one Redis commit point.
     * An unknown acknowledgement never restores the old session or retries this operation.
     */
    public void registerReplacing(LocalCredentialVerifier.VerifiedAccount account, LoginTokens tokens,
            String channel, UUID oldId, String oldValue) {
        registerReplacing(account, tokens, channel, oldId, oldValue, null);
    }

    public void registerReplacingAndReset(LocalCredentialVerifier.VerifiedAccount account, LoginTokens tokens,
            String channel, UUID oldId, String oldValue, List<String> failureKeys) {
        if (failureKeys == null || failureKeys.size() != 2) throw new IllegalArgumentException("Invalid failure keys");
        registerReplacing(account, tokens, channel, oldId, oldValue, failureKeys);
    }

    private void registerReplacing(LocalCredentialVerifier.VerifiedAccount account, LoginTokens tokens,
            String channel, UUID oldId, String oldValue, List<String> failureKeys) {
        String newKey = key(tokens.sessionId());
        String value = String.join("|", "v1", UUID.randomUUID().toString(), account.accountId().toString(),
                account.identityId().toString(), Long.toString(account.tokenVersion()), channel,
                hash(tokens.refreshToken()), tokens.refreshExpiresAt().toString());
        long millis = Duration.between(clock.instant(), tokens.refreshExpiresAt()).toMillis();
        if (millis <= 0 || !("WEB".equals(channel) || "ANDROID".equals(channel)))
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        try {
            List<String> keys = failureKeys == null ? List.of(newKey, oldId == null ? newKey : key(oldId))
                    : List.of(newKey, oldId == null ? newKey : key(oldId), failureKeys.get(0), failureKeys.get(1));
            Long result = redis.execute(REPLACE, keys, value, Long.toString(millis),
                    oldId == null ? "" : oldValue);
            if (Long.valueOf(-1).equals(result)) throw new BusinessException(ErrorCode.AUTH_CONTEXT_CONFLICT);
            if (!Long.valueOf(1).equals(result)) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            try { redis.execute(CLEANUP, List.of(newKey), value); } catch (RuntimeException ignored) { }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** Ticket deletion and session creation share one Redis commit point. Existing sessions are preserved. */
    public void registerConsumingOAuthTicket(LocalCredentialVerifier.VerifiedAccount account, LoginTokens tokens,
            String channel, String provider, String ticketKey, String expected) {
        if (!java.util.Set.of("GOOGLE","KAKAO","NAVER").contains(provider) || !ticketKey.startsWith("auth:oauth:ticket:") || expected == null || expected.isEmpty()
                || !("WEB".equals(channel) || "ANDROID".equals(channel)))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        String newKey=key(tokens.sessionId());
        String value=String.join("|","v2",UUID.randomUUID().toString(),account.accountId().toString(),
                account.identityId().toString(),Long.toString(account.tokenVersion()),channel,hash(tokens.refreshToken()),
                tokens.refreshExpiresAt().toString(),provider);
        long millis=Duration.between(clock.instant(),tokens.refreshExpiresAt()).toMillis();
        if (millis<=0) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        try {
            Long result=redis.execute(REPLACE,List.of(newKey,ticketKey),value,Long.toString(millis),expected);
            if (Long.valueOf(-1).equals(result)) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            if (!Long.valueOf(1).equals(result)) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        } catch (BusinessException e) { throw e;
        } catch (RuntimeException e) {
            try { redis.execute(CLEANUP,List.of(newKey),value); } catch (RuntimeException ignored) { }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}

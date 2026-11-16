package com.ssafy.scenecrew.account.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.global.error.*;

/** 내부 Refresh 회전 로직. 공개 빈과 복구 가능한 자격정보 저장은 구성하지 않는다. */
public final class RefreshService {
    private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            local receipt = redis.call('GET', KEYS[2])
            local logout = redis.call('GET', KEYS[3])
            if logout or not current then return 0 end
            -- 이전 회전 기록의 만료만으로 현재 세션의 회전을 막지 않는다.
            if current ~= ARGV[1] or (receipt and receipt ~= ARGV[5]) then return 2 end
            local evidence = ARGV[1] .. '|' .. ARGV[4] .. '|' .. ARGV[2]
            -- Scripts do not roll back errors: check both SET commands before the first mutation.
            if not redis.acl_check_cmd('SET', KEYS[1], ARGV[2], 'XX', 'PX', ARGV[3])
              or not redis.acl_check_cmd('SET', KEYS[2], evidence, 'PX', '120000') then
              return redis.error_reply('Rotation write permission unavailable')
            end
            redis.call('SET', KEYS[2], evidence, 'PX', 120000)
            redis.call('SET', KEYS[1], ARGV[2], 'XX', 'PX', ARGV[3])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final CurrentAccountReader accounts;
    private final LoginTokenIssuer issuer;
    private final Clock clock;

    public RefreshService(StringRedisTemplate redis, CurrentAccountReader accounts, LoginTokenIssuer issuer, Clock clock) {
        this.redis = redis; this.accounts = accounts; this.issuer = issuer; this.clock = clock;
    }

    public LoginTokens rotate(String refresh, String channel, String attempt) {
        if (attempt == null || !attempt.matches("[A-Za-z0-9_-]{22}"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (!("WEB".equals(channel) || "ANDROID".equals(channel)))
            throw new BusinessException(ErrorCode.INVALID_AUTH_CHANNEL);
        if (refresh == null || !refresh.matches("[0-9a-fA-F-]{36}\\.[A-Za-z0-9_-]{43}"))
            throw AccessJwtVerifier.denied();
        UUID sid = AccessJwtVerifier.uuid(refresh.substring(0, 36));
        String proof = hash(refresh);
        try {
            String expected = redis.opsForValue().get(RedisLoginSessions.key(sid));
            var session = StoredLoginSession.parse(expected, clock);
            String receipt = redis.opsForValue().get(receiptKey(sid));
            if (!matchesProof(expected, proof)) rejectUsedProof(sid, proof, channel, attempt);
            verifyProof(session, expected, proof, channel);
            if (!accounts.isActive(session)) throw AccessJwtVerifier.denied();
            return accounts.withIssuanceLock(session, () -> {
                if (receipt != null) {
                    var evidence = receiptFields(receipt);
                    if (evidence.previous().equals(expected)) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
                    if (!evidence.next().equals(expected)) throw new BusinessException(ErrorCode.AUTH_CONTEXT_CONFLICT);
                    // A retry may already carry the newly applied Cookie. Same K must not rotate B again.
                    if (attempt.equals(evidence.attempt())) throw new BusinessException(ErrorCode.REFRESH_ATTEMPT_MISMATCH);
                }
                var tokens = issuer.rotate(session, sid);
                boolean local = "LOCAL".equals(session.provider());
                String next = String.join("|", local ? "v1" : "v2", UUID.randomUUID().toString(), session.accountId().toString(),
                        session.identityId().toString(), Long.toString(session.tokenVersion()), channel,
                        hash(tokens.refreshToken()), tokens.refreshExpiresAt().toString());
                if (!local) next += "|" + session.provider();
                long ttl = Duration.between(clock.instant(), tokens.refreshExpiresAt()).toMillis();
                if (ttl <= 0) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
                Long result = redis.execute(ROTATE, List.of(RedisLoginSessions.key(sid), receiptKey(sid),
                        LogoutService.receiptKey(sid)), expected, next, Long.toString(ttl), attempt, receipt == null ? "" : receipt);
                if (Long.valueOf(2).equals(result)) rejectUsedProof(sid, proof, channel, attempt);
                if (Long.valueOf(0).equals(result)) throw AccessJwtVerifier.denied();
                if (!Long.valueOf(1).equals(result)) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
                return tokens;
            });
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // The old proof may already be consumed. Never restore, retry, or delete the new value here.
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private void rejectUsedProof(UUID sid, String proof, String channel, String attempt) {
        String current = redis.opsForValue().get(RedisLoginSessions.key(sid));
        if (current == null || redis.opsForValue().get(LogoutService.receiptKey(sid)) != null)
            throw AccessJwtVerifier.denied();
        String receipt = redis.opsForValue().get(receiptKey(sid));
        if (receipt == null) throw AccessJwtVerifier.denied();
        var evidence = receiptFields(receipt);
        String previous = evidence.previous();
        String next = evidence.next();
        var session = StoredLoginSession.parse(previous, clock);
        verifyProof(session, previous, proof, channel);
        if (!accounts.isActive(session)) throw AccessJwtVerifier.denied();
        // An evidence-only partial write is not a completed rotation.
        if (previous.equals(current)) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        if (!next.equals(current)) throw AccessJwtVerifier.denied();
        if (!attempt.equals(evidence.attempt())) throw new BusinessException(ErrorCode.REFRESH_ATTEMPT_MISMATCH);
        throw new BusinessException(ErrorCode.REFRESH_RECOVERY_UNAVAILABLE);
    }

    private static void verifyProof(StoredLoginSession session, String snapshot, String proof, String channel) {
        if (!matchesProof(snapshot, proof)) throw AccessJwtVerifier.denied();
        if (!session.channel().equals(channel)) throw new BusinessException(ErrorCode.AUTH_CHANNEL_MISMATCH);
    }
    private static boolean matchesProof(String snapshot, String proof) {
        return MessageDigest.isEqual(snapshot.split("\\|", -1)[6].getBytes(StandardCharsets.US_ASCII),
                proof.getBytes(StandardCharsets.US_ASCII));
    }
    private static RotationReceipt receiptFields(String receipt) {
        String[] fields = receipt.split("\\|", -1);
        int size = "v1".equals(fields[0]) ? 8 : "v2".equals(fields[0]) ? 9 : 0;
        if (size == 0 || fields.length != size * 2 + 1 || !fields[size].matches("[A-Za-z0-9_-]{22}")
                || !fields[0].equals(fields[size + 1]))
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        return new RotationReceipt(String.join("|", Arrays.copyOfRange(fields, 0, size)), fields[size],
                String.join("|", Arrays.copyOfRange(fields, size + 1, fields.length)));
    }
    private record RotationReceipt(String previous, String attempt, String next) {
        @Override public String toString() { return "RotationReceipt[redacted]"; }
    }
    public static String receiptKey(UUID sid) { return "auth:refresh:" + sid; }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}

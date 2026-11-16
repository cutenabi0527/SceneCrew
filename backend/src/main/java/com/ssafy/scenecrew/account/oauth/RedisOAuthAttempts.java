package com.ssafy.scenecrew.account.oauth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.global.error.*;

/** Standalone Redis. Secret values are hashed in keys; records never contain provider codes/tokens. */
public final class RedisOAuthAttempts {
    private static final DefaultRedisScript<Long> TAKE=new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end return redis.call('DEL',KEYS[1])",Long.class);
    private static final DefaultRedisScript<Long> MOVE=new DefaultRedisScript<>("""
            if not redis.acl_check_cmd('DEL', KEYS[1]) then return redis.error_reply('OAuth deletion unavailable') end
            if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end
            if not redis.call('SET',KEYS[2],ARGV[2],'NX','PX',ARGV[3]) then return -1 end
            redis.call('DEL',KEYS[1])
            return 1
            """,Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final JsonMapper json=JsonMapper.builder().build();
    private final SecureRandom random=new SecureRandom();
    public RedisOAuthAttempts(StringRedisTemplate redis, Clock clock) { this.redis=redis; this.clock=clock; }
    public String secret() { byte[] bytes=new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public String save(String kind, Attempt attempt, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || !attempt.expiresAt().isAfter(clock.instant())) throw unavailable();
        String secret=secret();
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(kind,secret),json.writeValueAsString(attempt),ttl))) throw unavailable();
            return secret;
        } catch (org.springframework.dao.DataAccessException e) { throw unavailable(); }
    }
    public Saved read(String kind,String secret,ErrorCode invalid) {
        if (secret==null || !secret.matches("[A-Za-z0-9_-]{43}")) throw new BusinessException(invalid);
        try {
            String value=redis.opsForValue().get(key(kind,secret));
            if (value==null) throw new BusinessException(invalid);
            var attempt=json.readValue(value,Attempt.class);
            if (!attempt.expiresAt().isAfter(clock.instant())) throw new BusinessException(invalid);
            return new Saved(key(kind,secret),value,attempt);
        } catch (BusinessException e) { throw e;
        } catch (RuntimeException e) { throw unavailable(); }
    }
    /** 형식이 맞는 secret의 저장 여부만 본다. 만료·결합 검사는 read가 맡는다. */
    public boolean has(String kind,String secret) {
        if (secret==null || !secret.matches("[A-Za-z0-9_-]{43}")) return false;
        try { return Boolean.TRUE.equals(redis.hasKey(key(kind,secret))); }
        catch (org.springframework.dao.DataAccessException e) { throw unavailable(); }
    }
    public void consume(Saved saved,ErrorCode invalid) {
        try {
            if (!Long.valueOf(1).equals(redis.execute(TAKE,List.of(saved.key()),saved.value()))) throw new BusinessException(invalid);
        } catch (org.springframework.dao.DataAccessException e) { throw unavailable(); }
    }
    /** One Redis commit point: consume callback ticket and retain its verified proof as OAuthPending. */
    PendingTicket pending(Saved callback,Duration ttl) {
        var a=callback.attempt();
        if (!"LOGIN".equals(a.purpose()) || a.subject()==null || a.subject().isBlank()
                || !callback.key().startsWith("auth:oauth:ticket:") || ttl.isNegative() || ttl.isZero())
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        String secret=secret();
        var pending=new Attempt(a.provider(),"COMPLETE_SIGNUP",a.channel(),a.attemptId(),a.initiatorHash(),
                a.returnUri(),clock.instant().plus(ttl),a.subject(),null);
        try {
            Long result=redis.execute(MOVE,List.of(callback.key(),key("signup",secret)),callback.value(),
                    json.writeValueAsString(pending),Long.toString(ttl.toMillis()));
            if (Long.valueOf(0).equals(result)) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            if (!Long.valueOf(1).equals(result)) throw unavailable();
            return new PendingTicket(secret, pending.expiresAt());
        } catch (org.springframework.dao.DataAccessException e) { throw unavailable(); }
    }
    public void claimCode(SocialProvider provider,String code,Duration ttl) {
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("auth:oauth:code:"+provider+":"+hash(code),"used",ttl)))
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
        } catch (org.springframework.dao.DataAccessException e) { throw unavailable(); }
    }
    public static String key(String kind,String secret) { return "auth:oauth:"+kind+":"+hash(secret); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    record PendingTicket(String secret, Instant expiresAt) {
        @Override public String toString() { return "PendingTicket[redacted]"; }
    }
    public record Attempt(SocialProvider provider,String purpose,String channel,String attemptId,String initiatorHash,
            String returnUri,Instant expiresAt,String subject,String codeVerifier) {
        @Override public String toString() { return "OAuthAttempt[redacted]"; }
    }
    public record Saved(String key,String value,Attempt attempt) {
        @Override public String toString() { return "SavedOAuthAttempt[redacted]"; }
    }
}

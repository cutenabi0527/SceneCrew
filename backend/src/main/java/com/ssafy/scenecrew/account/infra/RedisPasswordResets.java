package com.ssafy.scenecrew.account.infra;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.service.LocalCredentialVerifier;
import com.ssafy.scenecrew.account.service.PasswordResetAccountWriter;
import com.ssafy.scenecrew.account.service.PasswordResetDelivery;
import com.ssafy.scenecrew.global.error.*;

/** Separate recovery budget. Redis stores a random reference, never the usable recovery proof. */
public class RedisPasswordResets {
    private static final DefaultRedisScript<String> RESERVE=new DefaultRedisScript<>("""
            local kind=redis.call('TYPE',KEYS[2]).ok
            if kind ~= 'none' and kind ~= 'zset' then return 'UNAVAILABLE' end
            local t=redis.call('TIME')
            local now=t[1]*1000+math.floor(t[2]/1000)
            local recent=redis.call('ZREVRANGEBYSCORE',KEYS[2],'+inf',now-600000+1,'WITHSCORES')
            if #recent >= 10 or (#recent > 0 and now-tonumber(recent[2]) < 60000) then return 'LIMITED' end
            local old=redis.call('GET',KEYS[1])
            local reuse=false
            local payload=cjson.decode(ARGV[1])
            if old then
              local previous=cjson.decode(old)
              reuse=previous.binding == payload.binding and previous.expiresAt > now
              if reuse then payload=previous end
            end
            if not reuse then payload.expiresAt=now+600000 end
            local value=cjson.encode(payload)
            local ttl=tostring(payload.expiresAt-now)
            if not redis.acl_check_cmd('SET',KEYS[1],value,'PX',ttl)
              or not redis.acl_check_cmd('SET',KEYS[3],value,'PX',ttl)
              or not redis.acl_check_cmd('ZREMRANGEBYSCORE',KEYS[2],'-inf',now-600000)
              or not redis.acl_check_cmd('ZADD',KEYS[2],now,ARGV[2])
              or not redis.acl_check_cmd('PEXPIRE',KEYS[2],'600000') then return 'UNAVAILABLE' end
            -- The reused proof reference already exists with its original TTL; do not renew it.
            if not reuse then
              redis.call('SET',KEYS[3],value,'PX',ttl)
              redis.call('SET',KEYS[1],value,'PX',ttl)
            end
            redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now-600000)
            redis.call('ZADD',KEYS[2],now,ARGV[2])
            redis.call('PEXPIRE',KEYS[2],600000)
            return value
            """,String.class);
    private final StringRedisTemplate redis;
    private final SecretKeySpec secret;
    private final JsonMapper json=JsonMapper.builder().build();
    public RedisPasswordResets(StringRedisTemplate redis,byte[] key) {
        if(key.length<32) throw new IllegalArgumentException("Missing recovery secret");
        this.redis=redis; secret=new SecretKeySpec(key.clone(),"HmacSHA256");
    }
    public PasswordResetDelivery.Message reserve(String email, Optional<PasswordResetAccountWriter.Target> target) {
        String id=UUID.randomUUID().toString();
        var value=new HashMap<String,Object>();
        value.put("id",id);
        value.put("binding",target.map(t->t.accountId()+":"+t.identityId()+":"+t.tokenVersion()).orElse("NONE"));
        value.put("accountId",target.map(t->t.accountId().toString()).orElse(""));
        value.put("identityId",target.map(t->t.identityId().toString()).orElse(""));
        value.put("tokenVersion",target.map(t->Long.toString(t.tokenVersion())).orElse("-1"));
        String digest=hmac("email:"+email);
        String saved=redis.execute(RESERVE,List.of("auth:reset:current:"+digest,budgetKey(email),key(id)),
                json.writeValueAsString(value),id);
        if("LIMITED".equals(saved)) throw new BusinessException(ErrorCode.RATE_LIMITED,"잠시 후 다시 요청해 주세요.");
        if(saved==null || "UNAVAILABLE".equals(saved)) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        var p=json.readTree(saved);
        return new PasswordResetDelivery.Message(email,proof(p.get("id").asString()),
                Instant.ofEpochMilli(p.get("expiresAt").asLong()));
    }
    public Verified read(String proof) {
        if(proof==null || !proof.matches("[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}")) denied();
        String id=proof.substring(0,36);
        try { if(!UUID.fromString(id).toString().equals(id)) denied(); }
        catch(IllegalArgumentException invalid) { denied(); }
        if(!MessageDigest.isEqual(proof(id).getBytes(StandardCharsets.US_ASCII),proof.getBytes(StandardCharsets.US_ASCII))) denied();
        String saved=redis.opsForValue().get(key(id));
        if(saved==null) throw new BusinessException(ErrorCode.INVALID_STATE);
        var p=json.readTree(saved);
        if(!id.equals(p.path("id").asString()) || p.path("accountId").asString().isEmpty()) denied();
        return new Verified(new PasswordResetAccountWriter.Target(UUID.fromString(p.get("accountId").asString()),
                UUID.fromString(p.get("identityId").asString()),Long.parseLong(p.get("tokenVersion").asString())),
                Instant.ofEpochMilli(p.get("expiresAt").asLong()));
    }
    private String proof(String id) { return id+"."+hmac("proof:"+id); }
    private String hmac(String input) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(secret);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(
                    ("password-reset:v1:"+input).getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException e) { throw new IllegalStateException("Recovery secret unavailable"); }
    }
    public String budgetKey(String email) { return "auth:reset:send:"+hmac("email:"+LocalCredentialVerifier.normalizeEmail(email)); }
    public static String key(String id) { return "auth:reset:proof:"+id; }
    private static void denied() { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    public record Verified(PasswordResetAccountWriter.Target target,Instant expiresAt) {
        @Override public String toString() { return "ResetProof[redacted]"; }
    }
}

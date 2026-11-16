package com.ssafy.scenecrew.account.infra;

import java.time.Instant;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.dto.RegisterRequest;
import com.ssafy.scenecrew.account.service.RegistrationCodes;
import com.ssafy.scenecrew.global.error.*;

/** Standalone Redis state transitions. No lease: an uncertain owner is never silently replaced. */
public final class RedisVerificationRequests {
    private static final String LOAD = """
            local raw=redis.call('GET',KEYS[1])
            if not raw then return -1 end
            local p=cjson.decode(raw)
            local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000)
            if now >= p.expiresAtEpochMilli then return -1 end
            """;
    private static final DefaultRedisScript<Long> CLAIM = new DefaultRedisScript<>(LOAD + """
            if p.proofKeyId ~= ARGV[1] then return -4 end
            if p.state and p.state ~= 'PENDING' then return -1 end
            if ARGV[2] ~= 'true' then
              p.failures=(p.failures or 0)+1
              if p.failures >= 5 then p.state='TERMINATED' end
              redis.call('SET',KEYS[1],cjson.encode(p),'KEEPTTL')
              return -3
            end
            p.state='VERIFYING'; p.owner=ARGV[3]
            redis.call('SET',KEYS[1],cjson.encode(p),'KEEPTTL')
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>(LOAD + """
            if p.state ~= 'VERIFYING' or p.owner ~= ARGV[1] then return -1 end
            p.state=ARGV[2]; p.owner=nil
            if ARGV[2] == 'COMPLETED' then
              p.passwordHash=nil; p.consent=nil; p.email=nil; p.displayName=nil
            end
            redis.call('SET',KEYS[1],cjson.encode(p),'KEEPTTL')
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RESEND = new DefaultRedisScript<>(LOAD + """
            if p.proofKeyId ~= ARGV[1] then return -4 end
            if p.state and p.state ~= 'PENDING' then return -1 end
            local kind=redis.call('TYPE',KEYS[2]).ok
            if kind ~= 'none' and kind ~= 'zset' then return -4 end
            local recent=redis.call('ZREVRANGEBYSCORE',KEYS[2],'+inf',now-600000+1,'WITHSCORES')
            if #recent >= 10 or (#recent > 0 and now-tonumber(recent[2]) < 60000) then return -2 end
            if not redis.acl_check_cmd('ZREMRANGEBYSCORE',KEYS[2],'-inf',now-600000)
              or not redis.acl_check_cmd('ZADD',KEYS[2],now,ARGV[2])
              or not redis.acl_check_cmd('PEXPIRE',KEYS[2],'600000') then return -4 end
            redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now-600000)
            redis.call('ZADD',KEYS[2],now,ARGV[2]); redis.call('PEXPIRE',KEYS[2],600000)
            return p.expiresAtEpochMilli
            """,Long.class);
    private final StringRedisTemplate redis;
    private final RedisPendingRegistrations pending;
    private final RegistrationCodes codes;
    private final JsonMapper json=JsonMapper.builder().build();
    public RedisVerificationRequests(StringRedisTemplate redis,RedisPendingRegistrations pending,RegistrationCodes codes) {
        this.redis=redis; this.pending=pending; this.codes=codes;
    }
    public Pending read(UUID id) {
        try {
            String value=redis.opsForValue().get(RedisPendingRegistrations.key(id));
            if (value==null) throw new BusinessException(ErrorCode.INVALID_STATE);
            var p=json.readTree(value);
            if (p.has("state") && !Set.of("PENDING","VERIFYING").contains(p.get("state").asString()))
                throw new BusinessException(ErrorCode.INVALID_STATE);
            return new Pending(id,p.path("email").asString(),p.path("displayName").asString(),
                    p.path("passwordHash").asString(),json.treeToValue(p.path("consent"),RegisterRequest.Consent.class),
                    Instant.ofEpochMilli(p.path("expiresAtEpochMilli").asLong()));
        } catch (BusinessException e) { throw e; }
        catch (RuntimeException e) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }
    public void claim(UUID id,String supplied,UUID owner) {
        if (supplied==null || !supplied.matches("[0-9]{6}")) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        execute(CLAIM,List.of(RedisPendingRegistrations.key(id)),codes.keyId(),
                Boolean.toString(codes.matches(id,supplied)),owner.toString());
    }
    public void finish(UUID id,UUID owner,String state) {
        if (!Set.of("PENDING","COMPLETED","TERMINATED").contains(state)) throw new IllegalArgumentException();
        execute(FINISH,List.of(RedisPendingRegistrations.key(id)),owner.toString(),state);
    }
    public RedisPendingRegistrations.Accepted reserveResend(UUID id) {
        var p=read(id);
        long expiry=execute(RESEND,List.of(RedisPendingRegistrations.key(id),pending.sendBudgetKey(p.email())),
                codes.keyId(),UUID.randomUUID().toString());
        return new RedisPendingRegistrations.Accepted(id,Instant.ofEpochMilli(expiry));
    }
    private long execute(DefaultRedisScript<Long> script,List<String> keys,String... args) {
        try {
            Long result=redis.execute(script,keys,(Object[])args);
            if (result==null || result==-4) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            if (result==-1) throw new BusinessException(ErrorCode.INVALID_STATE);
            if (result==-2) throw new BusinessException(ErrorCode.RATE_LIMITED);
            if (result==-3) throw new BusinessException(ErrorCode.INVALID_REQUEST);
            return result;
        } catch (org.springframework.dao.DataAccessException e) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }
    public record Pending(UUID requestId,String email,String displayName,String passwordHash,
            RegisterRequest.Consent consent,Instant expiresAt) {
        @Override public String toString() { return "PendingRegistration[redacted]"; }
    }
}

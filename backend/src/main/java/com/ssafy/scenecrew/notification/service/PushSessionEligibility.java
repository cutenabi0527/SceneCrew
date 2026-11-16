package com.ssafy.scenecrew.notification.service;

import java.time.Clock;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.StoredLoginSession;
import com.ssafy.scenecrew.global.error.BusinessException;

@Component
public class PushSessionEligibility {
    private final StringRedisTemplate redis;

    PushSessionEligibility(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isCurrent(UUID sessionId, UUID accountId, long tokenVersion) {
        String value = redis.opsForValue().get(RedisLoginSessions.key(sessionId));
        try {
            var session = StoredLoginSession.parse(value, Clock.systemUTC());
            return session.accountId().equals(accountId) && session.tokenVersion() == tokenVersion
                    && "ANDROID".equals(session.channel());
        } catch (BusinessException invalidSession) {
            return false;
        }
    }
}

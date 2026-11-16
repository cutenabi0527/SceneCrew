package com.ssafy.scenecrew.account.service;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.global.error.*;

/** 내부 연결 전용. JWT→Redis→DB 순서로 검증하며 세션을 갱신하지 않는다. */
public final class SessionAuthenticator {
    private final AccessJwtVerifier jwt;
    private final StringRedisTemplate redis;
    private final CurrentAccountReader accounts;
    private final Clock clock;
    public SessionAuthenticator(AccessJwtVerifier jwt, StringRedisTemplate redis,
            CurrentAccountReader accounts, Clock clock) {
        this.jwt = jwt; this.redis = redis; this.accounts = accounts; this.clock = clock;
    }
    public Principal authenticate(String accessToken) {
        return snapshot(accessToken).principal();
    }
    Snapshot snapshot(String accessToken) {
        var claims = jwt.verify(accessToken);
        try {
            String stored = redis.opsForValue().get(RedisLoginSessions.key(claims.sessionId()));
            var session = StoredLoginSession.parse(stored, clock);
            if (!session.accountId().equals(claims.accountId())
                    || session.tokenVersion() != claims.tokenVersion() || !accounts.isActive(session)) {
                throw AccessJwtVerifier.denied();
            }
            return new Snapshot(new Principal(claims.accountId(), claims.sessionId(), session.identityId(),
                    session.tokenVersion(), session.channel()), stored);
        } catch (DataAccessException | TransactionException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
    record Snapshot(Principal principal, String storedValue) {
        @Override public String toString() { return "SessionSnapshot[redacted]"; }
    }
    public record Principal(UUID accountId, UUID sessionId, UUID identityId, long tokenVersion, String channel) {
        @Override public String toString() { return "AuthenticatedPrincipal[redacted]"; }
    }
}

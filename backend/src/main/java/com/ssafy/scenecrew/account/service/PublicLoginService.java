package com.ssafy.scenecrew.account.service;

import com.ssafy.scenecrew.account.dto.Credentials;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.global.error.*;

/** Candidate HTTP orchestration. No outer JPA transaction: state rechecks must be fresh reads. */
public final class PublicLoginService {
    private final EmailLoginService login;
    private final LoginFailureLimiter limiter;
    private final SessionAuthenticator auth;
    private final RedisLoginSessions sessions;
    public PublicLoginService(EmailLoginService login, LoginFailureLimiter limiter, SessionAuthenticator auth,
            RedisLoginSessions sessions) {
        this.login=login; this.limiter=limiter; this.auth=auth; this.sessions=sessions;
    }
    public EmailLoginService.Result login(String email, String password, Credentials credentials) {
        var keys = limiter.keys(email); limiter.check(keys);
        SessionAuthenticator.Principal old = null;
        String oldValue = null;
        if (credentials.access() != null && !credentials.access().isBlank()) {
            try {
                var snapshot = auth.snapshot(credentials.access());
                old = snapshot.principal();
                if (!old.channel().equals(credentials.channel())) throw new BusinessException(ErrorCode.AUTH_CHANNEL_MISMATCH);
                oldValue = snapshot.storedValue();
            } catch (BusinessException e) {
                if (e.getErrorCode() != ErrorCode.UNAUTHENTICATED) throw e;
                // An unverified old token is never used to locate/delete sessions.
                old = null;
            }
        }
        var oldId = old == null ? null : old.sessionId();
        String expected = oldValue;
        try {
            var result = login.login(email, password, credentials.channel(), (account, tokens, channel) ->
                    sessions.registerReplacingAndReset(account, tokens, channel, oldId, expected, keys));
            return result;
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.INVALID_CREDENTIALS) limiter.failure(keys);
            throw e;
        }
    }
}

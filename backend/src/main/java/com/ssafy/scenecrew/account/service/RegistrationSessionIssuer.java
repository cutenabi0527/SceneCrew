package com.ssafy.scenecrew.account.service;

import com.ssafy.scenecrew.account.dto.Credentials;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.global.error.*;

/** Reuses Login's current-state checks and atomic session replacement only on first completion. */
public final class RegistrationSessionIssuer {
    private final EmailLoginService login;
    private final SessionAuthenticator auth;
    private final RedisLoginSessions sessions;
    private final LoginFailureLimiter limiter;
    public RegistrationSessionIssuer(EmailLoginService login,SessionAuthenticator auth,
            RedisLoginSessions sessions,LoginFailureLimiter limiter) {
        this.login=login; this.auth=auth; this.sessions=sessions; this.limiter=limiter;
    }
    public Prepared prepare(Credentials credentials) {
        SessionAuthenticator.Principal old=null;
        String value=null;
        if (credentials.access()!=null && !credentials.access().isBlank()) {
            try {
                var snapshot=auth.snapshot(credentials.access()); old=snapshot.principal();
                if (!old.channel().equals(credentials.channel())) throw new BusinessException(ErrorCode.AUTH_CHANNEL_MISMATCH);
                value=snapshot.storedValue();
            } catch (BusinessException e) {
                if (e.getErrorCode()!=ErrorCode.UNAUTHENTICATED) throw e;
                old=null;
            }
        }
        var oldId=old==null?null:old.sessionId();
        String expected=value;
        return new Prepared(credentials.channel(),oldId,expected);
    }
    public EmailLoginService.Result issue(LocalCredentialVerifier.VerifiedAccount account,Prepared prepared) {
        var keys=limiter.keys(account.email());
        return login.issueVerified(account,prepared.channel(),(current,tokens,channel)->
                sessions.registerReplacingAndReset(current,tokens,channel,prepared.oldId(),prepared.expected(),keys));
    }
    public record Prepared(String channel,java.util.UUID oldId,String expected) {
        @Override public String toString() { return "PreparedSessionReplacement[redacted]"; }
    }
}

package com.ssafy.scenecrew.account.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;

/** Creates a social account and issues a session after the database commit. */
public final class SocialSignupService {
    private final RedisOAuthAttempts attempts;
    private final SocialSignupAccounts accounts;
    private final LoginTokenIssuer tokens;
    private final RedisLoginSessions sessions;
    private final Clock clock;
    private final Duration signupTtl;
    public SocialSignupService(RedisOAuthAttempts attempts,SocialSignupAccounts accounts,LoginTokenIssuer tokens,
            RedisLoginSessions sessions,Clock clock,Duration signupTtl) {
        if (signupTtl==null || signupTtl.isNegative() || signupTtl.toMillis()<1)
            throw new IllegalArgumentException("Explicit internal signup ticket lifetime required");
        this.attempts=attempts; this.accounts=accounts; this.tokens=tokens; this.sessions=sessions;
        this.clock=clock; this.signupTtl=signupTtl;
    }
    OAuthLoginService.OAuthPending pending(RedisOAuthAttempts.Saved callback) {
        var ticket = attempts.pending(callback, signupTtl);
        return new OAuthLoginService.OAuthPending(ticket.secret(), ticket.expiresAt(), callback.attempt().provider());
    }
    public EmailLoginService.Result complete(String signupTicket,String channel,String initiatorProof,
            String displayName,Consent consent) {
        var saved=attempts.read("signup",signupTicket,ErrorCode.UNAUTHENTICATED);
        var a=saved.attempt();
        if (!"COMPLETE_SIGNUP".equals(a.purpose()) || a.subject()==null || a.subject().isBlank()
                || !("WEB".equals(channel)||"ANDROID".equals(channel)) || !a.channel().equals(channel)
                || initiatorProof==null || !initiatorProof.matches("[A-Za-z0-9_-]{43}")
                || !MessageDigest.isEqual(a.initiatorHash().getBytes(StandardCharsets.UTF_8),
                    RedisOAuthAttempts.hash(initiatorProof).getBytes(StandardCharsets.UTF_8)))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        String name=displayName==null?"":displayName.trim();
        if (name.isEmpty() || name.codePointCount(0,name.length())>20
                || displayName.codePoints().anyMatch(c->Character.isISOControl(c)
                    || Character.getType(c)==Character.LINE_SEPARATOR || Character.getType(c)==Character.PARAGRAPH_SEPARATOR)
                || consent==null || !consent.service()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        // Consume before any DB write. Rollback/unknown commit/response loss never restores this proof.
        attempts.consume(saved,ErrorCode.UNAUTHENTICATED);
        var account=accounts.create(a,name,consent);
        // Only an acknowledged DB commit reaches the existing safe social session issuer.
        // A private, never returned grant lets it atomically register a session and consume the grant.
        var grant=new RedisOAuthAttempts.Attempt(a.provider(),"SIGNUP_SESSION",a.channel(),a.attemptId(),
                a.initiatorHash(),a.returnUri(),clock.instant().plusSeconds(60),a.subject(),null);
        String secret=attempts.save("ticket",grant,Duration.ofSeconds(60));
        var stored=attempts.read("ticket",secret,ErrorCode.UNAUTHENTICATED);
        return accounts.withIssuanceLock(account,a.provider(),()->{
            var issued=tokens.issue(account);
            sessions.registerConsumingOAuthTicket(account,issued,channel,a.provider().name(),stored.key(),stored.value());
            return new EmailLoginService.Result(account,issued);
        });
    }
    public record Consent(boolean service,boolean voice,boolean push,boolean externalAi) { }
}

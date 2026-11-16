package com.ssafy.scenecrew.account.oauth;

import java.net.URI;
import java.time.Duration;
import java.time.Clock;
import java.util.*;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.account.service.SessionAuthenticator.Principal;
import com.ssafy.scenecrew.global.error.*;

/** #227 연결 흐름. WEB 015 시작(OAuthLinkController)과 010 callback의 link-state 분기에서 쓴다.
 * Reuses #226 provider client, state storage, S256 and one-time code claims.
 * 별도 linkTicket 없이 callback에서 서버가 직접 완료한다(오세진 결정 2026-11-05, WEB 연결만 공개).
 * 016 해제는 OAuthLinkController가 같은 재확인으로 공개한다. Android 연결은 아직 공개하지 않는다.
 */
public final class OAuthIdentityChanges {
    private final SessionAuthenticator authentication;
    private final IdentityChanges changes;
    private final IdentityReauthentication reauthentication;
    private final RedisOAuthAttempts attempts;
    private final Map<SocialProvider,OAuthProviderClient> clients;
    private final Clock clock;
    private final Duration stateTtl;
    public OAuthIdentityChanges(SessionAuthenticator authentication, IdentityChanges changes,
            IdentityReauthentication reauthentication, RedisOAuthAttempts attempts,
            List<OAuthProviderClient> clients, Clock clock, Duration stateTtl) {
        this.authentication=authentication; this.changes=changes; this.reauthentication=Objects.requireNonNull(reauthentication);
        this.attempts=attempts; this.clock=clock; this.stateTtl=stateTtl;
        if (stateTtl.isZero() || stateTtl.isNegative() || stateTtl.compareTo(Duration.ofMinutes(10))>0)
            throw new IllegalArgumentException("Invalid OAuth state lifetime");
        var map=new EnumMap<SocialProvider,OAuthProviderClient>(SocialProvider.class);
        for (var client:clients) if (map.put(client.provider(),client)!=null) throw new IllegalArgumentException("Duplicate provider");
        this.clients=Map.copyOf(map); // borrowed #226 clients: owner closes them
    }
    public URI authorize(String accessToken, String proof, SocialProvider provider, String browserProof) {
        var principal=authentication.authenticate(accessToken);
        reauthentication.require(principal,IdentityReauthentication.Operation.LINK_IDENTITY,provider,proof);
        String binding=binding(principal,browserProof);
        var client=client(provider);
        String verifier=attempts.secret(); client.authorizationUrl("configuration-check",verifier);
        var state=new RedisOAuthAttempts.Attempt(provider,"LINK",principal.channel(),principal.sessionId().toString(),
                binding,"",clock.instant().plus(stateTtl),null,verifier);
        return client.authorizationUrl(attempts.save("link-state",state,stateTtl),verifier);
    }
    /** Internal completion, not a public wire or replacement for candidate.3's future LINK ticket. */
    public UUID complete(String accessToken, String proof, SocialProvider provider, String state, String code, String browserProof) {
        var principal=authentication.authenticate(accessToken);
        reauthentication.require(principal,IdentityReauthentication.Operation.LINK_IDENTITY,provider,proof);
        var saved=attempts.read("link-state",state,ErrorCode.INVALID_REQUEST);
        var a=saved.attempt();
        if (a.provider()!=provider || !"LINK".equals(a.purpose()) || !principal.channel().equals(a.channel())
                || !principal.sessionId().toString().equals(a.attemptId()) || !binding(principal,browserProof).equals(a.initiatorHash())
                || code==null || code.isBlank() || code.length()>8192) throw invalid();
        attempts.consume(saved,ErrorCode.INVALID_REQUEST);
        attempts.claimCode(provider,code,Duration.ofMinutes(10));
        String subject=client(provider).verifiedSubject(code,state,a.codeVerifier());
        // Reauthenticate Redis session after the external HTTP wait; DB and reauth are rechecked under the account lock.
        var current=authentication.authenticate(accessToken);
        return changes.link(current,provider,subject,proof);
    }
    /** callback의 state가 연결 시도인지 확인한다. 로그인 state와 저장 종류가 달라 섞이지 않는다. */
    public boolean owns(String state) { return attempts.has("link-state",state); }
    /** 제공자 화면에서 취소(access_denied)한 연결 시도를 닫는다. 제공자·목적이 맞을 때만 소비한다. */
    public void cancel(SocialProvider provider, String state) {
        var saved=attempts.read("link-state",state,ErrorCode.INVALID_REQUEST);
        if (saved.attempt().provider()!=provider || !"LINK".equals(saved.attempt().purpose())) throw invalid();
        attempts.consume(saved,ErrorCode.INVALID_REQUEST);
    }
    public void unlink(String accessToken, String proof, SocialProvider provider) {
        changes.unlink(authentication.authenticate(accessToken),provider,proof);
    }
    private static String binding(Principal p,String browserProof) {
        if (browserProof==null || !browserProof.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        return RedisOAuthAttempts.hash(p.accountId()+":"+p.sessionId()+":"+p.tokenVersion()+":"+p.channel()+":"+browserProof);
    }
    private OAuthProviderClient client(SocialProvider provider) {
        var client=clients.get(provider);
        if (client==null) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        return client;
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

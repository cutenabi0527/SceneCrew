package com.ssafy.scenecrew.account.oauth;

import java.net.URI;
import java.time.*;
import java.util.*;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;

/** LOGIN only. The transport must establish initiatorProof; channel headers alone are not proof.
 * No HTTP transport is activated until its binding wire has been accepted.
 */
public final class OAuthLoginService implements AutoCloseable {
    static final String ANDROID_STATE = "android-state";
    private static final String ANDROID_HOME = "ANDROID:HOME";
    private static final Duration ANDROID_STATE_TTL = Duration.ofMinutes(5);
    private final Map<SocialProvider,OAuthProviderClient> clients;
    private final Map<String,URI> returns;
    private final RedisOAuthAttempts attempts;
    private final OAuthAccountReader accounts;
    private final LoginTokenIssuer tokens;
    private final RedisLoginSessions sessions;
    private final Clock clock;
    private final Duration stateTtl;
    public OAuthLoginService(List<OAuthProviderClient> clients,Map<String,URI> returns,RedisOAuthAttempts attempts,
            OAuthAccountReader accounts,LoginTokenIssuer tokens,RedisLoginSessions sessions,Clock clock,Duration stateTtl) {
        var map=new EnumMap<SocialProvider,OAuthProviderClient>(SocialProvider.class);
        for (var client:clients) if (map.put(client.provider(),client)!=null) throw new IllegalArgumentException("Duplicate OAuth provider");
        this.clients=Map.copyOf(map); this.returns=Map.copyOf(returns); this.attempts=attempts;
        this.accounts=accounts; this.tokens=tokens; this.sessions=sessions; this.clock=clock;
        if (stateTtl.isZero() || stateTtl.isNegative() || stateTtl.compareTo(Duration.ofMinutes(10))>0)
            throw new IllegalArgumentException("Invalid OAuth state lifetime");
        this.stateTtl=stateTtl;
        for (var uri:returns.values()) if (!"https".equals(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("Invalid registered OAuth return URI");
    }
    public URI authorize(SocialProvider provider,String purpose,String channel,String returnRoute,String initiatorProof) {
        if (!"LOGIN".equals(purpose) || !("WEB".equals(channel)||"ANDROID".equals(channel))
                || !("HOME".equals(returnRoute)||"INVITE".equals(returnRoute))) throw invalid();
        proof(initiatorProof,ErrorCode.INVALID_REQUEST);
        return start(provider, channel, returns.get(channel+":"+returnRoute), "state",
                RedisOAuthAttempts.hash(initiatorProof), stateTtl);
    }

    boolean androidEnabled() { return returns.containsKey(ANDROID_HOME); }

    URI authorizeAndroid(SocialProvider provider, String purpose, String returnRoute, String challenge, String method) {
        if (!"LOGIN".equals(purpose) || !"HOME".equals(returnRoute) || !"S256".equals(method)) throw invalid();
        proof(challenge, ErrorCode.INVALID_REQUEST);
        byte[] digest = Base64.getUrlDecoder().decode(challenge);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(digest).equals(challenge)) throw invalid();
        return start(provider, "ANDROID", returns.get(ANDROID_HOME), ANDROID_STATE, HexFormat.of().formatHex(digest),
                ANDROID_STATE_TTL);
    }

    private URI start(SocialProvider provider, String channel, URI uri, String stateKind, String initiatorHash,
            Duration ttl) {
        if (uri==null) throw invalid();
        var client=client(provider);
        // Configuration must fail before allocating state.
        String verifier=attempts.secret();
        client.authorizationUrl("configuration-check",verifier);
        var attempt=new RedisOAuthAttempts.Attempt(provider,"LOGIN",channel,UUID.randomUUID().toString(),
                initiatorHash,uri.toString(),clock.instant().plus(ttl),null,verifier);
        return client.authorizationUrl(attempts.save(stateKind,attempt,ttl),verifier);
    }
    public URI callback(SocialProvider provider,String state,String code,String error,String initiatorProof) {
        validateCallback(code,error);
        var saved=attempts.read("state",state,ErrorCode.INVALID_REQUEST); var a=saved.attempt();
        binding(a,provider,a.channel(),initiatorProof,ErrorCode.INVALID_REQUEST);
        if (!returns.containsValue(URI.create(a.returnUri()))) throw invalid();
        return finishCallback(provider,state,code,error,saved);
    }

    boolean ownsAndroidState(String state) {
        return androidEnabled() && attempts.has(ANDROID_STATE,state);
    }

    URI callbackAndroid(SocialProvider provider, String state, String code, String error) {
        validateCallback(code,error);
        var saved=attempts.read(ANDROID_STATE,state,ErrorCode.INVALID_REQUEST);
        var a=saved.attempt();
        var target=returns.get(ANDROID_HOME);
        if (target==null || a.provider()!=provider || !"ANDROID".equals(a.channel())
                || !"LOGIN".equals(a.purpose()) || !target.toString().equals(a.returnUri())) throw invalid();
        return finishCallback(provider,state,code,error,saved);
    }

    private static void validateCallback(String code, String error) {
        if ((code==null)==(error==null) || code!=null && (code.isBlank()||code.length()>8192)
                || error!=null && error.isBlank()) throw invalid();
    }

    private URI finishCallback(SocialProvider provider, String state, String code, String error,
            RedisOAuthAttempts.Saved saved) {
        var a=saved.attempt();
        attempts.consume(saved,ErrorCode.INVALID_REQUEST);
        if (error!=null) {
            if (!"access_denied".equals(error)) throw invalid();
            return URI.create(a.returnUri()+"?error=access_denied");
        }
        // State consumption blocks duplicate callbacks. Code claim blocks redemption across different attempts.
        // The provider additionally enforces single-use codes beyond this local bounded retention.
        attempts.claimCode(provider,code,Duration.ofMinutes(10));
        String subject=client(provider).verifiedSubject(code,state,a.codeVerifier());
        var ticket=new RedisOAuthAttempts.Attempt(provider,a.purpose(),a.channel(),a.attemptId(),a.initiatorHash(),
                a.returnUri(),clock.instant().plusSeconds(60),subject,null);
        return URI.create(a.returnUri()+"?ticket="+attempts.save("ticket",ticket,Duration.ofSeconds(60)));
    }
    public EmailLoginService.Result exchange(String ticket,String action,String channel,String initiatorProof) {
        if (!"LOGIN".equals(action)) throw invalid();
        var saved=attempts.read("ticket",ticket,ErrorCode.UNAUTHENTICATED); var a=saved.attempt();
        binding(a,a.provider(),channel,initiatorProof,ErrorCode.UNAUTHENTICATED);
        var account=accounts.linked(a.provider(),a.subject());
        return accounts.withIssuanceLock(account,a.provider(),()->{
            var issued=tokens.issue(account);
            sessions.registerConsumingOAuthTicket(account,issued,channel,a.provider().name(),saved.key(),saved.value());
            return new EmailLoginService.Result(account,issued);
        });
    }
    /** Returns a session for a linked account or starts signup for a new social identity. */
    public Continuation continueLogin(String ticket,String channel,String initiatorProof,SocialSignupService signup) {
        var saved=attempts.read("ticket",ticket,ErrorCode.UNAUTHENTICATED);
        var a=saved.attempt();
        binding(a,a.provider(),channel,initiatorProof,ErrorCode.UNAUTHENTICATED);
        if (!accounts.hasIdentity(a.provider(),a.subject())) return signup.pending(saved);
        return new Authenticated(exchange(ticket,"LOGIN",channel,initiatorProof));
    }
    public sealed interface Continuation permits Authenticated,OAuthPending { }
    public record Authenticated(EmailLoginService.Result result) implements Continuation { }
    /** No Account or session exists for this pending proof; never serialize as an approved public DTO. */
    public record OAuthPending(String signupTicket, Instant signupExpiresAt, SocialProvider provider) implements Continuation {
        public boolean signupCompleted() { return false; }
        @Override public String toString() { return "OAuthPending[redacted]"; }
    }
    private void binding(RedisOAuthAttempts.Attempt a,SocialProvider provider,String channel,String proof,ErrorCode code) {
        proof(proof,code);
        if (a.provider()!=provider || !"LOGIN".equals(a.purpose()) || !a.channel().equals(channel)
                || !java.security.MessageDigest.isEqual(a.initiatorHash().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    RedisOAuthAttempts.hash(proof).getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new BusinessException(code);
    }
    private static void proof(String proof,ErrorCode code) {
        if (proof==null || !proof.matches("[A-Za-z0-9_-]{43}")) throw new BusinessException(code);
    }
    private OAuthProviderClient client(SocialProvider provider) {
        var client=clients.get(provider); if (client==null) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); return client;
    }
    /** #227 연결이 같은 제공자 client를 빌려 쓴다. 닫는 책임은 이 서비스에 남는다. */
    List<OAuthProviderClient> clients() { return List.copyOf(clients.values()); }
    @Override public void close() { clients.values().forEach(OAuthProviderClient::close); }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

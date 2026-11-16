package com.ssafy.scenecrew.account.oauth;

import java.time.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.ssafy.scenecrew.account.dto.Credentials;
import com.ssafy.scenecrew.account.service.SessionAuthenticator.Principal;
import com.ssafy.scenecrew.account.controller.AuthTransport;
import com.ssafy.scenecrew.account.filter.PublicAuthFilter;
import com.ssafy.scenecrew.global.error.*;

/**
 * #227 WEB 소셜 계정 연결(오세진 결정 2026-11-05: 비밀번호 재확인, WEB 연결만).
 * 014 재확인 → 015 연결 시작 → 제공자 → 010 callback이 연결을 끝내고 내 정보 화면으로 돌려보낸다.
 * 재확인 증명은 HttpOnly Cookie로만 전달해 callback까지 이어지게 한다. 016 해제도 같은 재확인을 쓴다. Android는 열지 않는다.
 */
@RestController
@ConditionalOnProperty(prefix="scenecrew.auth",name={"public-enabled","oauth.web-enabled"},havingValue="true")
public class OAuthLinkController {
    private static final java.util.Map<String,IdentityReauthentication.Operation> OPERATIONS=java.util.Map.of(
            "LINK_IDENTITY",IdentityReauthentication.Operation.LINK_IDENTITY,
            "UNLINK_IDENTITY",IdentityReauthentication.Operation.UNLINK_IDENTITY);
    private final RedisIdentityReauthentication reauthentication;
    private final OAuthIdentityChanges links;
    private final RedisOAuthAttempts attempts;
    private final Duration ttl;
    public OAuthLinkController(RedisIdentityReauthentication reauthentication,OAuthIdentityChanges links,
            RedisOAuthAttempts attempts,Environment env) {
        this.reauthentication=reauthentication; this.links=links; this.attempts=attempts; ttl=OAuthWebConfiguration.stateTtl(env);
    }

    @PostMapping(value="/api/v1/me/reauthenticate",consumes="application/json")
    public ResponseEntity<ReauthenticationResult> reauthenticate(@RequestBody JsonNode body,Authentication authentication) {
        var principal=web(authentication);
        if (!body.isObject() || body.size()!=3 || !text(body,"password") || !text(body,"operation") || !text(body,"provider")
                || !OPERATIONS.containsKey(body.get("operation").asString())) throw invalid();
        var issued=reauthentication.issue(principal,OPERATIONS.get(body.get("operation").asString()),
                provider(body.get("provider").asString()),body.get("password").asString());
        return ResponseEntity.ok().headers(noStore())
                .header(HttpHeaders.SET_COOKIE,OAuthWebController.reauthCookie(issued.token(),RedisIdentityReauthentication.TTL))
                .body(new ReauthenticationResult(issued.expiresAt()));
    }

    @PostMapping(value="/api/v1/me/identities/{provider}",consumes="application/json")
    public ResponseEntity<OAuthWebController.AuthorizeResult> link(@PathVariable String provider,@RequestBody JsonNode body,
            Authentication authentication,HttpServletRequest request) {
        web(authentication);
        if (!body.isObject() || !body.isEmpty()) throw invalid();
        var target=provider(provider);
        var credentials=(Credentials)request.getAttribute(PublicAuthFilter.CREDENTIALS);
        String proof=OAuthWebController.named(request,OAuthWebController.REAUTH,false);
        String browserProof=attempts.secret();
        try {
            var url=links.authorize(credentials.access(),proof,target,browserProof);
            return ResponseEntity.ok().headers(noStore()).header(HttpHeaders.SET_COOKIE,OAuthWebController.cookie(browserProof,ttl))
                    .body(new OAuthWebController.AuthorizeResult(url.toString()));
        } catch (IdentityChangeException e) {
            throw new BusinessException(e.reason()==IdentityChangeException.Reason.REAUTHENTICATION_REQUIRED
                    ? ErrorCode.REAUTHENTICATION_REQUIRED : ErrorCode.INVALID_REQUEST);
        }
    }

    /**
     * 016 연결 해제. 해제용 재확인(UNLINK_IDENTITY)이 필요하고, 남은 유효 로그인 수단이 없으면 409 LAST_IDENTITY다.
     * 해제한 수단으로 만든 세션은 이후 요청부터 거절된다(CurrentAccountReader). 성공하면 재확인 증명을 지운다.
     */
    @DeleteMapping(value="/api/v1/me/identities/{provider}",consumes="application/json")
    public ResponseEntity<Void> unlink(@PathVariable String provider,@RequestBody JsonNode body,
            Authentication authentication,HttpServletRequest request) {
        web(authentication);
        if (!body.isObject() || !body.isEmpty()) throw invalid();
        var target=provider(provider);
        var credentials=(Credentials)request.getAttribute(PublicAuthFilter.CREDENTIALS);
        String proof=OAuthWebController.named(request,OAuthWebController.REAUTH,false);
        try {
            links.unlink(credentials.access(),proof,target);
        } catch (IdentityChangeException e) {
            throw new BusinessException(switch (e.reason()) {
                case REAUTHENTICATION_REQUIRED -> ErrorCode.REAUTHENTICATION_REQUIRED;
                case LAST_IDENTITY -> ErrorCode.LAST_IDENTITY;
                case TARGET_NOT_FOUND -> ErrorCode.NOT_FOUND;
                default -> ErrorCode.INVALID_REQUEST;
            });
        }
        return ResponseEntity.noContent().headers(noStore())
                .header(HttpHeaders.SET_COOKIE,OAuthWebController.reauthCookie("",Duration.ZERO)).build();
    }

    private static Principal web(Authentication authentication) {
        var principal=(Principal)authentication.getPrincipal();
        if (!"WEB".equals(principal.channel())) throw new BusinessException(ErrorCode.INVALID_AUTH_CHANNEL);
        return principal;
    }
    private static SocialProvider provider(String value) {
        for (var provider:SocialProvider.values()) if (provider.name().equals(value)) return provider;
        throw invalid();
    }
    private static boolean text(JsonNode body,String name) { return body.has(name) && body.get(name).isString(); }
    private static HttpHeaders noStore() {
        var headers=new HttpHeaders(); headers.setCacheControl("no-store"); headers.set("Referrer-Policy","no-referrer"); return headers;
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
    public record ReauthenticationResult(Instant expiresAt) { }
}

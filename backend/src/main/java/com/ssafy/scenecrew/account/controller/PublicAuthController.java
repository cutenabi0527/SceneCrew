package com.ssafy.scenecrew.account.controller;

import java.time.Clock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import jakarta.validation.Validator;
import com.ssafy.scenecrew.account.dto.Credentials;
import com.ssafy.scenecrew.account.dto.*;
import com.ssafy.scenecrew.account.filter.PublicAuthFilter;
import com.ssafy.scenecrew.account.service.AccountSummaryReader;
import com.ssafy.scenecrew.account.service.PublicLoginService;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import org.springframework.security.core.Authentication;
import com.ssafy.scenecrew.global.error.*;

@RestController
@ConditionalOnProperty(name="scenecrew.auth.public-enabled", havingValue="true")
public class PublicAuthController {
    private final PublicLoginService login;
    private final AccountSummaryReader accounts;
    private final Validator validator;
    private final Clock clock;
    private final com.ssafy.scenecrew.account.service.LogoutService logout;
    private final com.ssafy.scenecrew.account.service.RefreshService refresh;
    public PublicAuthController(PublicLoginService login, AccountSummaryReader accounts, Validator validator, Clock clock,
            com.ssafy.scenecrew.account.service.LogoutService logout,
            com.ssafy.scenecrew.account.service.RefreshService refresh) {
        this.login=login; this.accounts=accounts; this.validator=validator; this.clock=clock; this.logout=logout;
        this.refresh=refresh;
    }
    /** SC-API-005. 증명 형식은 Logout과 같다: WEB은 Refresh Cookie와 빈 본문, Android는 본문 refreshToken. */
    @PostMapping(value="/api/v1/auth/refresh", consumes="application/json")
    public ResponseEntity<RefreshResult> refresh(@RequestBody JsonNode body, HttpServletRequest request) {
        var credentials = (Credentials) request.getAttribute(PublicAuthFilter.CREDENTIALS);
        boolean web = "WEB".equals(credentials.channel());
        if (!body.isObject() || (web ? body.size()!=0 : body.size()!=1 || !body.has("refreshToken")
                || !body.get("refreshToken").isString())) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        String proof = web ? credentials.refresh() : body.get("refreshToken").asString();
        String attempt = AuthTransport.single(request, "X-SceneCrew-Refresh-Attempt", ErrorCode.INVALID_REQUEST);
        var tokens = refresh.rotate(proof, credentials.channel(), attempt);
        return LoginResponses.refresh(tokens, credentials.channel(), clock);
    }
    @PostMapping(value="/api/v1/auth/logout", consumes="application/json")
    public ResponseEntity<Void> logout(@RequestBody JsonNode body, HttpServletRequest request) {
        var credentials = (Credentials) request.getAttribute(PublicAuthFilter.CREDENTIALS);
        boolean web = "WEB".equals(credentials.channel());
        if (!body.isObject() || (web ? body.size()!=0 : body.size()!=1 || !body.has("refreshToken")
                || !body.get("refreshToken").isString())) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        String refresh = web ? credentials.refresh() : body.get("refreshToken").asString();
        String attempt = AuthTransport.single(request, "X-SceneCrew-Logout-Attempt", ErrorCode.INVALID_REQUEST);
        logout.logout(refresh, credentials.channel(), attempt);
        var response = ResponseEntity.noContent().header("Cache-Control", "no-store");
        if (web) response.header("Set-Cookie", expired(AuthTransport.ACCESS, "/api/v1"),
                expired(AuthTransport.REFRESH, "/api/v1/auth"));
        return response.build();
    }
    private static String expired(String name, String path) {
        return ResponseCookie.from(name, "").path(path).secure(true).httpOnly(true).sameSite("Lax")
                .maxAge(0).build().toString();
    }
    @PostMapping(value="/api/v1/auth/login", consumes="application/json")
    public ResponseEntity<?> login(@RequestBody JsonNode body, HttpServletRequest request) {
        if (!body.isObject() || body.size()!=2 || !body.has("email") || !body.has("password")
                || !body.get("email").isString() || !body.get("password").isString())
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var input = new LoginRequest(body.get("email").asString(), body.get("password").asString());
        if (!validator.validate(input).isEmpty()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var credentials = (Credentials) request.getAttribute(PublicAuthFilter.CREDENTIALS);
        var result = login.login(input.email(), input.password(), credentials);
        return "WEB".equals(credentials.channel()) ? LoginResponses.web(result, clock) : LoginResponses.android(result);
    }
    @GetMapping("/api/v1/me")
    public ResponseEntity<AccountSummary> me(Authentication authentication) {
        var principal = (SessionAuthenticator.Principal) authentication.getPrincipal();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(accounts.read(principal));
    }
}

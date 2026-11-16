package com.ssafy.scenecrew.account.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.scenecrew.account.service.AccountDeactivationService;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

import tools.jackson.databind.JsonNode;

/** SC-API-018 탈퇴(#334, V9-D39). 본문은 {"password"} 하나다. 성공하면 WEB 인증 Cookie도 지운다. */
@RestController
@ConditionalOnProperty(name="scenecrew.auth.public-enabled", havingValue="true")
public class AccountDeactivationController {
    private final AccountDeactivationService deactivation;

    public AccountDeactivationController(AccountDeactivationService deactivation) { this.deactivation = deactivation; }

    @PostMapping(value="/api/v1/me/deactivate", consumes="application/json")
    public ResponseEntity<AccountDeactivationService.Result> deactivate(@RequestBody JsonNode body,
            Authentication authentication) {
        if (!body.isObject() || body.size() != 1 || !body.has("password") || !body.get("password").isString())
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var principal = (SessionAuthenticator.Principal) authentication.getPrincipal();
        var result = deactivation.deactivate(principal, body.get("password").asString());
        var response = ResponseEntity.ok().header("Cache-Control", "no-store");
        if ("WEB".equals(principal.channel())) response.header("Set-Cookie", expired(AuthTransport.ACCESS, "/api/v1"),
                expired(AuthTransport.REFRESH, "/api/v1/auth"));
        return response.body(result);
    }

    /** 탈퇴 처리 상태(V9-D40). 로그인 없이 접수 번호로 본다. 없는 번호는 404. */
    @GetMapping("/api/v1/deletion-requests/{receiptNo}")
    public ResponseEntity<AccountDeactivationService.DeletionStatus> status(@PathVariable String receiptNo) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(deactivation.status(receiptNo));
    }

    private static String expired(String name, String path) {
        return ResponseCookie.from(name, "").path(path).secure(true).httpOnly(true).sameSite("Lax")
                .maxAge(0).build().toString();
    }
}

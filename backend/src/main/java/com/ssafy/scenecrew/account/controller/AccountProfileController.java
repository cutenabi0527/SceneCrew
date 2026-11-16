package com.ssafy.scenecrew.account.controller;

import com.ssafy.scenecrew.account.dto.AccountSummary;
import com.ssafy.scenecrew.account.service.AccountProfileService;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(name="scenecrew.auth.public-enabled", havingValue="true")
public class AccountProfileController {
    private final AccountProfileService profiles;

    public AccountProfileController(AccountProfileService profiles) { this.profiles = profiles; }

    @PatchMapping(value="/api/v1/me", consumes="application/json")
    public ResponseEntity<AccountSummary> update(@RequestBody JsonNode body, Authentication authentication) {
        if (!body.isObject() || body.size() != 1 || !body.has("displayName")
                || !body.get("displayName").isString()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var principal = (SessionAuthenticator.Principal) authentication.getPrincipal();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(profiles.update(principal, body.get("displayName").asString()));
    }
}

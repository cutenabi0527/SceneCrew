package com.ssafy.scenecrew.account.controller;

import java.util.LinkedHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.scenecrew.account.service.AccountConsentService;
import com.ssafy.scenecrew.account.service.ConsentRecords;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

import tools.jackson.databind.JsonNode;

/** 내 동의 조회(버전·이력 포함)와 변경(SC-API-017). #229·#334, V9-D40. */
@RestController
@ConditionalOnProperty(name="scenecrew.auth.public-enabled", havingValue="true")
public class AccountConsentController {
    private final AccountConsentService consents;

    public AccountConsentController(AccountConsentService consents) { this.consents = consents; }

    @GetMapping("/api/v1/me/consents")
    public ResponseEntity<AccountConsentService.View> read(Authentication authentication) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(consents.read((SessionAuthenticator.Principal) authentication.getPrincipal()));
    }

    @PatchMapping(value="/api/v1/me/consents", consumes="application/json")
    public ResponseEntity<AccountConsentService.Summary> change(@RequestBody JsonNode body, Authentication authentication) {
        if (!body.isObject() || body.isEmpty()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var requested = new LinkedHashMap<String, Boolean>();
        for (var entry : body.properties()) {
            if (!ConsentRecords.KEYS.contains(entry.getKey()) || !entry.getValue().isBoolean())
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            requested.put(entry.getKey(), entry.getValue().asBoolean());
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(consents.change((SessionAuthenticator.Principal) authentication.getPrincipal(), requested));
    }
}

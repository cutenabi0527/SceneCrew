package com.ssafy.scenecrew.event.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.event.dto.MemberAvailabilityRequest;
import com.ssafy.scenecrew.event.service.MemberAvailabilityService;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

@RestController
public class MemberAvailabilityController {
    private final MemberAvailabilityService service;
    public MemberAvailabilityController(MemberAvailabilityService service) { this.service = service; }

    @PatchMapping(value = "/api/v1/members/{m}/availability", consumes = "application/json")
    public ResponseEntity<MemberAvailabilityService.Result> change(@PathVariable("m") String memberId,
            @RequestBody JsonNode body, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        var id = MemberAvailabilityRequest.memberId(memberId);
        var request = MemberAvailabilityRequest.parse(body);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.change(id, principal, request.available(), request.expectedEventRevision()));
    }
}

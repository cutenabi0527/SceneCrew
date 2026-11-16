package com.ssafy.scenecrew.invite.controller;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.invite.dto.JoinRequest;
import com.ssafy.scenecrew.invite.dto.JoinResult;
import com.ssafy.scenecrew.invite.service.InviteJoinService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** 인증된 참여 요청의 최초 응답과 재전달에 같은 HTTP 상태·본문을 사용한다. */
@RestController
public class InviteJoinController {
    private final InviteJoinService service;
    public InviteJoinController(InviteJoinService service) { this.service = service; }
    @PostMapping(value="/api/v1/invites/{token}/join", consumes="application/json")
    public ResponseEntity<JoinResult> join(@PathVariable String token,
            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        JoinRequest.uuid(key);
        var result = service.join(principal, token, key, JoinRequest.parse(body));
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }
}

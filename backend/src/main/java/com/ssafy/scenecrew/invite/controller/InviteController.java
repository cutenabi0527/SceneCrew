package com.ssafy.scenecrew.invite.controller;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.invite.service.InviteService;

@RestController
@ConditionalOnProperty(name="scenecrew.auth.public-enabled", havingValue="true")
public class InviteController {
    private final InviteService service;
    public InviteController(InviteService service) { this.service = service; }
    @GetMapping("/api/v1/events/{e}/invite")
    public ResponseEntity<InviteService.InviteView> read(@PathVariable String e, Authentication auth, HttpServletRequest request) {
        noQuery(request); return response(service.read(id(e), account(auth)));
    }
    @PostMapping(value="/api/v1/events/{e}/invite/rotate", consumes="application/json")
    public ResponseEntity<InviteService.InviteView> rotate(@PathVariable String e, @RequestBody JsonNode body,
            Authentication auth, HttpServletRequest request) {
        noQuery(request);
        if (body == null || !body.isObject() || body.size()!=1 || !body.has("expectedEventRevision")) throw invalid();
        var revision=body.get("expectedEventRevision");
        if (!revision.isString() || !revision.asString().matches("0|[1-9][0-9]{0,18}")) throw invalid();
        long expected;
        try { expected=Long.parseLong(revision.asString()); } catch (NumberFormatException failure) { throw invalid(); }
        return response(service.rotate(id(e),account(auth),expected));
    }
    @GetMapping("/api/v1/invites/{token}/options")
    public ResponseEntity<InviteService.Options> options(@PathVariable String token, Authentication auth, HttpServletRequest request) {
        noQuery(request); return response(service.options(token,account(auth)));
    }
    private UUID account(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof SessionAuthenticator.Principal p))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return p.accountId();
    }
    private UUID id(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid();
        return UUID.fromString(value);
    }
    private void noQuery(HttpServletRequest r) { if (!r.getParameterMap().isEmpty()) throw invalid(); }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
    private <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").body(value);
    }
}

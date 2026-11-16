package com.ssafy.scenecrew.plan.controller;

import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.text.UnicodeText;
import com.ssafy.scenecrew.plan.service.InitialAssignmentService;

@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class InitialAssignmentController {
    private final InitialAssignmentService assignments;

    public InitialAssignmentController(InitialAssignmentService assignments) { this.assignments = assignments; }

    @PostMapping(value = "/api/v1/events/{e}/initial-assignments", consumes = "application/json")
    public ResponseEntity<InitialAssignmentService.Applied> assign(@PathVariable("e") String eventId,
            @RequestBody JsonNode body, HttpServletRequest request, Authentication auth) {
        if (!request.getParameterMap().isEmpty() || !body.isObject()
                || !body.propertyNames().equals(Set.of("bundleKey", "memberId", "expectedEventRevision"))) throw invalid();
        var bundle = body.get("bundleKey");
        var revision = body.get("expectedEventRevision");
        if (!bundle.isString() || UnicodeText.isBlank(bundle.asString()) || !revision.isString()
                || !revision.asString().matches("0|[1-9][0-9]{0,18}")) throw invalid();
        long expected;
        try { expected = Long.parseLong(revision.asString()); }
        catch (NumberFormatException ex) { throw invalid(); }
        if (auth == null || !(auth.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        JsonNode member = body.get("memberId");
        if (!member.isString()) throw invalid();
        var result = assignments.assign(uuid(eventId), principal.accountId(), key(request),
                new InitialAssignmentService.Input(bundle.asString(), uuid(member.asString()), expected));
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    private static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw invalid();
        return UUID.fromString(value);
    }

    private static String key(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw invalid();
        return values.getFirst();
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

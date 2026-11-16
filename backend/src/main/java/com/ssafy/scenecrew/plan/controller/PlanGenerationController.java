package com.ssafy.scenecrew.plan.controller;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.plan.ai.generate.PlanGenerationService;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.issue.dto.IssueResponses.JobAccepted;

/** SC-API-043 POST /api/v1/events/{e}/plan-generations. 인증·허용 Origin·JSON은 PublicAuthFilter(businessWrite)가 끝낸다. */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class PlanGenerationController {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String REVISION = "0|[1-9][0-9]{0,18}";
    private final PlanGenerationService generations;

    public PlanGenerationController(PlanGenerationService generations) { this.generations = generations; }

    /** 본문은 계약 Generation {expectedEventRevision}. 요청 키 필수 */
    @PostMapping(value = "/api/v1/events/{e}/plan-generations", consumes = "application/json")
    public ResponseEntity<JobAccepted> accept(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        if (!request.getParameterMap().isEmpty() || !e.matches(UUID_FORMAT)) throw invalid();
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("expectedEventRevision")) throw invalid();
        JsonNode revision = body.get("expectedEventRevision");
        if (!revision.isString() || !revision.asString().matches(REVISION)) throw invalid();
        long expected;
        try {
            expected = Long.parseLong(revision.asString());
        } catch (NumberFormatException ex) {
            throw invalid();
        }
        List<String> keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key가 필요합니다.");
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        var result = generations.accept(UUID.fromString(e), principal.accountId(), keys.getFirst(), expected);
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

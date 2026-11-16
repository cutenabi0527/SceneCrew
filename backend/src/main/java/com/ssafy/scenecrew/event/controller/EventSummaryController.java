package com.ssafy.scenecrew.event.controller;

import java.util.Collections;
import java.util.List;
import java.util.Set;
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
import com.ssafy.scenecrew.event.summary.EventSummaryService;
import com.ssafy.scenecrew.event.summary.EventSummaryService.Summary;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.web.StrictJson;

/**
 * SC-API-080 POST /api/v1/events/{e}/summary. 본문은 계약 ExpectedEventCommand {expectedEventRevision}, 요청 키 필수.
 * 인증·허용 Origin·JSON은 PublicAuthFilter(businessWrite)가 끝낸다. 응답 본문(요약문)은 no-store로 보낸다.
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class EventSummaryController {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Set<String> BODY = Set.of("expectedEventRevision");
    private final EventSummaryService summaries;

    public EventSummaryController(EventSummaryService summaries) { this.summaries = summaries; }

    @PostMapping(value = "/api/v1/events/{e}/summary", consumes = "application/json")
    public ResponseEntity<Summary> summarize(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        if (!request.getParameterMap().isEmpty() || !e.matches(UUID_FORMAT)) throw invalid();
        StrictJson.object(body, "", BODY, BODY);
        long expected = StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision");
        List<String> keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1 || !keys.getFirst().matches(UUID_FORMAT))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key가 필요합니다.");
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        Summary summary = summaries.summarize(UUID.fromString(e), principal.accountId(), keys.getFirst(), expected);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(summary);
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

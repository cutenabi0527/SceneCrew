package com.ssafy.scenecrew.change.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
import com.ssafy.scenecrew.change.ai.ChangeGenerationService;
import com.ssafy.scenecrew.change.ai.ChangeGenerationService.ChangeGeneration;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.issue.dto.IssueResponses.JobAccepted;

/**
 * SC-API-064 POST /api/v1/events/{e}/change-generations. 인증·허용 Origin·JSON은 PublicAuthFilter(businessWrite)가 끝낸다.
 * 본문은 계약 V9ChangeGeneration {issueId, instruction, reportIds?, expectedEventRevision, safetyObservationId?, evidenceRefs?}.
 * issueId는 이번 범위에서 필수다. safetyObservationId·evidenceRefs는 형식만 받고 아직 쓰지 않는다(장비·관측 근거는 후속).
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class ChangeGenerationController {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String REVISION = "0|[1-9][0-9]{0,18}";
    private static final Set<String> KEYS = Set.of("issueId", "instruction", "reportIds", "expectedEventRevision",
            "safetyObservationId", "evidenceRefs");
    static final int MAX_INSTRUCTION = 1000;
    static final int MAX_REPORTS = 20;
    private final ChangeGenerationService generations;

    public ChangeGenerationController(ChangeGenerationService generations) { this.generations = generations; }

    @PostMapping(value = "/api/v1/events/{e}/change-generations", consumes = "application/json")
    public ResponseEntity<JobAccepted> accept(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        if (!request.getParameterMap().isEmpty() || !e.matches(UUID_FORMAT)) throw invalid();
        if (body == null || !body.isObject()) throw invalid();
        for (String key : body.propertyNames()) if (!KEYS.contains(key)) throw invalid();
        UUID issueId = uuid(body.get("issueId"));
        JsonNode instruction = body.get("instruction");
        if (instruction == null || !instruction.isString()
                || instruction.asString().codePointCount(0, instruction.asString().length()) > MAX_INSTRUCTION) throw invalid();
        List<UUID> reportIds = new ArrayList<>();
        JsonNode reports = body.get("reportIds");
        if (reports != null) {
            if (!reports.isArray() || reports.size() > MAX_REPORTS) throw invalid();
            Set<UUID> seen = new HashSet<>();
            for (JsonNode r : reports) {
                UUID id = uuid(r);
                if (!seen.add(id)) throw invalid();
                reportIds.add(id);
            }
        }
        JsonNode observation = body.get("safetyObservationId");
        UUID observationId = observation == null ? null : uuid(observation);
        JsonNode evidence = body.get("evidenceRefs");
        if (evidence != null && !evidence.isArray()) throw invalid();
        JsonNode revision = body.get("expectedEventRevision");
        if (revision == null || !revision.isString() || !revision.asString().matches(REVISION)) throw invalid();
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
        var result = generations.accept(UUID.fromString(e), principal.accountId(), keys.getFirst(),
                new ChangeGeneration(issueId, instruction.asString(), reportIds, expected, observationId,
                        evidence == null ? null : evidence.toString()));
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    /** 소문자로 맞춘 UUID. 문자열이 아니거나 형식이 다르면 400 */
    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isString() || !node.asString().matches(UUID_FORMAT)) throw invalid();
        return UUID.fromString(node.asString().toLowerCase());
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

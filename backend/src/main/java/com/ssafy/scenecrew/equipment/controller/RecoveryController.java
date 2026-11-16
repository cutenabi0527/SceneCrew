package com.ssafy.scenecrew.equipment.controller;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.change.service.Operations;
import com.ssafy.scenecrew.equipment.service.RecoveryService;
import com.ssafy.scenecrew.equipment.service.RecoveryViews;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.global.web.StrictJson;
import com.ssafy.scenecrew.issue.dto.IssueResponses.JobAccepted;

/**
 * 복구 대안 계산 EXT06(POST /events/{e}/recovery-simulations), 결과 EXT07(GET /recovery-simulations/{s}),
 * 최근 결과 Q1(GET /issues/{i}/recovery-simulations/latest). 형식만 여기서 보고 권한·상태는 서비스가 본다.
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class RecoveryController {
    static final int MAX_TARGETS = 20;
    static final int MAX_ASSUMPTIONS = 20;
    private final RecoveryService recovery;

    public RecoveryController(RecoveryService recovery) {
        this.recovery = recovery;
    }

    /** EXT06. 요청 키 필수, 202 JobAccepted */
    @PostMapping(value = "/api/v1/events/{e}/recovery-simulations", consumes = "application/json")
    public ResponseEntity<JobAccepted> simulate(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID eventId = StrictJson.pathId(e, "eventId");
        String key = StrictJson.idempotencyKey(request);
        StrictJson.object(body, "", Set.of("expectedEventRevision", "issueId", "targetAssetIds", "evidenceRefs", "assumptions"),
                Set.of("expectedEventRevision", "targetAssetIds", "evidenceRefs", "assumptions"));
        long revision = StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision");
        UUID issueId = body.has("issueId") ? StrictJson.nullableUuid(body.get("issueId"), "issueId") : null;
        List<UUID> targets = StrictJson.uuids(body.get("targetAssetIds"), "targetAssetIds", MAX_TARGETS);
        if (targets.isEmpty()) throw StrictJson.invalid("targetAssetIds", "EMPTY");
        var evidence = Operations.evidenceRefs(body.get("evidenceRefs"));
        List<RecoveryViews.Assumption> assumptions = StrictJson.list(body.get("assumptions"), "assumptions", MAX_ASSUMPTIONS,
                (node, path) -> {
                    StrictJson.object(node, path, Set.of("key", "description", "confirmed"),
                            Set.of("key", "description", "confirmed"));
                    String k = StrictJson.text(node.get("key"), StrictJson.join(path, "key"), 100);
                    String d = StrictJson.text(node.get("description"), StrictJson.join(path, "description"), 500);
                    // 가정은 확인되지 않은 전제다(계약 confirmed const false).
                    if (StrictJson.bool(node.get("confirmed"), StrictJson.join(path, "confirmed")))
                        throw StrictJson.invalid(StrictJson.join(path, "confirmed"), "MUST_BE_FALSE");
                    return new RecoveryViews.Assumption(k, d, false);
                });
        var result = recovery.simulate(eventId, StrictJson.accountId(authentication), key,
                new RecoveryService.Request(revision, issueId, targets, evidence, assumptions));
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    /** EXT07 */
    @GetMapping("/api/v1/recovery-simulations/{s}")
    public ResponseEntity<RecoveryViews.Simulation> get(@PathVariable("s") String s, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(recovery.get(StrictJson.pathId(s, "simulationId"), StrictJson.accountId(authentication)));
    }

    /** Q1 */
    @GetMapping("/api/v1/issues/{i}/recovery-simulations/latest")
    public ResponseEntity<RecoveryViews.Pointer> latest(@PathVariable("i") String i, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(recovery.latest(StrictJson.pathId(i, "issueId"), StrictJson.accountId(authentication)));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
    static class Beans {
        @Bean RecoveryService recoveryService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency,
                EventWriteSupport events) {
            return new RecoveryService(jdbc, members, idempotency, events);
        }
    }
}

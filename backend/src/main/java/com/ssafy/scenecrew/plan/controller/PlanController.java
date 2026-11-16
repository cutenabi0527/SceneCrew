package com.ssafy.scenecrew.plan.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.plan.dto.DraftPlanRequest;
import com.ssafy.scenecrew.plan.service.DraftPlanService.DraftPlanSaved;
import com.ssafy.scenecrew.plan.service.DraftPlanService;
import com.ssafy.scenecrew.plan.service.PlanReadService.PlanView;
import com.ssafy.scenecrew.plan.service.PlanReadService;

/**
 * SC-API-027 계획 조회·028 초안 저장. 인증과 쓰기 검사(WEB 허용 Origin·JSON)는 PublicAuthFilter가 끝낸 뒤 들어온다.
 * 여기서는 경로·쿼리·본문 형식만 검사하고(400), 권한·상태·revision·참조는 서비스가 트랜잭션 안에서 검사한다.
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class PlanController {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final PlanReadService plans;
    private final DraftPlanService drafts;

    public PlanController(PlanReadService plans, DraftPlanService drafts) {
        this.plans = plans;
        this.drafts = drafts;
    }

    /** 027 */
    @GetMapping("/api/v1/events/{e}/plan")
    public ResponseEntity<PlanView> plan(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        if (!request.getParameterMap().isEmpty()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(plans.read(eventId(e), accountId(authentication)));
    }

    /** 028 */
    @PutMapping(value = "/api/v1/events/{e}/draft-plan", consumes = "application/json")
    public ResponseEntity<DraftPlanSaved> save(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        if (!request.getParameterMap().isEmpty()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        UUID eventId = eventId(e);
        var command = DraftPlanRequest.parse(body);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(drafts.save(eventId, accountId(authentication), command));
    }

    private static UUID eventId(String value) {
        if (!value.matches(UUID_FORMAT)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return UUID.fromString(value);
    }

    private static UUID accountId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return principal.accountId();
    }
}

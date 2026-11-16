package com.ssafy.scenecrew.aijob.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.aijob.read.AiJobRead;
import com.ssafy.scenecrew.aijob.read.AiJobReadService;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/** SC-API-063 GET /api/v1/ai-jobs/{j}. 인증은 PublicAuthFilter(eventRead)가 현재 세션으로 끝낸 뒤 들어온다. */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class AiJobController {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private final AiJobReadService jobs;

    public AiJobController(AiJobReadService jobs) { this.jobs = jobs; }

    @GetMapping("/api/v1/ai-jobs/{j}")
    public ResponseEntity<AiJobRead> get(@PathVariable("j") String j, HttpServletRequest request,
            Authentication authentication) {
        // 정의된 쿼리가 없다. 형식이 틀린 ID는 존재 여부와 무관한 입력 오류라 400이다.
        if (!request.getParameterMap().isEmpty() || !j.matches(UUID_FORMAT))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(jobs.get(UUID.fromString(j), principal.accountId()));
    }
}

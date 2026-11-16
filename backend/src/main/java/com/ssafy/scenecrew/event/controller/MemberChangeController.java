package com.ssafy.scenecrew.event.controller;

import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.event.service.EventLeaveService;
import com.ssafy.scenecrew.event.service.MemberUpdateService;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.web.StrictJson;

/**
 * SC-API-038 팀원 그룹 이동·예비 지정(PATCH /members/{m})과 SC-API-040 행사 이탈(POST /events/{e}/leave).
 * 038은 JSON 업무 쓰기 검사(businessWrite), 040은 본문 없는 쓰기라 허용 Origin만 본다(PublicAuthFilter).
 * 여기서는 형식만 검사하고 권한·상태·revision은 서비스가 트랜잭션 안에서 본다.
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class MemberChangeController {
    private final MemberUpdateService updates;
    private final EventLeaveService leaves;

    public MemberChangeController(MemberUpdateService updates, EventLeaveService leaves) {
        this.updates = updates;
        this.leaves = leaves;
    }

    /** 038. workGroupId·isReserve 중 하나 이상과 expectedEventRevision. 요청 키는 받지 않는다(계약 NOT_REQUIRED). */
    @PatchMapping(value = "/api/v1/members/{m}", consumes = "application/json")
    public ResponseEntity<MemberUpdateService.Result> update(@PathVariable("m") String m, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID memberId = StrictJson.pathId(m, "memberId");
        StrictJson.object(body, "", Set.of("workGroupId", "isReserve", "expectedEventRevision"), Set.of("expectedEventRevision"));
        if (body.size() < 2) throw StrictJson.invalid("body", "NO_CHANGE");
        UUID group = body.has("workGroupId") ? StrictJson.uuid(body.get("workGroupId"), "workGroupId") : null;
        Boolean reserve = body.has("isReserve") ? StrictJson.bool(body.get("isReserve"), "isReserve") : null;
        long revision = StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision");
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(updates.update(memberId, StrictJson.accountId(authentication), group, reserve, revision));
    }

    /** 040. 본문이 없다. 요청 키 필수 */
    @PostMapping("/api/v1/events/{e}/leave")
    public ResponseEntity<EventLeaveService.Receipt> leave(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID eventId = StrictJson.pathId(e, "eventId");
        String key = StrictJson.idempotencyKey(request);
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        var result = leaves.leave(eventId, principal, key);
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }
}

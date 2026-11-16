package com.ssafy.scenecrew.plan.controller;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.web.StrictJson;
import com.ssafy.scenecrew.plan.dto.UnconfirmedDraftRequest;
import com.ssafy.scenecrew.plan.service.UnconfirmedDraftService.DraftView;
import com.ssafy.scenecrew.plan.service.UnconfirmedDraftService.Receipt;
import com.ssafy.scenecrew.plan.service.UnconfirmedDraftService.SaveCommand;
import com.ssafy.scenecrew.plan.service.UnconfirmedDraftService;

/**
 * Q3-A 미확정 장비·장소 초안 조회·저장(#355). 계약: v9-327 final327Q3-read·final327Q3-save.
 * 본문 크기·JSON 문법과 계약 스키마 전체(UnconfirmedDraftRequest)를 여기서 보고, 상태·revision·참조는 서비스가 본다.
 */
@RestController
public class UnconfirmedDraftController {
    private static final String UUID_FORMAT =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    /** 초안 본문 상한(받은 원문 바이트). 계약에는 없는 서버 규칙이며 장비·연결·예약 수백 개를 담고도 남는 크기다. */
    static final int MAX_BYTES = 512 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final UnconfirmedDraftService drafts;

    public UnconfirmedDraftController(UnconfirmedDraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping("/api/v1/events/{e}/unconfirmed-draft")
    public ResponseEntity<DraftView> read(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        if (!request.getParameterMap().isEmpty()) throw invalid();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(drafts.read(uuid(e), account(authentication)));
    }

    @PutMapping(value = "/api/v1/events/{e}/unconfirmed-draft", consumes = "application/json")
    public ResponseEntity<Receipt> save(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) throws IOException {
        if (!request.getParameterMap().isEmpty()) throw invalid();
        UUID eventId = uuid(e);
        SaveCommand command = UnconfirmedDraftRequest.parse(body(request));
        var result = drafts.save(eventId, account(authentication), key(request), command);
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    /**
     * 받은 원문 바이트로 상한을 본다. Content-Length가 상한을 넘으면 본문을 읽지 않고 거절하고, 길이를 모르면(chunked)
     * 상한+1바이트까지만 읽는다. 같은 키 중복·JSON 뒤 군더더기도 400이다.
     */
    private static JsonNode body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BYTES) throw StrictJson.invalid("body", "TOO_LARGE");
        byte[] raw = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (raw.length > MAX_BYTES) throw StrictJson.invalid("body", "TOO_LARGE");
        try {
            return JSON.readTree(raw);
        } catch (JacksonException ex) {
            throw StrictJson.invalid("body", "JSON");
        }
    }

    private static String key(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key가 필요합니다.");
        return values.getFirst();
    }

    private static UUID uuid(String value) {
        if (value == null || !value.matches(UUID_FORMAT)) throw invalid();
        return UUID.fromString(value);
    }

    private static UUID account(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return principal.accountId();
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}

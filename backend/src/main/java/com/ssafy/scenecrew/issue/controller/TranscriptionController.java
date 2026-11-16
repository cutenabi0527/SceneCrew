package com.ssafy.scenecrew.issue.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.issue.service.AudioAssetService;
import com.ssafy.scenecrew.issue.ai.transcription.TranscriptionService;
import com.ssafy.scenecrew.issue.ai.transcription.TranscriptionService.TranscriptionAccepted;

/**
 * 060 음성 전사 접수. multipart/form-data의 audio(필수, audio/mp4·audio/webm 한 개)와 pipeline(선택, RAW만)을 받는다.
 * 인증·WEB 허용 Origin·multipart 검사는 PublicAuthFilter(businessUpload)가 먼저 한다. backend/docs/stt-transcription.md
 */
@RestController
public class TranscriptionController {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Set<String> AUDIO_TYPES = Set.of("audio/mp4", "audio/webm");

    private final TranscriptionService transcriptions;

    public TranscriptionController(TranscriptionService transcriptions) {
        this.transcriptions = transcriptions;
    }

    @PostMapping(value = "/api/v1/events/{e}/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TranscriptionAccepted> submit(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        UUID eventId = uuid(e);
        String key = key(request);
        Collection<Part> parts = parts(request);
        Part audio = null;
        String pipeline = null;
        int pipelines = 0;
        for (Part part : parts) {
            switch (part.getName()) {
                case "audio" -> {
                    if (audio != null) throw invalid();
                    audio = part;
                }
                case "pipeline" -> {
                    pipelines++;
                    pipeline = text(part);
                }
                default -> throw invalid();
            }
        }
        // 쿼리 문자열이나 같은 이름의 값이 더 있으면 거절한다(additionalProperties: false).
        for (var entry : request.getParameterMap().entrySet()) {
            if (!"pipeline".equals(entry.getKey()) || entry.getValue().length != 1 || pipelines != 1) throw invalid();
        }
        if (audio == null || pipelines > 1 || (pipeline != null && !"RAW".equals(pipeline))) throw invalid();
        if (audio.getSize() > AudioAssetService.MAX_BYTES) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        if (!audioType(audio.getContentType())) throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);

        var result = transcriptions.accept(eventId, account(authentication), key, bytes(audio), pipeline != null);
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    private static boolean audioType(String contentType) {
        if (contentType == null) return false;
        try {
            MediaType media = MediaType.parseMediaType(contentType);
            return AUDIO_TYPES.contains((media.getType() + "/" + media.getSubtype()).toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Collection<Part> parts(HttpServletRequest request) {
        try {
            return request.getParts();
        } catch (IOException | ServletException | IllegalStateException e) {
            throw invalid();
        }
    }

    private static String text(Part part) {
        if (part.getSubmittedFileName() != null || part.getSize() > 16) throw invalid();
        try (InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw invalid();
        }
    }

    private static byte[] bytes(Part part) {
        try (InputStream in = part.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw invalid();
        }
    }

    /** Idempotency-Key는 한 번만, UUID 형식(형식 검사는 IdempotencyRequest) */
    private static String key(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key가 필요합니다.");
        return values.getFirst();
    }

    private static UUID uuid(String value) {
        if (!value.matches(UUID_FORMAT)) throw invalid();
        return UUID.fromString(value);
    }

    static UUID account(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return principal.accountId();
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}

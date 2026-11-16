package com.ssafy.scenecrew.issue.controller;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.issue.service.AudioAssetService;

/**
 * 061 음성 원본·임시 전사 정리. 업로더 본인만 지울 수 있고, 아니면 404다. Report.text는 그대로 둔다.
 * 인증·WEB 허용 Origin 검사는 PublicAuthFilter(businessDelete)가 먼저 한다.
 */
@RestController
public class AudioAssetController {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final AudioAssetService audioAssets;

    public AudioAssetController(AudioAssetService audioAssets) {
        this.audioAssets = audioAssets;
    }

    @DeleteMapping("/api/v1/audio-assets/{a}")
    public ResponseEntity<Void> delete(@PathVariable("a") String a, HttpServletRequest request,
            Authentication authentication) {
        if (!request.getParameterMap().isEmpty() || !a.matches(UUID_FORMAT)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        audioAssets.deleteOwn(UUID.fromString(a), TranscriptionController.account(authentication));
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}

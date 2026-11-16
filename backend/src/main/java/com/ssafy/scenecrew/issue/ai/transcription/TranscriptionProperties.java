package com.ssafy.scenecrew.issue.ai.transcription;

import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 음성 전사 설정(scenecrew.ai.transcription).
 *
 * @param audioBaseUrl AI 서버가 음성을 내려받을 업무 서버 주소. 예: https://service.p.example.test
 *                     audioUrl은 이 값 + /internal/audio-assets/{audioId}다. AI 서버는 다른 EC2라 backend:8080으로는 닿지 않고
 *                     업무 서버 nginx(/internal/, AI 서버 IP만 허용)를 거친다. 비어 있으면 전사를 접수하지 않는다.
 */
@ConfigurationProperties("scenecrew.ai.transcription")
public record TranscriptionProperties(@DefaultValue("") String audioBaseUrl) {

    public static final String AUDIO_PATH = "/internal/audio-assets/";

    /** 음성 하나의 다운로드 주소 */
    public String audioUrl(UUID audioId) {
        return audioBaseUrl.replaceAll("/+$", "") + AUDIO_PATH + audioId;
    }

    public boolean configured() {
        return !audioBaseUrl.isBlank();
    }
}

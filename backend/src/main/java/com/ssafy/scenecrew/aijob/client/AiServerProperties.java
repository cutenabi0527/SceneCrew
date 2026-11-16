package com.ssafy.scenecrew.aijob.client;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 서버 연결(scenecrew.ai.server).
 *
 * @param baseUrl        AI 서버 주소. 예: http://127.0.0.1:8000 (nginx가 /internal/*을 AI 컨테이너로 넘긴다)
 * @param serviceToken   BE↔AI 서비스 토큰. Authorization: Bearer로 보낸다. 서버 .env에만 둔다.
 * @param connectTimeout 연결까지 기다리는 시간. 응답 대기 시간은 작업의 남은 마감으로 정한다.
 */
@ConfigurationProperties("scenecrew.ai.server")
public record AiServerProperties(
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String serviceToken,
        @DefaultValue("5s") Duration connectTimeout) {
}

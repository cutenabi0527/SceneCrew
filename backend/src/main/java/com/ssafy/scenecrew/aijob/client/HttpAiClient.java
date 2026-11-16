package com.ssafy.scenecrew.aijob.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.stereotype.Component;

/** JDK HttpClient로 AI 서버를 호출한다. 요청마다 응답 대기 시간을 따로 정한다. */
@Component
public class HttpAiClient implements AiClient {
    private final AiServerProperties properties;
    private final HttpClient http;

    public HttpAiClient(AiServerProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public AiResponse post(String path, String jsonBody, Duration timeout)
            throws AiTransportException, InterruptedException {
        if (properties.baseUrl().isBlank() || properties.serviceToken().isBlank()) {
            throw new IllegalStateException("scenecrew.ai.server.base-url과 service-token을 설정해야 합니다.");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(properties.baseUrl()) + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + properties.serviceToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new AiResponse(response.statusCode(), response.body());
        } catch (HttpTimeoutException e) {
            throw new AiTransportException("AI 서버 응답 시간 초과: " + path, true, e);
        } catch (IOException e) {
            throw new AiTransportException("AI 서버 연결 실패: " + path, false, e);
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

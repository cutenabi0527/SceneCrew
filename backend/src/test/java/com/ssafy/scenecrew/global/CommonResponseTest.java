package com.ssafy.scenecrew.global;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #182 공통 HTTP 형식: 오류 본문, requestId, 401/403, 시간·BIGINT 표기를 실제 HTTP로 확인한다.
 * /test/** 는 이 테스트에서만 열리는 확인용 경로다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import({CommonResponseTest.TestSecurity.class, CommonResponseTest.TestController.class})
class CommonResponseTest {
    @LocalServerPort
    private int port;

    // --- 인증·권한 (Security 필터에서 나는 오류) ---

    @Test
    void unauthenticatedRequestReturns401WithErrorBody() throws Exception {
        var response = send(get("/api/v1/events"));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"", "\"message\":");
        assertRequestIdMatchesHeader(response);
    }

    @Test
    void deniedRequestReturns403WithErrorBody() throws Exception {
        // CSRF 토큰 없는 POST는 Security가 접근 거부로 처리한다.
        var response = send(post("/api/v1/events", "{}"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertRequestIdMatchesHeader(response);
    }

    // --- requestId ---

    @Test
    void safeIncomingRequestIdIsKept() throws Exception {
        var response = send(get("/test/business").header("X-Request-Id", "abc-123"));
        assertThat(response.headers().firstValue("X-Request-Id")).hasValue("abc-123");
        assertThat(response.body()).contains("\"requestId\":\"abc-123\"");
    }

    @Test
    void unsafeIncomingRequestIdIsReplaced() throws Exception {
        var response = send(get("/test/business").header("X-Request-Id", "bad\"id"));
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent().get().isNotEqualTo("bad\"id");
        assertRequestIdMatchesHeader(response);
    }

    // --- 컨트롤러 오류 ---

    @Test
    void businessExceptionUsesItsCodeAndMessage() throws Exception {
        var response = send(get("/test/business"));
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"", "\"message\":\"행사를 찾을 수 없습니다.\"");
        assertThat(response.body()).doesNotContain("details");
    }

    @Test
    void unexpectedExceptionHidesInternalDetails() throws Exception {
        var response = send(get("/test/boom"));
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("\"code\":\"INTERNAL_ERROR\"");
        assertThat(response.body()).doesNotContain("secret-internal", "Exception", "trace");
    }

    @Test
    void validationFailureListsFields() throws Exception {
        var response = send(post("/test/items", "{\"name\":\" \",\"at\":\"2026-10-27T08:01:32Z\"}"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_REQUEST\"", "\"fields\":",
                "\"field\":\"name\"", "\"reason\":\"NotBlank\"");
    }

    @Test
    void malformedJsonIsInvalidRequest() throws Exception {
        var response = send(post("/test/items", "{not json"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_REQUEST\"");
    }

    @Test
    void frameworkErrorsUseCommonBody() throws Exception {
        var notFound = send(get("/test/nothing-here"));
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(notFound.body()).contains("\"code\":\"NOT_FOUND\"");

        var wrongMethod = send(get("/test/items"));
        assertThat(wrongMethod.statusCode()).isEqualTo(405);
        assertThat(wrongMethod.body()).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
    }

    // --- 시간·BIGINT 표기 ---

    @Test
    void timeIsUtcWithZAndPublicBigintIsString() throws Exception {
        var response = send(get("/test/format"));
        assertThat(response.statusCode()).isEqualTo(200);
        // FE 응답용: BIGINT는 문자열, 시간은 UTC Z
        assertThat(response.body()).contains("\"revision\":\"12\"", "\"createdAt\":\"2026-10-27T08:01:32Z\"");
        // 어노테이션이 없는 Long(AI 내부 계약 등)은 숫자 그대로
        assertThat(response.body()).contains("\"contextRevision\":12");
    }

    @Test
    void requestTimeNeedsOffset() throws Exception {
        var withOffset = send(post("/test/items", "{\"name\":\"a\",\"at\":\"2026-10-27T17:01:32+09:00\"}"));
        assertThat(withOffset.statusCode()).isEqualTo(200);
        assertThat(withOffset.body()).contains("\"at\":\"2026-10-27T08:01:32Z\"");

        var withoutOffset = send(post("/test/items", "{\"name\":\"a\",\"at\":\"2026-10-27T17:01:32\"}"));
        assertThat(withoutOffset.statusCode()).isEqualTo(400);
        assertThat(withoutOffset.body()).contains("\"code\":\"INVALID_REQUEST\"");
    }

    // --- 도우미 ---

    private void assertRequestIdMatchesHeader(HttpResponse<String> response) {
        String header = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(response.body()).contains("\"requestId\":\"" + header + "\"");
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(uri(path)).GET();
    }

    private HttpRequest.Builder post(String path, String json) {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    // --- 확인용 구성 ---

    @TestConfiguration
    static class TestSecurity {
        @Bean
        @Order(0)
        SecurityFilterChain testEndpoints(HttpSecurity http) throws Exception {
            return http
                    .securityMatcher("/test/**")
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable())
                    .build();
        }
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {
        @GetMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.NOT_FOUND, "행사를 찾을 수 없습니다.");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret-internal");
        }

        @PostMapping("/items")
        Item create(@Valid @RequestBody Item item) {
            return item;
        }

        @GetMapping("/format")
        FormatSample format() {
            return new FormatSample(12L, 12L, Instant.parse("2026-10-27T08:01:32Z"));
        }
    }

    record Item(@NotBlank String name, @NotNull Instant at) {
    }

    record FormatSample(
            @JsonFormat(shape = JsonFormat.Shape.STRING) Long revision,
            Long contextRevision,
            Instant createdAt) {
    }
}

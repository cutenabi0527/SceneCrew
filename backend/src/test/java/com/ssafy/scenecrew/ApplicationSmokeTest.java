package com.ssafy.scenecrew;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationSmokeTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        String host = REDIS.getHost();
        int redisPort = REDIS.getMappedPort(6379);
        registry.add("spring.data.redis.host", () -> host);
        registry.add("spring.data.redis.port", () -> redisPort);
        registry.add("spring.data.redis.timeout", () -> "500ms");
        registry.add("spring.data.redis.connect-timeout", () -> "500ms");
    }

    @AfterAll static void stopRedis() { REDIS.stop(); }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationContext context;
    @LocalServerPort
    private int port;

    @Test
    void healthIsPublicWithoutDetails() throws Exception {
        var response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(response.body()).doesNotContain("components", "details");
    }

    @Test
    void otherEndpointsAreNotPublic() throws Exception {
        // 인증 구현 전에는 로그인하지 않은 요청으로 보고 401을 반환한다.
        assertThat(get("/api/v1/events").statusCode()).isEqualTo(401);
        assertThat(get("/actuator/env").statusCode()).isEqualTo(401);
    }

    @Test
    void logoutCandidateIsDisabledByDefault() throws Exception {
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.service.LogoutService.class)).isEmpty();
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.controller.PublicAuthController.class)).isEmpty();
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/logout"))
                    .header("Content-Type", "application/json").header("X-SceneCrew-Client", "WEB")
                    .header("Origin", "https://web.example.invalid").header("X-SceneCrew-CSRF", "1")
                    .header("X-SceneCrew-Logout-Attempt", "AAAAAAAAAAAAAAAAAAAAAA")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            // Existing Spring CSRF may create JSESSIONID; no candidate authentication cookies are issued.
            assertThat(response.headers().allValues("Set-Cookie"))
                    .noneSatisfy(cookie -> assertThat(cookie).contains("__Secure-sc-"));
        }
    }

    @Test
    void refreshCoreAndPublicRouteAreUnconfiguredByDefault() throws Exception {
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.service.RefreshService.class)).isEmpty();
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/refresh"))
                    .header("Content-Type", "application/json").header("X-SceneCrew-Client", "WEB")
                    .header("Origin", "https://web.example.invalid").header("X-SceneCrew-CSRF", "1")
                    .header("X-SceneCrew-Refresh-Attempt", "AAAAAAAAAAAAAAAAAAAAAA")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().allValues("Set-Cookie"))
                    .allSatisfy(cookie -> assertThat(cookie).doesNotContain("__Secure-sc-"));
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}

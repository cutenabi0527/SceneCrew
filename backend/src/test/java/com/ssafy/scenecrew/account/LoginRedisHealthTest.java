package com.ssafy.scenecrew.account;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.ssafy.scenecrew.account.service.LocalCredentialVerifier;
import com.ssafy.scenecrew.account.service.LoginTokens;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

import static org.assertj.core.api.Assertions.*;

/** 제품 health/Security 설정을 그대로 사용하며, 이 테스트가 소유한 Redis만 중단한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginRedisHealthTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.10.2-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("spring.data.redis.host", REDIS::getHost);
        // 중단 후에도 같은 포트를 사용한다. 컨테이너 상태에 의존하는 supplier를 남기지 않는다.
        int redisPort = REDIS.getMappedPort(6379);
        registry.add("spring.data.redis.port", () -> redisPort);
        registry.add("spring.data.redis.timeout", () -> "500ms");
        registry.add("spring.data.redis.connect-timeout", () -> "500ms");
    }

    @AfterAll static void stop() { REDIS.stop(); }

    @LocalServerPort int port;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @Test void redisOutageMakesHealthUnavailableAndSessionRegistrationFailClosed() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var up = get(client, "/actuator/health");
            assertThat(up.statusCode()).isEqualTo(200);
            assertThat(up.body()).contains("\"status\":\"UP\"").doesNotContain("details", "components");
            // 현재 제품 설정에는 별도 공개 readiness/liveness 경로가 없다.
            assertThat(get(client, "/actuator/health/readiness").statusCode()).isEqualTo(401);
            assertThat(get(client, "/actuator/health/liveness").statusCode()).isEqualTo(401);

            REDIS.stop();
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            var down = get(client, "/actuator/health");
            assertThat(down.statusCode()).isEqualTo(503);
            assertThat(down.body()).contains("\"status\":\"DOWN\"")
                    .doesNotContain("details", "components", "Exception", "redis://");

            var clock = Clock.systemUTC();
            var account = new LocalCredentialVerifier.VerifiedAccount(UUID.randomUUID(), "합성계정",
                    "fixture@example.invalid", 0, UUID.randomUUID());
            var tokens = new LoginTokens(UUID.randomUUID(), "synthetic-access", "synthetic-refresh",
                    clock.instant().plusSeconds(3600), clock.instant().plus(Duration.ofDays(14)));
            assertThatThrownBy(() -> new RedisLoginSessions(redis, clock).register(account, tokens, "WEB"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                    .hasNoCause().hasMessageNotContaining("synthetic-access")
                    .hasMessageNotContaining("synthetic-refresh");
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}

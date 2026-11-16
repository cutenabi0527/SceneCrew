package com.ssafy.scenecrew.global;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.account.controller.AuthTransport;
import com.ssafy.scenecrew.account.filter.PublicAuthFilter;
import com.ssafy.scenecrew.global.config.CorsConfig;
import com.ssafy.scenecrew.global.config.SecurityConfig;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.error.GlobalErrorController;
import com.ssafy.scenecrew.global.error.GlobalExceptionHandler;
import com.ssafy.scenecrew.global.error.SecurityErrorHandler;
import com.ssafy.scenecrew.global.filter.RequestIdFilter;

import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = MissingApiHttpTest.HttpConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "scenecrew.cors.allowed-origins=https://android.example.invalid"
        })
class MissingApiHttpTest {
    private static final String MISSING = "/api/v1/me/352-missing";
    private static final String ANDROID_ORIGIN = "https://android.example.invalid";
    private static final String DENIED_ORIGIN = "https://denied.example.invalid";
    private static final String WEB_COOKIE = "__Secure-sc-access=web-fixture";

    @LocalServerPort
    int port;

    @Test
    void webCookieReceivesNotFound() throws Exception {
        error(send(web(MISSING)), 404, "NOT_FOUND");
    }

    @Test
    void androidBearerReceivesNotFoundAndCorsHeaders() throws Exception {
        var response = send(android(MISSING, "android-fixture"));
        error(response, 404, "NOT_FOUND");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ANDROID_ORIGIN);
    }

    @Test
    void anonymousAndInvalidSessionsStillReceiveUnauthorized() throws Exception {
        error(send(request(MISSING)), 401, "UNAUTHENTICATED");
        error(send(request("/api/v1/me")), 401, "UNAUTHENTICATED");
        error(send(android(MISSING, "invalid")), 401, "UNAUTHENTICATED");
        var invalid = send(android("/api/v1/me", "invalid"));
        error(invalid, 401, "UNAUTHENTICATED");
        assertThat(invalid.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"invalid_token\"");
    }

    @Test
    void existingRoutesKeepSuccessAndBothAuthorizationBoundaries() throws Exception {
        var me = send(web("/api/v1/me"));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).isEqualTo("fixture");
        error(send(web("/api/v1/events/forbidden")), 403, "FORBIDDEN");
        // A controller route omitted from the security allowlist must stay blocked.
        error(send(web("/api/v1/unlisted")), 401, "UNAUTHENTICATED");
        error(send(web("/api/v1/me").method("HEAD", HttpRequest.BodyPublishers.noBody())), 401);
        error(send(web("/api/v1/events/post-only")), 405, "METHOD_NOT_ALLOWED");
    }

    @Test
    void containerErrorDispatchKeepsNotFoundEnvelope() throws Exception {
        error(send(web("/api/v1/events/container-error")), 404, "NOT_FOUND");
    }

    @Test
    void missingPostRequiresSpringCsrfToken() throws Exception {
        var token = send(web("/api/v1/events/csrf-token"));
        assertThat(token.statusCode()).isEqualTo(200);
        assertThat(token.body()).isNotBlank();
        String session = token.headers().allValues("Set-Cookie").stream()
                .map(header -> header.split(";", 2)[0])
                .filter(cookie -> cookie.startsWith("JSESSIONID="))
                .findFirst().orElseThrow();
        String cookies = WEB_COOKIE + "; " + session;
        var post = request(MISSING).header("Cookie", cookies).POST(HttpRequest.BodyPublishers.noBody());
        error(send(post), 403, "FORBIDDEN");
        error(send(post.header("X-CSRF-TOKEN", token.body())), 404, "NOT_FOUND");
    }

    @Test
    void missingPathRejectsUnconfiguredAndroidOrigin() throws Exception {
        var response = send(android(MISSING, "android-fixture").setHeader("Origin", DENIED_ORIGIN));
        error(response, 403, "AUTH_CHANNEL_MISMATCH");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void missingPathUsesExistingAndroidPreflightPolicy() throws Exception {
        var preflight = request(MISSING).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", ANDROID_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization");
        var allowed = send(preflight);
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ANDROID_ORIGIN);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Methods")).hasValueSatisfying(
                value -> assertThat(value.split(",")).contains("GET"));
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Headers")).hasValueSatisfying(
                value -> assertThat(value).isEqualToIgnoringCase("authorization"));
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();

        var denied = send(preflight.setHeader("Origin", DENIED_ORIGIN));
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(denied.headers().firstValue("Access-Control-Allow-Methods")).isEmpty();
        assertThat(denied.headers().firstValue("Access-Control-Allow-Headers")).isEmpty();
        assertThat(denied.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Request-Id", "jira-352");
    }

    private HttpRequest.Builder web(String path) {
        return request(path).header("Cookie", WEB_COOKIE);
    }

    private HttpRequest.Builder android(String path, String token) {
        return request(path).header("Authorization", "Bearer " + token).header("Origin", ANDROID_ORIGIN);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void error(HttpResponse<String> response, int status, String code) {
        error(response, status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/json"));
        assertThat(response.headers().firstValue("X-Request-Id")).hasValue("jira-352");
        assertThat(response.body()).contains("\"code\":\"" + code + "\"", "\"message\":",
                "\"requestId\":\"jira-352\"").doesNotContain("\"details\"");
    }

    private void error(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @EnableAutoConfiguration
    @Import({SecurityConfig.class, CorsConfig.class, RequestIdFilter.class, SecurityErrorHandler.class,
            GlobalErrorController.class, GlobalExceptionHandler.class, FixtureController.class})
    static class HttpConfig {
        @Bean
        PublicAuthFilter authFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
            var auth = mock(SessionAuthenticator.class);
            when(auth.authenticate(nullable(String.class))).thenAnswer(invocation -> {
                String token = invocation.getArgument(0);
                if ("web-fixture".equals(token)) return principal("WEB");
                if ("android-fixture".equals(token)) return principal("ANDROID");
                throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            });
            return new PublicAuthFilter(new AuthTransport(Set.of(), Set.of(ANDROID_ORIGIN)), auth, errors);
        }

        @Bean
        FilterRegistrationBean<PublicAuthFilter> disableContainerRegistration(PublicAuthFilter filter) {
            var registration = new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }

        private SessionAuthenticator.Principal principal(String channel) {
            return new SessionAuthenticator.Principal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, channel);
        }
    }

    @RestController
    @TestComponent
    static class FixtureController {
        @GetMapping("/api/v1/me")
        String me() {
            return "fixture";
        }

        @GetMapping("/api/v1/events/forbidden")
        void forbidden() {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        @GetMapping("/api/v1/events/csrf-token")
        String csrfToken(CsrfToken token) {
            return token.getToken();
        }

        @GetMapping("/api/v1/unlisted")
        String unlisted() {
            return "blocked";
        }

        @PostMapping("/api/v1/events/post-only")
        void postOnly() {
        }

        @GetMapping("/api/v1/events/container-error")
        void containerError(HttpServletResponse response) throws java.io.IOException {
            response.sendError(404);
        }
    }
}

package com.ssafy.scenecrew.global.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * #263 교차 출처 허용. WEB은 같은 출처(/api/v1)라 대상이 아니고, Android 앱(https://localhost)이 대상이다.
 * nginx는 CORS 헤더를 붙이지 않는다(박지훈, 2026-10-28). 허용 목록이 비면 CORS를 열지 않는다.
 * 앱은 Bearer로 인증하므로 Cookie 자격(credentials)은 허용하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {
    static final List<String> REQUEST_HEADERS = List.of("Accept", "Authorization", "Content-Type",
            "Idempotency-Key", "X-Request-Id", "X-SceneCrew-Client",
            "X-SceneCrew-Refresh-Attempt", "X-SceneCrew-Logout-Attempt");
    static final List<String> RESPONSE_HEADERS = List.of("X-Request-Id", "Retry-After", "WWW-Authenticate",
            "X-Captured-At", "X-Received-At", "X-Frame-Freshness");

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${scenecrew.cors.allowed-origins:${scenecrew.auth.android-origins:}}") String origins) {
        List<String> allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList();
        var config = new CorsConfiguration();
        config.setAllowedOrigins(allowed);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(REQUEST_HEADERS);
        config.setExposedHeaders(RESPONSE_HEADERS);
        config.setAllowCredentials(false);
        config.setMaxAge(600L);
        var deviceDelete = new CorsConfiguration(config);
        deviceDelete.addAllowedHeader("X-Device-Proof");
        // 허용 목록의 Origin에만 CORS를 적용한다. 그 밖(WEB 같은 출처, 낯선 출처)은 CORS 처리 없이
        // 기존 Origin·CSRF 검사와 denyAll이 그대로 판단한다. 낯선 출처를 여기서 일괄 403으로 막으면
        // 프록시 뒤 WEB 요청까지 교차 출처로 오판할 수 있다.
        return request -> {
            String path = request.getRequestURI();
            if (!path.startsWith("/api/v1/") || !allowed.contains(request.getHeader("Origin"))) return null;
            if (path.matches("/api/v1/me/devices/[^/]+") && ("DELETE".equals(request.getMethod())
                    || "OPTIONS".equals(request.getMethod()) && "DELETE".equals(request.getHeader("Access-Control-Request-Method")))) {
                return deviceDelete;
            }
            return config;
        };
    }
}

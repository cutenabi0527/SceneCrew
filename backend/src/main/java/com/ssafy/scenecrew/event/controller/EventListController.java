package com.ssafy.scenecrew.event.controller;

import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.event.dto.EventListResponse;
import com.ssafy.scenecrew.event.service.EventListService;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/** SC-API-021 GET /api/v1/events. 인증은 PublicAuthFilter가 현재 세션으로 끝낸 뒤 들어온다. */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class EventListController {
    private static final Set<String> PARAMS = Set.of("cursor", "limit");
    private final EventListService events;

    public EventListController(EventListService events) { this.events = events; }

    @GetMapping("/api/v1/events")
    public ResponseEntity<EventListResponse> list(HttpServletRequest request, Authentication authentication) {
        for (var e : request.getParameterMap().entrySet())
            if (!PARAMS.contains(e.getKey()) || e.getValue().length != 1) throw invalid();
        String cursor = request.getParameter("cursor");
        String limit = request.getParameter("limit");
        Integer size = null;
        if (limit != null) {
            if (!limit.matches("[1-9][0-9]{0,2}")) throw invalid();
            size = Integer.parseInt(limit);
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(events.list(accountId(authentication), cursor, size));
    }

    private static java.util.UUID accountId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return principal.accountId();
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
    static class Beans {
        @Bean EventListService eventListService(JdbcTemplate jdbc) { return new EventListService(jdbc); }
    }
}

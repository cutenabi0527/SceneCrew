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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import com.ssafy.scenecrew.event.dto.EventListResponse;
import com.ssafy.scenecrew.event.dto.EventRequests;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.event.service.EventReadService;

/** SC-API-023 GET /api/v1/events/{e}. 인증은 PublicAuthFilter가 현재 세션으로 끝낸 뒤 들어온다. */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class EventReadController {
    private final EventReadService events;

    public EventReadController(EventReadService events) { this.events = events; }

    @GetMapping("/api/v1/events/{e}")
    public ResponseEntity<EventListResponse.EventItem> get(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        // 정의된 쿼리가 없다. 형식이 틀린 행사 ID는 존재 여부와 무관한 입력 오류라 400이다.
        EventRequests.onlyParams(request, Set.of());
        var eventId = EventRequests.eventId(e);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(events.get(eventId, EventRequests.accountId(authentication)));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
    static class Beans {
        @Bean EventReadService eventReadService(JdbcTemplate jdbc, CurrentMembers members) {
            return new EventReadService(jdbc, members);
        }
    }
}

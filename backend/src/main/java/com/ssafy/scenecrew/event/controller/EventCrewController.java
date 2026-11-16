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
import com.ssafy.scenecrew.event.dto.EventCrewResponse;
import com.ssafy.scenecrew.event.dto.EventRequests;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.event.service.EventCrewService;

/** SC-API-036 GET /api/v1/events/{e}/work-groups·SC-API-037 GET /api/v1/events/{e}/members. 인증은 PublicAuthFilter가 끝낸다. */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class EventCrewController {
    private final EventCrewService crew;

    public EventCrewController(EventCrewService crew) { this.crew = crew; }

    @GetMapping("/api/v1/events/{e}/work-groups")
    public ResponseEntity<EventCrewResponse.WorkGroupPage> groups(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        EventRequests.onlyParams(request, Set.of());
        var eventId = EventRequests.eventId(e);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(crew.groups(eventId, EventRequests.accountId(authentication)));
    }

    @GetMapping("/api/v1/events/{e}/members")
    public ResponseEntity<EventCrewResponse.MemberPage> members(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        EventRequests.onlyParams(request, Set.of("cursor", "limit"));
        var eventId = EventRequests.eventId(e);
        Integer limit = EventRequests.limit(request);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(crew.members(eventId, EventRequests.accountId(authentication), request.getParameter("cursor"), limit));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
    static class Beans {
        @Bean EventCrewService eventCrewService(JdbcTemplate jdbc, CurrentMembers members) {
            return new EventCrewService(jdbc, members);
        }
    }
}

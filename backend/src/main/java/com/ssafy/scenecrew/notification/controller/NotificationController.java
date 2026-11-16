package com.ssafy.scenecrew.notification.controller;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.notification.dto.NotificationView;
import com.ssafy.scenecrew.notification.service.NotificationCursor;
import com.ssafy.scenecrew.notification.service.NotificationService;

@RestController
@ConditionalOnProperty(name="scenecrew.auth.public-enabled", havingValue="true")
public class NotificationController {
    private final NotificationService service;
    private final NotificationCursor cursor;
    public NotificationController(NotificationService service, NotificationCursor cursor) {
        this.service = service; this.cursor = cursor;
    }
    @GetMapping("/api/v1/me/notifications")
    public NotificationView.Page list(Authentication authentication, @RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="30") int limit) {
        return service.list((SessionAuthenticator.Principal) authentication.getPrincipal(), cursor, limit, this.cursor);
    }
    @PostMapping("/api/v1/notifications/{notificationId}/read")
    public NotificationView read(Authentication authentication, @PathVariable UUID notificationId) {
        return service.read((SessionAuthenticator.Principal) authentication.getPrincipal(), notificationId);
    }
}

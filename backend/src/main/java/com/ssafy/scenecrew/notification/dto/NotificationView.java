package com.ssafy.scenecrew.notification.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Current, minimal projection. Stored payloads are never serialized. */
public record NotificationView(UUID id, UUID eventId, String participationRevision, String title,
        String body, Instant createdAt, Instant readAt, Target target) {
    public record Target(String kind, UUID id) {}
    public record Page(List<NotificationView> items, String nextCursor) {}
}

package com.ssafy.scenecrew.notification.service;

import java.util.UUID;

public interface PushSender {
    Outcome send(String token, UUID notificationId, UUID eventId);

    enum Status { SENT, UNREGISTERED, RETRY, FAILED }

    record Outcome(Status status, String messageId) {
        public static Outcome of(Status status) {
            return new Outcome(status, null);
        }
    }
}

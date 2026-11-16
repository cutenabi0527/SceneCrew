package com.ssafy.scenecrew.notification.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notification_delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDelivery {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID notificationId;

    @Column(nullable = false)
    private UUID deviceTokenId;

    @Column(nullable = false)
    private Long attempt;

    @Column(nullable = false, columnDefinition = "text")
    private String state;

    @Column(columnDefinition = "text")
    private String providerMessageId;

    @Column(columnDefinition = "text")
    private String lastError;

    private Instant sentAt;

    private Instant nextAttemptAt;

    private Instant leaseUntil;
}

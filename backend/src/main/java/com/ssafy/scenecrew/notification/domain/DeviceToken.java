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
@Table(name = "device_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID memberId;

    @Column(nullable = false, columnDefinition = "text")
    private String deviceId;

    @Column(nullable = false, columnDefinition = "text")
    private String fcmToken;

    private Long participationRevision;

    @Column(nullable = false)
    private Boolean active;

    @Column(nullable = false)
    private Instant updatedAt;
}

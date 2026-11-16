package com.ssafy.scenecrew.safety.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "safety_camera")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SafetyCamera {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    /** 서버 설정 경로(예비)의 연결 이름. 웹으로만 연결하는 카메라는 null(V9-D58) */
    @Column(columnDefinition = "text")
    private String sourceBindingRef;

    /** BROWSER(웹 연결 중)·COLLECTOR(서버 설정 경로)·null(입력 없음) */
    @Column(length = 16)
    private String inputKind;

    /** 웹 연결 식별자. 연결한 브라우저만 안다 */
    private UUID linkId;

    private UUID linkMemberId;

    private Instant linkedAt;

    /** 연결 담당 업무(#393) */
    private UUID linkTaskId;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false)
    private Long revision;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String coverage;

    @Column(nullable = false)
    private UUID configuredByMemberId;

    @Column(nullable = false)
    private Instant configuredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CameraConnectionState connectionState;

    private UUID streamSessionId;

    private Long lastSequence;

    private Instant lastCapturedAt;

    private Instant lastReceivedAt;

    private Instant lastProcessedAt;

    private UUID latestObservationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ObservationFreshness freshness;
}

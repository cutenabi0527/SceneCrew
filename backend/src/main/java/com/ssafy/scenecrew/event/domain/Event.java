package com.ssafy.scenecrew.event.domain;

import java.time.Instant;
import java.time.LocalDate;
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
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {
    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    private LocalDate date;

    private Instant prepStartAt;

    private Instant openAt;

    private Instant closeAt;

    private Instant teardownEndAt;

    @Column(columnDefinition = "text")
    private String locationName;

    @Column(columnDefinition = "text")
    private String address;

    private Long totalCapacity;

    private Long expectedVisitors;

    private Long peakVisitors;

    @Column(nullable = false)
    private Long desiredReserveCount;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String confirmedInput;

    @Column(nullable = false, columnDefinition = "text")
    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private EventState state;

    @Column(nullable = false)
    private Long revision;

    @Column(columnDefinition = "text")
    private String inviteTokenHash;

    @Column(columnDefinition = "text")
    private String inviteTokenCiphertext;

    @Column(nullable = false)
    private Instant lastInputModifiedAt;

    private Instant endedAt;

    @Column(columnDefinition = "text")
    private String endReason;

    public void advanceRevision() {
        revision = Math.incrementExact(revision);
    }

    /** 확인한 입력 묶음(조건·입력 문서)이 바뀌었다. 옛 입력으로 만든 추출·계획 결과를 적용하지 않도록 revision도 올린다. */
    public void markInputChanged(Instant at) {
        advanceRevision();
        lastInputModifiedAt = at;
    }
}

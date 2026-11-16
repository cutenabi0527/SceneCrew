package com.ssafy.scenecrew.plan.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.ColumnTransformer;

/**
 * 행사별 미확정 계획 초안의 최신 공유본(행사당 1행). 확정 전에는 revision을 비교해 편집하고 확정 후에는 읽기만 한다.
 * 과거 초안은 남기지 않고, 행사 종료 후 2년 보관한다.
 */
@Entity
@Table(name = "event_unconfirmed_draft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventUnconfirmedDraft {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private Long revision;

    private UUID sourceJobId;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String payload;

    @Column(nullable = false)
    private UUID savedByMemberId;

    @Column(nullable = false)
    private Instant savedAt;
}

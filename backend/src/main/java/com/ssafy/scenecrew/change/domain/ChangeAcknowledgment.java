package com.ssafy.scenecrew.change.domain;

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

@Entity
@Table(name = "change_acknowledgment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeAcknowledgment {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID changeSetId;

    @Column(nullable = false)
    private UUID memberId;

    @Column(nullable = false)
    private Long participationRevision;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String targets;

    @Column(nullable = false, columnDefinition = "text")
    private String targetsHash;

    private Instant acknowledgedAt;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String acknowledgedTargets;
}

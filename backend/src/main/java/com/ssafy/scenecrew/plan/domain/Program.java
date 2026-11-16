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

@Entity
@Table(name = "program")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Program {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    private UUID zoneId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(nullable = false)
    private Instant startAt;

    @Column(nullable = false)
    private Instant endAt;
}

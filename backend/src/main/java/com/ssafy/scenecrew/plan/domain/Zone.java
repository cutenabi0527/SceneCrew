package com.ssafy.scenecrew.plan.domain;

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
@Table(name = "zone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Zone {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String aliases;
}

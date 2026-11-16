package com.ssafy.scenecrew.account.domain;

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
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {
    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String displayName;

    @Column(nullable = false, columnDefinition = "text")
    private String status;

    @Column(nullable = false)
    private Boolean signupCompleted;

    @Column(nullable = false)
    private Long tokenVersion;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String consent;

    @Column(nullable = false)
    private Instant createdAt;

    /** 탈퇴(=삭제 요청) 시각(#334, V9-D39). 탈퇴 전에는 null. */
    private Instant deactivatedAt;
}

package com.ssafy.scenecrew.account.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "auth_identity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthIdentity {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false, columnDefinition = "text")
    private String provider;

    @Column(nullable = false, columnDefinition = "text")
    private String subject;

    @Column(columnDefinition = "text")
    private String passwordHash;

    @Column(columnDefinition = "text")
    private String email;

    @Column(nullable = false)
    private Boolean emailVerified;
}

package com.ssafy.scenecrew.document.domain;

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
@Table(name = "intake_document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IntakeDocument {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID ownerAccountId;

    @Column(nullable = false, columnDefinition = "text")
    private String originalName;

    @Column(nullable = false, columnDefinition = "text")
    private String mime;

    @Column(nullable = false)
    private Long bytes;

    private Integer pageCount;

    @Column(nullable = false, columnDefinition = "text")
    private String sha256;

    @Column(columnDefinition = "text")
    private String storageKey;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    /** S3에 원본을 저장한 뒤 등록할 문서. id는 저장 키를 만들 때 이미 정했다. */
    public static IntakeDocument create(UUID id, UUID eventId, UUID ownerAccountId, String originalName, String mime,
            long bytes, Integer pageCount, String sha256, String storageKey, Instant createdAt, Instant expiresAt) {
        IntakeDocument document = new IntakeDocument();
        document.id = id;
        document.eventId = eventId;
        document.ownerAccountId = ownerAccountId;
        document.originalName = originalName;
        document.mime = mime;
        document.bytes = bytes;
        document.pageCount = pageCount;
        document.sha256 = sha256;
        document.storageKey = storageKey;
        document.createdAt = createdAt;
        document.expiresAt = expiresAt;
        return document;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** 입력 묶음이 바뀐 시각 기준으로 만료를 다시 정한다. 조회나 재시도로는 부르지 않는다. */
    public void expireAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    /** S3 원본을 지운 뒤 부른다. 원본 키만 비우고 나머지 메타데이터는 남긴다. */
    public void removeOriginal() {
        this.storageKey = null;
    }
}

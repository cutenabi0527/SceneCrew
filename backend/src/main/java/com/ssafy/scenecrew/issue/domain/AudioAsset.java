package com.ssafy.scenecrew.issue.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.ColumnTransformer;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "audio_asset")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AudioAsset {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID uploaderMemberId;

    @Column(columnDefinition = "text")
    private String storageKey;

    @Column(nullable = false, columnDefinition = "text")
    private String mime;

    @Column(nullable = false)
    private Long bytes;

    @Column(nullable = false, columnDefinition = "numeric")
    private BigDecimal durationSec;

    @Column(columnDefinition = "text")
    private String transcript;

    /** 임시 전사의 구간 [{startSec,endSec,text}]. transcript와 같은 수명이다(V7, STT 명세 Q22). */
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String transcriptSegments;

    @Column(nullable = false)
    private Instant createdAt;

    /** S3에 원본을 저장한 뒤 등록할 음성. id는 저장 키를 만들 때 이미 정했다. */
    public static AudioAsset create(UUID id, UUID eventId, UUID uploaderMemberId, String storageKey, String mime,
            long bytes, BigDecimal durationSec, Instant createdAt) {
        AudioAsset asset = new AudioAsset();
        asset.id = id;
        asset.eventId = eventId;
        asset.uploaderMemberId = uploaderMemberId;
        asset.storageKey = storageKey;
        asset.mime = mime;
        asset.bytes = bytes;
        asset.durationSec = durationSec;
        asset.createdAt = createdAt;
        return asset;
    }

    /** 전사 결과를 임시 전사로 기록한다. 사용자가 확인·수정해 제출하기 전에는 Report.text가 아니다. */
    public void recordTranscript(String text, String segmentsJson) {
        this.transcript = text;
        this.transcriptSegments = segmentsJson;
    }

    /** 원본이 아직 있고 보관 기간(생성 후 retention) 안인지 */
    public boolean isOriginalAvailable(Instant now, Duration retention) {
        return storageKey != null && createdAt.plus(retention).isAfter(now);
    }
}

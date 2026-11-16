package com.ssafy.scenecrew.global.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 파일 처리 설정(scenecrew.storage.files).
 *
 * @param ffprobePath    음성 길이를 잴 ffprobe 실행 파일. 이미지에는 PATH에 설치되어 있다
 * @param ffprobeTimeout ffprobe 한 번에 기다릴 최대 시간
 * @param cleanup        만료 원본 정리
 */
@ConfigurationProperties("scenecrew.storage.files")
public record FileStorageProperties(
        @DefaultValue("ffprobe") String ffprobePath,
        @DefaultValue("10s") Duration ffprobeTimeout,
        @DefaultValue Cleanup cleanup) {

    /**
     * 주기 실행 여부(enabled)와 주기(interval)는 FileCleanupScheduler가 같은 키를 직접 읽는다.
     *
     * @param batchSize 한 번에 지울 최대 개수(음성·문서 각각)
     */
    public record Cleanup(@DefaultValue("100") int batchSize) {
    }
}

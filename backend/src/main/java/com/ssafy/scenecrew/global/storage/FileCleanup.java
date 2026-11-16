package com.ssafy.scenecrew.global.storage;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 음성(생성 후 24시간)·문서(expires_at 경과) 원본 정리. 한 종류가 실패해도 다른 종류는 계속한다. */
@Component
public class FileCleanup {
    private static final Logger log = LoggerFactory.getLogger(FileCleanup.class);
    private final List<ExpiredFileCleaner> cleaners;
    private final FileStorageProperties properties;

    public FileCleanup(List<ExpiredFileCleaner> cleaners, FileStorageProperties properties) {
        this.cleaners = cleaners;
        this.properties = properties;
    }

    /** 지운 원본 수(모든 종류 합) */
    public int cleanupOnce() {
        return cleanupOnce(Instant.now());
    }

    /** now 기준으로 만료된 원본을 지운다. 테스트는 시각을 앞당겨 부른다. */
    public int cleanupOnce(Instant now) {
        int removed = 0;
        for (ExpiredFileCleaner cleaner : cleaners) {
            try {
                removed += cleaner.cleanupExpired(now, properties.cleanup().batchSize());
            } catch (RuntimeException e) {
                log.warn("만료 원본 정리 중 오류: {}", cleaner.getClass().getSimpleName(), e);
            }
        }
        if (removed > 0) {
            log.info("만료 원본 {}개를 지웠습니다.", removed);
        }
        return removed;
    }
}

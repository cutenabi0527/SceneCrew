package com.ssafy.scenecrew.global.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** scenecrew.storage.files.cleanup.enabled가 true일 때 FileCleanup을 주기적으로 실행한다. */
@Component
@ConditionalOnProperty(name = "scenecrew.storage.files.cleanup.enabled", havingValue = "true", matchIfMissing = true)
class FileCleanupScheduler {
    private final FileCleanup cleanup;

    FileCleanupScheduler(FileCleanup cleanup) {
        this.cleanup = cleanup;
    }

    // 주기 기본값은 application.yml(FILE_CLEANUP_INTERVAL, 10m) 한 곳에만 둔다.
    @Scheduled(initialDelayString = "${scenecrew.storage.files.cleanup.interval}",
            fixedDelayString = "${scenecrew.storage.files.cleanup.interval}")
    void run() {
        cleanup.cleanupOnce();
    }
}

package com.ssafy.scenecrew.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class FileCleanupTest {
    private static final FileStorageProperties PROPERTIES = new FileStorageProperties("ffprobe", Duration.ofSeconds(10),
            new FileStorageProperties.Cleanup(7));

    @Test
    void oneFailingKindDoesNotStopOthers() {
        ExpiredFileCleaner failing = (now, limit) -> {
            throw new IllegalStateException("정리 실패 시험");
        };
        ExpiredFileCleaner working = (now, limit) -> limit;

        int removed = new FileCleanup(List.of(failing, working), PROPERTIES).cleanupOnce(Instant.now());

        assertThat(removed).isEqualTo(7);
    }
}

package com.ssafy.scenecrew.global.idempotency.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "scenecrew.idempotency.cleanup.enabled", havingValue = "true", matchIfMissing = true)
class IdempotencyCleanupScheduler {
    private final IdempotencyCleanup cleanup;

    IdempotencyCleanupScheduler(IdempotencyCleanup cleanup) {
        this.cleanup = cleanup;
    }

    // 주기 기본값은 application.yml(IDEMPOTENCY_CLEANUP_INTERVAL, 10m) 한 곳에만 둔다.
    @Scheduled(initialDelayString = "${scenecrew.idempotency.cleanup.interval}",
            fixedDelayString = "${scenecrew.idempotency.cleanup.interval}")
    void run() {
        cleanup.clearExpiredReceipts();
    }
}

package com.ssafy.scenecrew.aijob.pipeline;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** scenecrew.ai.job.recovery.enabled가 true일 때 AiJobRecovery를 주기적으로 실행한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scenecrew.ai.job.recovery.enabled", havingValue = "true", matchIfMissing = true)
class AiJobRecoveryScheduler {
    private final AiJobRecovery recovery;

    @Scheduled(fixedDelayString = "${scenecrew.ai.job.recovery.delay-ms:30000}")
    void run() {
        recovery.recoverOnce();
    }
}

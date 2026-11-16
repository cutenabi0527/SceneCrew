package com.ssafy.scenecrew.global.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** scenecrew.outbox.relay.enabled가 true일 때 OutboxRelay를 주기적으로 실행한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scenecrew.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler {
    private final OutboxRelay relay;
    private final OutboxRelayProperties properties;

    @Scheduled(fixedDelayString = "${scenecrew.outbox.relay.delay-ms:1000}")
    void run() {
        // 한 묶음을 가득 꺼냈으면 쌓인 작업이 더 있을 수 있어 기다리지 않고 이어서 처리한다.
        int claimed;
        do {
            claimed = relay.relayOnce();
        } while (claimed == properties.batchSize());
    }
}

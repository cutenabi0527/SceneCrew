package com.ssafy.scenecrew.global.outbox;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * outbox 작업을 꺼내 처리하는 규칙(scenecrew.outbox.relay).
 *
 * @param enabled      주기 실행을 켤지. 테스트는 끄고 OutboxRelay를 직접 호출한다.
 * @param delayMs      한 번 처리를 마친 뒤 다음 확인까지 기다리는 시간
 * @param batchSize    한 번에 꺼내는 최대 작업 수
 * @param leaseSeconds 꺼낸 작업을 붙잡아 두는 시간. 처리 중 서버가 죽으면 이 시간 뒤 다시 꺼낸다.
 * @param maxAttempts  최대 시도 횟수. 넘으면 더 꺼내지 않고 ERROR 로그를 남긴다.
 * @param backoff      실패 n번째 뒤 다시 시도할 때까지 기다리는 시간. 횟수가 목록보다 많으면 마지막 값을 쓴다.
 */
@ConfigurationProperties("scenecrew.outbox.relay")
public record OutboxRelayProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") long delayMs,
        @DefaultValue("10") int batchSize,
        @DefaultValue("30") long leaseSeconds,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue({"5s", "30s", "2m", "10m"}) List<Duration> backoff) {

    public OutboxRelayProperties {
        if (batchSize < 1 || leaseSeconds < 1 || maxAttempts < 1 || backoff.isEmpty()) {
            throw new IllegalArgumentException("scenecrew.outbox.relay 값이 올바르지 않습니다.");
        }
        backoff = List.copyOf(backoff);
    }

    /** 지금까지 실패한 횟수(1부터)에 맞는 대기 시간 */
    public Duration backoffAfter(long failedAttempts) {
        int index = (int) Math.min(Math.max(failedAttempts, 1), backoff.size()) - 1;
        return backoff.get(index);
    }
}

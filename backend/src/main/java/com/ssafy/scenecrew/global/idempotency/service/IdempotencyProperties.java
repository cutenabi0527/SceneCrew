package com.ssafy.scenecrew.global.idempotency.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 요청 키 처리 규칙(scenecrew.idempotency). backend/docs/idempotency.md
 *
 * @param inProgressWait 같은 키 요청이 처리 중일 때 기다리는 최대 시간. 그 안에 끝나면 그 결과를 돌려주고,
 *                       넘기면 409 REQUEST_IN_PROGRESS다(be/11 "제한 대기 뒤").
 * @param lockRetry      기다리는 동안 잠금을 다시 시도하는 간격
 * @param cleanup        24시간 지난 응답 본문 정리
 */
@ConfigurationProperties("scenecrew.idempotency")
public record IdempotencyProperties(
        @DefaultValue("3s") Duration inProgressWait,
        @DefaultValue("100ms") Duration lockRetry,
        @DefaultValue Cleanup cleanup) {

    public IdempotencyProperties {
        if (inProgressWait.isNegative() || !lockRetry.isPositive()) {
            throw new IllegalArgumentException("scenecrew.idempotency 값이 올바르지 않습니다.");
        }
    }

    /**
     * 주기 실행 여부(enabled)와 주기(interval)는 IdempotencyCleanupScheduler가 같은 키를 직접 읽는다.
     *
     * @param batchSize 한 번에 비울 최대 기록 수
     */
    public record Cleanup(@DefaultValue("500") int batchSize) {
    }
}

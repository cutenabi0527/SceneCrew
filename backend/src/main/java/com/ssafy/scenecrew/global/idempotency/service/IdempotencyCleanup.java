package com.ssafy.scenecrew.global.idempotency.service;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 응답 복구 기간(24시간)이 지난 기록의 응답 본문(receipt)을 비운다(POLICIES V9-D12, 사전 B IdempotencyRecord).
 * 키·요청 비교 값·결과 ID(resultRefs)·시각은 최소 완료 증거로 남긴다. 30일 지난 기록 삭제는 하지 않는다.
 * 비운 뒤에도 같은 키 재요청은 IDEMPOTENCY_RESULT_EXPIRED이고 업무를 다시 하지 않는다. S15P31A202-341.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCleanup {
    /** 비운 응답 본문. receipt는 NOT NULL이라 빈 객체로 둔다. */
    public static final String CLEARED_RECEIPT = "{}";

    private final JdbcTemplate jdbc;
    private final IdempotencyProperties properties;

    public int clearExpiredReceipts() {
        return clearExpiredReceipts(Instant.now());
    }

    /** now 기준 복구 기간이 지난 기록의 본문을 한 번에 batchSize개까지 비운다. 비운 개수를 돌려준다. */
    public int clearExpiredReceipts(Instant now) {
        int cleared = jdbc.update("""
                update idempotency_record set receipt = '{}'::jsonb
                 where id in (select id from idempotency_record
                               where replay_expires_at <= ? and receipt <> '{}'::jsonb
                               order by replay_expires_at
                               limit ?)
                """, Timestamp.from(now), properties.cleanup().batchSize());
        if (cleared > 0) {
            log.info("요청 키 응답 본문 정리: {}건", cleared);
        }
        return cleared;
    }
}

package com.ssafy.scenecrew.global.outbox.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.scenecrew.global.outbox.domain.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * 지금 처리할 수 있는 작업을 오래 기다린 순서로 고르고 행 잠금을 건다.
     * 다른 서버가 잠근 행은 기다리지 않고 건너뛴다(SKIP LOCKED). 같은 트랜잭션에서 lease를 적어야 한다.
     */
    @Query(value = """
            SELECT id FROM outbox_event
             WHERE processed_at IS NULL
               AND available_at <= :now
               AND (lease_until IS NULL OR lease_until < :now)
               AND attempts < :maxAttempts
             ORDER BY available_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockReady(@Param("now") Instant now, @Param("maxAttempts") long maxAttempts, @Param("limit") int limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OutboxEvent e set e.leaseUntil = :leaseUntil where e.id in :ids")
    int lease(@Param("ids") List<UUID> ids, @Param("leaseUntil") Instant leaseUntil);

    /**
     * 처리 완료. 꺼낼 때 받은 lease 값이 그대로일 때만 기록한다.
     * lease가 만료돼 다른 작업자가 다시 꺼냈으면 lease 값이 바뀌어 0건이 된다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update OutboxEvent e set e.processedAt = :now, e.leaseUntil = null
             where e.id = :id and e.processedAt is null and e.leaseUntil = :claimedLease
            """)
    int markProcessed(@Param("id") UUID id, @Param("now") Instant now, @Param("claimedLease") Instant claimedLease);

    /** 처리 실패. 시도 횟수를 기록하고 availableAt 이후에 다시 꺼내게 한다. 지금도 담당자일 때만 기록한다. */
    @Modifying(clearAutomatically = true)
    @Query("""
            update OutboxEvent e set e.attempts = :attempts, e.availableAt = :availableAt, e.leaseUntil = null
             where e.id = :id and e.processedAt is null and e.leaseUntil = :claimedLease
            """)
    int markFailed(@Param("id") UUID id, @Param("attempts") long attempts, @Param("availableAt") Instant availableAt,
            @Param("claimedLease") Instant claimedLease);
}

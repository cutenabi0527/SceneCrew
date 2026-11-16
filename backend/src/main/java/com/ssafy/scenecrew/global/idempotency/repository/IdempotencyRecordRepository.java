package com.ssafy.scenecrew.global.idempotency.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.global.idempotency.domain.IdempotencyRecord;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByActorAccountIdAndOperationAndIdempotencyKey(UUID actorAccountId, String operation,
            String idempotencyKey);
}

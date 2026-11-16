package com.ssafy.scenecrew.document.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.scenecrew.document.domain.IntakeDocument;

public interface IntakeDocumentRepository extends JpaRepository<IntakeDocument, UUID> {

    /** 행사의 입력 묶음: 원본이 남아 있고 아직 만료되지 않은 문서 */
    @Query("""
            select d from IntakeDocument d
             where d.eventId = :eventId and d.storageKey is not null and d.expiresAt > :now
             order by d.createdAt
            """)
    List<IntakeDocument> findLiveByEventId(@Param("eventId") UUID eventId, @Param("now") Instant now);

    /** 만료 정리 대상의 id와 행사 */
    interface ExpiredDocument {
        UUID getId();

        UUID getEventId();
    }

    /** 원본이 남아 있지만 만료 시각이 지난 문서. 정리 작업이 행사별로 잠근 뒤 다시 확인하고 지운다. */
    @Query("""
            select d.id as id, d.eventId as eventId from IntakeDocument d
             where d.storageKey is not null and d.expiresAt <= :now
             order by d.expiresAt
            """)
    List<ExpiredDocument> findExpired(@Param("now") Instant now, Pageable page);

    /** 이 계정이 올렸고 원본이 남아 있는 문서(만료 여부와 상관없이) */
    List<IntakeDocument> findByEventIdAndOwnerAccountIdAndStorageKeyIsNotNull(UUID eventId, UUID ownerAccountId);
}

package com.ssafy.scenecrew.issue.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.scenecrew.issue.domain.AudioAsset;

public interface AudioAssetRepository extends JpaRepository<AudioAsset, UUID> {

    /** 전사 결과를 반영하기 전에 행을 잠근다. 반영과 본인 삭제·만료 정리가 엇갈리지 않게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AudioAsset a where a.id = :id")
    Optional<AudioAsset> findForUpdate(@Param("id") UUID id);

    /** 원본 id와 저장 키 */
    interface StoredAudio {
        UUID getId();

        String getStorageKey();
    }

    /** 원본이 아직 남아 있고 createdBefore보다 먼저 만든 음성. 정리 작업이 지울 대상이다. */
    @Query("""
            select a.id as id, a.storageKey as storageKey from AudioAsset a
             where a.storageKey is not null and a.createdAt <= :createdBefore
             order by a.createdAt
            """)
    List<StoredAudio> findExpired(@Param("createdBefore") Instant createdBefore, Pageable page);

    /**
     * S3 원본을 지운 뒤 원본 키와 임시 전사문·전사 구간을 비운다. 키가 그새 바뀌었으면(이미 정리됨) 아무것도 하지 않는다.
     * 음성 만료는 생성 시각 기준으로 고정이라 정리 중에 만료가 늘어나는 경우는 없다.
     */
    @Modifying
    @Query("""
            update AudioAsset a set a.storageKey = null, a.transcript = null, a.transcriptSegments = null
             where a.id = :id and a.storageKey = :key
            """)
    int clearOriginal(@Param("id") UUID id, @Param("key") String key);
}

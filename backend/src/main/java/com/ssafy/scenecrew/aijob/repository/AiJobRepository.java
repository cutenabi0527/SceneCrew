package com.ssafy.scenecrew.aijob.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.domain.AiJobState;

public interface AiJobRepository extends JpaRepository<AiJob, UUID> {

    /** 상태를 바꾸기 전에 행을 잠근다. 실행기·복구·늦은 결과가 같은 작업을 동시에 바꾸지 않게 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AiJob j where j.id = :id")
    Optional<AiJob> findForUpdate(@Param("id") UUID id);

    /**
     * 마감 전이고 아무도 붙잡고 있지 않은 대기 작업. 실행기에 넘긴 뒤 사라진 작업을 다시 찾는 데 쓴다.
     * 막 접수한 작업은 outbox가 곧 넘기므로 createdBefore보다 먼저 만든 작업만 고른다.
     */
    @Query("""
            select j.id from AiJob j
             where j.state = :queued
               and (j.leaseUntil is null or j.leaseUntil < :now)
               and j.deadlineAt > :now
               and j.createdAt < :createdBefore
             order by j.createdAt
            """)
    List<UUID> findDispatchable(@Param("queued") AiJobState queued, @Param("now") Instant now,
            @Param("createdBefore") Instant createdBefore, Pageable page);

    /** 마감이 지난 대기 작업과 담당 증표가 만료된 실행 작업 */
    @Query("""
            select j.id from AiJob j
             where (j.state = :queued and j.deadlineAt <= :now)
                or (j.state = :running and (j.leaseUntil is null or j.leaseUntil < :now))
             order by j.createdAt
            """)
    List<UUID> findExpired(@Param("queued") AiJobState queued, @Param("running") AiJobState running,
            @Param("now") Instant now, Pageable page);

    /** 행사에 아직 끝나지 않은(대기·실행 중) 해당 종류의 작업이 있는지. 문서 추출 중 파일 변경을 막을 때 쓴다. */
    boolean existsByEventIdAndKindAndStateIn(UUID eventId, String kind, Collection<AiJobState> states);

    /**
     * 이 음성을 입력으로 하는, 아직 끝나지 않고 마감 전인 작업이 있는지. AI 서버의 음성 다운로드를 허용할 때 쓴다.
     * 음성 id는 접수 때 ai_job.input(payload)의 audioId로 저장한다.
     */
    @Query(value = """
            select exists (
                select 1 from ai_job
                 where kind = :kind and event_id = :eventId and state in ('QUEUED', 'RUNNING')
                   and deadline_at > :now and input ->> 'audioId' = :audioId)
            """, nativeQuery = true)
    boolean existsActiveForAudio(@Param("kind") String kind, @Param("eventId") UUID eventId,
            @Param("audioId") String audioId, @Param("now") Instant now);
}

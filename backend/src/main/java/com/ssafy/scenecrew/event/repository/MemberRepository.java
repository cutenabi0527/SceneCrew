package com.ssafy.scenecrew.event.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.scenecrew.event.domain.Member;

public interface MemberRepository extends JpaRepository<Member, UUID> {

    /** 행사 잠금 전에 식별자만 읽어 오래된 Member 엔티티를 영속성 컨텍스트에 넣지 않는다. */
    @Query("select m.eventId from Member m where m.id = :id")
    Optional<UUID> findEventId(@Param("id") UUID id);

    /** 이 계정이 지금 이 행사의 활성 Member(총괄·팀원)이면 그 Member. 탈퇴·비활성이면 비어 있다. */
    @Query("""
            select m from Member m
             where m.eventId = :eventId and m.accountId = :accountId and m.active = true
            """)
    Optional<Member> findActive(@Param("eventId") UUID eventId, @Param("accountId") UUID accountId);

    /** 이 계정이 지금 이 행사의 활성 총괄이면 그 Member. 총괄을 넘긴 뒤에는 비어 있다. */
    @Query("""
            select m from Member m
             where m.eventId = :eventId and m.accountId = :accountId
               and m.active = true and m.eventRole = com.ssafy.scenecrew.event.domain.EventRole.CHIEF
            """)
    Optional<Member> findActiveChief(@Param("eventId") UUID eventId, @Param("accountId") UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.eventId = :eventId and m.accountId = :accountId and m.active = true")
    Optional<Member> findActiveForUpdate(@Param("eventId") UUID eventId, @Param("accountId") UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id and m.eventId = :eventId")
    Optional<Member> findInEventForUpdate(@Param("eventId") UUID eventId, @Param("id") UUID id);
}

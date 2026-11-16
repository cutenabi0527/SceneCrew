package com.ssafy.scenecrew.event.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.scenecrew.event.domain.Event;

public interface EventRepository extends JpaRepository<Event, UUID> {

    /**
     * 공유 잠금(FOR SHARE)으로 행사를 읽는다. 트랜잭션이 끝날 때까지 다른 트랜잭션이 이 행사를 바꾸지 못하고 기다린다.
     * AI 결과를 반영하는 동안 Event.revision이 바뀌지 않게 할 때 쓴다. 읽기끼리는 서로 막지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findForShare(@Param("id") UUID id);

    /** 배타 잠금(FOR UPDATE)으로 행사를 읽는다. 같은 행사의 문서 묶음을 동시에 바꾸지 않게 할 때 쓴다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findForUpdate(@Param("id") UUID id);
}

package com.ssafy.scenecrew.plan.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.plan.domain.WorkGroup;

public interface WorkGroupRepository extends JpaRepository<WorkGroup, UUID> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select g from WorkGroup g where g.id = :id and g.eventId = :eventId and g.active = true")
    Optional<WorkGroup> findActiveForShare(@Param("eventId") UUID eventId, @Param("id") UUID id);
}

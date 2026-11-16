package com.ssafy.scenecrew.plan.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.scenecrew.plan.domain.Task;
import com.ssafy.scenecrew.plan.domain.TaskStatus;

public interface TaskRepository extends JpaRepository<Task, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from Task t where t.eventId = :eventId and t.assigneeId = :memberId
                and t.status in :statuses order by t.id
            """)
    List<Task> findAssignedForUpdate(@Param("eventId") UUID eventId, @Param("memberId") UUID memberId,
            @Param("statuses") Collection<TaskStatus> statuses);
}

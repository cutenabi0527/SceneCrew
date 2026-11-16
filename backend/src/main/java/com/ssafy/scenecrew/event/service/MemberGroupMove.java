package com.ssafy.scenecrew.event.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.change.domain.OperationLog;
import com.ssafy.scenecrew.change.repository.OperationLogRepository;
import com.ssafy.scenecrew.change.service.PendingAcknowledgments;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.plan.domain.Task;
import com.ssafy.scenecrew.plan.domain.TaskStatus;
import com.ssafy.scenecrew.plan.repository.TaskRepository;
import com.ssafy.scenecrew.safety.service.SafetySupportProtection;

import tools.jackson.databind.ObjectMapper;

@Component
public class MemberGroupMove {
    private final TaskRepository tasks;
    private final OperationLogRepository logs;
    private final ObjectMapper json;
    private final PendingAcknowledgments pending;
    private final SafetySupportProtection support;

    public MemberGroupMove(TaskRepository tasks, OperationLogRepository logs, ObjectMapper json,
            PendingAcknowledgments pending, SafetySupportProtection support) {
        this.tasks = tasks;
        this.logs = logs;
        this.json = json;
        this.pending = pending;
        this.support = support;
    }

    /** The caller holds the Event write lock before checking or changing assignments. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepare(UUID eventId, Member member, UUID targetGroupId) {
        if (Objects.equals(member.getWorkGroupId(), targetGroupId)) {
            return;
        }

        var assigned = tasks.findAssignedForUpdate(eventId, member.getId(),
                List.of(TaskStatus.WAITING, TaskStatus.IN_PROGRESS));
        if (assigned.stream().anyMatch(task -> task.getStatus() == TaskStatus.IN_PROGRESS)) {
            throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        support.requireMemberCanMove(eventId, member.getId(), Instant.now());
        assigned.forEach(Task::releaseWaitingAssignment);
        pending.release(eventId, member.getId(), assigned.stream().map(Task::getId).collect(java.util.stream.Collectors.toSet()),
                Instant.now());
        var changes = new Changes(member.getWorkGroupId(), targetGroupId, assigned.stream().map(Task::getId).toList());
        logs.save(OperationLog.memberGroupChanged(eventId, member.getId(), json.writeValueAsString(changes), Instant.now()));
    }

    private record Changes(UUID previousWorkGroupId, UUID currentWorkGroupId, List<UUID> releasedTaskIds) {}
}

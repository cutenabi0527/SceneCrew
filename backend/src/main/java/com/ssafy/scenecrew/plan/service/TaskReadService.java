package com.ssafy.scenecrew.plan.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.plan.repository.TaskReadQueries;
import com.ssafy.scenecrew.plan.service.InitialAssignmentService.TaskView;
import com.ssafy.scenecrew.event.domain.Member;

/**
 * #281 manual MVP. WORKER current lists contain only unfinished assignments; detail also permits own history.
 * Rejoin blocks remaining unfinished assignments. This does not recover historical assignment rounds.
 */
@Service
public class TaskReadService {
    private final CurrentMembers members;
    private final TaskReadQueries tasks;

    public TaskReadService(CurrentMembers members, TaskReadQueries tasks) {
        this.members = members;
        this.tasks = tasks;
    }

    public record TaskPage(List<TaskView> items, String nextCursor) { }

    @Transactional(readOnly = true)
    public TaskPage list(UUID eventId, UUID accountId, String cursor, Integer limit, TaskCursor codec) {
        int size = limit == null ? 30 : limit;
        if (size < 1 || size > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        try {
            var scope = scope(eventId, accountId, members.require(eventId, accountId));
            var rows = tasks.page(scope, codec.decode(scope, cursor), size + 1);
            if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
            var visible = rows.stream().filter(Objects::nonNull).toList();
            var items = visible.stream().limit(size).toList();
            String next = null;
            if (visible.size() > size) {
                var last = items.getLast();
                next = codec.encode(scope, new TaskCursor.Position(last.startAt(), last.id()));
            }
            return new TaskPage(items, next);
        } catch (DataAccessException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static TaskCursor.Scope scope(UUID eventId, UUID accountId, Member member) {
        return new TaskCursor.Scope(eventId, accountId, member.getId(), member.getParticipationRevision(), member.getEventRole().name());
    }

    @Transactional(readOnly = true)
    public TaskView get(UUID taskId, UUID accountId) {
        try {
            UUID eventId = tasks.eventId(taskId);
            if (eventId == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            var me = members.require(eventId, accountId);
            var rows = tasks.oneForMember(scope(eventId, accountId, me), taskId);
            if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
            return rows.getFirst();
        } catch (DataAccessException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}

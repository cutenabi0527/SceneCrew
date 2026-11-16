package com.ssafy.scenecrew.plan;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.domain.EventRole;
import org.springframework.dao.DataAccessResourceFailureException;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.plan.repository.TaskReadQueries;
import com.ssafy.scenecrew.plan.service.TaskReadService;
import com.ssafy.scenecrew.plan.service.TaskCursor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskReadServiceTest {
    final CurrentMembers members = mock(CurrentMembers.class);
    final TaskReadQueries queries = mock(TaskReadQueries.class);
    final TaskReadService service = new TaskReadService(members, queries);
    final TaskCursor cursor = new TaskCursor(new byte[32]);
    final UUID event = UUID.randomUUID(), account = UUID.randomUUID(), task = UUID.randomUUID();

    @BeforeEach void memberScope() {
        Member me = mock(Member.class);
        when(me.getId()).thenReturn(account);
        when(me.getParticipationRevision()).thenReturn(1L);
        when(me.getEventRole()).thenReturn(EventRole.CHIEF);
        when(members.require(event, account)).thenReturn(me);
    }

    @Test void rejectedMembershipNeverReadsTaskList() {
        when(members.require(event, account)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        code(() -> service.list(event, account, null, null, cursor), ErrorCode.NOT_FOUND);
        verifyNoInteractions(queries);
    }

    @Test void accessLostBetweenMembershipCheckAndListIsNotAnEmptySuccess() {
        when(queries.page(any(), isNull(), eq(31))).thenReturn(List.of());
        code(() -> service.list(event, account, null, null, cursor), ErrorCode.NOT_FOUND);
    }

    @Test void authorizedEmptyEventReturnsAnEmptyPage() {
        when(queries.page(any(), isNull(), eq(31))).thenReturn(Arrays.asList((com.ssafy.scenecrew.plan.service.InitialAssignmentService.TaskView) null));
        var page = service.list(event, account, null, null, cursor);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test void accessLostBetweenMembershipCheckAndDetailFailsClosed() {
        when(queries.eventId(task)).thenReturn(event);
        when(queries.oneForMember(any(), eq(task))).thenReturn(List.of());
        code(() -> service.get(task, account), ErrorCode.NOT_FOUND);
    }

    @Test void nonexistentTaskIsNotFoundWithoutReadingMembership() {
        code(() -> service.get(task, account), ErrorCode.NOT_FOUND);
        verifyNoInteractions(members);
    }

    @Test void storageFailureDoesNotBecomeAnEmptyList() {
        when(queries.page(any(), isNull(), eq(31))).thenThrow(new DataAccessResourceFailureException("synthetic"));
        code(() -> service.list(event, account, null, null, cursor), ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test void detailStorageFailureUsesExistingUnavailableCode() {
        when(queries.eventId(task)).thenThrow(new DataAccessResourceFailureException("synthetic"));
        code(() -> service.get(task, account), ErrorCode.SERVICE_UNAVAILABLE);
    }

    static void code(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}

package com.ssafy.scenecrew.plan.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * SC-API-027 행사 계획 조회(GET /events/{e}/plan). 규칙은 backend/docs/plan-draft.md.
 *
 * <ul>
 *   <li>이 행사의 활성 Member만 본다. 아니면 404(행사가 있다는 것도 알리지 않는다). DRAFT~ENDED 모두 읽을 수 있다.</li>
 *   <li>현재 계획만 보여 준다: 활성 직무 그룹과 취소되지 않은 업무. 구역·프로그램은 전체다.</li>
 *   <li>그룹 안내는 총괄에게 전부, 팀원에게는 본인 그룹 것만 주고 다른 그룹은 null이다.</li>
 *   <li>업무는 공통 요약만 준다. 담당자·절차·점검 항목은 본인 업무 조회(050·051)에서 본다.</li>
 *   <li>행사 안내(event.instructions)가 비어 있지 않으면 EVENT 안내 1개로 준다.</li>
 * </ul>
 */
@Service
public class PlanReadService {
    private final CurrentMembers members;
    private final JdbcTemplate jdbc;

    public PlanReadService(CurrentMembers members, JdbcTemplate jdbc) {
        this.members = members;
        this.jdbc = jdbc;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PlanView(UUID eventId, String eventRevision, String viewerRole, List<GroupView> workGroups,
            List<ZoneView> zones, List<ProgramView> programs, List<TaskSummary> tasks,
            List<InstructionView> instructions) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GroupView(UUID id, String name, String instructions) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ZoneView(UUID id, String name, String description) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProgramView(UUID id, String name, UUID zoneId, Instant startAt, Instant endAt) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TaskSummary(UUID id, UUID workGroupId, String title, Instant startAt, Instant endAt, UUID zoneId,
            UUID programId) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InstructionView(String scope, UUID workGroupId, String body) { }

    /** 027 */
    @Transactional(readOnly = true)
    public PlanView read(UUID eventId, UUID accountId) {
        Member me = members.require(eventId, accountId);
        return project(eventId, me);
    }

    /**
     * 지금 트랜잭션에서 본 계획. 초안 저장(028)이 저장 직후 같은 트랜잭션에서 응답을 만들 때도 쓴다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PlanView project(UUID eventId, Member viewer) {
        boolean chief = viewer.getEventRole() == EventRole.CHIEF;
        var event = jdbc.query("SELECT revision, instructions FROM event WHERE id = ?",
                (r, n) -> new EventRow(r.getLong("revision"), r.getString("instructions")), eventId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        List<GroupView> groups = jdbc.query("""
                SELECT id, name, instructions FROM work_group WHERE event_id = ? AND active ORDER BY name, id""",
                (r, n) -> {
                    UUID id = r.getObject("id", UUID.class);
                    boolean visible = chief || Objects.equals(id, viewer.getWorkGroupId());
                    return new GroupView(id, r.getString("name"), visible ? r.getString("instructions") : null);
                }, eventId);
        List<ZoneView> zones = jdbc.query("SELECT id, name, description FROM zone WHERE event_id = ? ORDER BY name, id",
                (r, n) -> new ZoneView(r.getObject("id", UUID.class), r.getString("name"), r.getString("description")),
                eventId);
        List<ProgramView> programs = jdbc.query("""
                SELECT id, name, zone_id, start_at, end_at FROM program WHERE event_id = ? ORDER BY start_at, id""",
                (r, n) -> new ProgramView(r.getObject("id", UUID.class), r.getString("name"),
                        r.getObject("zone_id", UUID.class), instant(r.getObject("start_at", OffsetDateTime.class)),
                        instant(r.getObject("end_at", OffsetDateTime.class))), eventId);
        List<TaskSummary> tasks = jdbc.query("""
                SELECT t.id, t.work_group_id, t.title, t.start_at, t.end_at, t.zone_id, t.program_id
                  FROM task t JOIN work_group g ON g.id = t.work_group_id AND g.active
                 WHERE t.event_id = ? AND t.status <> 'CANCELED' ORDER BY t.start_at, t.id""",
                (r, n) -> new TaskSummary(r.getObject("id", UUID.class), r.getObject("work_group_id", UUID.class),
                        r.getString("title"), instant(r.getObject("start_at", OffsetDateTime.class)),
                        instant(r.getObject("end_at", OffsetDateTime.class)), r.getObject("zone_id", UUID.class),
                        r.getObject("program_id", UUID.class)), eventId);
        List<InstructionView> instructions = event.instructions() == null || event.instructions().isEmpty()
                ? List.of() : List.of(new InstructionView("EVENT", null, event.instructions()));
        return new PlanView(eventId, Long.toString(event.revision()), chief ? "CHIEF" : "WORKER", groups, zones, programs,
                tasks, instructions);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record EventRow(long revision, String instructions) { }
}

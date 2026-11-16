package com.ssafy.scenecrew.plan.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.text.UnicodeText;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.ChecklistItem;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.Group;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.Program;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.Ref;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.Task;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.Zone;
import com.ssafy.scenecrew.plan.service.PlanReadService.PlanView;

/**
 * SC-API-028 행사 계획 초안 저장(PUT /events/{e}/draft-plan). 규칙은 backend/docs/plan-draft.md.
 *
 * <p>순서: 행사 잠금 → 총괄 확인(팀원 403) → DRAFT(409 INVALID_STATE) → 기대 revision(409 VERSION_CONFLICT) →
 * 참조·이름·시간·묶음 검사(400 INVALID_REQUEST) → 지울 항목의 바깥 참조 검사(409 INVALID_STATE) → 저장.
 * 잠금 순서는 다른 행사 쓰기와 같은 Event → Member → 계획 행이다. 모두 한 트랜잭션이라 실패하면 아무것도 바뀌지 않는다.
 *
 * <p>요청 배열이 그룹·구역·프로그램·업무 전체다. 요청에 없는 기존 항목은 지운다. 실제로 바뀐 것이 있을 때만 revision을 1 올린다.
 * 확인한 입력(026)이 아니므로 last_input_modified_at은 바꾸지 않는다.
 */
@Service
public class DraftPlanService {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** 이름 맞바꾸기 중 (event_id, name) 유일 제약을 피하려고 잠깐 쓰는 이름. id가 들어가 겹치지 않는다. */
    private static final String RENAMING = "⁣draft-renaming:";

    private final CurrentMembers members;
    private final PlanReadService plans;
    private final JdbcTemplate jdbc;

    public DraftPlanService(CurrentMembers members, PlanReadService plans, JdbcTemplate jdbc) {
        this.members = members;
        this.plans = plans;
        this.jdbc = jdbc;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftPlanSaved(PlanView plan, List<IdMapping> idMappings) { }

    public record IdMapping(String tempId, UUID id) { }

    @Transactional
    public DraftPlanSaved save(UUID eventId, UUID accountId, DraftPlanCommand command) {
        EventRow event = lockEvent(eventId);
        Member chief = members.requireChiefLocked(eventId, accountId);
        if (!"DRAFT".equals(event.state())) throw new BusinessException(ErrorCode.INVALID_STATE);
        if (event.revision() != command.expectedEventRevision()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                    Map.of("target", "event"));
        }

        Current current = loadCurrent(eventId);
        Resolved resolved = resolve(command, current);
        requireNoOutsideReferences(eventId, resolved);
        boolean changed = write(eventId, command, current, resolved);

        String instructions = command.eventInstructions() == null ? "" : command.eventInstructions();
        if (!instructions.equals(event.instructions())) {
            jdbc.update("UPDATE event SET instructions = ? WHERE id = ?", instructions, eventId);
            changed = true;
        }
        if (changed) jdbc.update("UPDATE event SET revision = revision + 1 WHERE id = ?", eventId);
        return new DraftPlanSaved(plans.project(eventId, chief), resolved.mappings());
    }

    // ---- 현재 계획 -----------------------------------------------------------------------------------------------

    private record EventRow(String state, long revision, String instructions) { }

    private record GroupRow(UUID id, String name, String instructions) { }

    private record ZoneRow(UUID id, String name, String description) { }

    private record ProgramRow(UUID id, String name, UUID zoneId, Instant startAt, Instant endAt) { }

    private record TaskRow(UUID id, UUID groupId, String bundleKey, UUID zoneId, UUID programId, String title,
            Instant startAt, Instant endAt, String steps, String checklist) { }

    private record Current(Map<UUID, GroupRow> groups, Map<UUID, ZoneRow> zones, Map<UUID, ProgramRow> programs,
            Map<UUID, TaskRow> tasks) { }

    private EventRow lockEvent(UUID eventId) {
        return jdbc.query("SELECT state, revision, instructions FROM event WHERE id = ? FOR UPDATE",
                (r, n) -> new EventRow(r.getString("state"), r.getLong("revision"), r.getString("instructions")), eventId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    /** 행사를 잠근 뒤 읽으므로 다른 계획 쓰기(같은 Event 잠금을 먼저 잡는다)와 섞이지 않는다. */
    private Current loadCurrent(UUID eventId) {
        Map<UUID, GroupRow> groups = new LinkedHashMap<>();
        jdbc.query("SELECT id, name, instructions FROM work_group WHERE event_id = ?", r -> {
            UUID id = r.getObject("id", UUID.class);
            groups.put(id, new GroupRow(id, r.getString("name"), r.getString("instructions")));
        }, eventId);
        Map<UUID, ZoneRow> zones = new LinkedHashMap<>();
        jdbc.query("SELECT id, name, description FROM zone WHERE event_id = ?", r -> {
            UUID id = r.getObject("id", UUID.class);
            zones.put(id, new ZoneRow(id, r.getString("name"), r.getString("description")));
        }, eventId);
        Map<UUID, ProgramRow> programs = new LinkedHashMap<>();
        jdbc.query("SELECT id, name, zone_id, start_at, end_at FROM program WHERE event_id = ?", r -> {
            UUID id = r.getObject("id", UUID.class);
            programs.put(id, new ProgramRow(id, r.getString("name"), r.getObject("zone_id", UUID.class),
                    instant(r.getObject("start_at", OffsetDateTime.class)), instant(r.getObject("end_at", OffsetDateTime.class))));
        }, eventId);
        Map<UUID, TaskRow> tasks = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, work_group_id, bundle_key, zone_id, program_id, title, start_at, end_at, steps::text AS steps,
                       checklist::text AS checklist
                  FROM task WHERE event_id = ?""", r -> {
            UUID id = r.getObject("id", UUID.class);
            tasks.put(id, new TaskRow(id, r.getObject("work_group_id", UUID.class), r.getString("bundle_key"),
                    r.getObject("zone_id", UUID.class), r.getObject("program_id", UUID.class), r.getString("title"),
                    instant(r.getObject("start_at", OffsetDateTime.class)), instant(r.getObject("end_at", OffsetDateTime.class)),
                    r.getString("steps"), r.getString("checklist")));
        }, eventId);
        return new Current(groups, zones, programs, tasks);
    }

    // ---- 의미 검사 -----------------------------------------------------------------------------------------------

    /**
     * 요청의 각 항목에 최종 id를 정하고(기존 id 또는 새 UUID), 참조를 그 id로 바꾼다.
     *
     * @param ids 배열별 최종 id. 요청 순서와 같다.
     */
    private record Resolved(List<UUID> groupIds, List<UUID> zoneIds, List<UUID> programIds, List<UUID> taskIds,
            Map<UUID, UUID> programZone, Map<UUID, UUID> taskGroup, Map<UUID, UUID> taskZone, Map<UUID, UUID> taskProgram,
            Set<UUID> removedGroups, Set<UUID> removedZones, Set<UUID> removedPrograms, Set<UUID> removedTasks,
            List<IdMapping> mappings) { }

    private Resolved resolve(DraftPlanCommand command, Current current) {
        Set<String> temps = new HashSet<>();
        List<IdMapping> mappings = new ArrayList<>();
        Map<String, UUID> groupRefs = new HashMap<>();
        Map<String, UUID> zoneRefs = new HashMap<>();
        Map<String, UUID> programRefs = new HashMap<>();
        Map<String, UUID> taskRefs = new HashMap<>();
        List<UUID> groupIds = keys(command.workGroups(), Group::key, "workGroups", current.groups().keySet(), temps,
                mappings, groupRefs);
        List<UUID> zoneIds = keys(command.zones(), Zone::key, "zones", current.zones().keySet(), temps, mappings, zoneRefs);
        List<UUID> programIds = keys(command.programs(), Program::key, "programs", current.programs().keySet(), temps,
                mappings, programRefs);
        List<UUID> taskIds = keys(command.tasks(), Task::key, "tasks", current.tasks().keySet(), temps, mappings, taskRefs);

        // 구역·직무 그룹 이름은 모든 공백과 대소문자를 무시하고 행사 안에서 겹치면 안 된다(V9-D22). 표시 원문은 그대로 저장한다.
        uniqueNames(command.workGroups(), Group::name, "workGroups");
        uniqueNames(command.zones(), Zone::name, "zones");

        Map<UUID, UUID> programZone = new HashMap<>();
        for (int i = 0; i < command.programs().size(); i++) {
            Program p = command.programs().get(i);
            String path = "programs[" + i + "]";
            if (p.zone() != null) programZone.put(programIds.get(i), target(p.zone(), zoneRefs, path + ".zoneId"));
            if (!p.startAt().isBefore(p.endAt())) throw DraftPlanCommand.invalid(path + ".endAt", "TIME_ORDER");
        }

        Map<UUID, UUID> taskGroup = new HashMap<>();
        Map<UUID, UUID> taskZone = new HashMap<>();
        Map<UUID, UUID> taskProgram = new HashMap<>();
        Map<UUID, Program> programsById = new HashMap<>();
        for (int i = 0; i < command.programs().size(); i++) programsById.put(programIds.get(i), command.programs().get(i));
        for (int i = 0; i < command.tasks().size(); i++) {
            Task t = command.tasks().get(i);
            String path = "tasks[" + i + "]";
            UUID id = taskIds.get(i);
            taskGroup.put(id, target(t.workGroup(), groupRefs, path + ".workGroupId"));
            UUID zone = target(t.zone(), zoneRefs, path + ".zoneId");
            taskZone.put(id, zone);
            if (!t.startAt().isBefore(t.endAt())) throw DraftPlanCommand.invalid(path + ".endAt", "TIME_ORDER");
            if (t.program() != null) {
                UUID program = target(t.program(), programRefs, path + ".programId");
                taskProgram.put(id, program);
                // 프로그램에 구역이 있으면 같은 구역이고 시간 구간이 겹쳐야 한다(be/04, 변경안·공개 검사와 같은 규칙).
                Program p = programsById.get(program);
                UUID programZoneId = programZone.get(program);
                if ((programZoneId != null && !programZoneId.equals(zone))
                        || !t.startAt().isBefore(p.endAt()) || !p.startAt().isBefore(t.endAt()))
                    throw DraftPlanCommand.invalid(path + ".programId", "PROGRAM_CONTEXT");
            }
        }
        bundles(command.tasks(), taskIds, taskGroup);

        return new Resolved(groupIds, zoneIds, programIds, taskIds, programZone, taskGroup, taskZone, taskProgram,
                removed(current.groups().keySet(), groupIds), removed(current.zones().keySet(), zoneIds),
                removed(current.programs().keySet(), programIds), removed(current.tasks().keySet(), taskIds),
                List.copyOf(mappings));
    }

    /**
     * 기존 id는 이 행사의 같은 종류여야 하고 배열 안에서 한 번만 나온다. tempId는 요청 전체에서 한 번만 나오고 새 UUID를 받는다.
     * refs에는 참조 값(UUID 문자열 또는 tempId) → 최종 id를 담는다.
     */
    private static <T> List<UUID> keys(List<T> items, Function<T, Ref> key, String array, Set<UUID> existing,
            Set<String> temps, List<IdMapping> mappings, Map<String, UUID> refs) {
        List<UUID> ids = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            Ref ref = key.apply(items.get(i));
            String path = array + "[" + i + "]";
            UUID id;
            if (ref.isTemp()) {
                if (!temps.add(ref.tempId())) throw DraftPlanCommand.invalid(path + ".tempId", "DUPLICATE");
                id = UUID.randomUUID();
                mappings.add(new IdMapping(ref.tempId(), id));
                refs.put(ref.tempId(), id);
            } else {
                id = ref.id();
                if (!existing.contains(id)) throw DraftPlanCommand.invalid(path + ".id", "INVALID_REFERENCE");
                if (!seen.add(id)) throw DraftPlanCommand.invalid(path + ".id", "DUPLICATE");
                refs.put(id.toString(), id);
            }
            ids.add(id);
        }
        return ids;
    }

    /** 참조는 같은 요청 안의 항목만 가리킨다. 요청에서 빠진 기존 항목은 지워지므로 가리킬 수 없다. */
    private static UUID target(Ref ref, Map<String, UUID> refs, String path) {
        UUID id = refs.get(ref.isTemp() ? ref.tempId() : ref.id().toString());
        if (id == null) throw DraftPlanCommand.invalid(path, "INVALID_REFERENCE");
        return id;
    }

    private static <T> void uniqueNames(List<T> items, Function<T, String> name, String array) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            if (!keys.add(UnicodeText.nameKey(name.apply(items.get(i)))))
                throw DraftPlanCommand.invalid(array + "[" + i + "].name", "DUPLICATE");
        }
    }

    /** 한 묶음은 한 사람이 맡으므로 같은 그룹이고 안에서 시간이 겹치지 않는다(공개 검사와 같은 규칙). */
    private static void bundles(List<Task> tasks, List<UUID> taskIds, Map<UUID, UUID> taskGroup) {
        Map<String, List<Integer>> byBundle = new LinkedHashMap<>();
        for (int i = 0; i < tasks.size(); i++) byBundle.computeIfAbsent(tasks.get(i).bundleKey(), k -> new ArrayList<>()).add(i);
        for (List<Integer> bundle : byBundle.values()) {
            UUID group = taskGroup.get(taskIds.get(bundle.getFirst()));
            for (int i : bundle) {
                if (!taskGroup.get(taskIds.get(i)).equals(group))
                    throw DraftPlanCommand.invalid("tasks[" + i + "].bundleKey", "BUNDLE_MISMATCH");
            }
            List<Integer> ordered = new ArrayList<>(bundle);
            ordered.sort(Comparator.comparing((Integer i) -> tasks.get(i).startAt()).thenComparing(i -> i));
            Instant lastEnd = null;
            for (int i : ordered) {
                Task t = tasks.get(i);
                if (lastEnd != null && t.startAt().isBefore(lastEnd))
                    throw DraftPlanCommand.invalid("tasks[" + i + "].bundleKey", "TIME_OVERLAP");
                if (lastEnd == null || t.endAt().isAfter(lastEnd)) lastEnd = t.endAt();
            }
        }
    }

    private static Set<UUID> removed(Set<UUID> existing, List<UUID> kept) {
        Set<UUID> out = new HashSet<>(existing);
        kept.forEach(out::remove);
        return out;
    }

    // ---- 바깥 참조 --------------------------------------------------------------------------------------------------

    /**
     * 지울 항목을 계획 밖(장비·안전·보고·알림·채팅·참여·운영 관계)에서 쓰고 있으면 409로 거절한다(오세진 결정, plan-draft.md).
     * 다른 기능의 데이터를 조용히 지우거나 비우지 않는다. 외래키 칸과, id를 JSON으로 담는 칸을 모두 본다.
     */
    private void requireNoOutsideReferences(UUID eventId, Resolved r) {
        List<Map<String, String>> found = new ArrayList<>();
        check(found, "zones", r.removedZones(), eventId, Map.of(
                "equipment_asset", "SELECT storage_zone_id FROM equipment_asset WHERE event_id = ? AND storage_zone_id = ANY(?)",
                "safety_zone_profile", "SELECT zone_id FROM safety_zone_profile WHERE event_id = ? AND zone_id = ANY(?)",
                "issue", "SELECT zone_id FROM issue WHERE event_id = ? AND zone_id = ANY(?)"));
        checkJson(found, "zones", r.removedZones(), eventId, List.of("venue_layout.zone_polygons",
                "safety_camera.coverage", "safety_observation.coverage", "safety_observation.estimates"));
        check(found, "programs", r.removedPrograms(), eventId, Map.of(
                "equipment_requirement", "SELECT program_id FROM equipment_requirement WHERE event_id = ? AND program_id = ANY(?)",
                "equipment_reservation", "SELECT program_id FROM equipment_reservation WHERE event_id = ? AND program_id = ANY(?)",
                "operational_dependency", """
                        SELECT x FROM (SELECT from_id AS x FROM operational_dependency WHERE event_id = ? AND from_kind = 'PROGRAM'
                                       UNION ALL
                                       SELECT to_id FROM operational_dependency WHERE event_id = ? AND to_kind = 'PROGRAM') d
                         WHERE x = ANY(?)"""));
        check(found, "tasks", r.removedTasks(), eventId, Map.of(
                "equipment_reservation", "SELECT task_id FROM equipment_reservation WHERE event_id = ? AND task_id = ANY(?)",
                "issue", "SELECT task_id FROM issue WHERE event_id = ? AND task_id = ANY(?)",
                "notification", "SELECT task_id FROM notification WHERE event_id = ? AND task_id = ANY(?)",
                "operational_dependency", """
                        SELECT x FROM (SELECT from_id AS x FROM operational_dependency WHERE event_id = ? AND from_kind = 'TASK'
                                       UNION ALL
                                       SELECT to_id FROM operational_dependency WHERE event_id = ? AND to_kind = 'TASK') d
                         WHERE x = ANY(?)"""));
        checkJson(found, "tasks", r.removedTasks(), eventId, List.of("equipment_requirement.task_ids"));
        check(found, "workGroups", r.removedGroups(), eventId, Map.of(
                "member", "SELECT work_group_id FROM member WHERE event_id = ? AND work_group_id = ANY(?)",
                "chat_message", "SELECT work_group_id FROM chat_message WHERE event_id = ? AND work_group_id = ANY(?)",
                "issue", "SELECT work_group_id FROM issue WHERE event_id = ? AND work_group_id = ANY(?)"));
        if (!found.isEmpty()) {
            found.sort(Comparator.comparing((Map<String, String> m) -> m.get("resource")).thenComparing(m -> m.get("id"))
                    .thenComparing(m -> m.get("referencedBy")));
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "다른 기능에서 쓰고 있는 항목은 지울 수 없습니다. 먼저 그 기능에서 연결을 바꿔 주세요.",
                    Map.of("references", List.copyOf(found)));
        }
    }

    private void check(List<Map<String, String>> found, String resource, Set<UUID> ids, UUID eventId,
            Map<String, String> queries) {
        if (ids.isEmpty()) return;
        UUID[] array = ids.toArray(UUID[]::new);
        for (var query : queries.entrySet()) {
            boolean twoEvents = query.getKey().equals("operational_dependency");
            Object[] args = twoEvents ? new Object[] {eventId, eventId, array} : new Object[] {eventId, array};
            Set<UUID> hits = new HashSet<>(jdbc.queryForList(query.getValue(), UUID.class, args));
            for (UUID id : hits) found.add(reference(resource, id, query.getKey()));
        }
    }

    /** id를 JSON 안에 담는 칸. UUID 문자열은 행사 안에서 겹치지 않으므로 글자 포함으로 찾는다. 계획 초안 단계에서는 보통 비어 있다. */
    private void checkJson(List<Map<String, String>> found, String resource, Set<UUID> ids, UUID eventId,
            List<String> columns) {
        if (ids.isEmpty()) return;
        for (String column : columns) {
            String[] parts = column.split("\\.");
            String sql = "SELECT EXISTS (SELECT 1 FROM " + parts[0] + " WHERE event_id = ? AND " + parts[1]
                    + "::text LIKE ?)";
            for (UUID id : ids) {
                if (Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, eventId, "%" + id + "%")))
                    found.add(reference(resource, id, parts[0]));
            }
        }
    }

    private static Map<String, String> reference(String resource, UUID id, String referencedBy) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("resource", resource);
        item.put("id", id.toString());
        item.put("referencedBy", referencedBy);
        return item;
    }

    // ---- 저장 ----------------------------------------------------------------------------------------------------

    /**
     * 외래키와 (event_id, name) 유일 제약을 지키는 순서로 저장한다. 바뀐 것이 있으면 true.
     * <ol>
     *   <li>지울 업무를 먼저 지운다.</li>
     *   <li>지울 그룹·구역과 이름이 바뀌는 그룹·구역에 잠깐 임시 이름을 준다(이름 맞바꾸기·재사용 대비).</li>
     *   <li>그룹·구역 → 프로그램 → 업무 순으로 추가·수정한다.</li>
     *   <li>지울 프로그램 → 구역 → 그룹을 지운다. 이때는 남은 행이 더 이상 가리키지 않는다.</li>
     * </ol>
     */
    private boolean write(UUID eventId, DraftPlanCommand command, Current current, Resolved r) {
        boolean changed = false;
        if (!r.removedTasks().isEmpty()) {
            jdbc.update("DELETE FROM task WHERE event_id = ? AND id = ANY(?)", eventId, r.removedTasks().toArray(UUID[]::new));
            changed = true;
        }

        Set<UUID> renamingGroups = new HashSet<>(r.removedGroups());
        for (int i = 0; i < command.workGroups().size(); i++) {
            GroupRow before = current.groups().get(r.groupIds().get(i));
            if (before != null && !before.name().equals(command.workGroups().get(i).name())) renamingGroups.add(before.id());
        }
        Set<UUID> renamingZones = new HashSet<>(r.removedZones());
        for (int i = 0; i < command.zones().size(); i++) {
            ZoneRow before = current.zones().get(r.zoneIds().get(i));
            if (before != null && !before.name().equals(command.zones().get(i).name())) renamingZones.add(before.id());
        }
        for (UUID id : renamingGroups) jdbc.update("UPDATE work_group SET name = ? WHERE id = ?", RENAMING + id, id);
        for (UUID id : renamingZones) jdbc.update("UPDATE zone SET name = ? WHERE id = ?", RENAMING + id, id);

        for (int i = 0; i < command.workGroups().size(); i++) {
            Group g = command.workGroups().get(i);
            UUID id = r.groupIds().get(i);
            GroupRow before = current.groups().get(id);
            if (before == null) {
                jdbc.update("INSERT INTO work_group (id, event_id, name, instructions, active) VALUES (?, ?, ?, ?, true)",
                        id, eventId, g.name(), g.instructions());
                changed = true;
            } else if (renamingGroups.contains(id) || !before.instructions().equals(g.instructions())) {
                jdbc.update("UPDATE work_group SET name = ?, instructions = ? WHERE id = ?", g.name(), g.instructions(), id);
                changed = true;
            }
        }
        for (int i = 0; i < command.zones().size(); i++) {
            Zone z = command.zones().get(i);
            UUID id = r.zoneIds().get(i);
            ZoneRow before = current.zones().get(id);
            if (before == null) {
                jdbc.update("INSERT INTO zone (id, event_id, name, description, aliases) VALUES (?, ?, ?, ?, '[]'::jsonb)",
                        id, eventId, z.name(), z.description());
                changed = true;
            } else if (renamingZones.contains(id) || !Objects.equals(before.description(), z.description())) {
                jdbc.update("UPDATE zone SET name = ?, description = ? WHERE id = ?", z.name(), z.description(), id);
                changed = true;
            }
        }
        for (int i = 0; i < command.programs().size(); i++) {
            Program p = command.programs().get(i);
            UUID id = r.programIds().get(i);
            UUID zone = r.programZone().get(id);
            ProgramRow before = current.programs().get(id);
            if (before == null) {
                jdbc.update("INSERT INTO program (id, event_id, zone_id, name, start_at, end_at) VALUES (?, ?, ?, ?, ?, ?)",
                        id, eventId, zone, p.name(), ts(p.startAt()), ts(p.endAt()));
                changed = true;
            } else if (!before.name().equals(p.name()) || !Objects.equals(before.zoneId(), zone)
                    || !before.startAt().equals(p.startAt()) || !before.endAt().equals(p.endAt())) {
                jdbc.update("UPDATE program SET zone_id = ?, name = ?, start_at = ?, end_at = ? WHERE id = ?",
                        zone, p.name(), ts(p.startAt()), ts(p.endAt()), id);
                changed = true;
            }
        }
        for (int i = 0; i < command.tasks().size(); i++) {
            Task t = command.tasks().get(i);
            UUID id = r.taskIds().get(i);
            UUID group = r.taskGroup().get(id);
            UUID zone = r.taskZone().get(id);
            UUID program = r.taskProgram().get(id);
            String steps = MAPPER.writeValueAsString(t.steps());
            TaskRow before = current.tasks().get(id);
            if (before == null) {
                jdbc.update("""
                        INSERT INTO task (id, event_id, work_group_id, bundle_key, assignee_id, zone_id, program_id, source_issue_id,
                                          title, start_at, end_at, steps, checklist, status, first_assigned_at)
                        VALUES (?, ?, ?, ?, NULL, ?, ?, NULL, ?, ?, ?, ?::jsonb, ?::jsonb, 'WAITING', NULL)""",
                        id, eventId, group, t.bundleKey(), zone, program, t.title(), ts(t.startAt()), ts(t.endAt()), steps,
                        checklistJson(t.checklist(), null));
                changed = true;
            } else {
                String checklist = checklistJson(t.checklist(), before.checklist());
                if (!before.groupId().equals(group) || !before.bundleKey().equals(t.bundleKey())
                        || !before.zoneId().equals(zone) || !Objects.equals(before.programId(), program)
                        || !before.title().equals(t.title()) || !before.startAt().equals(t.startAt())
                        || !before.endAt().equals(t.endAt()) || !sameJson(before.steps(), steps)
                        || !sameJson(before.checklist(), checklist)) {
                    jdbc.update("""
                            UPDATE task SET work_group_id = ?, bundle_key = ?, zone_id = ?, program_id = ?, title = ?,
                                            start_at = ?, end_at = ?, steps = ?::jsonb, checklist = ?::jsonb
                             WHERE id = ?""",
                            group, t.bundleKey(), zone, program, t.title(), ts(t.startAt()), ts(t.endAt()), steps, checklist, id);
                    changed = true;
                }
            }
        }

        if (!r.removedPrograms().isEmpty()) {
            jdbc.update("DELETE FROM program WHERE event_id = ? AND id = ANY(?)", eventId,
                    r.removedPrograms().toArray(UUID[]::new));
            changed = true;
        }
        if (!r.removedZones().isEmpty()) {
            jdbc.update("DELETE FROM zone WHERE event_id = ? AND id = ANY(?)", eventId, r.removedZones().toArray(UUID[]::new));
            changed = true;
        }
        if (!r.removedGroups().isEmpty()) {
            jdbc.update("DELETE FROM work_group WHERE event_id = ? AND id = ANY(?)", eventId,
                    r.removedGroups().toArray(UUID[]::new));
            changed = true;
        }
        return changed;
    }

    /** 점검 항목: 남은 key는 수행값(checked·checkedAt·checkedByMemberId)을 유지하고 새 key는 미체크(변경안 엔진과 같은 모양). */
    private static String checklistJson(List<ChecklistItem> items, String previous) {
        Map<String, JsonNode> old = new LinkedHashMap<>();
        if (previous != null) for (JsonNode o : MAPPER.readTree(previous)) old.put(o.path("key").asString(), o);
        ArrayNode out = MAPPER.createArrayNode();
        for (ChecklistItem item : items) {
            ObjectNode o = out.addObject();
            o.put("key", item.key());
            o.put("text", item.text());
            o.put("required", item.required());
            JsonNode kept = old.get(item.key());
            o.put("checked", kept != null && kept.path("checked").asBoolean(false));
            JsonNode checkedAt = kept == null ? null : kept.get("checkedAt");
            JsonNode checkedBy = kept == null ? null : kept.get("checkedByMemberId");
            o.set("checkedAt", checkedAt == null ? MAPPER.nullNode() : checkedAt);
            o.set("checkedByMemberId", checkedBy == null ? MAPPER.nullNode() : checkedBy);
        }
        return MAPPER.writeValueAsString(out);
    }

    private static boolean sameJson(String a, String b) {
        return MAPPER.readTree(a).equals(MAPPER.readTree(b));
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}

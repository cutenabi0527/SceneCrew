package com.ssafy.scenecrew.change.service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.ssafy.scenecrew.change.dto.ChangeResponses.DiffItem;
import com.ssafy.scenecrew.equipment.service.EquipmentViews;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.text.UnicodeText;
import com.ssafy.scenecrew.plan.service.AssignmentChecks;

/**
 * 변경안 연산을 순서대로 실제 테이블에 반영한다(#283). 미리보기(069)는 저장점 안에서 반영해 diff를 얻은 뒤 되돌리고,
 * 적용(070)은 그대로 커밋한다. 같은 코드가 두 경로의 검사와 결과를 만든다.
 *
 * <ul>
 *   <li>새로 만드는 업무·그룹의 ID는 (변경안 ID, 연산 순서)로 정해 미리보기와 적용의 diff가 같다.</li>
 *   <li>앞 연산의 결과를 뒤 연산이 본다(예: 추가한 업무를 같은 변경안에서 배정).</li>
 *   <li>담당 업무 시간이 겹치면 422 TIME_OVERLAP, 이 행사에 없는 참조는 422 INVALID_REFERENCE,
 *       이미 끝난·취소된 업무를 바꾸면 409 INVALID_STATE, 이전 담당자 기대가 다르면 409 VERSION_CONFLICT(task).</li>
 * </ul>
 */
@Component
public class OperationEngine {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(KST);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final int MAX_TASKS = 200;
    static final int MAX_WORK_GROUPS = 31;

    private final JdbcTemplate jdbc;
    private final AssignmentChecks assignmentChecks;

    public OperationEngine(JdbcTemplate jdbc, AssignmentChecks assignmentChecks) {
        this.jdbc = jdbc;
        this.assignmentChecks = assignmentChecks;
    }

    /** 반영 결과. targets는 Member마다 업무별 변경 종류다(076·077 확인 대상). */
    public record Outcome(List<DiffItem> diff, Map<UUID, Map<UUID, Target>> targets, Map<UUID, Set<UUID>> affected) { }

    /** 한 Member가 확인할 한 업무의 변경 */
    public static final class Target {
        final String title;
        final Set<String> kinds = new LinkedHashSet<>();
        boolean removed;
        Target(String title) { this.title = title; }
        public String title() { return title; }
        public Set<String> kinds() { return kinds; }
        public boolean removed() { return removed; }
    }

    private record Task(UUID id, UUID workGroupId, UUID assigneeId, UUID zoneId, UUID programId, String title,
            Instant startAt, Instant endAt, String steps, String checklist, String status) { }

    /**
     * 연산을 반영한다. 호출하는 쪽의 트랜잭션 안에서 부른다.
     *
     * @param actorMemberId 기록(OperationLog)에 남길 총괄
     */
    public Outcome apply(UUID eventId, UUID changeSetId, UUID actorMemberId, ArrayNode operations, Instant now) {
        List<DiffItem> diff = new ArrayList<>();
        Map<UUID, Map<UUID, Target>> targets = new LinkedHashMap<>();
        Map<UUID, Task> originals = new LinkedHashMap<>();
        Set<UUID> changedTasks = new LinkedHashSet<>();
        Set<UUID> changedPrograms = new LinkedHashSet<>();
        Map<String, UUID> tempTasks = new LinkedHashMap<>();
        for (int i = 0; i < operations.size(); i++) {
            JsonNode op = operations.get(i);
            String type = op.get("type").asString();
            JsonNode p = op.get("payload");
            String field = "operations[" + i + "].payload.";
            if (Set.of("UPDATE_TASK", "ASSIGN_TASK", "CANCEL_TASK").contains(type)) {
                UUID taskId = uuid(p.get("taskId"));
                if (!originals.containsKey(taskId)) originals.put(taskId, task(eventId, taskId, field + "taskId"));
            }
            DiffItem item = switch (type) {
                case "ADD_TASK" -> addTask(eventId, changeSetId, i, p, field, now, targets);
                case "UPDATE_TASK" -> updateTask(eventId, i, p, field, targets);
                case "ASSIGN_TASK" -> assignTask(eventId, i, p, field, now, targets);
                case "CANCEL_TASK" -> cancelTask(eventId, i, p, field, targets);
                case "UPDATE_PROGRAM" -> updateProgram(eventId, i, p, field);
                case "UPSERT_WORK_GROUP" -> upsertGroup(eventId, changeSetId, i, p, field);
                case "DEACTIVATE_WORK_GROUP" -> deactivateGroup(eventId, i, p, field);
                case "UPDATE_INSTRUCTIONS" -> updateInstructions(eventId, i, p, field);
                case "RESERVE_EQUIPMENT" -> reserve(eventId, changeSetId, i, p, field, tempTasks);
                case "UPDATE_EQUIPMENT_RESERVATION" -> updateReservation(eventId, i, p, field, tempTasks);
                case "CANCEL_EQUIPMENT_RESERVATION" -> cancelReservation(eventId, i, p, field);
                default -> throw new IllegalStateException("검사하지 않은 연산: " + type);
            };
            diff.add(item);
            if ("ADD_TASK".equals(type)) {
                originals.put(item.targetId(), null);
                tempTasks.put(p.get("tempId").asString(), item.targetId());
            }
            if ("ADD_TASK".equals(type) || "UPDATE_TASK".equals(type)) changedTasks.add(item.targetId());
            if ("UPDATE_PROGRAM".equals(type)) changedPrograms.add(item.targetId());
            jdbc.update("""
                    INSERT INTO operation_log (id, event_id, type, actor_member_id, resource_type, resource_id, changes, reason,
                                               change_set_id, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""",
                    UUID.randomUUID(), eventId, type, actorMemberId, resourceType(type),
                    item.targetId() == null ? eventId : item.targetId(), MAPPER.writeValueAsString(p),
                    p.has("reason") ? p.get("reason").asString() : null, changeSetId, Timestamp.from(now));
        }
        // 연관 프로그램과 업무를 함께 옮기는 변경안도 있으므로 최종 연산 결과에서 관계를 검사한다.
        for (UUID program : changedPrograms)
            changedTasks.addAll(jdbc.queryForList("SELECT id FROM task WHERE event_id = ? AND program_id = ?",
                    UUID.class, eventId, program));
        if (!changedTasks.isEmpty()) {
            requireProgramContext(eventId, changedTasks);
            requireEventWindow(eventId, changedTasks, "task", "prep_start_at", "teardown_end_at");
        }
        if (!changedPrograms.isEmpty())
            requireEventWindow(eventId, changedPrograms, "program", "open_at", "close_at");
        // 같은 Task의 여러 연산은 최종 상태로 판단한다. preview에서 외부 효과를 만들지 않는다.
        Map<UUID, Map<UUID, Target>> finalTargets = new LinkedHashMap<>();
        Map<UUID, Set<UUID>> affected = new LinkedHashMap<>();
        originals.forEach((id, before) -> {
            Task after = task(eventId, id, "taskId");
            Set<String> kinds = new LinkedHashSet<>();
            if (before == null) {
                kinds.add("ADDED");
                if ("CANCELED".equals(after.status())) kinds.add("CANCELED");
            } else {
                if (!Objects.equals(before.assigneeId(), after.assigneeId())) kinds.add("ASSIGNEE");
                if (!before.startAt().equals(after.startAt()) || !before.endAt().equals(after.endAt())) kinds.add("TIME");
                if (!before.zoneId().equals(after.zoneId())) kinds.add("ZONE");
                if (!sameJson(before.steps(), after.steps())) kinds.add("STEPS");
                if (!sameJson(before.checklist(), after.checklist())) kinds.add("CHECKLIST");
                if (!before.status().equals(after.status()) && "CANCELED".equals(after.status())) kinds.add("CANCELED");
            }
            // 제목·프로그램 연결은 과거 pending만 무효화하며 새 공식 확인은 요구하지 않는다.
            boolean metadataChanged = before != null && (!before.title().equals(after.title())
                    || !Objects.equals(before.programId(), after.programId()));
            if (kinds.isEmpty() && !metadataChanged) return;
            Set<UUID> members = new LinkedHashSet<>();
            if (before != null && before.assigneeId() != null) members.add(before.assigneeId());
            if (after.assigneeId() != null) members.add(after.assigneeId());
            for (UUID member : members) {
                affected.computeIfAbsent(member, ignored -> new LinkedHashSet<>()).add(id);
                if (kinds.isEmpty()) continue;
                boolean removed = !member.equals(after.assigneeId()) || "CANCELED".equals(after.status());
                Target t = target(finalTargets, member, id, removed && before != null ? before.title() : after.title());
                t.removed = removed;
                // 이전 담당자에게는 당시 업무명·해제 정보만 제공한다.
                if (removed && kinds.contains("ASSIGNEE")) t.kinds.add("ASSIGNEE");
                else t.kinds.addAll(kinds);
            }
        });
        return new Outcome(diff, finalTargets, affected);
    }

    // ---- 업무 ----------------------------------------------------------------------------------------------------

    private DiffItem addTask(UUID eventId, UUID changeSetId, int index, JsonNode p, String field, Instant now,
            Map<UUID, Map<UUID, Target>> targets) {
        UUID group = uuid(p.get("workGroupId"));
        if (!exists("SELECT 1 FROM work_group WHERE id = ? AND event_id = ? AND active", group, eventId))
            throw reference(field + "workGroupId");
        UUID zone = uuid(p.get("zoneId"));
        requireZone(eventId, zone, field + "zoneId");
        UUID program = uuidOrNull(p.get("programId"));
        if (program != null && !exists("SELECT 1 FROM program WHERE id = ? AND event_id = ?", program, eventId))
            throw reference(field + "programId");
        Instant start = Operations.instant(p.get("startAt"));
        Instant end = Operations.instant(p.get("endAt"));
        UUID assignee = uuidOrNull(p.get("assigneeId"));
        String bundleKey = p.get("bundleKey").asString();
        if (exists("SELECT 1 FROM task WHERE event_id = ? AND bundle_key = ? AND work_group_id <> ?",
                eventId, bundleKey, group)) throw reference(field + "bundleKey");
        if (assignee != null) {
            requireAssignable(eventId, assignee, group, field + "assigneeId");
            requireNoOverlap(eventId, assignee, null, start, end);
        } else if (exists("SELECT 1 FROM task WHERE event_id = ? AND bundle_key = ? AND first_assigned_at IS NOT NULL",
                eventId, bundleKey)) {
            // 이미 처음 배정한 묶음에 미배정 업무를 섞지 않는다(be/04 R03).
            throw reference(field + "bundleKey");
        }
        Long count = jdbc.queryForObject("SELECT count(*) FROM task WHERE event_id = ?", Long.class, eventId);
        if (count != null && count >= MAX_TASKS)
            throw new BusinessException(ErrorCode.INVALID_STATE, "행사의 업무가 상한(200)에 이르렀습니다.");
        UUID id = derivedId(changeSetId, index);
        String title = p.get("title").asString();
        jdbc.update("""
                INSERT INTO task (id, event_id, work_group_id, bundle_key, assignee_id, zone_id, program_id, source_issue_id,
                                  title, start_at, end_at, steps, checklist, status, first_assigned_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?::jsonb, ?::jsonb, 'WAITING', ?)""",
                id, eventId, group, bundleKey, assignee, zone, program, title, Timestamp.from(start), Timestamp.from(end),
                MAPPER.writeValueAsString(p.get("steps")), checklistJson(p.get("checklist"), null),
                assignee == null ? null : Timestamp.from(now));
        if (assignee != null) target(targets, assignee, id, title).kinds.add("ADDED");
        String who = assignee == null ? "미배정" : "담당 " + memberName(assignee);
        return new DiffItem(index, "ADD_TASK", id,
                "업무 추가: " + title + " (" + HM.format(start) + "~" + HM.format(end) + ", " + zoneName(zone) + ", " + who + ")");
    }

    private DiffItem updateTask(UUID eventId, int index, JsonNode p, String field, Map<UUID, Map<UUID, Target>> targets) {
        Task task = task(eventId, uuid(p.get("taskId")), field + "taskId");
        requireOpen(task);
        UUID zone = p.has("zoneId") ? uuid(p.get("zoneId")) : task.zoneId();
        if (p.has("zoneId")) requireZone(eventId, zone, field + "zoneId");
        UUID program = p.has("programId") ? uuidOrNull(p.get("programId")) : task.programId();
        if (p.has("programId") && program != null
                && !exists("SELECT 1 FROM program WHERE id = ? AND event_id = ?", program, eventId))
            throw reference(field + "programId");
        Instant start = p.has("startAt") ? Operations.instant(p.get("startAt")) : task.startAt();
        Instant end = p.has("endAt") ? Operations.instant(p.get("endAt")) : task.endAt();
        if (!start.isBefore(end)) throw new BusinessException(ErrorCode.INVALID_REQUEST, "시작은 끝보다 앞이어야 합니다.");
        boolean timeChanged = !start.equals(task.startAt()) || !end.equals(task.endAt());
        if (timeChanged && task.assigneeId() != null) requireNoOverlap(eventId, task.assigneeId(), task.id(), start, end);
        String title = p.has("title") ? p.get("title").asString() : task.title();
        String steps = p.has("steps") ? MAPPER.writeValueAsString(p.get("steps")) : task.steps();
        String checklist = p.has("checklist") ? checklistJson(p.get("checklist"), task.checklist()) : task.checklist();
        jdbc.update("""
                UPDATE task SET zone_id = ?, program_id = ?, title = ?, start_at = ?, end_at = ?, steps = ?::jsonb,
                                checklist = ?::jsonb WHERE id = ?""",
                zone, program, title, Timestamp.from(start), Timestamp.from(end), steps, checklist, task.id());
        List<String> parts = new ArrayList<>();
        Set<String> kinds = new LinkedHashSet<>();
        if (timeChanged) {
            parts.add("시간 " + HM.format(task.startAt()) + "~" + HM.format(task.endAt()) + " → " + HM.format(start) + "~"
                    + HM.format(end));
            kinds.add("TIME");
        }
        if (!zone.equals(task.zoneId())) {
            parts.add("구역 " + zoneName(task.zoneId()) + " → " + zoneName(zone));
            kinds.add("ZONE");
        }
        if (!title.equals(task.title())) parts.add("제목 변경");
        if (!Objects.equals(program, task.programId())) parts.add("프로그램 변경");
        if (p.has("steps") && !sameJson(steps, task.steps())) {
            parts.add("절차 변경");
            kinds.add("STEPS");
        }
        if (p.has("checklist") && !sameJson(checklist, task.checklist())) {
            parts.add("점검 항목 변경");
            kinds.add("CHECKLIST");
        }
        if (task.assigneeId() != null && !kinds.isEmpty()) target(targets, task.assigneeId(), task.id(), title).kinds.addAll(kinds);
        return new DiffItem(index, "UPDATE_TASK", task.id(),
                "업무 수정: " + title + (parts.isEmpty() ? " (바뀐 내용 없음)" : " — " + String.join(", ", parts)));
    }

    private DiffItem assignTask(UUID eventId, int index, JsonNode p, String field, Instant now,
            Map<UUID, Map<UUID, Target>> targets) {
        Task task = task(eventId, uuid(p.get("taskId")), field + "taskId");
        if (!"WAITING".equals(task.status()))
            throw new BusinessException(ErrorCode.INVALID_STATE, "시작 전(WAITING) 업무만 담당을 바꿀 수 있습니다.");
        UUID from = uuidOrNull(p.get("fromMemberId"));
        if (!Objects.equals(from, task.assigneeId()))
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                    Map.of("target", "task", "field", field + "fromMemberId"));
        UUID to = uuidOrNull(p.get("toMemberId"));
        if (to != null) {
            requireAssignable(eventId, to, task.workGroupId(), field + "toMemberId");
            requireNoOverlap(eventId, to, task.id(), task.startAt(), task.endAt());
        }
        jdbc.update("UPDATE task SET assignee_id = ?, first_assigned_at = coalesce(first_assigned_at, ?) WHERE id = ?",
                to, to == null ? null : Timestamp.from(now), task.id());
        if (from != null && !from.equals(to)) {
            Target t = target(targets, from, task.id(), task.title());
            t.kinds.add("ASSIGNEE");
            t.removed = true;
        }
        if (to != null && !to.equals(from)) {
            Target t = target(targets, to, task.id(), task.title());
            t.kinds.add("ASSIGNEE");
            t.removed = false;
        }
        String before = from == null ? "미배정" : memberName(from);
        String after = to == null ? "미배정" : memberName(to);
        return new DiffItem(index, "ASSIGN_TASK", task.id(), "담당 변경: " + task.title() + " — " + before + " → " + after);
    }

    private DiffItem cancelTask(UUID eventId, int index, JsonNode p, String field, Map<UUID, Map<UUID, Target>> targets) {
        Task task = task(eventId, uuid(p.get("taskId")), field + "taskId");
        requireOpen(task);
        jdbc.update("UPDATE task SET status = 'CANCELED' WHERE id = ?", task.id());
        if (task.assigneeId() != null) {
            Target t = target(targets, task.assigneeId(), task.id(), task.title());
            t.kinds.add("CANCELED");
            t.removed = true;
        }
        return new DiffItem(index, "CANCEL_TASK", task.id(), "업무 취소: " + task.title() + " — " + p.get("reason").asString());
    }

    // ---- 프로그램·그룹·안내 ----------------------------------------------------------------------------------------

    private DiffItem updateProgram(UUID eventId, int index, JsonNode p, String field) {
        UUID id = uuid(p.get("programId"));
        var rows = jdbc.query("SELECT name, zone_id, start_at, end_at FROM program WHERE id = ? AND event_id = ? FOR UPDATE",
                (r, n) -> new Object[] {r.getString(1), r.getObject(2, UUID.class), r.getTimestamp(3).toInstant(),
                        r.getTimestamp(4).toInstant()}, id, eventId);
        if (rows.isEmpty()) throw reference(field + "programId");
        Object[] old = rows.getFirst();
        String name = p.has("name") ? p.get("name").asString() : (String) old[0];
        UUID zone = p.has("zoneId") ? uuidOrNull(p.get("zoneId")) : (UUID) old[1];
        if (p.has("zoneId") && zone != null) requireZone(eventId, zone, field + "zoneId");
        Instant start = p.has("startAt") ? Operations.instant(p.get("startAt")) : (Instant) old[2];
        Instant end = p.has("endAt") ? Operations.instant(p.get("endAt")) : (Instant) old[3];
        if (!start.isBefore(end)) throw new BusinessException(ErrorCode.INVALID_REQUEST, "시작은 끝보다 앞이어야 합니다.");
        jdbc.update("UPDATE program SET name = ?, zone_id = ?, start_at = ?, end_at = ? WHERE id = ?", name, zone,
                Timestamp.from(start), Timestamp.from(end), id);
        return new DiffItem(index, "UPDATE_PROGRAM", id,
                "프로그램 수정: " + name + " (" + HM.format(start) + "~" + HM.format(end) + ")");
    }

    private DiffItem upsertGroup(UUID eventId, UUID changeSetId, int index, JsonNode p, String field) {
        String name = p.get("name").asString();
        String instructions = p.get("instructions").asString();
        if (p.has("id")) {
            UUID id = uuid(p.get("id"));
            if (!exists("SELECT 1 FROM work_group WHERE id = ? AND event_id = ?", id, eventId)) throw reference(field + "id");
            requireUniqueGroupName(eventId, name, id, field + "name");
            jdbc.update("UPDATE work_group SET name = ?, instructions = ? WHERE id = ?", name, instructions, id);
            return new DiffItem(index, "UPSERT_WORK_GROUP", id, "직무 그룹 수정: " + name);
        }
        requireUniqueGroupName(eventId, name, null, field + "name");
        Long count = jdbc.queryForObject("SELECT count(*) FROM work_group WHERE event_id = ?", Long.class, eventId);
        if (count != null && count >= MAX_WORK_GROUPS)
            throw new BusinessException(ErrorCode.INVALID_STATE, "직무 그룹은 31개까지 만들 수 있습니다.");
        UUID id = derivedId(changeSetId, index);
        jdbc.update("INSERT INTO work_group (id, event_id, name, instructions, active) VALUES (?, ?, ?, ?, true)",
                id, eventId, name, instructions);
        return new DiffItem(index, "UPSERT_WORK_GROUP", id, "직무 그룹 추가: " + name);
    }

    private DiffItem deactivateGroup(UUID eventId, int index, JsonNode p, String field) {
        UUID id = uuid(p.get("workGroupId"));
        var names = jdbc.queryForList("SELECT name FROM work_group WHERE id = ? AND event_id = ? AND active FOR UPDATE",
                String.class, id, eventId);
        if (names.isEmpty()) throw reference(field + "workGroupId");
        // 아직 소속 팀원이 있으면 먼저 그룹을 옮겨야 한다(038 인원 수정).
        if (exists("SELECT 1 FROM member WHERE work_group_id = ? AND active", id))
            throw new BusinessException(ErrorCode.INVALID_STATE, "소속 팀원이 있는 직무 그룹은 비활성으로 바꿀 수 없습니다.");
        if (exists("SELECT 1 FROM task WHERE work_group_id = ? AND status IN ('WAITING', 'IN_PROGRESS')", id))
            throw new BusinessException(ErrorCode.INVALID_STATE, "미완료 업무가 있는 직무 그룹은 비활성으로 바꿀 수 없습니다.");
        jdbc.update("UPDATE work_group SET active = false WHERE id = ?", id);
        return new DiffItem(index, "DEACTIVATE_WORK_GROUP", id, "직무 그룹 비활성: " + names.getFirst());
    }

    private DiffItem updateInstructions(UUID eventId, int index, JsonNode p, String field) {
        String body = p.get("body").asString();
        if ("EVENT".equals(p.get("scope").asString())) {
            jdbc.update("UPDATE event SET instructions = ? WHERE id = ?", body, eventId);
            return new DiffItem(index, "UPDATE_INSTRUCTIONS", null, "행사 안내 수정");
        }
        UUID group = uuid(p.get("workGroupId"));
        var names = jdbc.queryForList("SELECT name FROM work_group WHERE id = ? AND event_id = ?", String.class, group, eventId);
        if (names.isEmpty()) throw reference(field + "workGroupId");
        jdbc.update("UPDATE work_group SET instructions = ? WHERE id = ?", body, group);
        return new DiffItem(index, "UPDATE_INSTRUCTIONS", group, "그룹 안내 수정: " + names.getFirst());
    }

    // ---- 장비 예약(V9-D44) ------------------------------------------------------------------------------------------

    private record Reservation(UUID id, long revision, String status, Instant startAt, Instant endAt, List<UUID> assetIds) { }

    private record Refs(UUID programId, UUID taskId) { }

    private DiffItem reserve(UUID eventId, UUID changeSetId, int index, JsonNode p, String field,
            Map<String, UUID> tempTasks) {
        JsonNode w = p.get("reservation");
        String wf = field + "reservation.";
        Refs refs = reservationRefs(eventId, w, wf, tempTasks);
        Instant start = Operations.instant(w.get("startAt"));
        Instant end = Operations.instant(w.get("endAt"));
        requireReservationWindow(eventId, start, end, wf + "startAt");
        List<String> names = lockAssetsAndCheck(eventId, w.get("assetIds"), wf + "assetIds", null, start, end);
        UUID id = derivedId(changeSetId, index);
        jdbc.update("""
                INSERT INTO equipment_reservation (id, event_id, program_id, task_id, start_at, end_at, status,
                                                   source_change_set_id, revision)
                VALUES (?, ?, ?, ?, ?, ?, 'PLANNED', ?, 1)""",
                id, eventId, refs.programId(), refs.taskId(), Timestamp.from(start), Timestamp.from(end), changeSetId);
        insertReservationAssets(id, w.get("assetIds"));
        return new DiffItem(index, "RESERVE_EQUIPMENT", id,
                "장비 예약: " + String.join(", ", names) + " (" + HM.format(start) + "~" + HM.format(end) + ")",
                MAPPER.nullNode(), reservationView(id), p.get("evidenceRefs"));
    }

    private DiffItem updateReservation(UUID eventId, int index, JsonNode p, String field, Map<String, UUID> tempTasks) {
        Reservation old = lockReservation(eventId, p, field);
        JsonNode before = reservationView(old.id());
        JsonNode w = p.get("after");
        String wf = field + "after.";
        Refs refs = reservationRefs(eventId, w, wf, tempTasks);
        Instant start = Operations.instant(w.get("startAt"));
        Instant end = Operations.instant(w.get("endAt"));
        requireReservationWindow(eventId, start, end, wf + "startAt");
        List<String> names = lockAssetsAndCheck(eventId, w.get("assetIds"), wf + "assetIds", old.id(), start, end);
        jdbc.update("""
                UPDATE equipment_reservation SET program_id = ?, task_id = ?, start_at = ?, end_at = ?, revision = revision + 1
                 WHERE id = ?""", refs.programId(), refs.taskId(), Timestamp.from(start), Timestamp.from(end), old.id());
        jdbc.update("DELETE FROM equipment_reservation_asset WHERE reservation_id = ?", old.id());
        insertReservationAssets(old.id(), w.get("assetIds"));
        return new DiffItem(index, "UPDATE_EQUIPMENT_RESERVATION", old.id(),
                "장비 예약 변경: " + HM.format(old.startAt()) + "~" + HM.format(old.endAt()) + " → "
                        + String.join(", ", names) + " (" + HM.format(start) + "~" + HM.format(end) + ")",
                before, reservationView(old.id()), p.get("evidenceRefs"));
    }

    private DiffItem cancelReservation(UUID eventId, int index, JsonNode p, String field) {
        Reservation old = lockReservation(eventId, p, field);
        JsonNode before = reservationView(old.id());
        jdbc.update("UPDATE equipment_reservation SET status = 'CANCELED', revision = revision + 1 WHERE id = ?", old.id());
        return new DiffItem(index, "CANCEL_EQUIPMENT_RESERVATION", old.id(),
                "장비 예약 취소: " + String.join(", ", assetNames(old.assetIds())) + " (" + HM.format(old.startAt()) + "~"
                        + HM.format(old.endAt()) + ") — " + p.get("reason").asString(),
                before, reservationView(old.id()), MAPPER.createArrayNode());
    }

    /** 예약 시간도 업무처럼 행사 준비 시작~철수 끝 안이어야 한다. 경계를 모르는(null) 쪽은 막지 않는다. */
    private void requireReservationWindow(UUID eventId, Instant start, Instant end, String field) {
        if (exists("""
                SELECT 1 FROM event WHERE id = ? AND ((prep_start_at IS NOT NULL AND ? < prep_start_at)
                                                  OR (teardown_end_at IS NOT NULL AND ? > teardown_end_at))""",
                eventId, Timestamp.from(start), Timestamp.from(end)))
            throw reference(field);
    }

    /** 업무·프로그램 참조. tmp-는 이 변경안 앞쪽 ADD_TASK의 tempId만 풀 수 있다(프로그램을 추가하는 연산은 없다). */
    private Refs reservationRefs(UUID eventId, JsonNode w, String field, Map<String, UUID> tempTasks) {
        UUID program = null;
        JsonNode pv = w.get("programId");
        if (!pv.isNull()) {
            if (pv.asString().startsWith("tmp-")) throw reference(field + "programId");
            program = uuid(pv);
            if (!exists("SELECT 1 FROM program WHERE id = ? AND event_id = ?", program, eventId))
                throw reference(field + "programId");
        }
        UUID task = null;
        JsonNode tv = w.get("taskId");
        if (!tv.isNull()) {
            String raw = tv.asString();
            task = raw.startsWith("tmp-") ? tempTasks.get(raw) : uuid(tv);
            // 끝났거나 취소된 업무에는 장비를 잡지 않는다(장비 요구 R1과 같은 기준).
            if (task == null || !exists("SELECT 1 FROM task WHERE id = ? AND event_id = ? AND status NOT IN ('DONE', 'CANCELED')",
                    task, eventId))
                throw reference(field + "taskId");
        }
        return new Refs(program, task);
    }

    /**
     * 장비와 그 구성품을 id 순서로 잠근 뒤, 같은 장비·구성품을 쓰는 PLANNED·ACTIVE 예약과 [start, end)가 겹치는지 본다.
     * 기존 예약 쪽 장비도 구성품까지 펼쳐 비교한다. 겹치면 422 TIME_OVERLAP. 요청 순서대로 장비 이름을 돌려준다(요약용).
     */
    private List<String> lockAssetsAndCheck(UUID eventId, JsonNode assetIds, String field, UUID exceptReservation,
            Instant start, Instant end) {
        UUID[] ids = new UUID[assetIds.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = uuid(assetIds.get(i));
        var found = jdbc.query("""
                SELECT id, name, component_asset_ids::text FROM equipment_asset
                 WHERE event_id = ? AND id = ANY(?) ORDER BY id FOR UPDATE""",
                (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getString(2), r.getString(3)}, eventId, ids);
        if (found.size() != ids.length) throw reference(field);
        Set<UUID> expanded = new TreeSet<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Object[] row : found) {
            expanded.add((UUID) row[0]);
            names.put((UUID) row[0], (String) row[1]);
            for (JsonNode c : MAPPER.readTree((String) row[2])) expanded.add(UUID.fromString(c.asString()));
        }
        // 구성품도 잠가 부모와 구성품을 서로 다른 변경안이 동시에 잡지 못하게 한다.
        UUID[] all = expanded.toArray(UUID[]::new);
        jdbc.queryForList("SELECT id FROM equipment_asset WHERE event_id = ? AND id = ANY(?) ORDER BY id FOR UPDATE",
                UUID.class, eventId, all);
        var conflicts = jdbc.queryForList("""
                SELECT v.id FROM equipment_reservation v
                  JOIN equipment_reservation_asset ra ON ra.reservation_id = v.id
                  JOIN equipment_asset a ON a.id = ra.asset_id
                 WHERE v.event_id = ? AND v.status IN ('PLANNED', 'ACTIVE') AND (?::uuid IS NULL OR v.id <> ?)
                   AND v.start_at < ? AND v.end_at > ?
                   AND (a.id = ANY(?) OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(a.component_asset_ids) c
                                                  WHERE c::uuid = ANY(?)))
                 ORDER BY v.id LIMIT 1""",
                UUID.class, eventId, exceptReservation, exceptReservation, Timestamp.from(end), Timestamp.from(start),
                all, all);
        if (!conflicts.isEmpty())
            throw new BusinessException(ErrorCode.TIME_OVERLAP, ErrorCode.TIME_OVERLAP.getMessage(),
                    Map.of("field", field, "conflictReservationId", conflicts.getFirst().toString()));
        List<String> ordered = new ArrayList<>();
        for (UUID id : ids) ordered.add(names.get(id));
        return ordered;
    }

    /** 수정·취소 대상. 이 행사 예약이 아니면 422, revision이 다르면 409 VERSION_CONFLICT, PLANNED·ACTIVE가 아니면 409 INVALID_STATE. */
    private Reservation lockReservation(UUID eventId, JsonNode p, String field) {
        UUID id = uuid(p.get("reservationId"));
        var rows = jdbc.query(
                "SELECT revision, status, start_at, end_at FROM equipment_reservation WHERE id = ? AND event_id = ? FOR UPDATE",
                (r, n) -> new Object[] {r.getLong(1), r.getString(2), r.getTimestamp(3).toInstant(),
                        r.getTimestamp(4).toInstant()}, id, eventId);
        if (rows.isEmpty()) throw reference(field + "reservationId");
        Object[] row = rows.getFirst();
        if ((long) row[0] != Long.parseLong(p.get("expectedRevision").asString()))
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                    Map.of("target", "equipmentReservation", "field", field + "expectedRevision"));
        if (!Set.of("PLANNED", "ACTIVE").contains((String) row[1]))
            throw new BusinessException(ErrorCode.INVALID_STATE, "끝났거나 취소된 예약은 바꿀 수 없습니다.");
        List<UUID> assets = jdbc.queryForList(
                "SELECT asset_id FROM equipment_reservation_asset WHERE reservation_id = ? ORDER BY asset_id", UUID.class, id);
        return new Reservation(id, (long) row[0], (String) row[1], (Instant) row[2], (Instant) row[3], assets);
    }

    private void insertReservationAssets(UUID reservationId, JsonNode assetIds) {
        for (JsonNode a : assetIds)
            jdbc.update("INSERT INTO equipment_reservation_asset (reservation_id, asset_id) VALUES (?, ?)", reservationId,
                    uuid(a));
    }

    /** EXT05와 같은 V9EquipmentReservation 모양 */
    private JsonNode reservationView(UUID id) {
        var view = jdbc.queryForObject("SELECT " + EquipmentViews.RESERVATION_COLUMNS
                + " FROM equipment_reservation v WHERE v.id = ?", EquipmentViews::reservation, id);
        return MAPPER.valueToTree(view);
    }

    private List<String> assetNames(List<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            var n = jdbc.queryForList("SELECT name FROM equipment_asset WHERE id = ?", String.class, id);
            names.add(n.isEmpty() ? "알 수 없음" : n.getFirst());
        }
        return names;
    }

    // ---- 공통 검사 --------------------------------------------------------------------------------------------

    private void requireUniqueGroupName(UUID eventId, String name, UUID except, String field) {
        String key = groupNameKey(name);
        var names = jdbc.queryForList("SELECT name FROM work_group WHERE event_id = ? AND (?::uuid IS NULL OR id <> ?)",
                String.class, eventId, except, except);
        if (names.stream().anyMatch(existing -> groupNameKey(existing).equals(key))) throw reference(field);
    }

    private static String groupNameKey(String name) {
        return UnicodeText.nameKey(name);
    }

    private void requireProgramContext(UUID eventId, Set<UUID> taskIds) {
        var invalid = jdbc.queryForList("""
                SELECT t.id FROM task t JOIN program p ON p.id = t.program_id
                 WHERE t.event_id = ? AND t.id = ANY(?) AND
                       (p.event_id <> t.event_id OR (p.zone_id IS NOT NULL AND p.zone_id <> t.zone_id)
                        OR t.start_at >= p.end_at OR t.end_at <= p.start_at) LIMIT 1""",
                UUID.class, eventId, taskIds.toArray(UUID[]::new));
        if (!invalid.isEmpty()) throw reference("operations.programId");
    }

    /** Table/column arguments are fixed internal identifiers. Unknown DRAFT bounds do not invent an interval. */
    private void requireEventWindow(UUID eventId, Set<UUID> ids, String table, String startColumn, String endColumn) {
        var invalid = jdbc.queryForList("SELECT r.id FROM " + table + " r JOIN event e ON e.id = r.event_id"
                + " WHERE r.event_id = ? AND r.id = ANY(?) AND ((e." + startColumn + " IS NOT NULL AND r.start_at < e."
                + startColumn + ") OR (e." + endColumn + " IS NOT NULL AND r.end_at > e." + endColumn + ")) LIMIT 1",
                UUID.class, eventId, ids.toArray(UUID[]::new));
        if (!invalid.isEmpty()) throw reference("operations." + table + ".time");
    }

    private Task task(UUID eventId, UUID id, String field) {
        var rows = jdbc.query("""
                SELECT id, work_group_id, assignee_id, zone_id, program_id, title, start_at, end_at, steps::text, checklist::text,
                       status FROM task WHERE id = ? AND event_id = ? FOR UPDATE""",
                (r, n) -> new Task(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                        r.getObject(4, UUID.class), r.getObject(5, UUID.class), r.getString(6),
                        r.getTimestamp(7).toInstant(), r.getTimestamp(8).toInstant(), r.getString(9), r.getString(10),
                        r.getString(11)), id, eventId);
        if (rows.isEmpty()) throw reference(field);
        return rows.getFirst();
    }

    private static void requireOpen(Task task) {
        if (Set.of("DONE", "CANCELED").contains(task.status()))
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 끝났거나 취소된 업무입니다.");
    }

    private void requireZone(UUID eventId, UUID zone, String field) {
        if (!exists("SELECT 1 FROM zone WHERE id = ? AND event_id = ?", zone, eventId)) throw reference(field);
    }

    /**
     * 담당자는 이 행사의 활성·가용 Member이고 업무와 같은 직무 그룹이어야 한다(그룹 이동은 038, 가용 변경은 039).
     * 예비(isReserve)는 총괄 승인으로 투입할 수 있으므로 막지 않는다(be/04). 가용 변경도 Event를 먼저 잠그므로
     * 적용 트랜잭션의 Event 잠금 아래에서 읽은 값이 최신이다.
     */
    private void requireAssignable(UUID eventId, UUID memberId, UUID group, String field) {
        if (!exists("SELECT 1 FROM member WHERE id = ? AND event_id = ? AND active AND available AND work_group_id = ?",
                memberId, eventId, group))
            throw reference(field);
    }

    private void requireNoOverlap(UUID eventId, UUID memberId, UUID exceptTask, Instant start, Instant end) {
        assignmentChecks.requireNoOverlap(eventId, memberId, exceptTask, start, end);
    }

    /** 점검 항목: 남은 key는 수행값(checked·checkedAt·checkedByMemberId)을 유지하고 새 key는 미체크(사전 A Task). */
    private static String checklistJson(JsonNode items, String previous) {
        Map<String, JsonNode> old = new LinkedHashMap<>();
        if (previous != null) for (JsonNode o : MAPPER.readTree(previous)) old.put(o.path("key").asString(), o);
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode item : items) {
            ObjectNode o = out.addObject();
            String key = item.get("key").asString();
            o.put("key", key);
            o.put("text", item.get("text").asString());
            o.put("required", item.get("required").asBoolean());
            JsonNode kept = old.get(key);
            o.put("checked", kept != null && kept.path("checked").asBoolean(false));
            o.set("checkedAt", kept == null ? MAPPER.nullNode() : kept.path("checkedAt"));
            o.set("checkedByMemberId", kept == null ? MAPPER.nullNode() : kept.path("checkedByMemberId"));
            if (o.get("checkedAt").isMissingNode()) o.putNull("checkedAt");
            if (o.get("checkedByMemberId").isMissingNode()) o.putNull("checkedByMemberId");
        }
        return MAPPER.writeValueAsString(out);
    }

    private static Target target(Map<UUID, Map<UUID, Target>> targets, UUID member, UUID task, String title) {
        return targets.computeIfAbsent(member, k -> new LinkedHashMap<>()).computeIfAbsent(task, k -> new Target(title));
    }

    private String memberName(UUID memberId) {
        var names = jdbc.queryForList("SELECT display_name FROM member WHERE id = ?", String.class, memberId);
        return names.isEmpty() ? "알 수 없음" : names.getFirst();
    }

    private String zoneName(UUID zoneId) {
        var names = jdbc.queryForList("SELECT name FROM zone WHERE id = ?", String.class, zoneId);
        return names.isEmpty() ? "알 수 없음" : names.getFirst();
    }

    private boolean exists(String sql, Object... args) {
        return !jdbc.queryForList(sql, args).isEmpty();
    }

    private static boolean sameJson(String a, String b) {
        return MAPPER.readTree(a).equals(MAPPER.readTree(b));
    }

    /** 변경안 ID와 연산 순서로 정한 새 행 ID. 미리보기와 적용이 같은 ID를 쓴다. */
    static UUID derivedId(UUID changeSetId, int index) {
        return UUID.nameUUIDFromBytes((changeSetId + "#" + index).getBytes(StandardCharsets.UTF_8));
    }

    private static String resourceType(String type) {
        return switch (type) {
            case "ADD_TASK", "UPDATE_TASK", "ASSIGN_TASK", "CANCEL_TASK" -> "TASK";
            case "UPDATE_PROGRAM" -> "PROGRAM";
            case "UPSERT_WORK_GROUP", "DEACTIVATE_WORK_GROUP" -> "WORK_GROUP";
            case "RESERVE_EQUIPMENT", "UPDATE_EQUIPMENT_RESERVATION", "CANCEL_EQUIPMENT_RESERVATION" ->
                    "EQUIPMENT_RESERVATION";
            default -> "EVENT";
        };
    }

    private static UUID uuid(JsonNode node) { return UUID.fromString(node.asString()); }
    private static UUID uuidOrNull(JsonNode node) { return node == null || node.isNull() ? null : uuid(node); }

    private static BusinessException reference(String field) {
        return new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.INVALID_REFERENCE.getMessage(),
                Map.of("field", field));
    }
}

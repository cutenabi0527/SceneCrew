package com.ssafy.scenecrew.event.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.global.text.UnicodeText;

/**
 * 행사 공개(029) 때의 계획 검사(V9-D38, be/04 "공개 계획 검사"). 기본정보 완결성(EventCompleteness)과 함께 모아
 * 422 EVENT_INCOMPLETE의 details.fields로 돌려준다. 행 단위 위반은 대상 id를 함께 넣는다.
 *
 * <ul>
 *   <li>개수 상한(LIMIT): 저장된 전체 건수로 센다. 구역10·프로그램20·직무 그룹31(비활성 포함)·업무200(취소 포함).</li>
 *   <li>최소 계획(MISSING): 활성 직무 그룹 1개 이상, 활성 그룹에 속한 취소되지 않은 업무 1개 이상.</li>
 *   <li>프로그램: 같은 행사 구역(INVALID_REFERENCE), startAt &lt; endAt이고 운영 구간(openAt~closeAt) 안(TIME_OUT_OF_RANGE).</li>
 *   <li>업무(취소 제외): 같은 행사의 활성 그룹·구역·프로그램, 프로그램과 같은 구역·겹치는 시간(INVALID_REFERENCE),
 *       startAt &lt; endAt이고 준비~정리 구간 안(TIME_OUT_OF_RANGE), steps 1~6개·각 120자(RANGE),
 *       checklist 0~6개·각 80자(RANGE)·key 중복 없음(DUPLICATE).</li>
 *   <li>묶음(취소 제외): 같은 bundleKey는 같은 그룹(BUNDLE_MISMATCH), 안에서 시간이 겹치지 않음(TIME_OVERLAP).
 *       묶음 수는 행사 정원과 무관하다.</li>
 * </ul>
 * 행사·그룹 안내(description·instructions)는 비어 있어도 된다. 운영 시각이 비어 있으면 구간 검사는 건너뛴다(완결성이 MISSING을 낸다).
 * 호출하는 쪽이 Event를 배타 잠금한 상태여야 한다. 계획을 바꾸는 쓰기도 Event를 먼저 잠근다.
 */
final class PublishPlanChecks {
    static final long MAX_ZONES = 10;
    static final long MAX_PROGRAMS = 20;
    static final long MAX_WORK_GROUPS = 31;
    static final long MAX_TASKS = 200;
    static final int MAX_STEPS = 6;
    static final int STEP_MAX = 120;
    static final int MAX_CHECKLIST = 6;
    static final int CHECKLIST_TEXT_MAX = 80;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private PublishPlanChecks() { }

    static List<Map<String, String>> check(JdbcTemplate jdbc, UUID eventId, EventCompleteness.Fields event) {
        List<Map<String, String>> out = new ArrayList<>();
        counts(jdbc, eventId, out);
        programs(jdbc, eventId, event, out);
        tasks(jdbc, eventId, event, out);
        return out;
    }

    private static void counts(JdbcTemplate jdbc, UUID eventId, List<Map<String, String>> out) {
        var row = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM zone WHERE event_id = ?) AS zones,
                       (SELECT count(*) FROM program WHERE event_id = ?) AS programs,
                       (SELECT count(*) FROM work_group WHERE event_id = ?) AS groups,
                       (SELECT count(*) FROM work_group WHERE event_id = ? AND active) AS active_groups,
                       (SELECT count(*) FROM task WHERE event_id = ?) AS tasks""",
                eventId, eventId, eventId, eventId, eventId);
        limit(out, "zones", row.get("zones"), MAX_ZONES);
        limit(out, "programs", row.get("programs"), MAX_PROGRAMS);
        limit(out, "workGroups", row.get("groups"), MAX_WORK_GROUPS);
        limit(out, "tasks", row.get("tasks"), MAX_TASKS);
        if (((Number) row.get("active_groups")).longValue() == 0) add(out, "workGroups", "MISSING", null);
    }

    private static void programs(JdbcTemplate jdbc, UUID eventId, EventCompleteness.Fields event,
            List<Map<String, String>> out) {
        jdbc.query("""
                SELECT p.id, p.zone_id IS NULL OR z.id IS NOT NULL AS zone_ok, p.start_at, p.end_at
                  FROM program p LEFT JOIN zone z ON z.id = p.zone_id AND z.event_id = p.event_id
                 WHERE p.event_id = ? ORDER BY p.start_at, p.id""", r -> {
            String id = r.getObject("id", UUID.class).toString();
            if (!r.getBoolean("zone_ok")) add(out, "programs.zoneId", "INVALID_REFERENCE", id);
            if (!within(instant(r.getObject("start_at", OffsetDateTime.class)), instant(r.getObject("end_at", OffsetDateTime.class)),
                    event.openAt(), event.closeAt()))
                add(out, "programs.time", "TIME_OUT_OF_RANGE", id);
        }, eventId);
    }

    private record TaskRow(String id, String bundleKey, UUID groupId, Instant startAt, Instant endAt) { }

    private static void tasks(JdbcTemplate jdbc, UUID eventId, EventCompleteness.Fields event,
            List<Map<String, String>> out) {
        List<TaskRow> rows = new ArrayList<>();
        int[] valid = {0};
        jdbc.query("""
                SELECT t.id, t.bundle_key, t.work_group_id, t.start_at, t.end_at, t.steps::text AS steps,
                       t.checklist::text AS checklist,
                       g.id IS NOT NULL AND g.active AS group_ok,
                       z.id IS NOT NULL AS zone_ok,
                       t.program_id IS NULL OR (p.id IS NOT NULL
                           AND (p.zone_id IS NULL OR p.zone_id = t.zone_id)
                           AND t.start_at < p.end_at AND p.start_at < t.end_at) AS program_ok
                  FROM task t
                  LEFT JOIN work_group g ON g.id = t.work_group_id AND g.event_id = t.event_id
                  LEFT JOIN zone z ON z.id = t.zone_id AND z.event_id = t.event_id
                  LEFT JOIN program p ON p.id = t.program_id AND p.event_id = t.event_id
                 WHERE t.event_id = ? AND t.status <> 'CANCELED'
                 ORDER BY t.start_at, t.id""", r -> {
            String id = r.getObject("id", UUID.class).toString();
            Instant start = instant(r.getObject("start_at", OffsetDateTime.class));
            Instant end = instant(r.getObject("end_at", OffsetDateTime.class));
            if (r.getBoolean("group_ok")) valid[0]++;
            else add(out, "tasks.workGroupId", "INVALID_REFERENCE", id);
            if (!r.getBoolean("zone_ok")) add(out, "tasks.zoneId", "INVALID_REFERENCE", id);
            if (!r.getBoolean("program_ok")) add(out, "tasks.programId", "INVALID_REFERENCE", id);
            if (!within(start, end, event.prepStartAt(), event.teardownEndAt()))
                add(out, "tasks.time", "TIME_OUT_OF_RANGE", id);
            if (!steps(r.getString("steps"))) add(out, "tasks.steps", "RANGE", id);
            String checklist = checklist(r.getString("checklist"));
            if (checklist != null) add(out, "tasks.checklist", checklist, id);
            rows.add(new TaskRow(id, r.getString("bundle_key"), r.getObject("work_group_id", UUID.class), start, end));
        }, eventId);
        if (valid[0] == 0) add(out, "tasks", "MISSING", null);
        bundles(rows, out);
    }

    /** 한 묶음은 한 사람이 맡으므로 같은 그룹이고 안에서 시간이 겹치지 않는다. rows는 startAt 순이다. */
    private static void bundles(List<TaskRow> rows, List<Map<String, String>> out) {
        Map<String, List<TaskRow>> byBundle = new LinkedHashMap<>();
        for (TaskRow row : rows) byBundle.computeIfAbsent(row.bundleKey(), k -> new ArrayList<>()).add(row);
        for (List<TaskRow> bundle : byBundle.values()) {
            UUID group = bundle.getFirst().groupId();
            for (TaskRow row : bundle) {
                if (!row.groupId().equals(group)) add(out, "tasks.bundleKey", "BUNDLE_MISMATCH", row.id());
            }
            Instant lastEnd = null;
            for (TaskRow row : bundle) {
                if (lastEnd != null && row.startAt().isBefore(lastEnd)) add(out, "tasks.bundleKey", "TIME_OVERLAP", row.id());
                if (lastEnd == null || row.endAt().isAfter(lastEnd)) lastEnd = row.endAt();
            }
        }
    }

    /** steps: 문자열 1~6개, 각 공백뿐이 아니고 120자 이하 */
    private static boolean steps(String json) {
        JsonNode steps = read(json);
        if (steps == null || !steps.isArray() || steps.isEmpty() || steps.size() > MAX_STEPS) return false;
        for (JsonNode step : steps) if (!text(step, STEP_MAX)) return false;
        return true;
    }

    /** checklist: 0~6개, text는 80자 이하, key는 비어 있지 않고 업무 안에서 유일, required는 참/거짓. 위반 reason 또는 null */
    private static String checklist(String json) {
        JsonNode items = read(json);
        if (items == null || !items.isArray() || items.size() > MAX_CHECKLIST) return "RANGE";
        Set<String> keys = new HashSet<>();
        for (JsonNode item : items) {
            if (!item.isObject() || !text(item.get("text"), CHECKLIST_TEXT_MAX) || !text(item.get("key"), Integer.MAX_VALUE)
                    || item.get("required") == null || !item.get("required").isBoolean())
                return "RANGE";
            if (!keys.add(item.get("key").asString())) return "DUPLICATE";
        }
        return null;
    }

    private static JsonNode read(String json) {
        try {
            return json == null ? null : MAPPER.readTree(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean text(JsonNode node, int maxCodePoints) {
        if (node == null || !node.isString()) return false;
        String value = node.asString();
        return !UnicodeText.isBlank(value) && value.codePointCount(0, value.length()) <= maxCodePoints;
    }

    /** start &lt; end이고, 구간 끝이 알려져 있으면 그 안 */
    private static boolean within(Instant start, Instant end, Instant from, Instant to) {
        if (!start.isBefore(end)) return false;
        return (from == null || !start.isBefore(from)) && (to == null || !end.isAfter(to));
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    private static void limit(List<Map<String, String>> out, String field, Object count, long max) {
        if (((Number) count).longValue() > max) add(out, field, "LIMIT", null);
    }

    private static void add(List<Map<String, String>> out, String field, String reason, String id) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("field", field);
        item.put("reason", reason);
        if (id != null) item.put("id", id);
        out.add(item);
    }
}

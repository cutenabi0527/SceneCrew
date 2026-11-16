package com.ssafy.scenecrew.plan.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.global.text.UnicodeText;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.ChecklistItem;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.Group;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.Program;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.Ref;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.Task;
import com.ssafy.scenecrew.plan.service.DraftPlanCommand.Zone;

/**
 * SC-API-028 본문(DraftPlanWrite)의 형식 검사. 계약 스키마의 additionalProperties=false·필수 키·길이·개수를 그대로 본다.
 * 오류는 400 INVALID_REQUEST이고 details.fields[0]에 경로(예: tasks[2].steps)와 이유를 담는다.
 * 길이는 코드포인트로 센다(V9-D35). 이름·절차·점검 항목처럼 공개(029) 검사가 공백만 있는 값을 거절하는 칸은 여기서도 거절한다.
 */
public final class DraftPlanRequest {
    static final int MAX_GROUPS = 31;
    static final int MAX_ZONES = 10;
    static final int MAX_PROGRAMS = 20;
    static final int MAX_TASKS = 200;
    static final int GROUP_NAME_MAX = 20;
    static final int ZONE_NAME_MAX = 30;
    static final int ZONE_DESCRIPTION_MAX = 1000;
    static final int PROGRAM_NAME_MAX = 20;
    static final int TASK_TITLE_MAX = 100;
    static final int MAX_STEPS = 6;
    static final int STEP_MAX = 120;
    static final int MAX_CHECKLIST = 6;
    static final int CHECKLIST_TEXT_MAX = 80;

    private static final String REVISION = "0|[1-9][0-9]{0,18}";
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String TEMP_ID = "tmp-[A-Za-z0-9_-]{1,40}";

    private DraftPlanRequest() { }

    public static DraftPlanCommand parse(JsonNode body) {
        object(body, "", Set.of("expectedEventRevision", "workGroups", "zones", "programs", "tasks", "instructions"),
                Set.of("expectedEventRevision", "workGroups", "zones", "programs", "tasks", "instructions"));
        long revision = revision(body.get("expectedEventRevision"));
        List<Group> groups = list(body.get("workGroups"), "workGroups", MAX_GROUPS, DraftPlanRequest::group);
        List<Zone> zones = list(body.get("zones"), "zones", MAX_ZONES, DraftPlanRequest::zone);
        List<Program> programs = list(body.get("programs"), "programs", MAX_PROGRAMS, DraftPlanRequest::program);
        List<Task> tasks = list(body.get("tasks"), "tasks", MAX_TASKS, DraftPlanRequest::task);
        List<String> instructions = list(body.get("instructions"), "instructions", Integer.MAX_VALUE,
                DraftPlanRequest::instruction);
        // 행사 공통 안내는 event.instructions 한 칸이다. 두 개 이상이면 어느 것을 저장할지 정할 수 없다.
        if (instructions.size() > 1) throw DraftPlanCommand.invalid("instructions", "LIMIT");
        return new DraftPlanCommand(revision, groups, zones, programs, tasks,
                instructions.isEmpty() ? null : instructions.getFirst());
    }

    private static Group group(JsonNode node, String path) {
        object(node, path, Set.of("id", "tempId", "name", "instructions"), Set.of("name", "instructions"));
        Ref key = key(node, path);
        String name = text(node.get("name"), path + ".name", GROUP_NAME_MAX);
        // 그룹 안내는 비워도 된다(빈 문자열 = 안내 없음).
        JsonNode instructions = node.get("instructions");
        if (!instructions.isString()) throw DraftPlanCommand.invalid(path + ".instructions", "TYPE");
        return new Group(key, name, instructions.asString());
    }

    private static Zone zone(JsonNode node, String path) {
        object(node, path, Set.of("id", "tempId", "name", "description"), Set.of("name"));
        Ref key = key(node, path);
        String name = text(node.get("name"), path + ".name", ZONE_NAME_MAX);
        String description = null;
        JsonNode value = node.get("description");
        if (value != null && !value.isNull()) {
            if (!value.isString()) throw DraftPlanCommand.invalid(path + ".description", "TYPE");
            description = value.asString();
            if (codePoints(description) > ZONE_DESCRIPTION_MAX)
                throw DraftPlanCommand.invalid(path + ".description", "LENGTH");
        }
        return new Zone(key, name, description);
    }

    private static Program program(JsonNode node, String path) {
        object(node, path, Set.of("id", "tempId", "name", "zoneId", "startAt", "endAt"),
                Set.of("name", "zoneId", "startAt", "endAt"));
        Ref key = key(node, path);
        String name = text(node.get("name"), path + ".name", PROGRAM_NAME_MAX);
        Ref zone = node.get("zoneId").isNull() ? null : ref(node.get("zoneId"), path + ".zoneId");
        return new Program(key, name, zone, time(node.get("startAt"), path + ".startAt"),
                time(node.get("endAt"), path + ".endAt"));
    }

    private static Task task(JsonNode node, String path) {
        object(node, path, Set.of("id", "tempId", "workGroupId", "bundleKey", "zoneId", "programId", "title", "startAt",
                "endAt", "steps", "checklist"), Set.of("workGroupId", "bundleKey", "zoneId", "programId", "title", "startAt",
                "endAt", "steps", "checklist"));
        Ref key = key(node, path);
        Ref group = ref(node.get("workGroupId"), path + ".workGroupId");
        String bundleKey = text(node.get("bundleKey"), path + ".bundleKey", Integer.MAX_VALUE);
        Ref zone = ref(node.get("zoneId"), path + ".zoneId");
        Ref program = node.get("programId").isNull() ? null : ref(node.get("programId"), path + ".programId");
        String title = text(node.get("title"), path + ".title", TASK_TITLE_MAX);
        Instant start = time(node.get("startAt"), path + ".startAt");
        Instant end = time(node.get("endAt"), path + ".endAt");

        JsonNode stepsNode = node.get("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty() || stepsNode.size() > MAX_STEPS)
            throw DraftPlanCommand.invalid(path + ".steps", "RANGE");
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < stepsNode.size(); i++)
            steps.add(text(stepsNode.get(i), path + ".steps[" + i + "]", STEP_MAX));

        JsonNode checklistNode = node.get("checklist");
        if (!checklistNode.isArray() || checklistNode.size() > MAX_CHECKLIST)
            throw DraftPlanCommand.invalid(path + ".checklist", "RANGE");
        List<ChecklistItem> checklist = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < checklistNode.size(); i++) {
            String itemPath = path + ".checklist[" + i + "]";
            JsonNode item = checklistNode.get(i);
            object(item, itemPath, Set.of("key", "text", "required"), Set.of("key", "text", "required"));
            String checkKey = text(item.get("key"), itemPath + ".key", Integer.MAX_VALUE);
            if (!keys.add(checkKey)) throw DraftPlanCommand.invalid(itemPath + ".key", "DUPLICATE");
            String text = text(item.get("text"), itemPath + ".text", CHECKLIST_TEXT_MAX);
            if (!item.get("required").isBoolean()) throw DraftPlanCommand.invalid(itemPath + ".required", "TYPE");
            checklist.add(new ChecklistItem(checkKey, text, item.get("required").asBoolean()));
        }
        return new Task(key, group, bundleKey, zone, program, title, start, end, List.copyOf(steps),
                List.copyOf(checklist));
    }

    /** DraftInstruction: scope=EVENT, workGroupId=null만. 그룹 안내는 workGroups[].instructions로만 받는다. */
    private static String instruction(JsonNode node, String path) {
        object(node, path, Set.of("scope", "workGroupId", "body"), Set.of("scope", "workGroupId", "body"));
        if (!node.get("scope").isString() || !"EVENT".equals(node.get("scope").asString()))
            throw DraftPlanCommand.invalid(path + ".scope", "EVENT_ONLY");
        if (!node.get("workGroupId").isNull()) throw DraftPlanCommand.invalid(path + ".workGroupId", "EVENT_ONLY");
        return text(node.get("body"), path + ".body", Integer.MAX_VALUE);
    }

    // ---- 공통 ----------------------------------------------------------------------------------------------

    private static <T> List<T> list(JsonNode node, String path, int max, BiFunction<JsonNode, String, T> item) {
        if (node == null || !node.isArray()) throw DraftPlanCommand.invalid(path, "TYPE");
        if (node.size() > max) throw DraftPlanCommand.invalid(path, "LIMIT");
        List<T> out = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) out.add(item.apply(node.get(i), path + "[" + i + "]"));
        return List.copyOf(out);
    }

    /** id와 tempId 중 정확히 하나 */
    private static Ref key(JsonNode node, String path) {
        boolean hasId = node.has("id");
        boolean hasTemp = node.has("tempId");
        if (hasId == hasTemp) throw DraftPlanCommand.invalid(path, "ID_OR_TEMP_ID");
        if (hasId) return Ref.existing(uuid(node.get("id"), path + ".id"));
        JsonNode temp = node.get("tempId");
        if (!temp.isString() || !temp.asString().matches(TEMP_ID)) throw DraftPlanCommand.invalid(path + ".tempId", "FORMAT");
        return Ref.temp(temp.asString());
    }

    /** 참조 값: 기존 UUID 또는 같은 요청의 tempId */
    private static Ref ref(JsonNode node, String path) {
        if (node == null || !node.isString()) throw DraftPlanCommand.invalid(path, "TYPE");
        String value = node.asString();
        if (value.matches(TEMP_ID)) return Ref.temp(value);
        if (value.matches(UUID_FORMAT)) return Ref.existing(UUID.fromString(value));
        throw DraftPlanCommand.invalid(path, "FORMAT");
    }

    private static UUID uuid(JsonNode node, String path) {
        if (node == null || !node.isString() || !node.asString().matches(UUID_FORMAT))
            throw DraftPlanCommand.invalid(path, "FORMAT");
        return UUID.fromString(node.asString());
    }

    /** 공백만이 아니고 maxCodePoints 이하인 문자열 */
    private static String text(JsonNode node, String path, int maxCodePoints) {
        if (node == null || !node.isString()) throw DraftPlanCommand.invalid(path, "TYPE");
        String value = node.asString();
        if (UnicodeText.isBlank(value)) throw DraftPlanCommand.invalid(path, "BLANK");
        if (codePoints(value) > maxCodePoints) throw DraftPlanCommand.invalid(path, "LENGTH");
        return value;
    }

    /** RFC 3339 date-time. 시간대가 반드시 있어야 하고 DB 정밀도(마이크로초)로 자른다. */
    private static Instant time(JsonNode node, String path) {
        if (node == null || !node.isString()) throw DraftPlanCommand.invalid(path, "TYPE");
        try {
            return OffsetDateTime.parse(node.asString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
                    .truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw DraftPlanCommand.invalid(path, "FORMAT");
        }
    }

    private static long revision(JsonNode node) {
        if (node == null || !node.isString() || !node.asString().matches(REVISION))
            throw DraftPlanCommand.invalid("expectedEventRevision", "FORMAT");
        try {
            return Long.parseLong(node.asString());
        } catch (NumberFormatException e) {
            throw DraftPlanCommand.invalid("expectedEventRevision", "FORMAT");
        }
    }

    /** 객체이고, 허용한 키만 있고, 필수 키가 모두 있다. 필수 키의 null은 각 칸이 따로 본다. */
    private static void object(JsonNode node, String path, Set<String> allowed, Set<String> required) {
        String at = path.isEmpty() ? "body" : path;
        if (node == null || !node.isObject()) throw DraftPlanCommand.invalid(at, "TYPE");
        for (String name : node.propertyNames())
            if (!allowed.contains(name)) throw DraftPlanCommand.invalid(path.isEmpty() ? name : path + "." + name, "UNKNOWN");
        for (String name : required)
            if (!node.has(name)) throw DraftPlanCommand.invalid(path.isEmpty() ? name : path + "." + name, "REQUIRED");
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}

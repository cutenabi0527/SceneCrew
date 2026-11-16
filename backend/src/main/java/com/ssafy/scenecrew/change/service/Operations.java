package com.ssafy.scenecrew.change.service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.text.UnicodeText;

/**
 * 변경안 연산(V9Operation)과 근거 참조(V9EvidenceRef)의 형식 검사. 계약 스키마와 다르면 400 INVALID_REQUEST다.
 * 장비 예약 연산 3종(V9OpReserveEquipment·V9OpUpdateReservation·V9OpCancelReservation)도 여기서 형식을 본다(V9-D44).
 * 참조가 이 행사에 있는지·시간 충돌 같은 현재 상태 검사는 OperationEngine이 한다.
 */
public final class Operations {
    public static final Set<String> SUPPORTED = Set.of("ADD_TASK", "UPDATE_TASK", "ASSIGN_TASK", "CANCEL_TASK",
            "UPDATE_PROGRAM", "UPSERT_WORK_GROUP", "DEACTIVATE_WORK_GROUP", "UPDATE_INSTRUCTIONS");
    public static final Set<String> RESERVATION = Set.of("RESERVE_EQUIPMENT", "UPDATE_EQUIPMENT_RESERVATION",
            "CANCEL_EQUIPMENT_RESERVATION");
    public static final int MAX_OPERATIONS = 50;
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String TEMP = "tmp-[A-Za-z0-9_-]{1,40}";
    private static final String REVISION = "0|[1-9][0-9]*";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Operations() { }

    /** 연산 배열(1개 이상)을 검사하고 그대로 복사해 돌려준다. */
    public static ArrayNode check(JsonNode operations) {
        if (operations == null || !operations.isArray() || operations.isEmpty() || operations.size() > MAX_OPERATIONS)
            throw invalid();
        Set<String> tempIds = new HashSet<>();
        for (int i = 0; i < operations.size(); i++) {
            JsonNode op = operations.get(i);
            object(op, Set.of("type", "payload"), Set.of("type", "payload"));
            String type = text(op.get("type"));
            if (type == null) throw invalid();
            JsonNode p = op.get("payload");
            switch (type) {
                case "ADD_TASK" -> {
                    object(p, Set.of("workGroupId", "bundleKey", "zoneId", "programId", "title", "startAt", "endAt", "steps",
                            "checklist", "tempId", "assigneeId"), Set.of("workGroupId", "bundleKey", "zoneId", "programId",
                            "title", "startAt", "endAt", "steps", "checklist", "tempId", "assigneeId"));
                    uuid(p.get("workGroupId"), false);
                    nonBlank(p.get("bundleKey"), Integer.MAX_VALUE);
                    uuid(p.get("zoneId"), false);
                    uuid(p.get("programId"), true);
                    nonBlank(p.get("title"), 100);
                    period(p.get("startAt"), p.get("endAt"));
                    steps(p.get("steps"));
                    checklist(p.get("checklist"));
                    if (!matches(p.get("tempId"), TEMP) || !tempIds.add(p.get("tempId").asString())) throw invalid();
                    uuid(p.get("assigneeId"), true);
                }
                case "UPDATE_TASK" -> {
                    object(p, Set.of("taskId", "zoneId", "programId", "title", "startAt", "endAt", "steps", "checklist"),
                            Set.of("taskId"));
                    if (p.size() < 2) throw invalid();
                    uuid(p.get("taskId"), false);
                    if (p.has("zoneId")) uuid(p.get("zoneId"), false);
                    if (p.has("programId")) uuid(p.get("programId"), true);
                    if (p.has("title")) nonBlank(p.get("title"), 100);
                    if (p.has("startAt")) instant(p.get("startAt"));
                    if (p.has("endAt")) instant(p.get("endAt"));
                    if (p.has("steps")) steps(p.get("steps"));
                    if (p.has("checklist")) checklist(p.get("checklist"));
                }
                case "ASSIGN_TASK" -> {
                    object(p, Set.of("taskId", "fromMemberId", "toMemberId"), Set.of("taskId", "fromMemberId", "toMemberId"));
                    uuid(p.get("taskId"), false);
                    uuid(p.get("fromMemberId"), true);
                    uuid(p.get("toMemberId"), true);
                }
                case "CANCEL_TASK" -> {
                    object(p, Set.of("taskId", "reason"), Set.of("taskId", "reason"));
                    uuid(p.get("taskId"), false);
                    nonBlank(p.get("reason"), Integer.MAX_VALUE);
                }
                case "UPDATE_PROGRAM" -> {
                    object(p, Set.of("programId", "name", "zoneId", "startAt", "endAt"), Set.of("programId"));
                    if (p.size() < 2) throw invalid();
                    uuid(p.get("programId"), false);
                    if (p.has("name")) nonBlank(p.get("name"), 20);
                    if (p.has("zoneId")) uuid(p.get("zoneId"), true);
                    if (p.has("startAt")) instant(p.get("startAt"));
                    if (p.has("endAt")) instant(p.get("endAt"));
                }
                case "UPSERT_WORK_GROUP" -> {
                    object(p, Set.of("id", "tempId", "name", "instructions"), Set.of("name", "instructions"));
                    if (p.has("id") == p.has("tempId")) throw invalid();
                    if (p.has("id")) uuid(p.get("id"), false);
                    else if (!matches(p.get("tempId"), TEMP) || !tempIds.add(p.get("tempId").asString())) throw invalid();
                    nonBlank(p.get("name"), 20);
                    if (text(p.get("instructions")) == null) throw invalid();
                }
                case "DEACTIVATE_WORK_GROUP" -> {
                    object(p, Set.of("workGroupId"), Set.of("workGroupId"));
                    uuid(p.get("workGroupId"), false);
                }
                case "UPDATE_INSTRUCTIONS" -> {
                    object(p, Set.of("scope", "workGroupId", "body"), Set.of("scope", "workGroupId", "body"));
                    String scope = text(p.get("scope"));
                    if ("EVENT".equals(scope)) {
                        if (!p.get("workGroupId").isNull()) throw invalid();
                    } else if ("WORK_GROUP".equals(scope)) {
                        uuid(p.get("workGroupId"), false);
                    } else throw invalid();
                    nonBlank(p.get("body"), Integer.MAX_VALUE);
                }
                case "RESERVE_EQUIPMENT" -> {
                    object(p, Set.of("reservation", "evidenceRefs"), Set.of("reservation", "evidenceRefs"));
                    reservation(p.get("reservation"));
                    requiredEvidence(p.get("evidenceRefs"));
                }
                case "UPDATE_EQUIPMENT_RESERVATION" -> {
                    object(p, Set.of("reservationId", "expectedRevision", "after", "evidenceRefs"),
                            Set.of("reservationId", "expectedRevision", "after", "evidenceRefs"));
                    uuid(p.get("reservationId"), false);
                    if (!matches(p.get("expectedRevision"), REVISION)) throw invalid();
                    reservation(p.get("after"));
                    requiredEvidence(p.get("evidenceRefs"));
                }
                case "CANCEL_EQUIPMENT_RESERVATION" -> {
                    object(p, Set.of("reservationId", "expectedRevision", "reason"),
                            Set.of("reservationId", "expectedRevision", "reason"));
                    uuid(p.get("reservationId"), false);
                    if (!matches(p.get("expectedRevision"), REVISION)) throw invalid();
                    nonBlank(p.get("reason"), Integer.MAX_VALUE);
                }
                default -> throw invalid();
            }
        }
        return (ArrayNode) MAPPER.readTree(MAPPER.writeValueAsString(operations));
    }

    /** V9EvidenceRef 배열. 생략은 빈 배열이다. */
    public static ArrayNode evidenceRefs(JsonNode refs) {
        if (refs == null) return MAPPER.createArrayNode();
        if (!refs.isArray()) throw invalid();
        for (JsonNode r : refs) {
            object(r, Set.of("kind", "id", "revision", "contentHash", "observedAt", "preconditions"),
                    Set.of("kind", "id", "revision", "contentHash", "observedAt", "preconditions"));
            nonBlank(r.get("kind"), Integer.MAX_VALUE);
            uuid(r.get("id"), false);
            if (!r.get("revision").isNull() && !matches(r.get("revision"), "0|[1-9][0-9]*")) throw invalid();
            if (!r.get("contentHash").isNull() && !matches(r.get("contentHash"), "[a-f0-9]{64}")) throw invalid();
            if (!r.get("observedAt").isNull()) instant(r.get("observedAt"));
            if (!r.get("preconditions").isArray()) throw invalid();
            for (JsonNode c : r.get("preconditions")) nonBlank(c, Integer.MAX_VALUE);
        }
        return (ArrayNode) MAPPER.readTree(MAPPER.writeValueAsString(refs));
    }

    /**
     * V9SimulationRef 배열. 생략은 빈 배열이다. 한 변경안은 후보 하나만 고른다(2개 이상은 422).
     * 같은 행사·만료 전·후보 존재·inputHash·연산 일치는 ChangeSetService가 현재 상태로 본다(V9-D44).
     */
    public static ArrayNode simulationRefs(JsonNode refs) {
        if (refs == null) return MAPPER.createArrayNode();
        if (!refs.isArray()) throw invalid();
        for (JsonNode r : refs) {
            object(r, Set.of("simulationId", "candidateId", "inputHash"), Set.of("simulationId", "candidateId", "inputHash"));
            uuid(r.get("simulationId"), false);
            nonBlank(r.get("candidateId"), Integer.MAX_VALUE);
            if (!matches(r.get("inputHash"), "[a-f0-9]{64}")) throw invalid();
        }
        if (refs.size() > 1)
            throw new BusinessException(ErrorCode.INVALID_REFERENCE, "변경안 하나에는 복구 후보 하나만 고를 수 있습니다.",
                    Map.of("field", "simulationRefs", "reason", "SINGLE_CANDIDATE_ONLY"));
        return (ArrayNode) MAPPER.readTree(MAPPER.writeValueAsString(refs));
    }

    static java.time.Instant instant(JsonNode node) {
        String value = text(node);
        if (value == null) throw invalid();
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    /** V9ReservationWrite. 장비는 확인된 UUID만, 업무·프로그램은 UUID·같은 변경안의 tmp- 참조·null이다. 중복 장비는 거절한다. */
    private static void reservation(JsonNode r) {
        object(r, Set.of("assetIds", "programId", "taskId", "startAt", "endAt"),
                Set.of("assetIds", "programId", "taskId", "startAt", "endAt"));
        JsonNode assets = r.get("assetIds");
        if (!assets.isArray() || assets.isEmpty() || assets.size() > MAX_OPERATIONS) throw invalid();
        Set<String> seen = new HashSet<>();
        for (JsonNode a : assets) {
            uuid(a, false);
            if (!seen.add(a.asString())) throw invalid();
        }
        for (String ref : new String[] {"programId", "taskId"}) {
            JsonNode v = r.get(ref);
            if (!v.isNull() && !matches(v, UUID) && !matches(v, TEMP)) throw invalid();
        }
        period(r.get("startAt"), r.get("endAt"));
    }

    /** 예약 연산의 근거는 1개 이상이다(계약 minItems 1). */
    private static void requiredEvidence(JsonNode refs) {
        if (refs == null || !refs.isArray() || refs.isEmpty()) throw invalid();
        evidenceRefs(refs);
    }

    private static void period(JsonNode start, JsonNode end) {
        if (!instant(start).isBefore(instant(end))) throw invalid();
    }

    private static void steps(JsonNode steps) {
        if (steps == null || !steps.isArray() || steps.isEmpty() || steps.size() > 6) throw invalid();
        for (JsonNode s : steps) nonBlank(s, 120);
    }

    private static void checklist(JsonNode list) {
        if (list == null || !list.isArray() || list.size() > 6) throw invalid();
        Set<String> keys = new HashSet<>();
        for (JsonNode item : list) {
            object(item, Set.of("key", "text", "required"), Set.of("key", "text", "required"));
            nonBlank(item.get("key"), Integer.MAX_VALUE);
            nonBlank(item.get("text"), 80);
            if (!item.get("required").isBoolean() || !keys.add(item.get("key").asString())) throw invalid();
        }
    }

    private static void object(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw invalid();
        for (String name : node.propertyNames()) if (!allowed.contains(name)) throw invalid();
        for (String name : required) if (!node.has(name)) throw invalid();
    }

    private static void uuid(JsonNode node, boolean nullable) {
        if (node == null) throw invalid();
        if (node.isNull() && nullable) return;
        if (!matches(node, UUID)) throw invalid();
    }

    private static void nonBlank(JsonNode node, int maxCodePoints) {
        String value = text(node);
        if (value == null || UnicodeText.isBlank(value) || value.codePointCount(0, value.length()) > maxCodePoints) throw invalid();
    }

    private static boolean matches(JsonNode node, String regex) {
        String value = text(node);
        return value != null && value.matches(regex);
    }

    static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

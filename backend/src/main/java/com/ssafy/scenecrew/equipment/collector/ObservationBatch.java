package com.ssafy.scenecrew.equipment.collector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 수집기가 보낸 관측 묶음(#330, internal-collector-proposed.yaml의 Batch). 계약에 없는 필드·타입은 받지 않는다.
 *
 * - 묶음 자체가 계약과 다르면 400 INVALID_COLLECTOR_REQUEST
 * - 기록 하나라도 계약과 다르면 422 COLLECTOR_RECORD_INVALID (묶음 전체를 거절한다)
 * - 기록이 100건을 넘으면 413 COLLECTOR_BATCH_TOO_LARGE
 */
public record ObservationBatch(String collectorId, long droppedSinceLast, List<Record> records) {
    public static final String SCHEMA = "scenecrew.equipment-observation/draft-1";
    public static final int MAX_RECORDS = 100;
    private static final long MAX_SAFE = 9007199254740991L;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final Set<String> BATCH_FIELDS = Set.of("schema", "collectorId", "sentAt", "droppedSinceLast", "records");
    private static final Set<String> COMMON = Set.of("schema", "recordType", "collectorId", "runId", "sessionId",
            "sequence", "adapter", "sourceRef", "mode", "channel", "receivedAt");
    private static final Map<String, Set<String>> EXTRA = Map.of(
            "READING", Set.of("origin", "param", "raw", "value", "valueStatus", "unit", "frame", "observedAt", "timeBasis"),
            "LINK", Set.of("linkState", "reason"),
            "STATUS", Set.of("linkState", "metering", "staleAfterMs", "lastSampleAt", "sampleAgeMs", "lastValues"),
            "UNPARSED", Set.of("frame"));
    private static final Set<String> MODES = Set.of("LIVE", "SIMULATOR", "REPLAY");
    private static final Set<String> ORIGINS = Set.of("SAMPLE", "REPORT");
    private static final Set<String> VALUE_STATUSES = Set.of("OK", "UNKNOWN_VALUE", "UNAVAILABLE", "UNPARSED", "OUT_OF_RANGE");
    private static final Set<String> LINK_STATES = Set.of("DISABLED", "CONNECTING", "CONNECTED", "OFFLINE");
    private static final Set<String> METERING = Set.of("UNKNOWN", "FRESH", "STALE", "OFFLINE");
    private static final Set<String> LAST_VALUE_FIELDS = Set.of("value", "valueStatus", "raw", "receivedAt", "sessionId");

    /** 기록 하나. node는 받은 원문 그대로이며 저장 내용과 중복 판정 해시의 기준이다. */
    public record Record(String type, UUID runId, UUID sessionId, long sequence, String sourceRef, String mode,
            int channel, Instant receivedAt, String item, JsonNode node, String contentHash) {
    }

    public static ObservationBatch parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            throw request("본문이 JSON이 아닙니다.");
        }
        if (root == null || !root.isObject()) throw request("본문은 JSON 객체여야 합니다.");
        for (String name : root.propertyNames())
            if (!BATCH_FIELDS.contains(name)) throw request("알 수 없는 필드입니다: " + name);
        if (!SCHEMA.equals(textOrNull(root, "schema"))) throw request("schema가 맞지 않습니다.");
        String collectorId = textOrNull(root, "collectorId");
        if (collectorId == null || collectorId.isEmpty()) throw request("collectorId가 필요합니다.");
        if (instantOrNull(root.get("sentAt")) == null) throw request("sentAt이 필요합니다.");
        JsonNode dropped = root.get("droppedSinceLast");
        if (!isCount(dropped)) throw request("droppedSinceLast가 올바르지 않습니다.");
        JsonNode records = root.get("records");
        if (records == null || !records.isArray()) throw request("records가 필요합니다.");
        if (records.isEmpty()) throw request("records가 비었습니다.");
        if (records.size() > MAX_RECORDS)
            throw new BusinessException(ErrorCode.COLLECTOR_BATCH_TOO_LARGE, ErrorCode.COLLECTOR_BATCH_TOO_LARGE.getMessage(),
                    Map.of("maxRecords", MAX_RECORDS));
        List<Record> parsed = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) parsed.add(record(records.get(i), i));
        return new ObservationBatch(collectorId, dropped.asLong(), List.copyOf(parsed));
    }

    private static Record record(JsonNode r, int index) {
        if (r == null || !r.isObject()) throw invalid(index, "record");
        String type = textOrNull(r, "recordType");
        Set<String> extra = type == null ? null : EXTRA.get(type);
        if (extra == null) throw invalid(index, "recordType");
        for (String name : r.propertyNames())
            if (!COMMON.contains(name) && !extra.contains(name)) throw invalid(index, name);
        for (String name : COMMON) if (!r.has(name)) throw invalid(index, name);
        for (String name : extra) if (!r.has(name)) throw invalid(index, name);

        if (!SCHEMA.equals(textOrNull(r, "schema"))) throw invalid(index, "schema");
        if (!nonEmptyText(r.get("collectorId"))) throw invalid(index, "collectorId");
        UUID runId = uuidOrNull(r.get("runId"));
        if (runId == null) throw invalid(index, "runId");
        UUID sessionId = null;
        if (!r.get("sessionId").isNull()) {
            sessionId = uuidOrNull(r.get("sessionId"));
            if (sessionId == null) throw invalid(index, "sessionId");
        }
        JsonNode seq = r.get("sequence");
        if (!isCount(seq) || seq.asLong() < 1) throw invalid(index, "sequence");
        if (!"shure-qlxd4".equals(textOrNull(r, "adapter"))) throw invalid(index, "adapter");
        if (!nonEmptyText(r.get("sourceRef"))) throw invalid(index, "sourceRef");
        String mode = textOrNull(r, "mode");
        if (!oneOf(MODES, mode)) throw invalid(index, "mode");
        JsonNode channel = r.get("channel");
        if (!channel.isIntegralNumber() || !channel.canConvertToInt() || channel.asInt() < 1) throw invalid(index, "channel");
        Instant receivedAt = instantOrNull(r.get("receivedAt"));
        if (receivedAt == null) throw invalid(index, "receivedAt");

        String item = switch (type) {
            case "READING" -> reading(r, index);
            case "LINK" -> { link(r, index); yield null; }
            case "STATUS" -> { status(r, index); yield null; }
            default -> { if (!r.get("frame").isString()) throw invalid(index, "frame"); yield null; }
        };
        return new Record(type, runId, sessionId, seq.asLong(), r.get("sourceRef").asString(), mode,
                channel.asInt(), receivedAt, item, r, hash(r));
    }

    private static String reading(JsonNode r, int index) {
        if (!oneOf(ORIGINS, textOrNull(r, "origin"))) throw invalid(index, "origin");
        if (!nonEmptyText(r.get("param"))) throw invalid(index, "param");
        if (!r.get("raw").isString()) throw invalid(index, "raw");
        if (!scalar(r.get("value"))) throw invalid(index, "value");
        if (!oneOf(VALUE_STATUSES, textOrNull(r, "valueStatus"))) throw invalid(index, "valueStatus");
        JsonNode unit = r.get("unit");
        if (!unit.isNull() && !nonEmptyText(unit)) throw invalid(index, "unit");
        if (!r.get("frame").isString()) throw invalid(index, "frame");
        if (!r.get("observedAt").isNull()) throw invalid(index, "observedAt");
        if (!"RECEIVED".equals(textOrNull(r, "timeBasis"))) throw invalid(index, "timeBasis");
        return r.get("param").asString();
    }

    private static void link(JsonNode r, int index) {
        if (!oneOf(LINK_STATES, textOrNull(r, "linkState"))) throw invalid(index, "linkState");
        JsonNode reason = r.get("reason");
        if (!reason.isNull() && !nonEmptyText(reason)) throw invalid(index, "reason");
    }

    private static void status(JsonNode r, int index) {
        if (!oneOf(LINK_STATES, textOrNull(r, "linkState"))) throw invalid(index, "linkState");
        if (!oneOf(METERING, textOrNull(r, "metering"))) throw invalid(index, "metering");
        JsonNode stale = r.get("staleAfterMs");
        if (!isCount(stale) || stale.asLong() < 1) throw invalid(index, "staleAfterMs");
        JsonNode last = r.get("lastSampleAt");
        if (!last.isNull() && instantOrNull(last) == null) throw invalid(index, "lastSampleAt");
        JsonNode age = r.get("sampleAgeMs");
        if (!age.isNull() && !isCount(age)) throw invalid(index, "sampleAgeMs");
        JsonNode values = r.get("lastValues");
        if (!values.isObject()) throw invalid(index, "lastValues");
        for (var entry : values.properties()) {
            JsonNode v = entry.getValue();
            if (!v.isObject()) throw invalid(index, "lastValues");
            for (String name : v.propertyNames()) if (!LAST_VALUE_FIELDS.contains(name)) throw invalid(index, "lastValues");
            for (String name : LAST_VALUE_FIELDS) if (!v.has(name)) throw invalid(index, "lastValues");
            if (!scalar(v.get("value")) || !nonEmptyText(v.get("valueStatus")) || !v.get("raw").isString()
                    || instantOrNull(v.get("receivedAt")) == null
                    || (!v.get("sessionId").isNull() && uuidOrNull(v.get("sessionId")) == null))
                throw invalid(index, "lastValues");
        }
    }

    /** 키 순서와 공백에 관계없이 같은 내용이면 같은 해시가 되도록 정렬한 JSON의 SHA-256. */
    static String hash(JsonNode node) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsString(sorted(node)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> map = new TreeMap<>();
            for (var entry : node.properties()) map.put(entry.getKey(), sorted(entry.getValue()));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) list.add(sorted(item));
            return list;
        }
        return node;
    }

    private static boolean scalar(JsonNode v) {
        return v != null && (v.isNull() || v.isNumber() || v.isString() || v.isBoolean());
    }

    private static boolean isCount(JsonNode v) {
        return v != null && v.isIntegralNumber() && v.canConvertToLong() && v.asLong() >= 0 && v.asLong() <= MAX_SAFE;
    }

    private static boolean nonEmptyText(JsonNode v) {
        return v != null && v.isString() && !v.asString().isEmpty();
    }

    private static boolean oneOf(Set<String> values, String value) {
        return value != null && values.contains(value);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static UUID uuidOrNull(JsonNode v) {
        if (v == null || !v.isString()) return null;
        try {
            UUID id = UUID.fromString(v.asString());
            return id.toString().equalsIgnoreCase(v.asString()) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instantOrNull(JsonNode v) {
        if (v == null || !v.isString()) return null;
        try {
            return OffsetDateTime.parse(v.asString()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BusinessException request(String why) {
        return new BusinessException(ErrorCode.INVALID_COLLECTOR_REQUEST, ErrorCode.INVALID_COLLECTOR_REQUEST.getMessage(),
                Map.of("reason", why));
    }

    private static BusinessException invalid(int index, String field) {
        return new BusinessException(ErrorCode.COLLECTOR_RECORD_INVALID, ErrorCode.COLLECTOR_RECORD_INVALID.getMessage(),
                Map.of("index", index, "field", field));
    }
}

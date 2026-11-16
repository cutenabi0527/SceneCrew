package com.ssafy.scenecrew.account.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * account.consent JSON 형식(#334·#229, V9-D40).
 * <pre>
 * {"service":true,"voice":true,"push":false,"externalAi":true,
 *  "version":"2026-11-06",
 *  "history":[{"at":"…Z","version":"2026-11-06","source":"SIGNUP","values":{…네 항목…}},
 *             {"at":"…Z","version":"2026-11-06","source":"CHANGE","values":{"voice":false}}]}
 * </pre>
 * 맨 위 네 값이 현재 동의다(음성 전사 등은 이 값을 읽는다). version은 마지막으로 동의한 약관 버전이고,
 * history는 가입·변경 때마다 바뀐 값만 덧붙인다. 이 기능 이전 가입자는 version·history가 없다.
 */
public final class ConsentRecords {
    /** 현재 서비스 약관·개인정보 처리방침 버전. 약관을 바꾸면 이 값을 올린다. */
    public static final String VERSION = "2026-11-06";
    public static final List<String> KEYS = List.of("service", "voice", "push", "externalAi");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ConsentRecords() {}

    public static String initial(boolean service, boolean voice, boolean push, boolean externalAi) {
        var values = new LinkedHashMap<String, Boolean>();
        values.put("service", service);
        values.put("voice", voice);
        values.put("push", push);
        values.put("externalAi", externalAi);
        ObjectNode root = JSON.createObjectNode();
        values.forEach(root::put);
        root.put("version", VERSION);
        root.putArray("history").add(entry("SIGNUP", values, now()));
        return JSON.writeValueAsString(root);
    }

    /** 바뀐 값만 반영하고 이력에 덧붙인다. 바뀐 값이 없으면 null(저장할 것 없음). */
    public static String change(String current, Map<String, Boolean> requested, Instant at) {
        ObjectNode root = parse(current);
        var changed = new LinkedHashMap<String, Boolean>();
        requested.forEach((key, value) -> {
            JsonNode old = root.get(key);
            if (old == null || !old.isBoolean() || old.asBoolean() != value) changed.put(key, value);
        });
        if (changed.isEmpty()) return null;
        changed.forEach(root::put);
        root.put("version", VERSION);
        JsonNode history = root.get("history");
        ArrayNode list = history != null && history.isArray() ? (ArrayNode) history : root.putArray("history");
        list.add(entry("CHANGE", changed, at));
        return JSON.writeValueAsString(root);
    }

    public static ObjectNode parse(String json) {
        JsonNode node = json == null ? null : JSON.readTree(json);
        return node != null && node.isObject() ? (ObjectNode) node : JSON.createObjectNode();
    }

    public static boolean value(ObjectNode root, String key) {
        JsonNode node = root.get(key);
        return node != null && node.isBoolean() && node.asBoolean();
    }

    private static ObjectNode entry(String source, Map<String, Boolean> values, Instant at) {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("at", at.toString());
        entry.put("version", VERSION);
        entry.put("source", source);
        ObjectNode v = entry.putObject("values");
        values.forEach(v::put);
        return entry;
    }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
}

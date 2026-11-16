package com.ssafy.scenecrew.equipment.collector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서버 설정에 둔 수집기 등록 정보(#330). 수집기·토큰·sourceRef→장비 연결·run 목록을 DB 대신 설정으로 관리한다.
 *
 * 설정은 JSON 배열 하나다(환경변수 COLLECTOR_REGISTRATIONS). 토큰 원문은 두지 않고 SHA-256 해시만 둔다.
 * <pre>
 * [{"collectorId":"collector-field-laptop","eventId":"…","tokenSha256":"…",
 *   "sources":{"qlxd4-main":"&lt;equipmentAssetId&gt;"},
 *   "runs":[{"runId":"…","active":false},{"runId":"…","active":true}]}]
 * </pre>
 * runs는 오래된 것부터 적는다. 순서가 뒤인 run의 기록이 앞 run의 최신 값을 덮는다.
 * 새 run을 열거나 토큰을 바꾸려면 설정을 고치고 서버를 다시 시작한다(backend/docs/equipment-collector.md).
 */
public final class CollectorRegistry {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Map<String, Collector> collectors;

    private CollectorRegistry(Map<String, Collector> collectors) {
        this.collectors = Map.copyOf(collectors);
    }

    public static CollectorRegistry parse(String json) {
        if (json == null || json.isBlank()) return new CollectorRegistry(Map.of());
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("수집기 등록 설정(JSON)을 읽을 수 없습니다.", e);
        }
        if (!root.isArray()) throw new IllegalStateException("수집기 등록 설정은 JSON 배열이어야 합니다.");
        Map<String, Collector> result = new LinkedHashMap<>();
        for (JsonNode node : root) {
            Collector c = collector(node);
            if (result.putIfAbsent(c.id(), c) != null)
                throw new IllegalStateException("같은 collectorId가 두 번 등록됐습니다: " + c.id());
        }
        return new CollectorRegistry(result);
    }

    public static CollectorRegistry of(Collector... collectors) {
        Map<String, Collector> result = new LinkedHashMap<>();
        for (Collector c : collectors) result.put(c.id(), c);
        return new CollectorRegistry(result);
    }

    public Optional<Collector> find(String collectorId) {
        return Optional.ofNullable(collectors.get(collectorId));
    }

    public boolean isEmpty() {
        return collectors.isEmpty();
    }


    /** 토큰 원문의 SHA-256(소문자 16진수). 등록 설정을 만들 때와 요청을 확인할 때 같은 함수를 쓴다. */
    public static String sha256(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Collector collector(JsonNode node) {
        String id = text(node, "collectorId");
        UUID eventId = uuid(text(node, "eventId"), "eventId");
        String hash = text(node, "tokenSha256").toLowerCase();
        if (!hash.matches("[0-9a-f]{64}"))
            throw new IllegalStateException(id + ": tokenSha256은 64자리 16진수여야 합니다.");
        JsonNode sourcesNode = node.get("sources");
        if (sourcesNode == null || !sourcesNode.isObject() || sourcesNode.isEmpty())
            throw new IllegalStateException(id + ": sources가 필요합니다.");
        Map<String, UUID> sources = new LinkedHashMap<>();
        for (var entry : sourcesNode.properties()) {
            if (!entry.getValue().isString())
                throw new IllegalStateException(id + ": sources 값은 장비 ID 문자열이어야 합니다.");
            sources.put(entry.getKey(), uuid(entry.getValue().asString(), "sources." + entry.getKey()));
        }
        JsonNode runsNode = node.get("runs");
        if (runsNode == null || !runsNode.isArray() || runsNode.isEmpty())
            throw new IllegalStateException(id + ": runs가 필요합니다.");
        List<Run> runs = new ArrayList<>();
        for (JsonNode r : runsNode) {
            UUID runId = uuid(text(r, "runId"), "runId");
            JsonNode active = r.get("active");
            if (active == null || !active.isBoolean())
                throw new IllegalStateException(id + ": run.active는 true/false여야 합니다.");
            if (runs.stream().anyMatch(x -> x.id().equals(runId)))
                throw new IllegalStateException(id + ": 같은 runId가 두 번 등록됐습니다.");
            runs.add(new Run(runId, active.asBoolean(), runs.size()));
        }
        return new Collector(id, eventId, hash, Map.copyOf(sources), List.copyOf(runs));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank())
            throw new IllegalStateException("수집기 등록 설정에 " + field + "가 필요합니다.");
        return value.asString();
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("수집기 등록 설정의 " + field + "가 UUID가 아닙니다.");
        }
    }

    /**
     * @param web 연결 코드로 DB에 등록한 수집기(#394)면 true. 설정 등록은 false다.
     */
    public record Collector(String id, UUID eventId, String tokenSha256, Map<String, UUID> sources, List<Run> runs, boolean web) {
        public Collector(String id, UUID eventId, String tokenSha256, Map<String, UUID> sources, List<Run> runs) {
            this(id, eventId, tokenSha256, sources, runs, false);
        }

        public Optional<Run> run(UUID runId) {
            return runs.stream().filter(r -> r.id().equals(runId)).findFirst();
        }

        /** 토큰 원문이 등록 해시와 같은지 일정한 시간으로 비교한다. */
        public boolean accepts(String token) {
            byte[] expected = tokenSha256.getBytes(StandardCharsets.US_ASCII);
            byte[] actual = sha256(token).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, actual);
        }
    }

    /** order는 설정에 적힌 순서다. 값이 클수록 나중 run이다. */
    public record Run(UUID id, boolean active, int order) {
    }
}

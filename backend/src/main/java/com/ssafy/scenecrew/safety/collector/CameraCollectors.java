package com.ssafy.scenecrew.safety.collector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서버 설정에 둔 카메라 수집기 등록 정보(#349). 장비 수집기(CollectorRegistry)와 같은 방식이다.
 *
 * <p>설정은 JSON 배열 하나다(환경변수 CAMERA_COLLECTOR_REGISTRATIONS). 토큰 원문은 두지 않고 SHA-256 해시만 둔다.
 * <pre>
 * [{"collectorId":"camera-field-laptop","eventId":"…","tokenSha256":"…","sources":["webcam-1"]}]
 * </pre>
 * 수집기는 행사 하나와, 그 행사에서 보낼 수 있는 연결 식별자(sources)에 묶인다(BE13: 수집 주체를 행사·카메라에 한정).
 * 어느 카메라의 프레임인지는 앱에서 등록한 카메라의 연결 식별자(sourceBindingRef)로 찾는다.
 */
public final class CameraCollectors {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Map<String, Collector> collectors;

    private CameraCollectors(Map<String, Collector> collectors) {
        this.collectors = Map.copyOf(collectors);
    }

    public static CameraCollectors parse(String json) {
        if (json == null || json.isBlank()) return new CameraCollectors(Map.of());
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("카메라 수집기 등록 설정(JSON)을 읽을 수 없습니다.", e);
        }
        if (!root.isArray()) throw new IllegalStateException("카메라 수집기 등록 설정은 JSON 배열이어야 합니다.");
        Map<String, Collector> result = new LinkedHashMap<>();
        for (JsonNode node : root) {
            String id = text(node, "collectorId");
            UUID eventId;
            try {
                eventId = UUID.fromString(text(node, "eventId"));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(id + ": eventId가 UUID가 아닙니다.");
            }
            String hash = text(node, "tokenSha256").toLowerCase();
            if (!hash.matches("[0-9a-f]{64}")) throw new IllegalStateException(id + ": tokenSha256은 64자리 16진수여야 합니다.");
            JsonNode sourcesNode = node.get("sources");
            if (sourcesNode == null || !sourcesNode.isArray() || sourcesNode.isEmpty())
                throw new IllegalStateException(id + ": sources(보낼 수 있는 연결 식별자 목록)가 필요합니다.");
            Set<String> sources = new LinkedHashSet<>();
            for (JsonNode s : sourcesNode) {
                if (!s.isString() || !s.asString().matches(CameraFrameService.SOURCE))
                    throw new IllegalStateException(id + ": sources 값은 1~100자 [A-Za-z0-9._:-]여야 합니다.");
                sources.add(s.asString());
            }
            if (result.putIfAbsent(id, new Collector(id, eventId, hash, Set.copyOf(sources))) != null)
                throw new IllegalStateException("같은 collectorId가 두 번 등록됐습니다: " + id);
        }
        return new CameraCollectors(result);
    }

    public static CameraCollectors of(Collector... collectors) {
        Map<String, Collector> result = new LinkedHashMap<>();
        for (Collector c : collectors) result.put(c.id(), c);
        return new CameraCollectors(result);
    }

    public Optional<Collector> find(String collectorId) {
        return Optional.ofNullable(collectors.get(collectorId));
    }

    /** 토큰 원문의 SHA-256(소문자 16진수). 등록 설정을 만들 때와 요청을 확인할 때 같은 함수를 쓴다. */
    public static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank())
            throw new IllegalStateException("카메라 수집기 등록 설정에 " + field + "가 필요합니다.");
        return value.asString();
    }

    /** @param sources 이 수집기가 보낼 수 있는 연결 식별자(카메라의 sourceBindingRef) */
    public record Collector(String id, UUID eventId, String tokenSha256, Set<String> sources) {
        /** 토큰 원문이 등록 해시와 같은지 일정한 시간으로 비교한다. */
        public boolean accepts(String token) {
            return MessageDigest.isEqual(tokenSha256.getBytes(StandardCharsets.US_ASCII),
                    sha256(token).getBytes(StandardCharsets.US_ASCII));
        }
    }
}

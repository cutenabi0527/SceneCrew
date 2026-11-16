package com.ssafy.scenecrew.safety.collector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.aijob.client.AiClient;
import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.client.AiTransportException;

/**
 * AI 서버 밀집도 경로 호출(POST /internal/estimate-crowd, #360). 형식은 backend/docs/safety-camera.md.
 *
 * <p>AI Job 대기열을 쓰지 않고 직접 호출한다. 주기적으로 반복되고, 늦은 결과는 다음 프레임이 대신하므로 재시도·보관이 필요 없다.
 * 응답은 그대로 믿지 않는다. 요청한 구역만, 0 이상 유한한 수만 받고 하나라도 어긋나면 이번 결과 전체를 버린다.
 */
public class AiCrowdEstimator implements CrowdEstimator {
    public static final String PATH = "/internal/estimate-crowd";
    static final double MAX_COUNT = 100_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AiClient client;
    private final Duration timeout;

    public AiCrowdEstimator(AiClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    public Result estimate(Request request) throws EstimateException, InterruptedException {
        AiResponse response;
        try {
            response = client.post(PATH, body(request), timeout);
        } catch (AiTransportException e) {
            throw new EstimateException(e.isTimeout() ? "밀집도 AI 응답 시간 초과" : "밀집도 AI 연결 실패", e);
        }
        if (response.status() != 200) throw new EstimateException("밀집도 AI 응답 HTTP " + response.status(), null);
        try {
            return parse(JSON.readTree(response.body()), request);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new EstimateException("밀집도 AI 응답 형식 오류: " + e.getMessage(), e);
        }
    }

    static String body(Request request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "1");
        body.put("eventId", request.eventId().toString());
        body.put("cameraId", request.cameraId().toString());
        body.put("streamSessionId", request.streamSessionId().toString());
        body.put("sequence", Long.toString(request.sequence()));
        body.put("capturedAt", request.capturedAt().toString());
        body.put("inputHash", request.inputHash());
        body.put("image", Map.of("contentType", "image/jpeg", "base64", Base64.getEncoder().encodeToString(request.jpeg())));
        List<Map<String, Object>> zones = new ArrayList<>();
        for (Zone z : request.zones()) {
            Map<String, Object> zone = new LinkedHashMap<>();
            zone.put("zoneId", z.zoneId().toString());
            zone.put("scope", z.scope());
            zone.put("roi", z.roi());
            zones.add(zone);
        }
        body.put("zones", zones);
        return JSON.writeValueAsString(body);
    }

    static Result parse(JsonNode root, Request request) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("본문이 객체가 아닙니다");
        JsonNode model = root.get("modelVersion");
        if (model == null || !model.isString() || model.asString().isBlank() || model.asString().length() > 100)
            throw new IllegalArgumentException("modelVersion");
        JsonNode zones = root.get("zones");
        if (zones == null || !zones.isArray()) throw new IllegalArgumentException("zones");
        Set<UUID> asked = new HashSet<>();
        for (Zone z : request.zones()) asked.add(z.zoneId());
        Set<UUID> seen = new HashSet<>();
        List<ZoneCount> out = new ArrayList<>();
        for (JsonNode z : zones) {
            JsonNode id = z.get("zoneId");
            if (id == null || !id.isString()) throw new IllegalArgumentException("zones[].zoneId");
            UUID zoneId = UUID.fromString(id.asString());
            if (!asked.contains(zoneId)) throw new IllegalArgumentException("요청하지 않은 구역 " + zoneId);
            if (!seen.add(zoneId)) throw new IllegalArgumentException("같은 구역이 두 번 " + zoneId);
            JsonNode count = z.get("estimatedCount");
            Double value = null;
            if (count != null && !count.isNull()) {
                if (!count.isNumber()) throw new IllegalArgumentException("zones[].estimatedCount");
                double v = count.asDouble();
                if (!Double.isFinite(v) || v < 0 || v > MAX_COUNT) throw new IllegalArgumentException("zones[].estimatedCount 범위");
                value = v;
            } else if (count == null) {
                throw new IllegalArgumentException("zones[].estimatedCount 없음");
            }
            JsonNode reasons = z.get("qualityReasons");
            if (reasons == null || !reasons.isArray() || reasons.size() > 10) throw new IllegalArgumentException("zones[].qualityReasons");
            List<String> list = new ArrayList<>();
            for (JsonNode r : reasons) {
                if (!r.isString() || !r.asString().matches("[A-Z][A-Z0-9_]{0,63}"))
                    throw new IllegalArgumentException("zones[].qualityReasons 값");
                list.add(r.asString());
            }
            out.add(new ZoneCount(zoneId, value, List.copyOf(list)));
        }
        return new Result(model.asString(), out);
    }
}

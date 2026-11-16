package com.ssafy.scenecrew.equipment.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장비 자동 관측 상태와 핵심 값(V9-D59·D60, #394). 장비 현황 조회(EXT01·03)와 내 연결 목록(L01)이 같은 값을 쓴다.
 *
 * <p>상태(state):
 * <ul>
 *   <li>UNLINKED(등록 안됨): 웹 연결도 설정 경로 기록도 없다. CODE_PENDING(코드 대기): 그중 이 행사에 쓰지 않은 유효 연결 코드가 있다.
 *       코드는 장비가 아니라 사람·노트북 단위라, 코드가 하나라도 있으면 연결 안 된 자동 관측 장비 모두가 코드 대기다.</li>
 *   <li>PROGRAM_OFFLINE(프로그램 끊김): 15초 동안 관측 묶음이 없다. 수신기 보고만 오고 묶음이 없어도 끊김이다(#394 리뷰).</li>
 *   <li>RECEIVER_NOT_RESPONDING(수신기 응답 없음): 묶음은 오지만 그 수신기의 STATUS linkState가 OFFLINE이다.</li>
 *   <li>CONNECTED(연결됨): 그 밖.</li>
 * </ul>
 * 핵심 값은 READING 최신 값 중 valueStatus=OK인 것만 쓰고 모르면 null이다(미수신·모르는 값을 0·정상으로 바꾸지 않는다).
 * 송신기 켜짐은 TX_TYPE이 OK면 true, UNKNOWN_VALUE(수신기가 송신기를 못 찾음)면 false다. 기술 상태는 이 값으로
 * AutoTechnicalStates가 따로 맞춘다(미확인이거나 그 규칙이 정한 상태일 때만, 2026-11-13 결정).
 * 수집기 토큰·장비 IP·원문은 넣지 않는다.
 */
public final class AutoObservations {
    public static final Duration OFFLINE_AFTER = Duration.ofSeconds(15);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AutoObservations() {
    }

    /**
     * @param inputKind WEB(웹 연결)·CONFIG(서버 설정 경로)·null(입력 없음)
     * @param rfLevel·audioLevel 수신기 원시 눈금(RF 0~115, 오디오 0~50)
     */
    public record AutoObservation(String kind, String inputKind, String state, Boolean transmitterOn, Integer batteryBars,
            Integer rfLevel, Integer audioLevel, Instant lastReceivedAt) {
    }

    /** 자동 관측 종류가 있는 장비만 값이 있다. 없는 장비는 결과에 없다. */
    public static Map<UUID, AutoObservation> of(JdbcTemplate jdbc, UUID eventId, Collection<UUID> assetIds, Instant now) {
        Map<UUID, AutoObservation> out = new HashMap<>();
        if (assetIds.isEmpty()) return out;
        UUID[] ids = assetIds.toArray(new UUID[0]);
        Map<UUID, Asset> assets = new HashMap<>();
        jdbc.query("""
                SELECT a.id, a.auto_observation_kind, a.link_collector_id, c.last_batch_at
                  FROM equipment_asset a LEFT JOIN equipment_collector c ON c.id = a.link_collector_id
                 WHERE a.id = ANY(?) AND a.auto_observation_kind IS NOT NULL""", rs -> {
            var seen = rs.getTimestamp(4);
            assets.put(rs.getObject(1, UUID.class), new Asset(rs.getString(2), rs.getString(3),
                    seen == null ? null : seen.toInstant()));
        }, (Object) ids);
        if (assets.isEmpty()) return out;

        Map<UUID, Map<String, Latest>> latest = new HashMap<>();
        jdbc.query("""
                SELECT equipment_asset_id, record_type, item, payload::text, collector_id, server_received_at
                  FROM equipment_observation_latest WHERE equipment_asset_id = ANY(?)""", rs -> {
            latest.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>()).put(rs.getString(2) + ":" + rs.getString(3),
                    new Latest(rs.getString(2), rs.getString(3), JSON.readTree(rs.getString(4)), rs.getString(5),
                            rs.getTimestamp(6).toInstant()));
        }, (Object) assets.keySet().toArray(new UUID[0]));
        Boolean pending = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM equipment_collector_code WHERE event_id = ? AND used_at IS NULL AND expires_at > ?)""",
                Boolean.class, eventId, now.atOffset(ZoneOffset.UTC));

        for (var entry : assets.entrySet()) {
            Asset a = entry.getValue();
            Map<String, Latest> values = latest.getOrDefault(entry.getKey(), Map.of());
            String inputKind;
            Instant lastBatch;
            if (a.collector() != null) {
                inputKind = "WEB";
                lastBatch = a.seen();
                // 지금 연결된 수집기가 보낸 값만 쓴다. 예전 연결(다른 수집기)이 남긴 최신 값이 새 값처럼 보이거나
                // 기술 상태를 정하지 않게 한다(#399 실기: 다시 연결한 직후 오전 값이 마지막 수신·배터리로 보였다).
                values = values.entrySet().stream().filter(e -> a.collector().equals(e.getValue().collector()))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            } else {
                // 설정 경로(예비) 기록만 본다. 해제한 웹 연결의 남은 값은 쓰지 않는다.
                Instant config = values.values().stream().filter(l -> !l.collector().startsWith("web-"))
                        .map(Latest::received).max(Instant::compareTo).orElse(null);
                inputKind = config == null ? null : "CONFIG";
                lastBatch = config;
            }
            if (inputKind == null) {
                out.put(entry.getKey(), new AutoObservation(a.kind(), null, Boolean.TRUE.equals(pending) ? "CODE_PENDING" : "UNLINKED",
                        null, null, null, null, null));
                continue;
            }
            String state;
            if (lastBatch == null || lastBatch.isBefore(now.minus(OFFLINE_AFTER))) state = "PROGRAM_OFFLINE";
            else {
                Latest status = values.get("STATUS:");
                boolean offline = status != null && "OFFLINE".equals(status.payload().path("linkState").asString(""));
                state = offline ? "RECEIVER_NOT_RESPONDING" : "CONNECTED";
            }
            Latest tx = values.get("READING:TX_TYPE");
            Boolean transmitterOn = tx == null ? null : switch (tx.payload().path("valueStatus").asString("")) {
                case "OK" -> true;
                case "UNKNOWN_VALUE" -> false;
                default -> null;
            };
            Instant received = values.values().stream().map(Latest::received).max(Instant::compareTo).orElse(null);
            out.put(entry.getKey(), new AutoObservation(a.kind(), inputKind, state, transmitterOn,
                    number(values.get("READING:BATT_BARS")), number(values.get("READING:RF_LEVEL")),
                    number(values.get("READING:AUDIO_LEVEL")), received));
        }
        return out;
    }

    private record Asset(String kind, String collector, Instant seen) { }

    private record Latest(String type, String item, JsonNode payload, String collector, Instant received) { }

    private static Integer number(Latest latest) {
        if (latest == null) return null;
        JsonNode payload = latest.payload();
        if (!"OK".equals(payload.path("valueStatus").asString("")) || !payload.path("value").isIntegralNumber()) return null;
        return payload.get("value").asInt();
    }
}

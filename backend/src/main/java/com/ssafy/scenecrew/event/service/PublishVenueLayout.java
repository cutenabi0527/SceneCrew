package com.ssafy.scenecrew.event.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.evidence.EvidenceInput;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.safety.service.VenueLayoutBackgrounds;

/**
 * 발행(029) 때 미확정 초안의 평면도(venueDraft.layout)를 안전 지도(venue_layout)로 저장한다(V9-D45, S15P31A202-280).
 * 화면이 계획 생성 결과·직접 입력으로 그린 구역 도형을 미확정 초안(unconfirmed-draft.md)에 보관해 두면, 총괄이 발행할 때 확정된다.
 *
 * <ul>
 *   <li>평면도가 없거나(null) 이미 안전 지도가 있으면 아무것도 하지 않는다.</li>
 *   <li>도형은 지금 이 행사의 구역(UUID, 대소문자 무관)을 가리키는 것만 저장한다. 임시 id(tmp-)·지워진 구역·같은 구역의
 *       두 번째 도형·점이 50개를 넘는 도형(안전 지도 저장 EXT11과 같은 상한)은 빼고, 남는 도형이 없으면 저장하지 않는다.
 *       발행을 막지 않기 위해서다(구역 확인은 발행 계획 검사가 따로 한다). 화면이 후보 구역(tmp-)에 그린 도형은
 *       028 저장 뒤 미확정 초안의 zoneId를 새 구역 id로 바꿔 두어야 저장된다.</li>
 *   <li>근거(source)는 안전 지도 저장(EXT11)과 같은 V9Evidence로 바꾸고, 확인자는 발행한 총괄이다.</li>
 *   <li>운영 기록 SAFETY_MAP_SAVED를 남긴다. Event revision은 발행이 한 번만 올린다.</li>
 *   <li>근거가 입력 PDF의 한 쪽이면 발행이 커밋된 뒤 그 쪽 그림을 배경으로 저장한다(V9-D62, VenueLayoutBackgrounds).</li>
 * </ul>
 * 호출자가 같은 트랜잭션에서 행사를 배타 잠금한 상태여야 한다.
 */
@Component
public class PublishVenueLayout {
    private static final Logger log = LoggerFactory.getLogger(PublishVenueLayout.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String UUID_FORMAT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** 안전 지도 저장(EXT11, SafetyRequests.MAX_POINTS)과 같은 도형 점 상한. 넘으면 나중에 화면에서 다시 저장할 때 400이 난다. */
    static final int MAX_POINTS = 50;

    private final JdbcTemplate jdbc;
    private final EventWriteSupport events;
    private final VenueLayoutBackgrounds backgrounds;

    public PublishVenueLayout(JdbcTemplate jdbc, EventWriteSupport events, VenueLayoutBackgrounds backgrounds) {
        this.jdbc = jdbc;
        this.events = events;
        this.backgrounds = backgrounds;
    }

    /** 저장한 평면도 id. 저장하지 않았으면 null */
    public UUID save(UUID eventId, UUID chiefMemberId) {
        String layoutJson = jdbc.query("""
                SELECT payload -> 'venueDraft' -> 'layout' FROM event_unconfirmed_draft WHERE event_id = ?""",
                (r, n) -> r.getString(1), eventId).stream().findFirst().orElse(null);
        if (layoutJson == null) return null;
        JsonNode layout = MAPPER.readTree(layoutJson);
        if (layout == null || !layout.isObject()) return null;
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM venue_layout WHERE event_id = ?)",
                Boolean.class, eventId);
        if (Boolean.TRUE.equals(exists)) return null;

        Set<String> zones = new HashSet<>(jdbc.queryForList("SELECT id::text FROM zone WHERE event_id = ?", String.class, eventId));
        Set<String> used = new HashSet<>();
        List<Map<String, Object>> polygons = new ArrayList<>();
        for (JsonNode polygon : layout.path("zonePolygons")) {
            // 미확정 초안은 대문자 UUID도 받는다. 구역 id(소문자)와 같은 기준으로 비교·저장한다.
            String zoneId = polygon.path("zoneId").asString("").toLowerCase(java.util.Locale.ROOT);
            if (!zoneId.matches(UUID_FORMAT) || !zones.contains(zoneId)) continue;
            if (polygon.path("points").size() > MAX_POINTS) {
                log.warn("점이 {}개를 넘는 평면도 도형은 안전 지도로 옮기지 않았습니다: eventId={}, zoneId={}", MAX_POINTS, eventId, zoneId);
                continue;
            }
            if (!used.add(zoneId)) continue;
            List<Map<String, Object>> points = new ArrayList<>();
            for (JsonNode p : polygon.path("points")) points.add(Map.of("x", p.get("x").decimalValue(), "y", p.get("y").decimalValue()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("zoneId", zoneId);
            m.put("points", points);
            polygons.add(m);
        }
        if (polygons.isEmpty()) return null;

        Instant now = EventWriteSupport.now();
        Map<String, Object> source;
        try {
            source = EvidenceInput.parse(layout.get("source"), "venueDraft.layout.source").stored(chiefMemberId, now);
        } catch (BusinessException | NullPointerException e) {
            // 미확정 초안 저장 때 이미 검사한 값이라 오지 않는다. 와도 발행은 막지 않고 평면도만 넘긴다.
            log.warn("평면도 초안의 근거를 읽지 못해 안전 지도를 저장하지 않았습니다: eventId={}", eventId);
            return null;
        }
        UUID layoutId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO venue_layout (id, event_id, revision, coordinate_system, width, height, zone_polygons, source,
                                          confirmed_by_member_id, confirmed_at)
                VALUES (?, ?, 1, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)""",
                layoutId, eventId, layout.path("coordinateSystem").asString(), layout.get("width").decimalValue(),
                layout.get("height").decimalValue(), EventWriteSupport.json(polygons), EventWriteSupport.json(source),
                chiefMemberId, now.atOffset(java.time.ZoneOffset.UTC));
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("layoutRevision", 1);
        changes.put("zones", polygons.stream().map(p -> p.get("zoneId")).toList());
        changes.put("from", "UNCONFIRMED_DRAFT");
        events.log(eventId, "SAFETY_MAP_SAVED", chiefMemberId, "VENUE_LAYOUT", layoutId, changes, now);
        backgrounds.saveAfterCommit(eventId, layoutId, layout.path("source").path("sourceRef").asString(null));
        return layoutId;
    }
}

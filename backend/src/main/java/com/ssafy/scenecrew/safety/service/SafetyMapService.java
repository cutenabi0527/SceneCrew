package com.ssafy.scenecrew.safety.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.evidence.EvidenceInput;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.global.operation.EventWriteSupport.EventRow;
import com.ssafy.scenecrew.safety.service.SafetyCommands.CrewRule;
import com.ssafy.scenecrew.safety.service.SafetyCommands.MapWrite;
import com.ssafy.scenecrew.safety.service.SafetyCommands.Point;
import com.ssafy.scenecrew.safety.service.SafetyCommands.Polygon;
import com.ssafy.scenecrew.safety.service.SafetyCommands.Profile;
import com.ssafy.scenecrew.safety.service.SafetyCommands.Rule;
import com.ssafy.scenecrew.safety.service.SafetyViews.LayoutView;
import com.ssafy.scenecrew.safety.service.SafetyViews.Receipt;

/**
 * 안전 지도 조회 EXT10·저장 EXT11. 규칙은 backend/docs/safety-api.md.
 *
 * <p>저장은 평면도와 구역 프로필 묶음을 원자적으로 교체한다(요청에 없는 기존 프로필은 지운다). 평면도 revision은 크기·도형이
 * 바뀔 때만, 프로필 revision은 내용이나 묶인 평면도 revision이 바뀔 때만 올린다. 그대로인 근거는 저장한 기록 시각·확인자를
 * 유지한다. 바뀐 revision을 가리키는 카메라는 재보정 전까지 INVALID_MAPPING이다(옛 관측 행은 그대로 둔다).
 */
public class SafetyMapService {
    static final String SAVE = "EXT11";

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final Idempotency idempotency;
    private final EventWriteSupport events;
    private final SafetySupportProtection support;
    private final VenueLayoutBackgrounds backgrounds;

    public SafetyMapService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency, EventWriteSupport events,
            SafetySupportProtection support, VenueLayoutBackgrounds backgrounds) {
        this.jdbc = jdbc;
        this.members = members;
        this.idempotency = idempotency;
        this.events = events;
        this.support = support;
        this.backgrounds = backgrounds;
    }

    /** EXT10. 관측은 각 카메라의 최신 관측이다. */
    @Transactional(readOnly = true)
    public SafetyViews.SafetyMap read(UUID eventId, UUID accountId) {
        requireChief(members.require(eventId, accountId));
        long revision = jdbc.queryForObject("SELECT revision FROM event WHERE id = ?", Long.class, eventId);
        LayoutView layout = jdbc.query("SELECT " + SafetyViews.LAYOUT_COLUMNS + " FROM venue_layout l WHERE l.event_id = ?",
                SafetyViews::layout, eventId).stream().findFirst().orElse(null);
        var profiles = jdbc.query("SELECT " + SafetyViews.PROFILE_COLUMNS + """
                 FROM safety_zone_profile p JOIN zone z ON z.id = p.zone_id
                WHERE p.event_id = ? ORDER BY z.name COLLATE "C", p.id""", SafetyViews::profile, eventId);
        var observations = jdbc.query("SELECT " + SafetyViews.OBSERVATION_COLUMNS + """
                 FROM safety_observation o JOIN safety_camera c ON c.latest_observation_id = o.id
                WHERE c.event_id = ? ORDER BY o.received_at DESC, o.id""", SafetyViews::observation, eventId);
        return new SafetyViews.SafetyMap(eventId, Long.toString(revision), layout, profiles, observations);
    }

    /** V9-D62. 발행 때 저장한 평면도 배경 PNG. 총괄만 보고, 없으면 404다. */
    @Transactional(readOnly = true)
    public VenueLayoutBackgrounds.Background background(UUID eventId, UUID accountId) {
        requireChief(members.require(eventId, accountId));
        var background = backgrounds.read(eventId);
        if (background == null) throw new BusinessException(ErrorCode.NOT_FOUND, "평면도 배경 그림이 없습니다.");
        return background;
    }

    /** EXT11. 영수증은 평면도 id·revision이다. */
    @Transactional
    public IdempotentResult<Receipt> save(UUID eventId, UUID accountId, String key, MapWrite command) {
        EventRow event = events.lock(eventId);
        Member member = members.requireLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, SAVE, key, IdempotencyRequest.hash(eventId, command.canonical()),
                eventId, member.getId(), member.getParticipationRevision());
        return idempotency.run(request, Receipt.class, () -> {
            requireChief(member);
            EventWriteSupport.requireState(event, "RECRUITING", "LIVE");
            EventWriteSupport.requireRevision(event.revision(), command.expectedEventRevision(), "event");
            Instant now = EventWriteSupport.now();
            var staffing = support.capture(eventId, now);

            var current = jdbc.query("""
                    SELECT id, revision, width, height, zone_polygons::text AS polygons FROM venue_layout
                     WHERE event_id = ? FOR UPDATE""", (r, n) -> new Object[] { r.getObject("id", UUID.class),
                    r.getLong("revision"), r.getBigDecimal("width"), r.getBigDecimal("height"), r.getString("polygons") },
                    eventId).stream().findFirst().orElse(null);
            Long currentRevision = current == null ? null : (Long) current[1];
            if (!Objects.equals(currentRevision, command.expectedLayoutRevision())) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                        Map.of("target", "layout"));
            }
            requireZones(eventId, command.zonePolygons());
            requireCrewGroups(eventId, command.profiles());

            String polygons = EventWriteSupport.json(polygons(command.zonePolygons()));
            String source = EventWriteSupport.json(command.source().stored(member.getId(), now));
            UUID layoutId;
            long layoutRevision;
            if (current == null) {
                layoutId = UUID.randomUUID();
                layoutRevision = 1;
                jdbc.update("""
                        INSERT INTO venue_layout (id, event_id, revision, coordinate_system, width, height, zone_polygons, source,
                                                  confirmed_by_member_id, confirmed_at)
                        VALUES (?, ?, 1, 'normalized-image', ?, ?, ?::jsonb, ?::jsonb, ?, ?)""",
                        layoutId, eventId, command.width(), command.height(), polygons, source, member.getId(), utc(now));
            } else {
                layoutId = (UUID) current[0];
                boolean changed = ((BigDecimal) current[2]).compareTo(command.width()) != 0
                        || ((BigDecimal) current[3]).compareTo(command.height()) != 0
                        || !samePolygons(SafetyViews.json((String) current[4]), command.zonePolygons());
                layoutRevision = changed ? currentRevision + 1 : currentRevision;
                jdbc.update("""
                        UPDATE venue_layout SET revision = ?, width = ?, height = ?, zone_polygons = ?::jsonb, source = ?::jsonb,
                               confirmed_by_member_id = ?, confirmed_at = ?
                         WHERE id = ?""", layoutRevision, command.width(), command.height(), polygons, source,
                        member.getId(), utc(now), layoutId);
            }
            Map<UUID, Long> profileRevisions = saveProfiles(eventId, layoutId, layoutRevision, command.profiles(), member, now);
            support.requireProfileChange(eventId, now, staffing);
            int invalidated = invalidateCameras(eventId, layoutId, layoutRevision, profileRevisions);

            long eventRevision = events.bump(eventId);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("layoutRevision", layoutRevision);
            changes.put("zones", command.zonePolygons().stream().map(p -> p.zoneId().toString()).toList());
            changes.put("profiles", command.profiles().size());
            changes.put("invalidatedCameras", invalidated);
            changes.put("note", command.source().note());
            events.log(eventId, "SAFETY_MAP_SAVED", member.getId(), "VENUE_LAYOUT", layoutId, changes, now);
            return IdempotentResult.of(200, Receipt.of(layoutId, layoutRevision, eventRevision), "SAFETY_MAP_SAVED",
                    Map.of("eventId", eventId.toString(), "layoutId", layoutId.toString(),
                            "eventRevision", Long.toString(eventRevision)));
        });
    }

    /** 프로필 교체. 돌려주는 값은 저장 뒤 구역별 프로필 revision이다. */
    private Map<UUID, Long> saveProfiles(UUID eventId, UUID layoutId, long layoutRevision, List<Profile> profiles,
            Member member, Instant now) {
        Map<UUID, Map<String, Object>> existing = new HashMap<>();
        jdbc.query("""
                SELECT id, zone_id, revision, layout_revision, effective_area_m2, area_evidence::text AS area_evidence,
                       risk_rule::text AS risk_rule, minimum_crew_rule::text AS minimum_crew_rule
                  FROM safety_zone_profile WHERE event_id = ? FOR UPDATE""", rs -> {
            Map<String, Object> row = new HashMap<>();
            row.put("id", rs.getObject("id", UUID.class));
            row.put("revision", rs.getLong("revision"));
            row.put("layoutRevision", rs.getLong("layout_revision"));
            row.put("area", rs.getBigDecimal("effective_area_m2"));
            row.put("areaEvidence", SafetyViews.nullableJson(rs.getString("area_evidence")));
            row.put("riskRule", SafetyViews.nullableJson(rs.getString("risk_rule")));
            row.put("crewRule", SafetyViews.nullableJson(rs.getString("minimum_crew_rule")));
            existing.put(rs.getObject("zone_id", UUID.class), row);
        }, eventId);

        Map<UUID, Long> revisions = new HashMap<>();
        for (int i = 0; i < profiles.size(); i++) {
            Profile p = profiles.get(i);
            Map<String, Object> before = existing.get(p.zoneId());
            Long beforeRevision = before == null ? null : (Long) before.get("revision");
            if (!Objects.equals(beforeRevision, p.expectedProfileRevision())) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                        Map.of("target", "profile", "zoneId", p.zoneId().toString()));
            }
            Object areaEvidence = keep(before == null ? null : (JsonNode) before.get("areaEvidence"), p.areaEvidence(),
                    member, now);
            Object riskRule = rule(before == null ? null : (JsonNode) before.get("riskRule"), p.riskRule(), member, now);
            Object crewRule = crew(before == null ? null : (JsonNode) before.get("crewRule"), p.minimumCrewRule(), member, now);
            if (before == null) {
                jdbc.update("""
                        INSERT INTO safety_zone_profile (id, event_id, zone_id, layout_id, layout_revision, effective_area_m2,
                                                         area_evidence, risk_rule, minimum_crew_rule, confirmed_by_member_id,
                                                         confirmed_at, revision)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, 1)""",
                        UUID.randomUUID(), eventId, p.zoneId(), layoutId, layoutRevision, p.effectiveAreaM2(),
                        nullableJson(areaEvidence), nullableJson(riskRule), nullableJson(crewRule), member.getId(), utc(now));
                revisions.put(p.zoneId(), 1L);
                continue;
            }
            boolean changed = (Long) before.get("layoutRevision") != layoutRevision
                    || !sameNumber((BigDecimal) before.get("area"), p.effectiveAreaM2())
                    || !Objects.equals(before.get("areaEvidence"), asNode(areaEvidence))
                    || !Objects.equals(before.get("riskRule"), asNode(riskRule))
                    || !Objects.equals(before.get("crewRule"), asNode(crewRule));
            long revision = changed ? beforeRevision + 1 : beforeRevision;
            if (changed) {
                jdbc.update("""
                        UPDATE safety_zone_profile SET layout_id = ?, layout_revision = ?, effective_area_m2 = ?,
                               area_evidence = ?::jsonb, risk_rule = ?::jsonb, minimum_crew_rule = ?::jsonb,
                               confirmed_by_member_id = ?, confirmed_at = ?, revision = ?
                         WHERE id = ?""", layoutId, layoutRevision, p.effectiveAreaM2(), nullableJson(areaEvidence),
                        nullableJson(riskRule), nullableJson(crewRule), member.getId(), utc(now), revision, before.get("id"));
            }
            revisions.put(p.zoneId(), revision);
        }
        // 교체이므로 요청에 없는 구역의 프로필은 지운다.
        Set<UUID> kept = new HashSet<>(revisions.keySet());
        for (var entry : existing.entrySet()) {
            if (!kept.contains(entry.getKey())) jdbc.update("DELETE FROM safety_zone_profile WHERE id = ?", entry.getValue().get("id"));
        }
        return revisions;
    }

    /** coverage가 지금 평면도·프로필 revision을 가리키지 않는 카메라를 INVALID_MAPPING으로 바꾼다. 바꾼 대수를 돌려준다. */
    private int invalidateCameras(UUID eventId, UUID layoutId, long layoutRevision, Map<UUID, Long> profileRevisions) {
        int count = 0;
        for (var row : jdbc.queryForList("""
                SELECT id, coverage::text AS coverage, freshness FROM safety_camera WHERE event_id = ? FOR UPDATE""", eventId)) {
            if ("INVALID_MAPPING".equals(row.get("freshness"))) continue;
            if (!SafetyCameraService.coverageCurrent(SafetyViews.json((String) row.get("coverage")), layoutId, layoutRevision,
                    profileRevisions)) {
                jdbc.update("UPDATE safety_camera SET freshness = 'INVALID_MAPPING' WHERE id = ?", row.get("id"));
                count++;
            }
        }
        return count;
    }

    private void requireZones(UUID eventId, List<Polygon> polygons) {
        if (polygons.isEmpty()) return;
        UUID[] ids = polygons.stream().map(Polygon::zoneId).toArray(UUID[]::new);
        Integer found = jdbc.queryForObject("SELECT count(*) FROM zone WHERE event_id = ? AND id = ANY (?)", Integer.class,
                eventId, ids);
        if (found != ids.length) throw reference("layout.zonePolygons");
    }

    private void requireCrewGroups(UUID eventId, List<Profile> profiles) {
        for (int i = 0; i < profiles.size(); i++) {
            CrewRule crew = profiles.get(i).minimumCrewRule();
            if (crew == null) continue;
            Boolean exists = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM work_group WHERE id = ? AND event_id = ? AND active)", Boolean.class,
                    crew.workGroupId(), eventId);
            if (!Boolean.TRUE.equals(exists)) throw reference("profiles[" + i + "].minimumCrewRule.workGroupId");
        }
    }

    // ---- 근거·규칙 유지 -----------------------------------------------------------------------------------------

    /**
     * 받은 근거가 저장한 근거와 같은 출처(종류·참조·관측 시각)면 저장한 근거(기록 시각·확인자)를 그대로 둔다.
     * 다르면 새로 기록한다. 그대로인 값을 다시 보낼 때마다 기록 시각이 바뀌어 프로필이 바뀐 것처럼 보이지 않게 한다.
     */
    static Object keep(JsonNode stored, EvidenceInput input, Member member, Instant now) {
        if (input == null) return null;
        if (stored != null && !stored.isNull() && sameSource(stored, input)) return stored;
        return input.stored(member.getId(), now);
    }

    static boolean sameSource(JsonNode stored, EvidenceInput input) {
        JsonNode observed = stored.get("observedAt");
        String storedObserved = observed == null || observed.isNull() ? null : Instant.parse(observed.asString()).toString();
        return input.sourceKind().equals(stored.get("sourceKind").asString())
                && input.sourceRef().equals(stored.get("sourceRef").asString())
                && Objects.equals(storedObserved, input.observedAt() == null ? null : input.observedAt().toString());
    }

    private static Object rule(JsonNode stored, Rule rule, Member member, Instant now) {
        if (rule == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", rule.version());
        out.put("unit", rule.unit());
        out.put("condition", rule.condition());
        out.put("evidence", keep(stored == null || stored.isNull() ? null : stored.get("evidence"), rule.evidence(), member, now));
        return out;
    }

    private static Object crew(JsonNode stored, CrewRule crew, Member member, Instant now) {
        if (crew == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workGroupId", crew.workGroupId().toString());
        out.put("minimumCount", crew.minimumCount());
        out.put("startAt", crew.startAt().toString());
        out.put("endAt", crew.endAt().toString());
        out.put("evidence", keep(stored == null || stored.isNull() ? null : stored.get("evidence"), crew.evidence(), member, now));
        return out;
    }

    private static List<Map<String, Object>> polygons(List<Polygon> polygons) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Polygon p : polygons) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("zoneId", p.zoneId().toString());
            m.put("points", points(p.points()));
            out.add(m);
        }
        return out;
    }

    static List<Map<String, Object>> points(List<Point> points) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Point point : points) out.add(Map.of("x", point.x(), "y", point.y()));
        return out;
    }

    /** 저장한 도형과 요청 도형이 같은지(순서·구역·좌표 값). 수의 표기 차이(0.5와 0.50)는 같게 본다. */
    private static boolean samePolygons(JsonNode stored, List<Polygon> polygons) {
        if (stored.size() != polygons.size()) return false;
        for (int i = 0; i < polygons.size(); i++) {
            JsonNode s = stored.get(i);
            Polygon p = polygons.get(i);
            if (!p.zoneId().toString().equals(s.get("zoneId").asString())) return false;
            JsonNode points = s.get("points");
            if (points.size() != p.points().size()) return false;
            for (int j = 0; j < points.size(); j++) {
                if (points.get(j).get("x").decimalValue().compareTo(p.points().get(j).x()) != 0
                        || points.get(j).get("y").decimalValue().compareTo(p.points().get(j).y()) != 0) return false;
            }
        }
        return true;
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static JsonNode asNode(Object value) {
        if (value == null) return null;
        if (value instanceof JsonNode node) return node;
        return SafetyViews.json(EventWriteSupport.json(value));
    }

    private static String nullableJson(Object value) {
        if (value == null) return null;
        return value instanceof JsonNode node ? node.toString() : EventWriteSupport.json(value);
    }

    static void requireChief(Member member) {
        if (member.getEventRole() != EventRole.CHIEF) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    static BusinessException reference(String field) {
        return new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.INVALID_REFERENCE.getMessage(),
                Map.of("field", field, "reason", "NOT_FOUND"));
    }

    static Object utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
}

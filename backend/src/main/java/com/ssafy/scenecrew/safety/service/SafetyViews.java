package com.ssafy.scenecrew.safety.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 안전 공개 응답 모양(계약 V9SafetyMap·V9VenueLayout·V9SafetyZoneProfile·V9SafetyCamera·V9SafetyObservation).
 * 필드 이름·nullable은 계약과 같고 revision·sequence는 10진 문자열이다. JSONB 칸은 저장한 모양 그대로 돌려준다.
 * 카메라의 sourceBindingRef는 주소·계정이 아닌 불투명 참조만 저장하므로 그대로 돌려준다(V9-D43 형식 검사).
 */
public final class SafetyViews {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SafetyViews() { }

    public record LayoutView(UUID id, UUID eventId, String revision, String coordinateSystem, BigDecimal width,
            BigDecimal height, JsonNode zonePolygons, JsonNode source, UUID confirmedByMemberId, Instant confirmedAt) { }

    public record ProfileView(UUID id, UUID eventId, UUID zoneId, UUID layoutId, String layoutRevision, String revision,
            BigDecimal effectiveAreaM2, JsonNode areaEvidence, JsonNode riskRule, JsonNode minimumCrewRule,
            UUID confirmedByMemberId, Instant confirmedAt) { }

    public record CameraView(UUID id, UUID eventId, String name, String sourceBindingRef, boolean enabled, String revision,
            JsonNode coverage, UUID configuredByMemberId, Instant configuredAt, String connectionState, UUID streamSessionId,
            String lastSequence, Instant lastCapturedAt, Instant lastReceivedAt, Instant lastProcessedAt,
            UUID latestObservationId, String freshness, String inputKind, UUID linkTaskId, UUID linkMemberId, Instant linkedAt) { }

    public record ObservationView(UUID id, UUID eventId, UUID cameraId, String cameraRevision, UUID streamSessionId,
            String sequence, Instant capturedAt, String captureTimeBasis, Instant receivedAt, String inputHash,
            JsonNode coverage, String state, String freshness, JsonNode estimates, String modelVersion, Instant processedAt,
            Instant metadataExpiresAt) { }

    public record SafetyMap(UUID eventId, String eventRevision, LayoutView layout, List<ProfileView> profiles,
            List<ObservationView> observations) { }

    public record CameraPage(List<CameraView> items, String nextCursor) { }

    public record ObservationPage(List<ObservationView> items, String nextCursor) { }

    /** FinalReceipt */
    public record Receipt(UUID id, String revision, String eventRevision) {
        public static Receipt of(UUID id, long revision, long eventRevision) {
            return new Receipt(id, Long.toString(revision), Long.toString(eventRevision));
        }
    }

    static final String LAYOUT_COLUMNS = """
            l.id, l.event_id, l.revision, l.coordinate_system, l.width, l.height, l.zone_polygons::text AS zone_polygons,
            l.source::text AS source, l.confirmed_by_member_id, l.confirmed_at""";

    static LayoutView layout(ResultSet r, int n) throws SQLException {
        return new LayoutView(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class),
                Long.toString(r.getLong("revision")), r.getString("coordinate_system"), r.getBigDecimal("width"),
                r.getBigDecimal("height"), json(r.getString("zone_polygons")), json(r.getString("source")),
                r.getObject("confirmed_by_member_id", UUID.class), instant(r, "confirmed_at"));
    }

    static final String PROFILE_COLUMNS = """
            p.id, p.event_id, p.zone_id, p.layout_id, p.layout_revision, p.revision, p.effective_area_m2,
            p.area_evidence::text AS area_evidence, p.risk_rule::text AS risk_rule,
            p.minimum_crew_rule::text AS minimum_crew_rule, p.confirmed_by_member_id, p.confirmed_at""";

    static ProfileView profile(ResultSet r, int n) throws SQLException {
        return new ProfileView(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class),
                r.getObject("zone_id", UUID.class), r.getObject("layout_id", UUID.class),
                Long.toString(r.getLong("layout_revision")), Long.toString(r.getLong("revision")),
                r.getBigDecimal("effective_area_m2"), nullableJson(r.getString("area_evidence")),
                nullableJson(r.getString("risk_rule")), nullableJson(r.getString("minimum_crew_rule")),
                r.getObject("confirmed_by_member_id", UUID.class), instant(r, "confirmed_at"));
    }

    static final String CAMERA_COLUMNS = """
            c.id, c.event_id, c.name, c.source_binding_ref, c.enabled, c.revision, c.coverage::text AS coverage,
            c.configured_by_member_id, c.configured_at, c.connection_state, c.stream_session_id, c.last_sequence,
            c.last_captured_at, c.last_received_at, c.last_processed_at, c.latest_observation_id, c.freshness, c.input_kind,
            c.link_task_id, c.link_member_id, c.linked_at""";

    static CameraView camera(ResultSet r, int n) throws SQLException {
        long sequence = r.getLong("last_sequence");
        String lastSequence = r.wasNull() ? null : Long.toString(sequence);
        return new CameraView(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class), r.getString("name"),
                r.getString("source_binding_ref"), r.getBoolean("enabled"), Long.toString(r.getLong("revision")),
                json(r.getString("coverage")), r.getObject("configured_by_member_id", UUID.class),
                instant(r, "configured_at"), r.getString("connection_state"), r.getObject("stream_session_id", UUID.class),
                lastSequence, instant(r, "last_captured_at"), instant(r, "last_received_at"),
                instant(r, "last_processed_at"), r.getObject("latest_observation_id", UUID.class), r.getString("freshness"),
                r.getString("input_kind"), r.getObject("link_task_id", UUID.class), r.getObject("link_member_id", UUID.class),
                instant(r, "linked_at"));
    }

    static final String OBSERVATION_COLUMNS = """
            o.id, o.event_id, o.camera_id, o.camera_revision, o.stream_session_id, o.sequence, o.captured_at,
            o.capture_time_basis, o.received_at, o.input_hash, o.coverage::text AS coverage, o.state, o.freshness,
            o.estimates::text AS estimates, o.model_version, o.processed_at, o.metadata_expires_at""";

    static ObservationView observation(ResultSet r, int n) throws SQLException {
        return new ObservationView(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class),
                r.getObject("camera_id", UUID.class), Long.toString(r.getLong("camera_revision")),
                r.getObject("stream_session_id", UUID.class), Long.toString(r.getLong("sequence")),
                instant(r, "captured_at"), r.getString("capture_time_basis"), instant(r, "received_at"),
                r.getString("input_hash"), json(r.getString("coverage")), r.getString("state"), r.getString("freshness"),
                json(r.getString("estimates")), r.getString("model_version"), instant(r, "processed_at"),
                instant(r, "metadata_expires_at"));
    }

    static JsonNode json(String value) {
        return MAPPER.readTree(value);
    }

    static JsonNode nullableJson(String value) {
        return value == null ? null : MAPPER.readTree(value);
    }

    static Instant instant(ResultSet r, String column) throws SQLException {
        OffsetDateTime value = r.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

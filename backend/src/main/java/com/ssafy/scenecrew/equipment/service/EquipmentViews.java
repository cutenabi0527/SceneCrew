package com.ssafy.scenecrew.equipment.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장비 공개 응답 모양(계약 V9EquipmentAsset·V9EquipmentConnection·V9EquipmentRequirement·V9EquipmentReservation, FinalReceipt).
 * 필드 이름·순서·nullable은 계약과 같고 revision은 10진 문자열이다. JSONB 칸은 저장한 모양 그대로 돌려준다.
 */
public final class EquipmentViews {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private EquipmentViews() { }

    /** autoObservation(#394)은 자동 관측 종류가 있는 장비에만 싣는다(선택 필드). */
    public record AssetView(UUID id, UUID eventId, String name, String model, String technicalState, JsonNode stateEvidence,
            JsonNode componentAssetIds, JsonNode ports, JsonNode verifiedCompatibility, UUID storageZoneId,
            UUID holderMemberId, String revision,
            @JsonInclude(JsonInclude.Include.NON_NULL) AutoObservations.AutoObservation autoObservation) {

        AssetView with(AutoObservations.AutoObservation value) {
            return new AssetView(id, eventId, name, model, technicalState, stateEvidence, componentAssetIds, ports,
                    verifiedCompatibility, storageZoneId, holderMemberId, revision, value);
        }
    }

    public record ConnectionView(UUID id, UUID eventId, UUID fromAssetId, String fromPort, UUID toAssetId, String toPort,
            UUID confirmedByMemberId, Instant confirmedAt, String revision, boolean active) { }

    public record RequirementView(UUID id, UUID eventId, UUID programId, JsonNode taskIds, JsonNode requirements,
            Instant readyBy, JsonNode schedulePolicy, JsonNode timeEvidence, String revision) { }

    public record ReservationView(UUID id, UUID eventId, JsonNode assetIds, UUID programId, UUID taskId, Instant startAt,
            Instant endAt, String status, UUID sourceChangeSetId, String revision) { }

    public record TypedRefView(String kind, UUID id) { }

    /** V9OperationalDependency. 계약에 revision이 없다(Event revision으로 충돌을 막는다). */
    public record DependencyView(UUID id, UUID eventId, TypedRefView from, TypedRefView to, java.math.BigDecimal lagSec,
            JsonNode evidence, UUID confirmedByMemberId, Instant confirmedAt) { }

    /** FinalRelationDeleteReceipt */
    public record ReleaseReceipt(UUID id, String eventRevision, boolean released) {
        public static ReleaseReceipt of(UUID id, long eventRevision) {
            return new ReleaseReceipt(id, Long.toString(eventRevision), true);
        }
    }

    /** FinalEquipmentContext */
    public record Context(UUID eventId, String eventRevision, java.util.List<ConnectionView> connections,
            java.util.List<RequirementView> requirements, java.util.List<DependencyView> dependencies) { }

    static final String DEPENDENCY_COLUMNS = """
            d.id, d.event_id, d.from_kind, d.from_id, d.to_kind, d.to_id, d.lag_sec, d.evidence::text AS evidence,
            d.confirmed_by_member_id, d.confirmed_at""";

    static DependencyView dependency(ResultSet r, int n) throws SQLException {
        return new DependencyView(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class),
                new TypedRefView(r.getString("from_kind"), r.getObject("from_id", UUID.class)),
                new TypedRefView(r.getString("to_kind"), r.getObject("to_id", UUID.class)),
                r.getBigDecimal("lag_sec"), json(r.getString("evidence")),
                r.getObject("confirmed_by_member_id", UUID.class), instant(r, "confirmed_at"));
    }

    /** EXT02·04·09와 관계 쓰기의 최초 응답. 같은 키 재요청은 보관한 이 값을 그대로 돌려준다. */
    public record Receipt(UUID id, String revision, String eventRevision) {
        public static Receipt of(UUID id, long revision, long eventRevision) {
            return new Receipt(id, Long.toString(revision), Long.toString(eventRevision));
        }
    }

    static final String ASSET_COLUMNS = """
            a.id, a.event_id, a.name, a.model, a.technical_state, a.state_evidence::text AS state_evidence,
            a.component_asset_ids::text AS component_asset_ids, a.ports::text AS ports,
            a.verified_compatibility::text AS verified_compatibility, a.storage_zone_id, a.holder_member_id, a.revision""";

    static AssetView asset(ResultSet r, int n) throws SQLException {
        return new AssetView(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class), r.getString("name"),
                r.getString("model"), r.getString("technical_state"), json(r.getString("state_evidence")),
                json(r.getString("component_asset_ids")), json(r.getString("ports")),
                json(r.getString("verified_compatibility")), r.getObject("storage_zone_id", UUID.class),
                r.getObject("holder_member_id", UUID.class), Long.toString(r.getLong("revision")), null);
    }

    static final String CONNECTION_COLUMNS = """
            c.id, c.event_id, c.from_asset_id, c.from_port, c.to_asset_id, c.to_port, c.confirmed_by_member_id,
            c.confirmed_at, c.revision, c.active""";

    static ConnectionView connection(ResultSet r, int n) throws SQLException {
        return new ConnectionView(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class),
                r.getObject("from_asset_id", UUID.class), r.getString("from_port"), r.getObject("to_asset_id", UUID.class),
                r.getString("to_port"), r.getObject("confirmed_by_member_id", UUID.class), instant(r, "confirmed_at"),
                Long.toString(r.getLong("revision")), r.getBoolean("active"));
    }

    static final String REQUIREMENT_COLUMNS = """
            q.id, q.event_id, q.program_id, q.task_ids::text AS task_ids, q.requirements::text AS requirements, q.ready_by,
            q.schedule_policy::text AS schedule_policy, q.time_evidence::text AS time_evidence, q.revision""";

    static RequirementView requirement(ResultSet r, int n) throws SQLException {
        return new RequirementView(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class),
                r.getObject("program_id", UUID.class), json(r.getString("task_ids")), json(r.getString("requirements")),
                instant(r, "ready_by"), json(r.getString("schedule_policy")), json(r.getString("time_evidence")),
                Long.toString(r.getLong("revision")));
    }

    /** 예약 한 건과 그 장비 id(정렬). 장비가 없는 예약은 빈 배열 */
    public static final String RESERVATION_COLUMNS = """
            v.id, v.event_id, COALESCE((SELECT jsonb_agg(ra.asset_id ORDER BY ra.asset_id)
                                          FROM equipment_reservation_asset ra WHERE ra.reservation_id = v.id),
                                       '[]'::jsonb)::text AS asset_ids,
            v.program_id, v.task_id, v.start_at, v.end_at, v.status, v.source_change_set_id, v.revision""";

    public static ReservationView reservation(ResultSet r, int n) throws SQLException {
        return new ReservationView(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class),
                json(r.getString("asset_ids")), r.getObject("program_id", UUID.class), r.getObject("task_id", UUID.class),
                instant(r, "start_at"), instant(r, "end_at"), r.getString("status"),
                r.getObject("source_change_set_id", UUID.class), Long.toString(r.getLong("revision")));
    }

    static JsonNode json(String value) {
        return MAPPER.readTree(value);
    }

    static Instant instant(ResultSet r, String column) throws SQLException {
        OffsetDateTime value = r.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

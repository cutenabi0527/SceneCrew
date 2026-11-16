package com.ssafy.scenecrew.equipment.collector;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장비별 최신 수집 값(#330). equipment_observation_latest에서 항목(채널·기록 종류·READING의 param)마다 1행을 읽는다.
 * 늦게 도착한 이전 기록은 도착 시각이 늦어도 최신 값이 되지 않는다. 기록을 보관 기간에 따라 지워도 최신 값은 남는다.
 *
 * 수집 값은 장비 상태의 근거일 뿐이다. technicalState를 정하거나 이슈를 만들지 않는다(BE13).
 * collectorReceivedAt은 수집기 시계, serverReceivedAt은 서버 수신 시각이다. 둘을 바꿔 쓰지 않는다.
 */
public class EquipmentObservationReader {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public EquipmentObservationReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** item은 READING이면 param, 그 밖에는 null이다. */
    public record Latest(int channel, String recordType, String item, String collectorId, UUID runId, long sequence,
            UUID sessionId, Instant collectorReceivedAt, Instant serverReceivedAt, JsonNode payload) {
    }

    public List<Latest> latest(UUID equipmentAssetId) {
        return jdbc.query("""
                select channel, record_type, item, collector_id, run_id, sequence, session_id,
                       collector_received_at, server_received_at, payload::text
                from equipment_observation_latest
                where equipment_asset_id = ?
                order by channel, record_type, item
                """, (rs, n) -> new Latest(rs.getInt(1), rs.getString(2), rs.getString(3).isEmpty() ? null : rs.getString(3),
                rs.getString(4), rs.getObject(5, UUID.class), rs.getLong(6), rs.getObject(7, UUID.class),
                rs.getTimestamp(8).toInstant(), rs.getTimestamp(9).toInstant(), JSON.readTree(rs.getString(10))),
                equipmentAssetId);
    }
}

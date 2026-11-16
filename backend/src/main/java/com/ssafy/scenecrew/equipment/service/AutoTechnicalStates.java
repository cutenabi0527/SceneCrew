package com.ssafy.scenecrew.equipment.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.equipment.service.AutoObservations.AutoObservation;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;

/**
 * 자동 관측으로 장비 기술 상태를 정한다(2026-11-13 오세진 결정, V9-D59 보완).
 *
 * <ul>
 *   <li>대상: 자동 관측 종류가 있고, 기술 상태가 미확인(UNKNOWN)이거나 이 규칙이 정한 상태인 장비. 사람이 기록한 상태
 *       (EXT04 상태 기록 등, 근거 출처가 이 규칙이 아님)는 덮지 않는다.</li>
 *   <li>정상(VERIFIED_USABLE): 연결됨이고 송신기가 켜졌고 배터리 값이 있다.</li>
 *   <li>점검 필요(CHECK_REQUIRED): 수신기 응답 없음.</li>
 *   <li>미확인(UNKNOWN): 그 밖(프로그램 끊김·연결 해제·송신기 꺼짐 등). 이 규칙이 정했던 상태도 이때 미확인으로 되돌린다.</li>
 * </ul>
 * 근거는 AUTOMATIC_MEASUREMENT, 출처 참조 {@link #SOURCE_REF}다. 장비 revision만 올리고 공연 revision은 올리지 않는다
 * (다른 사람의 저장을 409로 막지 않게. 장비 연결·해제와 같다). 바뀔 때마다 운영 기록 EQUIPMENT_STATE_AUTO를 남긴다.
 * 사람 기록과 겹치면 revision 조건으로 이번 전환을 건너뛰고 다음 주기에 다시 본다.
 */
public final class AutoTechnicalStates {
    public static final String SOURCE_REF = "auto-observation";
    private static final String UNKNOWN = "UNKNOWN";

    private AutoTechnicalStates() {
    }

    /** 자동 관측 값으로 정할 기술 상태 */
    public static String target(AutoObservation o) {
        if ("CONNECTED".equals(o.state()) && Boolean.TRUE.equals(o.transmitterOn()) && o.batteryBars() != null)
            return "VERIFIED_USABLE";
        if ("RECEIVER_NOT_RESPONDING".equals(o.state())) return "CHECK_REQUIRED";
        return UNKNOWN;
    }

    private record Row(UUID id, UUID eventId, String state, String sourceKind, String sourceRef, long revision) {
        boolean managed() {
            return UNKNOWN.equals(state) || ("AUTOMATIC_MEASUREMENT".equals(sourceKind) && SOURCE_REF.equals(sourceRef));
        }

        boolean fromRule() {
            return "AUTOMATIC_MEASUREMENT".equals(sourceKind) && SOURCE_REF.equals(sourceRef);
        }
    }

    /** 모집·진행 중 공연의 자동 관측 장비를 한 번 맞춘다. 바꾼 장비 수를 돌려준다. */
    public static int reconcile(JdbcTemplate jdbc, EventWriteSupport events, TransactionTemplate tx, Instant now) {
        List<Row> rows = jdbc.query("""
                SELECT a.id, a.event_id, a.technical_state, a.state_evidence->>'sourceKind', a.state_evidence->>'sourceRef',
                       a.revision
                  FROM equipment_asset a JOIN event e ON e.id = a.event_id
                 WHERE a.auto_observation_kind IS NOT NULL AND e.state IN ('RECRUITING', 'LIVE')""",
                (r, n) -> new Row(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4),
                        r.getString(5), r.getLong(6)));
        Map<UUID, List<Row>> byEvent = new HashMap<>();
        for (Row row : rows) if (row.managed()) byEvent.computeIfAbsent(row.eventId(), k -> new ArrayList<>()).add(row);
        int changed = 0;
        for (var entry : byEvent.entrySet()) {
            var observed = AutoObservations.of(jdbc, entry.getKey(), entry.getValue().stream().map(Row::id).toList(), now);
            for (Row row : entry.getValue()) {
                AutoObservation o = observed.get(row.id());
                if (o == null) continue;
                String target = target(o);
                // 처음부터 미확인이던 장비가 계속 미확인이면 손대지 않는다(근거를 바꾸지 않음)
                if (target.equals(row.state()) && (row.fromRule() || UNKNOWN.equals(target))) continue;
                // 상태 변경과 운영 기록은 한 트랜잭션으로 남긴다
                if (Boolean.TRUE.equals(tx.execute(st -> apply(jdbc, events, row, target, o, now)))) changed++;
            }
        }
        return changed;
    }

    private static boolean apply(JdbcTemplate jdbc, EventWriteSupport events, Row row, String target, AutoObservation o,
            Instant now) {
        boolean known = !UNKNOWN.equals(target);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceKind", "AUTOMATIC_MEASUREMENT");
        evidence.put("sourceRef", SOURCE_REF);
        evidence.put("observedAt", known && o.lastReceivedAt() != null ? o.lastReceivedAt().toString() : null);
        evidence.put("recordedAt", now.toString());
        evidence.put("confirmedByMemberId", null);
        // 정상·점검 필요는 측정으로 확인한 값이다. 미확인으로 되돌릴 때는 확인되지 않은 근거로 남긴다.
        evidence.put("confirmed", known);
        int updated = jdbc.update("""
                UPDATE equipment_asset SET technical_state = ?, state_evidence = ?::jsonb, revision = revision + 1
                 WHERE id = ? AND revision = ?""", target, EventWriteSupport.json(evidence), row.id(), row.revision());
        if (updated == 0) return false;
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("technicalState", target);
        changes.put("previousTechnicalState", row.state());
        changes.put("autoObservationState", o.state());
        changes.put("transmitterOn", o.transmitterOn());
        changes.put("batteryBars", o.batteryBars());
        events.log(row.eventId(), "EQUIPMENT_STATE_AUTO", null, "EQUIPMENT_ASSET", row.id(), changes, now);
        return true;
    }
}

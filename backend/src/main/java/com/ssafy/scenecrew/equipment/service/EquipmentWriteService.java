package com.ssafy.scenecrew.equipment.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.equipment.service.EquipmentCommands.AssetCreate;
import com.ssafy.scenecrew.equipment.service.EquipmentCommands.Check;
import com.ssafy.scenecrew.equipment.service.EquipmentCommands.StateRecord;
import com.ssafy.scenecrew.equipment.service.EquipmentViews.Receipt;
import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.global.operation.EventWriteSupport.EventRow;
import com.ssafy.scenecrew.global.text.UnicodeText;

/**
 * 장비 등록 EXT02·상태 기록 EXT04·업무 장비 점검 EXT09. 규칙은 backend/docs/equipment-api.md.
 *
 * <p>순서: 행사 잠금 → 현재 Member 잠금 → 같은 키 확인(Idempotency.run) → 권한·상태·revision → 참조 검사 → 저장 → 영수증.
 * 같은 키 재요청은 최초 영수증을 돌려준다. 권한·상태·revision은 새로 실행할 때만 본다(행사 공개 029와 같은 원칙).
 * EXT02·04는 Event.revision을 1 올리고, EXT09는 운영 기록만 남기므로 올리지 않는다(V9-D43).
 */
public class EquipmentWriteService {
    static final String REGISTER = "EXT02";
    static final String STATE_RECORD = "EXT04";
    static final String CHECK = "EXT09";
    /** V9-D43 행사당 장비 상한 */
    public static final int MAX_ASSETS_PER_EVENT = 200;

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final Idempotency idempotency;
    private final EventWriteSupport events;

    public EquipmentWriteService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency,
            EventWriteSupport events) {
        this.jdbc = jdbc;
        this.members = members;
        this.idempotency = idempotency;
        this.events = events;
    }

    /** EXT02 */
    @Transactional
    public IdempotentResult<Receipt> register(UUID eventId, UUID accountId, String key, AssetCreate command) {
        EventRow event = events.lock(eventId);
        Member member = members.requireLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, REGISTER, key,
                IdempotencyRequest.hash(eventId, command.canonical()), eventId, member.getId(),
                member.getParticipationRevision());
        return idempotency.run(request, Receipt.class, () -> {
            requireChief(member);
            EventWriteSupport.requireState(event, "DRAFT", "RECRUITING", "LIVE");
            EventWriteSupport.requireRevision(event.revision(), command.expectedEventRevision(), "event");
            Integer count = jdbc.queryForObject("SELECT count(*) FROM equipment_asset WHERE event_id = ?", Integer.class,
                    eventId);
            if (count >= MAX_ASSETS_PER_EVENT) throw state("ASSET_LIMIT");
            String nameKey = UnicodeText.nameKey(command.name());
            for (String existing : jdbc.queryForList("SELECT name FROM equipment_asset WHERE event_id = ?", String.class,
                    eventId)) {
                if (UnicodeText.nameKey(existing).equals(nameKey)) throw state("DUPLICATE_NAME");
            }
            requireAssets(eventId, command.componentAssetIds(), "componentAssetIds");
            if (command.storageZoneId() != null) requireZone(eventId, command.storageZoneId());

            Instant now = EventWriteSupport.now();
            UUID id = UUID.randomUUID();
            List<Map<String, String>> ports = new ArrayList<>();
            for (var port : command.ports()) ports.add(Map.of("key", port.key(), "kind", port.kind()));
            jdbc.update("""
                    INSERT INTO equipment_asset (id, event_id, name, model, technical_state, state_evidence,
                                                 component_asset_ids, ports, verified_compatibility, storage_zone_id,
                                                 holder_member_id, revision)
                    VALUES (?, ?, ?, ?, 'UNKNOWN', ?::jsonb, ?::jsonb, ?::jsonb, '[]'::jsonb, ?, NULL, 1)""",
                    id, eventId, command.name(), command.model(),
                    EventWriteSupport.json(command.evidence().stored(member.getId(), now)),
                    EventWriteSupport.json(command.componentAssetIds().stream().map(UUID::toString).toList()),
                    EventWriteSupport.json(ports), command.storageZoneId());
            long eventRevision = events.bump(eventId);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("name", command.name());
            changes.put("technicalState", "UNKNOWN");
            changes.put("note", command.evidence().note());
            events.log(eventId, "EQUIPMENT_REGISTERED", member.getId(), "EQUIPMENT_ASSET", id, changes, now);
            Receipt receipt = Receipt.of(id, 1, eventRevision);
            return IdempotentResult.of(201, receipt, "EQUIPMENT_REGISTERED", refs(eventId, id, eventRevision));
        });
    }

    /** EXT04 */
    @Transactional
    public IdempotentResult<Receipt> recordState(UUID assetId, UUID accountId, String key, StateRecord command) {
        UUID eventId = eventOfAsset(assetId);
        EventRow event = events.lock(eventId);
        Member member = members.requireLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, STATE_RECORD, key,
                IdempotencyRequest.hash(eventId, assetId, command.canonical()), eventId, member.getId(),
                member.getParticipationRevision());
        return idempotency.run(request, Receipt.class, () -> {
            if (member.getEventRole() == EventRole.CHIEF) {
                if (command.taskId() != null) requireTask(eventId, command.taskId());
            } else if (command.taskId() == null || !assignedWithAsset(eventId, command.taskId(), member.getId(), assetId)) {
                // 팀원은 이 장비를 예약에 포함한 업무를 지금 맡고 있을 때만 기록한다(계약 EXT04).
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            EventWriteSupport.requireState(event, "RECRUITING", "LIVE");
            EventWriteSupport.requireRevision(event.revision(), command.expectedEventRevision(), "event");
            var asset = jdbc.query("SELECT revision, technical_state FROM equipment_asset WHERE id = ? FOR UPDATE",
                    (r, n) -> new long[] { r.getLong("revision") }, assetId).getFirst();
            EventWriteSupport.requireRevision(asset[0], command.expectedAssetRevision(), "asset");

            Instant now = EventWriteSupport.now();
            long revision = asset[0] + 1;
            jdbc.update("UPDATE equipment_asset SET technical_state = ?, state_evidence = ?::jsonb, revision = ? WHERE id = ?",
                    command.technicalState(), EventWriteSupport.json(command.evidence().stored(member.getId(), now)),
                    revision, assetId);
            long eventRevision = events.bump(eventId);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("technicalState", command.technicalState());
            changes.put("taskId", command.taskId() == null ? null : command.taskId().toString());
            changes.put("note", command.evidence().note());
            events.log(eventId, "EQUIPMENT_STATE_RECORDED", member.getId(), "EQUIPMENT_ASSET", assetId, changes, now);
            Receipt receipt = Receipt.of(assetId, revision, eventRevision);
            return IdempotentResult.of(201, receipt, "EQUIPMENT_STATE_RECORDED", refs(eventId, assetId, eventRevision));
        });
    }

    /** EXT09. 영수증의 id는 운영 기록 id, revision은 바뀌지 않은 장비 revision이다. */
    @Transactional
    public IdempotentResult<Receipt> check(UUID taskId, UUID accountId, String key, Check command) {
        UUID eventId = jdbc.query("SELECT event_id FROM task WHERE id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, taskId);
        if (eventId == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        EventRow event = events.lock(eventId);
        Member member = members.requireLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, CHECK, key,
                IdempotencyRequest.hash(eventId, taskId, command.canonical()), eventId, member.getId(),
                member.getParticipationRevision());
        return idempotency.run(request, Receipt.class, () -> {
            var task = jdbc.query("SELECT assignee_id, status FROM task WHERE id = ? FOR UPDATE",
                    (r, n) -> new Object[] { r.getObject("assignee_id", UUID.class), r.getString("status") }, taskId)
                    .getFirst();
            // 역할과 무관하게 업무의 실제 담당자만 점검을 남긴다(계약 EXT09).
            if (!member.getId().equals(task[0])) throw new BusinessException(ErrorCode.FORBIDDEN);
            if ("CANCELED".equals(task[1])) throw new BusinessException(ErrorCode.INVALID_STATE);
            EventWriteSupport.requireState(event, "RECRUITING", "LIVE");
            EventWriteSupport.requireRevision(event.revision(), command.expectedEventRevision(), "event");
            Long assetRevision = jdbc.query("SELECT revision FROM equipment_asset WHERE id = ? AND event_id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, command.assetId(), eventId);
            if (assetRevision == null) throw reference("assetId");

            Instant now = EventWriteSupport.now();
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("taskId", taskId.toString());
            changes.put("kind", command.kind());
            changes.put("passed", command.passed());
            changes.put("evidence", command.evidence().stored(member.getId(), now));
            changes.put("note", command.evidence().note());
            UUID logId = events.log(eventId, "EQUIPMENT_CHECK", member.getId(), "EQUIPMENT_ASSET", command.assetId(),
                    changes, now);
            Receipt receipt = Receipt.of(logId, assetRevision, event.revision());
            return IdempotentResult.of(201, receipt, "EQUIPMENT_CHECK", refs(eventId, logId, event.revision()));
        });
    }

    // ---- 내부 -------------------------------------------------------------------------------------------

    private UUID eventOfAsset(UUID assetId) {
        UUID eventId = jdbc.query("SELECT event_id FROM equipment_asset WHERE id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, assetId);
        if (eventId == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return eventId;
    }

    /** 업무가 이 행사에 있고 담당자가 member이며 진행 가능한 상태이고, 그 업무의 유효 예약에 이 장비가 있다. */
    private boolean assignedWithAsset(UUID eventId, UUID taskId, UUID memberId, UUID assetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM task t
                      JOIN equipment_reservation v ON v.task_id = t.id AND v.event_id = t.event_id
                      JOIN equipment_reservation_asset ra ON ra.reservation_id = v.id
                     WHERE t.id = ? AND t.event_id = ? AND t.assignee_id = ? AND t.status IN ('WAITING', 'IN_PROGRESS')
                       AND v.status IN ('PLANNED', 'ACTIVE') AND ra.asset_id = ?)""",
                Boolean.class, taskId, eventId, memberId, assetId));
    }

    private void requireTask(UUID eventId, UUID taskId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM task WHERE id = ? AND event_id = ?)",
                Boolean.class, taskId, eventId);
        if (!Boolean.TRUE.equals(exists)) throw reference("taskId");
    }

    private void requireAssets(UUID eventId, List<UUID> ids, String field) {
        if (ids.isEmpty()) return;
        Integer found = jdbc.queryForObject("SELECT count(*) FROM equipment_asset WHERE event_id = ? AND id = ANY (?)",
                Integer.class, eventId, ids.toArray(UUID[]::new));
        if (found != ids.size()) throw reference(field);
    }

    private void requireZone(UUID eventId, UUID zoneId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM zone WHERE id = ? AND event_id = ?)",
                Boolean.class, zoneId, eventId);
        if (!Boolean.TRUE.equals(exists)) throw reference("storageZoneId");
    }

    private static void requireChief(Member member) {
        if (member.getEventRole() != EventRole.CHIEF) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    static BusinessException state(String reason) {
        return new BusinessException(ErrorCode.INVALID_STATE, ErrorCode.INVALID_STATE.getMessage(), Map.of("reason", reason));
    }

    static BusinessException reference(String field) {
        return new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.INVALID_REFERENCE.getMessage(),
                Map.of("field", field));
    }

    private static Map<String, Object> refs(UUID eventId, UUID id, long eventRevision) {
        return Map.of("eventId", eventId.toString(), "id", id.toString(), "eventRevision", Long.toString(eventRevision));
    }
}

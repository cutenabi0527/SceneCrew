package com.ssafy.scenecrew.equipment.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.equipment.collector.LinkCodes;
import com.ssafy.scenecrew.equipment.collector.WebCollectors;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;

/**
 * 장비 웹 연결(#394, V9-D59·D60): 연결 코드 발급, 수신기 ↔ 장비 칸 연결·해제. 규칙은 backend/docs/equipment-collector.md.
 *
 * <ul>
 *   <li>시작 자격: CHIEF, 또는 자동 관측 장비의 연결 담당 업무를 맡았고 그 업무가 WAITING·IN_PROGRESS인 WORKER. 행사는 RECRUITING·LIVE.</li>
 *   <li>코드: 10분·일회용. 원문은 응답에만 있고 서버는 해시만 둔다. 코드는 "이 담당자 + 이 노트북"이다.</li>
 *   <li>연결: 자동 관측 종류가 있는 장비만. 수집기는 같은 행사이고 폐기되지 않았으며, WORKER는 자기 코드로 등록한 수집기만 쓴다.
 *       수신기는 그 수집기가 보고한 것이어야 한다. 다른 연결이 수집 중(연결 15초 안이거나 그 수집기 묶음이 15초 안)이면
 *       takeover=true일 때만 바꾼다. 그 수신기가 다른 장비 칸에 연결돼 있어도 takeover로만 옮긴다.</li>
 *   <li>해제: 연결한 사람 또는 CHIEF. 연결된 장비가 남지 않은 수집기는 토큰을 폐기한다.</li>
 * </ul>
 * 연결·해제는 장비 revision을 올리고 운영 기록(EQUIPMENT_LINKED·EQUIPMENT_UNLINKED)을 남긴다. Event revision은 올리지 않는다.
 */
public class EquipmentLinkService {
    static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Set<String> OPEN_EVENT_STATES = Set.of("RECRUITING", "LIVE");
    private static final Set<String> START_TASK_STATES = Set.of("WAITING", "IN_PROGRESS");

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final EventWriteSupport events;
    private final WebCollectors collectors;

    public EquipmentLinkService(JdbcTemplate jdbc, CurrentMembers members, EventWriteSupport events, WebCollectors collectors) {
        this.jdbc = jdbc;
        this.members = members;
        this.events = events;
        this.collectors = collectors;
    }

    public record Code(String code, Instant expiresAt) {
    }

    public record LinkCommand(String collectorId, String receiverKey, int channel, boolean takeover) {
    }

    public record Receipt(UUID id, String revision) {
    }

    /** 연결 코드 발급 */
    @Transactional
    public Code issueCode(UUID eventId, UUID accountId) {
        Member me = members.require(eventId, accountId);
        requireOpen(eventId);
        if (me.getEventRole() != EventRole.CHIEF) {
            Boolean assignee = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM equipment_asset a JOIN task t ON t.id = a.link_task_id
                                    WHERE a.event_id = ? AND a.auto_observation_kind IS NOT NULL AND t.assignee_id = ?
                                      AND t.status IN ('WAITING', 'IN_PROGRESS'))""", Boolean.class, eventId, me.getId());
            if (!Boolean.TRUE.equals(assignee)) throw new BusinessException(ErrorCode.COLLECTOR_LINK_FORBIDDEN);
        }
        Instant now = EventWriteSupport.now();
        String code = LinkCodes.generate();
        Instant expiresAt = now.plus(CODE_TTL);
        jdbc.update("""
                INSERT INTO equipment_collector_code (code_sha256, event_id, member_id, created_at, expires_at, used_at, collector_id)
                VALUES (?, ?, ?, ?, ?, NULL, NULL)""", LinkCodes.hash(code), eventId, me.getId(), utc(now), utc(expiresAt));
        return new Code(code, expiresAt);
    }

    /** 수신기 ↔ 장비 칸 연결(넘겨받기 포함) */
    @Transactional
    public Receipt link(UUID assetId, UUID accountId, LinkCommand command) {
        Row asset = lock(assetId);
        Member me = members.require(asset.eventId(), accountId);
        boolean chief = me.getEventRole() == EventRole.CHIEF;
        if (asset.kind() == null) throw state("NOT_AUTO_OBSERVED");
        boolean assignee = me.getId().equals(asset.taskAssignee()) && START_TASK_STATES.contains(asset.taskStatus());
        if (!chief && !assignee) throw new BusinessException(ErrorCode.COLLECTOR_LINK_FORBIDDEN);
        requireOpen(asset.eventId());

        List<Object[]> collector = jdbc.query("""
                SELECT member_id, last_seen_at FROM equipment_collector WHERE id = ? AND event_id = ? AND revoked_at IS NULL""",
                (r, n) -> new Object[] { r.getObject(1, UUID.class), r.getTimestamp(2) }, command.collectorId(), asset.eventId());
        if (collector.isEmpty()) throw reference("collectorId");
        if (!chief && !me.getId().equals(collector.getFirst()[0])) throw new BusinessException(ErrorCode.COLLECTOR_LINK_FORBIDDEN);
        Integer channels = jdbc.query("SELECT channels FROM equipment_collector_receiver WHERE collector_id = ? AND receiver_key = ?",
                rs -> rs.next() ? rs.getInt(1) : null, command.collectorId(), command.receiverKey());
        if (channels == null) throw reference("receiverKey");
        // 지금은 수신기 하나 = 장비 칸 하나다(QLXD4는 1채널). 관측·점유가 채널을 나누지 않으므로 채널 1만 연결한다(#394 리뷰).
        if (command.channel() != 1 || channels < 1) throw reference("channel");

        Instant now = EventWriteSupport.now();
        boolean same = command.collectorId().equals(asset.collectorId()) && command.receiverKey().equals(asset.sourceRef())
                && command.channel() == asset.channel() && me.getId().equals(asset.linkMemberId());
        if (asset.collectorId() != null && !same && !command.takeover() && collecting(asset, now))
            throw taken("ASSET_IN_USE");
        // 같은 수신기가 다른 장비 칸에 연결돼 있으면 넘겨받기로만 옮긴다
        List<UUID> other = jdbc.query("""
                SELECT id FROM equipment_asset WHERE link_collector_id = ? AND link_source_ref = ? AND id <> ? FOR UPDATE""",
                (r, n) -> r.getObject(1, UUID.class), command.collectorId(), command.receiverKey(), assetId);
        if (!other.isEmpty()) {
            if (!command.takeover()) throw taken("RECEIVER_IN_USE");
            clear(other.getFirst(), asset.eventId(), me.getId(), now, "MOVED");
        }
        long revision = asset.revision() + 1;
        jdbc.update("""
                UPDATE equipment_asset SET link_collector_id = ?, link_source_ref = ?, link_channel = ?, link_member_id = ?,
                       linked_at = ?, revision = ?
                 WHERE id = ?""", command.collectorId(), command.receiverKey(), command.channel(), me.getId(), utc(now), revision,
                assetId);
        if (asset.collectorId() != null && !asset.collectorId().equals(command.collectorId()))
            collectors.revokeIfUnused(asset.collectorId(), now);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("collectorId", command.collectorId());
        changes.put("channel", command.channel());
        changes.put("takeover", asset.collectorId() != null && !same);
        events.log(asset.eventId(), "EQUIPMENT_LINKED", me.getId(), "EQUIPMENT_ASSET", assetId, changes, now);
        return new Receipt(assetId, Long.toString(revision));
    }

    /** 연결 해제 */
    @Transactional
    public Receipt unlink(UUID assetId, UUID accountId) {
        Row asset = lock(assetId);
        Member me = members.require(asset.eventId(), accountId);
        if (asset.collectorId() == null) throw new BusinessException(ErrorCode.EQUIPMENT_UNLINKED);
        if (me.getEventRole() != EventRole.CHIEF && !me.getId().equals(asset.linkMemberId()))
            throw new BusinessException(ErrorCode.COLLECTOR_LINK_FORBIDDEN);
        Instant now = EventWriteSupport.now();
        long revision = clear(assetId, asset.eventId(), me.getId(), now, "UNLINKED");
        collectors.revokeIfUnused(asset.collectorId(), now);
        return new Receipt(assetId, Long.toString(revision));
    }

    private long clear(UUID assetId, UUID eventId, UUID actor, Instant now, String reason) {
        Long revision = jdbc.queryForObject("""
                UPDATE equipment_asset SET link_collector_id = NULL, link_source_ref = NULL, link_channel = NULL,
                       link_member_id = NULL, linked_at = NULL, revision = revision + 1
                 WHERE id = ? RETURNING revision""", Long.class, assetId);
        events.log(eventId, "EQUIPMENT_UNLINKED", actor, "EQUIPMENT_ASSET", assetId, Map.of("reason", reason), now);
        return revision;
    }

    /** 지금 수집 중인지: 연결한 지 15초 안이거나 그 수집기의 묶음이 15초 안에 왔다 */
    private boolean collecting(Row asset, Instant now) {
        Instant limit = now.minus(AutoObservations.OFFLINE_AFTER);
        if (asset.linkedAt() != null && asset.linkedAt().isAfter(limit)) return true;
        var seen = jdbc.query("SELECT last_seen_at FROM equipment_collector WHERE id = ?",
                rs -> rs.next() ? rs.getTimestamp(1) : null, asset.collectorId());
        return seen != null && seen.toInstant().isAfter(limit);
    }

    private record Row(UUID eventId, String kind, long revision, String collectorId, String sourceRef, int channel,
            UUID linkMemberId, Instant linkedAt, UUID taskAssignee, String taskStatus) {
    }

    private Row lock(UUID assetId) {
        List<Row> rows = jdbc.query("""
                SELECT a.event_id, a.auto_observation_kind, a.revision, a.link_collector_id, a.link_source_ref, a.link_channel,
                       a.link_member_id, a.linked_at, t.assignee_id, t.status
                  FROM equipment_asset a LEFT JOIN task t ON t.id = a.link_task_id
                 WHERE a.id = ? FOR UPDATE OF a""", (r, n) -> {
            var linked = r.getTimestamp(8);
            return new Row(r.getObject(1, UUID.class), r.getString(2), r.getLong(3), r.getString(4), r.getString(5), r.getInt(6),
                    r.getObject(7, UUID.class), linked == null ? null : linked.toInstant(), r.getObject(9, UUID.class),
                    r.getString(10));
        }, assetId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private void requireOpen(UUID eventId) {
        String state = jdbc.queryForObject("SELECT state FROM event WHERE id = ?", String.class, eventId);
        if (!OPEN_EVENT_STATES.contains(state)) throw state("EVENT_CLOSED");
    }

    private static BusinessException taken(String reason) {
        return new BusinessException(ErrorCode.EQUIPMENT_LINK_TAKEN, ErrorCode.EQUIPMENT_LINK_TAKEN.getMessage(),
                Map.of("reason", reason));
    }

    private static BusinessException reference(String field) {
        return new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.INVALID_REFERENCE.getMessage(),
                Map.of("field", field, "reason", "NOT_FOUND"));
    }

    private static Object utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    /** INVALID_STATE(details.reason) */
    private static BusinessException state(String reason) {
        return new BusinessException(ErrorCode.INVALID_STATE, ErrorCode.INVALID_STATE.getMessage(), Map.of("reason", reason));
    }
}

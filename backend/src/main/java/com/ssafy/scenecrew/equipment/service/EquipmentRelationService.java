package com.ssafy.scenecrew.equipment.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.equipment.service.EquipmentViews.Context;
import com.ssafy.scenecrew.equipment.service.EquipmentViews.Receipt;
import com.ssafy.scenecrew.equipment.service.EquipmentViews.ReleaseReceipt;
import com.ssafy.scenecrew.equipment.service.RelationCommands.Confirmation;
import com.ssafy.scenecrew.equipment.service.RelationCommands.ConnectionWrite;
import com.ssafy.scenecrew.equipment.service.RelationCommands.DependencyWrite;
import com.ssafy.scenecrew.equipment.service.RelationCommands.RequirementItem;
import com.ssafy.scenecrew.equipment.service.RelationCommands.RequirementWrite;
import com.ssafy.scenecrew.equipment.service.RelationCommands.TypedRef;
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

/**
 * 장비 관계(R1): 연결·요구·운영 의존의 추가·교체·해제와 equipment-context 조회. 규칙은 backend/docs/equipment-api.md.
 *
 * <p>모두 CHIEF·DRAFT/RECRUITING/LIVE·요청 키 필수다. 순서는 장비 쓰기와 같다: 행사 잠금 → Member 잠금 → 같은 키 확인 →
 * 권한·상태·revision → 참조 검사 → 저장 → 영수증. 저장할 때마다 Event revision을 1 올리고 운영 기록을 남긴다.
 * 연결 해제는 active=false와 포트 점유 행 삭제, 요구·의존 해제는 행 삭제다(V9-D43). 예약·업무는 바꾸지 않는다.
 * 운영 의존은 계약에 revision이 없어 Event revision으로만 충돌을 막고, 영수증 revision도 Event revision이다.
 */
public class EquipmentRelationService {
    static final String CONNECTION_CREATE = "R1-CONNECTION-CREATE";
    static final String CONNECTION_REPLACE = "R1-CONNECTION-REPLACE";
    static final String CONNECTION_RELEASE = "R1-CONNECTION-RELEASE";
    static final String REQUIREMENT_CREATE = "R1-REQUIREMENT-CREATE";
    static final String REQUIREMENT_REPLACE = "R1-REQUIREMENT-REPLACE";
    static final String REQUIREMENT_RELEASE = "R1-REQUIREMENT-RELEASE";
    static final String DEPENDENCY_CREATE = "R1-DEPENDENCY-CREATE";
    static final String DEPENDENCY_REPLACE = "R1-DEPENDENCY-REPLACE";
    static final String DEPENDENCY_RELEASE = "R1-DEPENDENCY-RELEASE";

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final Idempotency idempotency;
    private final EventWriteSupport events;

    public EquipmentRelationService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency,
            EventWriteSupport events) {
        this.jdbc = jdbc;
        this.members = members;
        this.idempotency = idempotency;
        this.events = events;
    }

    private record Write(EventRow event, Member member, Instant now) { }

    // ---- 연결 -------------------------------------------------------------------------------------------------

    @Transactional
    public IdempotentResult<Receipt> createConnection(UUID eventId, UUID accountId, String key, ConnectionWrite c) {
        return write(eventId, accountId, key, CONNECTION_CREATE, IdempotencyRequest.hash(eventId, c.canonical()),
                c.expectedEventRevision(), Receipt.class, w -> {
                    checkConnection(eventId, c, null);
                    UUID id = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO equipment_connection (id, event_id, from_asset_id, from_port, to_asset_id, to_port,
                                                              confirmed_by_member_id, confirmed_at, revision, active)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, true)""",
                            id, eventId, c.fromAssetId(), c.fromPort(), c.toAssetId(), c.toPort(), w.member().getId(),
                            utc(w.now()));
                    occupyPorts(id, c);
                    long eventRevision = events.bump(eventId);
                    log(w, eventId, "EQUIPMENT_CONNECTION_CREATED", "EQUIPMENT_CONNECTION", id, connectionChanges(c), c.confirmation());
                    return IdempotentResult.of(201, Receipt.of(id, 1, eventRevision), "EQUIPMENT_CONNECTION_CREATED",
                            refs(eventId, id, eventRevision));
                });
    }

    @Transactional
    public IdempotentResult<Receipt> replaceConnection(UUID eventId, UUID connectionId, UUID accountId, String key,
            ConnectionWrite c) {
        return write(eventId, accountId, key, CONNECTION_REPLACE, IdempotencyRequest.hash(eventId, connectionId, c.canonical()),
                c.expectedEventRevision(), Receipt.class, w -> {
                    long revision = activeConnectionRevision(eventId, connectionId);
                    EventWriteSupport.requireRevision(revision, c.expectedRevision(), "connection");
                    checkConnection(eventId, c, connectionId);
                    jdbc.update("DELETE FROM equipment_connection_port WHERE connection_id = ?", connectionId);
                    jdbc.update("""
                            UPDATE equipment_connection SET from_asset_id = ?, from_port = ?, to_asset_id = ?, to_port = ?,
                                   confirmed_by_member_id = ?, confirmed_at = ?, revision = ?
                             WHERE id = ?""", c.fromAssetId(), c.fromPort(), c.toAssetId(), c.toPort(), w.member().getId(),
                            utc(w.now()), revision + 1, connectionId);
                    occupyPorts(connectionId, c);
                    long eventRevision = events.bump(eventId);
                    log(w, eventId, "EQUIPMENT_CONNECTION_REPLACED", "EQUIPMENT_CONNECTION", connectionId,
                            connectionChanges(c), c.confirmation());
                    return IdempotentResult.of(200, Receipt.of(connectionId, revision + 1, eventRevision),
                            "EQUIPMENT_CONNECTION_REPLACED", refs(eventId, connectionId, eventRevision));
                });
    }

    @Transactional
    public IdempotentResult<ReleaseReceipt> releaseConnection(UUID eventId, UUID connectionId, UUID accountId, String key,
            long expectedEventRevision, long expectedRevision) {
        return write(eventId, accountId, key, CONNECTION_RELEASE,
                IdempotencyRequest.hash(eventId, connectionId, expectedEventRevision, expectedRevision), expectedEventRevision,
                ReleaseReceipt.class, w -> {
                    long revision = activeConnectionRevision(eventId, connectionId);
                    EventWriteSupport.requireRevision(revision, expectedRevision, "connection");
                    jdbc.update("UPDATE equipment_connection SET active = false, revision = ? WHERE id = ?", revision + 1,
                            connectionId);
                    jdbc.update("DELETE FROM equipment_connection_port WHERE connection_id = ?", connectionId);
                    long eventRevision = events.bump(eventId);
                    log(w, eventId, "EQUIPMENT_CONNECTION_RELEASED", "EQUIPMENT_CONNECTION", connectionId, new LinkedHashMap<>(),
                            null);
                    return IdempotentResult.of(200, ReleaseReceipt.of(connectionId, eventRevision),
                            "EQUIPMENT_CONNECTION_RELEASED", refs(eventId, connectionId, eventRevision));
                });
    }

    /** 두 장비가 같은 행사에 있고 서로 다르며, 포트가 각 장비에 있고, 다른 활성 연결이 쓰지 않는다. */
    private void checkConnection(UUID eventId, ConnectionWrite c, UUID self) {
        if (c.fromAssetId().equals(c.toAssetId())) throw reference("toAssetId", "SELF");
        requirePort(eventId, c.fromAssetId(), c.fromPort(), "fromAssetId", "fromPort");
        requirePort(eventId, c.toAssetId(), c.toPort(), "toAssetId", "toPort");
        List<UUID> busy = jdbc.queryForList("""
                SELECT connection_id FROM equipment_connection_port
                 WHERE ((asset_id = ? AND port = ?) OR (asset_id = ? AND port = ?)) AND connection_id <> ?""", UUID.class,
                c.fromAssetId(), c.fromPort(), c.toAssetId(), c.toPort(), self == null ? new UUID(0, 0) : self);
        if (!busy.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, ErrorCode.INVALID_STATE.getMessage(),
                    Map.of("reason", "PORT_IN_USE", "connectionId", busy.getFirst().toString()));
        }
    }

    private void requirePort(UUID eventId, UUID assetId, String port, String assetField, String portField) {
        String ports = jdbc.query("SELECT ports::text FROM equipment_asset WHERE id = ? AND event_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, assetId, eventId);
        if (ports == null) throw reference(assetField, "NOT_FOUND");
        for (JsonNode p : EquipmentViews.json(ports)) if (port.equals(p.get("key").asString())) return;
        throw reference(portField, "NOT_FOUND");
    }

    private void occupyPorts(UUID connectionId, ConnectionWrite c) {
        jdbc.update("INSERT INTO equipment_connection_port (connection_id, asset_id, port) VALUES (?, ?, ?), (?, ?, ?)",
                connectionId, c.fromAssetId(), c.fromPort(), connectionId, c.toAssetId(), c.toPort());
    }

    /** 이 행사의 활성 연결 revision(잠금). 없으면 404, 해제됐으면 409 */
    private long activeConnectionRevision(UUID eventId, UUID connectionId) {
        var row = jdbc.query("SELECT revision, active FROM equipment_connection WHERE id = ? AND event_id = ? FOR UPDATE",
                (r, n) -> new Object[] { r.getLong("revision"), r.getBoolean("active") }, connectionId, eventId);
        if (row.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (!(Boolean) row.getFirst()[1]) throw state("RELEASED");
        return (Long) row.getFirst()[0];
    }

    private static Map<String, Object> connectionChanges(ConnectionWrite c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fromAssetId", c.fromAssetId().toString());
        m.put("fromPort", c.fromPort());
        m.put("toAssetId", c.toAssetId().toString());
        m.put("toPort", c.toPort());
        return m;
    }

    // ---- 요구 -------------------------------------------------------------------------------------------------

    @Transactional
    public IdempotentResult<Receipt> createRequirement(UUID eventId, UUID accountId, String key, RequirementWrite q) {
        return write(eventId, accountId, key, REQUIREMENT_CREATE, IdempotencyRequest.hash(eventId, q.canonical()),
                q.expectedEventRevision(), Receipt.class, w -> {
                    checkRequirement(eventId, q);
                    UUID id = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO equipment_requirement (id, event_id, program_id, task_ids, requirements, ready_by,
                                                               schedule_policy, time_evidence, revision)
                            VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, 1)""",
                            id, eventId, q.programId(), ids(q.taskIds()), items(q.requirements()), utc(q.readyBy()),
                            policy(q, w), EventWriteSupport.json(q.timeEvidence().stored(w.member().getId(), w.now())));
                    long eventRevision = events.bump(eventId);
                    log(w, eventId, "EQUIPMENT_REQUIREMENT_CREATED", "EQUIPMENT_REQUIREMENT", id, requirementChanges(q),
                            q.confirmation());
                    return IdempotentResult.of(201, Receipt.of(id, 1, eventRevision), "EQUIPMENT_REQUIREMENT_CREATED",
                            refs(eventId, id, eventRevision));
                });
    }

    @Transactional
    public IdempotentResult<Receipt> replaceRequirement(UUID eventId, UUID requirementId, UUID accountId, String key,
            RequirementWrite q) {
        return write(eventId, accountId, key, REQUIREMENT_REPLACE,
                IdempotencyRequest.hash(eventId, requirementId, q.canonical()), q.expectedEventRevision(), Receipt.class, w -> {
                    long revision = requirementRevision(eventId, requirementId);
                    EventWriteSupport.requireRevision(revision, q.expectedRevision(), "requirement");
                    checkRequirement(eventId, q);
                    jdbc.update("""
                            UPDATE equipment_requirement SET program_id = ?, task_ids = ?::jsonb, requirements = ?::jsonb,
                                   ready_by = ?, schedule_policy = ?::jsonb, time_evidence = ?::jsonb, revision = ?
                             WHERE id = ?""", q.programId(), ids(q.taskIds()), items(q.requirements()), utc(q.readyBy()),
                            policy(q, w), EventWriteSupport.json(q.timeEvidence().stored(w.member().getId(), w.now())),
                            revision + 1, requirementId);
                    long eventRevision = events.bump(eventId);
                    log(w, eventId, "EQUIPMENT_REQUIREMENT_REPLACED", "EQUIPMENT_REQUIREMENT", requirementId,
                            requirementChanges(q), q.confirmation());
                    return IdempotentResult.of(200, Receipt.of(requirementId, revision + 1, eventRevision),
                            "EQUIPMENT_REQUIREMENT_REPLACED", refs(eventId, requirementId, eventRevision));
                });
    }

    @Transactional
    public IdempotentResult<ReleaseReceipt> releaseRequirement(UUID eventId, UUID requirementId, UUID accountId, String key,
            long expectedEventRevision, long expectedRevision) {
        return write(eventId, accountId, key, REQUIREMENT_RELEASE,
                IdempotencyRequest.hash(eventId, requirementId, expectedEventRevision, expectedRevision), expectedEventRevision,
                ReleaseReceipt.class, w -> {
                    long revision = requirementRevision(eventId, requirementId);
                    EventWriteSupport.requireRevision(revision, expectedRevision, "requirement");
                    // 해제한 요구의 내용은 운영 기록에 남기고 행은 지운다(V9-D43).
                    String before = jdbc.queryForObject("""
                            SELECT jsonb_build_object('programId', program_id, 'taskIds', task_ids, 'requirements', requirements,
                                                      'readyBy', ready_by, 'revision', revision)::text
                              FROM equipment_requirement WHERE id = ?""", String.class, requirementId);
                    jdbc.update("DELETE FROM equipment_requirement WHERE id = ?", requirementId);
                    long eventRevision = events.bump(eventId);
                    Map<String, Object> changes = new LinkedHashMap<>();
                    changes.put("before", EquipmentViews.json(before));
                    log(w, eventId, "EQUIPMENT_REQUIREMENT_RELEASED", "EQUIPMENT_REQUIREMENT", requirementId, changes, null);
                    return IdempotentResult.of(200, ReleaseReceipt.of(requirementId, eventRevision),
                            "EQUIPMENT_REQUIREMENT_RELEASED", refs(eventId, requirementId, eventRevision));
                });
    }

    /** 대상(프로그램·업무)이 같은 행사에 있고 업무는 취소되지 않았으며, 요구 장비 목록이 모두 같은 행사 장비다. */
    private void checkRequirement(UUID eventId, RequirementWrite q) {
        if (q.programId() != null) {
            Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM program WHERE id = ? AND event_id = ?)",
                    Boolean.class, q.programId(), eventId);
            if (!Boolean.TRUE.equals(exists)) throw reference("programId", "NOT_FOUND");
        }
        if (!q.taskIds().isEmpty()) {
            Integer found = jdbc.queryForObject("""
                    SELECT count(*) FROM task WHERE event_id = ? AND id = ANY (?) AND status <> 'CANCELED'""",
                    Integer.class, eventId, q.taskIds().toArray(UUID[]::new));
            if (found != q.taskIds().size()) throw reference("taskIds", "NOT_FOUND");
        }
        for (int i = 0; i < q.requirements().size(); i++) {
            RequirementItem item = q.requirements().get(i);
            String path = "requirements[" + i + "]";
            requireAssets(eventId, item.assetIds(), path + ".assetIds");
            requireAssets(eventId, item.compatibleAssetIds(), path + ".compatibleAssetIds");
            requireAssets(eventId, item.componentAssetIds(), path + ".componentAssetIds");
        }
    }

    private void requireAssets(UUID eventId, List<UUID> ids, String field) {
        if (ids.isEmpty()) return;
        Integer found = jdbc.queryForObject("SELECT count(*) FROM equipment_asset WHERE event_id = ? AND id = ANY (?)",
                Integer.class, eventId, ids.toArray(UUID[]::new));
        if (found != ids.size()) throw reference(field, "NOT_FOUND");
    }

    private long requirementRevision(UUID eventId, UUID requirementId) {
        Long revision = jdbc.query("SELECT revision FROM equipment_requirement WHERE id = ? AND event_id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null, requirementId, eventId);
        if (revision == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return revision;
    }

    private static String ids(List<UUID> ids) {
        return EventWriteSupport.json(ids.stream().map(UUID::toString).toList());
    }

    /** V9RequirementItem 배열. id는 문자열 그대로 저장해 028 바깥 참조 검사와 상세 조회가 찾을 수 있게 한다. */
    private static String items(List<RequirementItem> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RequirementItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("assetIds", item.assetIds().stream().map(UUID::toString).toList());
            m.put("compatibleAssetIds", item.compatibleAssetIds().stream().map(UUID::toString).toList());
            m.put("quantity", item.quantity());
            m.put("componentAssetIds", item.componentAssetIds().stream().map(UUID::toString).toList());
            m.put("requiredPortKeys", item.requiredPortKeys());
            out.add(m);
        }
        return EventWriteSupport.json(out);
    }

    private static String policy(RequirementWrite q, Write w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", q.schedulePolicy().mode());
        m.put("allowedStartAt", q.schedulePolicy().allowedStartAt() == null ? null : q.schedulePolicy().allowedStartAt().toString());
        m.put("allowedEndAt", q.schedulePolicy().allowedEndAt() == null ? null : q.schedulePolicy().allowedEndAt().toString());
        m.put("evidence", q.schedulePolicy().evidence().stored(w.member().getId(), w.now()));
        return EventWriteSupport.json(m);
    }

    private static Map<String, Object> requirementChanges(RequirementWrite q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("programId", q.programId() == null ? null : q.programId().toString());
        m.put("taskIds", q.taskIds().stream().map(UUID::toString).toList());
        m.put("readyBy", q.readyBy().toString());
        m.put("mode", q.schedulePolicy().mode());
        return m;
    }

    // ---- 운영 의존 --------------------------------------------------------------------------------------------------

    @Transactional
    public IdempotentResult<Receipt> createDependency(UUID eventId, UUID accountId, String key, DependencyWrite d) {
        return write(eventId, accountId, key, DEPENDENCY_CREATE, IdempotencyRequest.hash(eventId, d.canonical()),
                d.expectedEventRevision(), Receipt.class, w -> {
                    checkDependency(eventId, d, null);
                    UUID id = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO operational_dependency (id, event_id, from_kind, from_id, to_kind, to_id, lag_sec, evidence,
                                                                confirmed_by_member_id, confirmed_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
                            id, eventId, d.from().kind(), d.from().id(), d.to().kind(), d.to().id(), d.lagSec(),
                            EventWriteSupport.json(d.evidence().stored(w.member().getId(), w.now())), w.member().getId(),
                            utc(w.now()));
                    long eventRevision = events.bump(eventId);
                    log(w, eventId, "OPERATIONAL_DEPENDENCY_CREATED", "OPERATIONAL_DEPENDENCY", id, dependencyChanges(d),
                            d.confirmation());
                    return IdempotentResult.of(201, Receipt.of(id, eventRevision, eventRevision),
                            "OPERATIONAL_DEPENDENCY_CREATED", refs(eventId, id, eventRevision));
                });
    }

    @Transactional
    public IdempotentResult<Receipt> replaceDependency(UUID eventId, UUID dependencyId, UUID accountId, String key,
            DependencyWrite d) {
        return write(eventId, accountId, key, DEPENDENCY_REPLACE, IdempotencyRequest.hash(eventId, dependencyId, d.canonical()),
                d.expectedEventRevision(), Receipt.class, w -> {
                    requireDependency(eventId, dependencyId);
                    checkDependency(eventId, d, dependencyId);
                    jdbc.update("""
                            UPDATE operational_dependency SET from_kind = ?, from_id = ?, to_kind = ?, to_id = ?, lag_sec = ?,
                                   evidence = ?::jsonb, confirmed_by_member_id = ?, confirmed_at = ?
                             WHERE id = ?""", d.from().kind(), d.from().id(), d.to().kind(), d.to().id(), d.lagSec(),
                            EventWriteSupport.json(d.evidence().stored(w.member().getId(), w.now())), w.member().getId(),
                            utc(w.now()), dependencyId);
                    long eventRevision = events.bump(eventId);
                    log(w, eventId, "OPERATIONAL_DEPENDENCY_REPLACED", "OPERATIONAL_DEPENDENCY", dependencyId,
                            dependencyChanges(d), d.confirmation());
                    return IdempotentResult.of(200, Receipt.of(dependencyId, eventRevision, eventRevision),
                            "OPERATIONAL_DEPENDENCY_REPLACED", refs(eventId, dependencyId, eventRevision));
                });
    }

    @Transactional
    public IdempotentResult<ReleaseReceipt> releaseDependency(UUID eventId, UUID dependencyId, UUID accountId, String key,
            long expectedEventRevision) {
        return write(eventId, accountId, key, DEPENDENCY_RELEASE,
                IdempotencyRequest.hash(eventId, dependencyId, expectedEventRevision), expectedEventRevision,
                ReleaseReceipt.class, w -> {
                    requireDependency(eventId, dependencyId);
                    String before = jdbc.queryForObject("""
                            SELECT jsonb_build_object('fromKind', from_kind, 'fromId', from_id, 'toKind', to_kind, 'toId', to_id,
                                                      'lagSec', lag_sec)::text
                              FROM operational_dependency WHERE id = ?""", String.class, dependencyId);
                    jdbc.update("DELETE FROM operational_dependency WHERE id = ?", dependencyId);
                    long eventRevision = events.bump(eventId);
                    Map<String, Object> changes = new LinkedHashMap<>();
                    changes.put("before", EquipmentViews.json(before));
                    log(w, eventId, "OPERATIONAL_DEPENDENCY_RELEASED", "OPERATIONAL_DEPENDENCY", dependencyId, changes, null);
                    return IdempotentResult.of(200, ReleaseReceipt.of(dependencyId, eventRevision),
                            "OPERATIONAL_DEPENDENCY_RELEASED", refs(eventId, dependencyId, eventRevision));
                });
    }

    /** 양 끝이 같은 행사의 Program·Task(취소 아님)이고, 자기 자신이 아니며, 같은 관계가 없고, 순환이 생기지 않는다. */
    private void checkDependency(UUID eventId, DependencyWrite d, UUID self) {
        requireTarget(eventId, d.from(), "from");
        requireTarget(eventId, d.to(), "to");
        if (d.from().equals(d.to())) throw reference("to", "SELF");
        Boolean duplicate = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM operational_dependency WHERE event_id = ? AND from_kind = ? AND from_id = ?
                                  AND to_kind = ? AND to_id = ? AND id <> ?)""", Boolean.class, eventId, d.from().kind(),
                d.from().id(), d.to().kind(), d.to().id(), self == null ? new UUID(0, 0) : self);
        if (Boolean.TRUE.equals(duplicate)) throw state("DUPLICATE");
        // 새 간선 from→to를 더했을 때 to에서 from으로 돌아오는 길이 있으면 순환이다.
        Map<String, List<String>> edges = new HashMap<>();
        jdbc.query("SELECT id, from_kind, from_id, to_kind, to_id FROM operational_dependency WHERE event_id = ?", rs -> {
            if (self != null && self.equals(rs.getObject("id", UUID.class))) return;
            edges.computeIfAbsent(rs.getString("from_kind") + ":" + rs.getObject("from_id", UUID.class), k -> new ArrayList<>())
                    .add(rs.getString("to_kind") + ":" + rs.getObject("to_id", UUID.class));
        }, eventId);
        String start = d.to().kind() + ":" + d.to().id();
        String goal = d.from().kind() + ":" + d.from().id();
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> stack = new ArrayDeque<>(List.of(start));
        while (!stack.isEmpty()) {
            String node = stack.pop();
            if (node.equals(goal)) throw reference("to", "CYCLE");
            if (seen.add(node)) stack.addAll(edges.getOrDefault(node, List.of()));
        }
    }

    private void requireTarget(UUID eventId, TypedRef ref, String field) {
        String sql = "PROGRAM".equals(ref.kind())
                ? "SELECT EXISTS (SELECT 1 FROM program WHERE id = ? AND event_id = ?)"
                : "SELECT EXISTS (SELECT 1 FROM task WHERE id = ? AND event_id = ? AND status <> 'CANCELED')";
        if (!Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, ref.id(), eventId))) throw reference(field, "NOT_FOUND");
    }

    private void requireDependency(UUID eventId, UUID dependencyId) {
        Boolean exists = jdbc.query("SELECT true FROM operational_dependency WHERE id = ? AND event_id = ? FOR UPDATE",
                rs -> rs.next() ? Boolean.TRUE : null, dependencyId, eventId);
        if (exists == null) throw new BusinessException(ErrorCode.NOT_FOUND);
    }

    private static Map<String, Object> dependencyChanges(DependencyWrite d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", Map.of("kind", d.from().kind(), "id", d.from().id().toString()));
        m.put("to", Map.of("kind", d.to().kind(), "id", d.to().id().toString()));
        m.put("lagSec", d.lagSec());
        return m;
    }

    // ---- equipment-context ------------------------------------------------------------------------------------------

    /** 총괄의 장비 관계 전체. 한 스냅샷에서 잘림 없이 읽는다(계약 final327R1-read). ENDED도 읽는다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Context context(UUID eventId, UUID accountId) {
        Member member = members.require(eventId, accountId);
        if (member.getEventRole() != EventRole.CHIEF) throw new BusinessException(ErrorCode.FORBIDDEN);
        long revision = jdbc.queryForObject("SELECT revision FROM event WHERE id = ?", Long.class, eventId);
        var connections = jdbc.query("SELECT " + EquipmentViews.CONNECTION_COLUMNS
                + " FROM equipment_connection c WHERE c.event_id = ? AND c.active ORDER BY c.confirmed_at, c.id",
                EquipmentViews::connection, eventId);
        var requirements = jdbc.query("SELECT " + EquipmentViews.REQUIREMENT_COLUMNS
                + " FROM equipment_requirement q WHERE q.event_id = ? ORDER BY q.ready_by, q.id", EquipmentViews::requirement, eventId);
        var dependencies = jdbc.query("SELECT " + EquipmentViews.DEPENDENCY_COLUMNS
                + " FROM operational_dependency d WHERE d.event_id = ? ORDER BY d.confirmed_at, d.id",
                EquipmentViews::dependency, eventId);
        return new Context(eventId, Long.toString(revision), connections, requirements, dependencies);
    }

    // ---- 공통 -------------------------------------------------------------------------------------------------

    /** 행사 잠금 → Member 잠금 → 같은 키 확인 → CHIEF·상태·Event revision → 본문 */
    private <T> IdempotentResult<T> write(UUID eventId, UUID accountId, String key, String operation, String hash,
            long expectedEventRevision, Class<T> type, Function<Write, IdempotentResult<T>> body) {
        EventRow event = events.lock(eventId);
        Member member = members.requireLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, operation, key, hash, eventId, member.getId(),
                member.getParticipationRevision());
        return idempotency.run(request, type, () -> {
            if (member.getEventRole() != EventRole.CHIEF) throw new BusinessException(ErrorCode.FORBIDDEN);
            EventWriteSupport.requireState(event, "DRAFT", "RECRUITING", "LIVE");
            EventWriteSupport.requireRevision(event.revision(), expectedEventRevision, "event");
            return body.apply(new Write(event, member, EventWriteSupport.now()));
        });
    }

    private void log(Write w, UUID eventId, String type, String resourceType, UUID resourceId, Map<String, Object> changes,
            Confirmation confirmation) {
        Map<String, Object> out = new LinkedHashMap<>(changes);
        if (confirmation != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("summary", confirmation.summary());
            c.put("sourceKind", confirmation.sourceKind());
            c.put("observedAt", confirmation.observedAt() == null ? null : confirmation.observedAt().toString());
            out.put("confirmation", c);
        }
        events.log(eventId, type, w.member().getId(), resourceType, resourceId, out, w.now());
    }

    static BusinessException reference(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.INVALID_REFERENCE.getMessage(),
                Map.of("field", field, "reason", reason));
    }

    static BusinessException state(String reason) {
        return new BusinessException(ErrorCode.INVALID_STATE, ErrorCode.INVALID_STATE.getMessage(), Map.of("reason", reason));
    }

    private static Map<String, Object> refs(UUID eventId, UUID id, long eventRevision) {
        return Map.of("eventId", eventId.toString(), "id", id.toString(), "eventRevision", Long.toString(eventRevision));
    }

    private static Object utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
}

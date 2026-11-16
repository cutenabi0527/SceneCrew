package com.ssafy.scenecrew.equipment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import com.ssafy.scenecrew.equipment.service.RecoveryCalculator.Asset;
import com.ssafy.scenecrew.equipment.service.RecoveryCalculator.Item;
import com.ssafy.scenecrew.equipment.service.RecoveryCalculator.Requirement;
import com.ssafy.scenecrew.equipment.service.RecoveryCalculator.Reservation;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.Assumption;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.Candidate;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.EvidenceRef;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.JobResult;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.Pointer;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.Simulation;
import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.global.operation.EventWriteSupport.EventRow;
import com.ssafy.scenecrew.issue.dto.IssueResponses.JobAccepted;

/**
 * 복구 대안 계산 EXT06·결과 EXT07·최근 결과 Q1. 규칙은 backend/docs/recovery.md, 결정 V9-D44.
 *
 * <p>EXT06은 요청 시점에 규칙(RecoveryCalculator)으로 바로 계산해 recovery_simulation에 저장하고, ai_job을 처음부터 SUCCEEDED로
 * 남긴다. 응답은 계약대로 202 JobAccepted이고 FE는 접수 → /ai-jobs/{id} → 결과 조회 흐름을 그대로 쓴다. AI 서버를 부르지 않고
 * outbox도 쓰지 않는다. 계산은 예약·업무를 바꾸지 않으므로 Event revision도 올리지 않는다.
 * 순서: 행사 잠금 → 총괄 잠금 → 같은 키 확인 → 상태·revision·참조 → 계산·저장.
 */
public class RecoveryService {
    public static final String KIND = "SIMULATE_RECOVERY";
    static final String OPERATION = "EXT06";
    public static final Duration LIFETIME = Duration.ofDays(7);
    static final int MAX_TARGETS = 20;
    private static final String STATUS_URL = "/api/v1/ai-jobs/";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final Idempotency idempotency;
    private final EventWriteSupport events;

    public RecoveryService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency, EventWriteSupport events) {
        this.jdbc = jdbc;
        this.members = members;
        this.idempotency = idempotency;
        this.events = events;
    }

    /** V9RecoveryRequest. evidenceRefs는 Operations.evidenceRefs로 형식을 본 값이다. */
    public record Request(long expectedEventRevision, UUID issueId, List<UUID> targetAssetIds, ArrayNode evidenceRefs,
            List<Assumption> assumptions) {
        String canonical() {
            return expectedEventRevision + "|" + issueId + "|" + RecoveryCalculator.sorted(targetAssetIds) + "|"
                    + MAPPER.writeValueAsString(evidenceRefs) + "|" + MAPPER.writeValueAsString(assumptions);
        }
    }

    /** EXT06 */
    @Transactional
    public IdempotentResult<JobAccepted> simulate(UUID eventId, UUID accountId, String key, Request command) {
        EventRow event = events.lock(eventId);
        Member chief = members.requireChiefLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, OPERATION, key, IdempotencyRequest.hash(eventId, command.canonical()),
                eventId, chief.getId(), chief.getParticipationRevision());
        return idempotency.run(request, JobAccepted.class, () -> {
            EventWriteSupport.requireState(event, "RECRUITING", "LIVE");
            EventWriteSupport.requireRevision(event.revision(), command.expectedEventRevision(), "event");
            if (command.issueId() != null && jdbc.queryForList("SELECT 1 FROM issue WHERE id = ? AND event_id = ?",
                    command.issueId(), eventId).isEmpty())
                throw reference("issueId");

            Map<UUID, Asset> assets = assets(eventId);
            for (UUID target : command.targetAssetIds()) if (!assets.containsKey(target)) throw reference("targetAssetIds");
            List<Requirement> requirements = requirements(eventId);
            List<Reservation> reservations = reservations(eventId);
            String inputHash = inputHash(eventId, command, assets, requirements, reservations);
            var result = RecoveryCalculator.calculate(new RecoveryCalculator.Input(eventId, command.targetAssetIds(), assets,
                    requirements, reservations, inputHash));

            Instant now = EventWriteSupport.now();
            UUID simulationId = UUID.randomUUID();
            List<Assumption> assumptions = new ArrayList<>(command.assumptions());
            assumptions.addAll(result.assumptions());
            jdbc.update("""
                    INSERT INTO recovery_simulation (id, event_id, issue_id, target_asset_ids, created_by_member_id, base_revision,
                                                     input_hash, evidence_refs, policy_version, assumptions, calculated_at, state,
                                                     candidates)
                    VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?::jsonb)""",
                    simulationId, eventId, command.issueId(), json(RecoveryCalculator.sorted(command.targetAssetIds())), chief.getId(),
                    event.revision(), inputHash, json(command.evidenceRefs()), RecoveryCalculator.POLICY_VERSION,
                    json(assumptions), Timestamp.from(now), result.state(), json(result.candidates()));
            UUID jobId = UUID.randomUUID();
            JobResult jobResult = new JobResult(simulationId, now, now.plus(LIFETIME));
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("issueId", command.issueId());
            input.put("targetAssetIds", RecoveryCalculator.sorted(command.targetAssetIds()));
            jdbc.update("""
                    INSERT INTO ai_job (id, event_id, requested_by_member_id, kind, state, input_hash, context_revision,
                                        request_schema_version, result_schema_version, model_version, input, result, error, attempt,
                                        execution_meta, trigger_key, analysis_flow_id, auto_reanalysis_count, created_at,
                                        first_started_at, deadline_at, lease_until, finished_at)
                    VALUES (?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?, ?, NULL, ?::jsonb, ?::jsonb, NULL, 1, ?::jsonb, NULL, NULL, 0, ?, ?, ?,
                            NULL, ?)""",
                    jobId, eventId, chief.getId(), KIND, inputHash, event.revision(), RecoveryCalculator.POLICY_VERSION,
                    "FinalRecoveryJobResult", json(input), json(jobResult), json(Map.of("pipeline", "SERVER_RULE")),
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            return IdempotentResult.of(202, new JobAccepted(jobId, STATUS_URL + jobId), "RECOVERY_SIMULATED",
                    Map.of("jobId", jobId.toString(), "simulationId", simulationId.toString()));
        });
    }

    /** EXT07. 현재 총괄만, 모든 행사 상태에서 본다. 완료 7일 뒤부터는 410 */
    @Transactional(readOnly = true)
    public Simulation get(UUID simulationId, UUID accountId) {
        Row row = row(simulationId);
        members.requireChief(row.eventId(), accountId);
        if (!EventWriteSupport.now().isBefore(row.calculatedAt().plus(LIFETIME)))
            throw new BusinessException(ErrorCode.RECOVERY_RESULT_EXPIRED);
        return new Simulation(row.id(), row.eventId(), row.issueId(), uuids(row.targets()), row.createdBy(),
                Long.toString(row.baseRevision()), row.inputHash(),
                Arrays.asList(MAPPER.readValue(row.evidenceRefs(), EvidenceRef[].class)), row.policyVersion(),
                Arrays.asList(MAPPER.readValue(row.assumptions(), Assumption[].class)), row.calculatedAt(), row.state(),
                Arrays.asList(MAPPER.readValue(row.candidates(), Candidate[].class)), row.calculatedAt().plus(LIFETIME));
    }

    /** Q1. 그 보고의 가장 최근 완료 결과 위치. 만료돼도 위치와 expired=true를 준다. 없으면 404 */
    @Transactional(readOnly = true)
    public Pointer latest(UUID issueId, UUID accountId) {
        var eventIds = jdbc.queryForList("SELECT event_id FROM issue WHERE id = ?", UUID.class, issueId);
        if (eventIds.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        members.requireChief(eventIds.getFirst(), accountId);
        var rows = jdbc.query("""
                SELECT id, calculated_at FROM recovery_simulation WHERE issue_id = ? AND event_id = ?
                 ORDER BY calculated_at DESC, id DESC LIMIT 1""",
                (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getObject(2, OffsetDateTime.class).toInstant()},
                issueId, eventIds.getFirst());
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        Instant calculated = (Instant) rows.getFirst()[1];
        Instant expires = calculated.plus(LIFETIME);
        return new Pointer((UUID) rows.getFirst()[0], calculated, expires, !EventWriteSupport.now().isBefore(expires));
    }

    // ---- 읽기 -----------------------------------------------------------------------------------------------

    record Row(UUID id, UUID eventId, UUID issueId, String targets, UUID createdBy, long baseRevision, String inputHash,
            String evidenceRefs, String policyVersion, String assumptions, Instant calculatedAt, String state,
            String candidates) { }

    private Row row(UUID id) {
        return jdbc.query("""
                SELECT id, event_id, issue_id, target_asset_ids::text, created_by_member_id, base_revision, input_hash,
                       evidence_refs::text, policy_version, assumptions::text, calculated_at, state, candidates::text
                  FROM recovery_simulation WHERE id = ?""",
                (r, n) -> new Row(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                        r.getString(4), r.getObject(5, UUID.class), r.getLong(6), r.getString(7), r.getString(8),
                        r.getString(9), r.getString(10), r.getObject(11, OffsetDateTime.class).toInstant(), r.getString(12),
                        r.getString(13)), id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Map<UUID, Asset> assets(UUID eventId) {
        Map<UUID, Asset> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, name, technical_state, component_asset_ids::text, revision FROM equipment_asset
                 WHERE event_id = ? ORDER BY id""", r -> {
            UUID id = r.getObject(1, UUID.class);
            out.put(id, new Asset(id, r.getString(2), r.getString(3), uuids(r.getString(4)), r.getLong(5)));
        }, eventId);
        return out;
    }

    /** 필요 조건과 점유 구간(취소되지 않은 연결 업무·공연 시간의 최소 시작·최대 끝) */
    private List<Requirement> requirements(UUID eventId) {
        List<Requirement> out = new ArrayList<>();
        jdbc.query("""
                SELECT q.id, q.revision, q.program_id, p.name, q.task_ids::text, q.requirements::text, q.ready_by,
                       LEAST(p.start_at, (SELECT min(t.start_at) FROM task t WHERE t.event_id = q.event_id
                                            AND t.id::text IN (SELECT jsonb_array_elements_text(q.task_ids))
                                            AND t.status <> 'CANCELED')) AS window_start,
                       GREATEST(p.end_at, (SELECT max(t.end_at) FROM task t WHERE t.event_id = q.event_id
                                             AND t.id::text IN (SELECT jsonb_array_elements_text(q.task_ids))
                                             AND t.status <> 'CANCELED')) AS window_end
                  FROM equipment_requirement q LEFT JOIN program p ON p.id = q.program_id
                 WHERE q.event_id = ? ORDER BY q.id""", r -> {
            List<Item> items = new ArrayList<>();
            for (JsonNode item : MAPPER.readTree(r.getString(6)))
                items.add(new Item(uuids(item.path("assetIds")), uuids(item.path("compatibleAssetIds"))));
            OffsetDateTime start = r.getObject(8, OffsetDateTime.class);
            OffsetDateTime end = r.getObject(9, OffsetDateTime.class);
            out.add(new Requirement(r.getObject(1, UUID.class), r.getLong(2), r.getObject(3, UUID.class), r.getString(4),
                    uuids(r.getString(5)), items, r.getObject(7, OffsetDateTime.class).toInstant(),
                    start == null ? null : start.toInstant(), end == null ? null : end.toInstant()));
        }, eventId);
        return out;
    }

    private List<Reservation> reservations(UUID eventId) {
        return jdbc.query("""
                SELECT v.id, v.revision, v.status, v.start_at, v.end_at,
                       COALESCE((SELECT jsonb_agg(ra.asset_id ORDER BY ra.asset_id) FROM equipment_reservation_asset ra
                                  WHERE ra.reservation_id = v.id), '[]'::jsonb)::text, v.program_id, v.task_id, v.source_change_set_id
                  FROM equipment_reservation v WHERE v.event_id = ? AND v.status IN ('PLANNED', 'ACTIVE') ORDER BY v.id""",
                (r, n) -> new Reservation(r.getObject(1, UUID.class), r.getLong(2), r.getString(3),
                        r.getObject(4, OffsetDateTime.class).toInstant(), r.getObject(5, OffsetDateTime.class).toInstant(),
                        uuids(r.getString(6)), r.getObject(7, UUID.class), r.getObject(8, UUID.class), r.getObject(9, UUID.class)),
                eventId);
    }

    /**
     * 읽은 행(장비·예약·필요 조건의 id·revision·계산에 쓴 값)과 요청, 정책 버전의 SHA-256. 계산이 현재 시각을 쓰지 않으므로
     * 기준 시각은 넣지 않는다. 같은 입력이면 같은 해시·후보·순서가 나온다.
     */
    static String inputHash(UUID eventId, Request command, Map<UUID, Asset> assets, List<Requirement> requirements,
            List<Reservation> reservations) {
        StringBuilder s = new StringBuilder(RecoveryCalculator.POLICY_VERSION).append('|').append(eventId).append('|')
                .append(command.issueId()).append('|').append(RecoveryCalculator.sorted(command.targetAssetIds())).append('|')
                .append(MAPPER.writeValueAsString(command.evidenceRefs())).append('|')
                .append(MAPPER.writeValueAsString(command.assumptions()));
        for (Asset a : assets.values())
            s.append("|A").append(a.id()).append(':').append(a.revision()).append(':').append(a.technicalState()).append(':')
                    .append(a.components()).append(':').append(a.name());
        for (Requirement q : requirements)
            s.append("|Q").append(q.id()).append(':').append(q.revision()).append(':').append(q.windowStart()).append(':')
                    .append(q.windowEnd()).append(':').append(q.programName());
        for (Reservation v : reservations)
            s.append("|R").append(v.id()).append(':').append(v.revision()).append(':').append(v.status()).append(':')
                    .append(v.startAt()).append(':').append(v.endAt()).append(':').append(v.assetIds());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<UUID> uuids(String json) {
        return uuids(MAPPER.readTree(json));
    }

    private static List<UUID> uuids(JsonNode array) {
        List<UUID> out = new ArrayList<>();
        if (array != null && array.isArray()) for (JsonNode n : array) out.add(UUID.fromString(n.asString()));
        return out;
    }

    private static String json(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    private static BusinessException reference(String field) {
        return new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.INVALID_REFERENCE.getMessage(),
                Map.of("field", field));
    }
}

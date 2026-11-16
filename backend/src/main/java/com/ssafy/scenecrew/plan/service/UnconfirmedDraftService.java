package com.ssafy.scenecrew.plan.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.event.domain.Member;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotencyRequest;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;

/**
 * 행사 공유 미확정 장비·장소 초안(Q3-A, V9-D17 · #355). 행사당 최신본 1개를 총괄만 읽고 저장한다.
 *
 * <ul>
 *   <li>조회: 모든 행사 상태에서 총괄만. 아직 저장한 적이 없으면 404.</li>
 *   <li>저장: DRAFT 행사에서만 전체 교체. 처음은 expectedRevision="0", 이후 현재 초안 revision과 같아야 한다(409).</li>
 *   <li>확정된 장비·계획(028)과 섞지 않고, 저장해도 Event.revision을 올리지 않는다. 영수증의 eventRevision은 저장 때 본 값이다.</li>
 *   <li>장비 참조: 임시 키(tmp-)는 같은 초안의 장비만, 실제 id(UUID)는 이 행사의 등록 장비만 가리킬 수 있다(422).</li>
 * </ul>
 */
@Service
public class UnconfirmedDraftService {
    public static final String OPERATION = "SC-API-Q3-SAVE";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CurrentMembers members;
    private final JdbcTemplate jdbc;
    private final Idempotency idempotency;

    public UnconfirmedDraftService(CurrentMembers members, JdbcTemplate jdbc, Idempotency idempotency) {
        this.members = members;
        this.jdbc = jdbc;
        this.idempotency = idempotency;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"id", "eventId", "revision", "sourceJobId", "equipmentDraft", "venueDraft", "savedByMemberId",
            "savedAt"})
    public record DraftView(UUID id, UUID eventId, String revision, UUID sourceJobId, JsonNode equipmentDraft,
            JsonNode venueDraft, UUID savedByMemberId, Instant savedAt) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"id", "revision", "eventRevision"})
    public record Receipt(UUID id, String revision, String eventRevision) { }

    public record SaveCommand(long expectedRevision, ObjectNode equipmentDraft, ObjectNode venueDraft, UUID sourceJobId) { }

    @Transactional(readOnly = true)
    public DraftView read(UUID eventId, UUID accountId) {
        members.requireChief(eventId, accountId);
        var rows = jdbc.query("""
                SELECT id, revision, source_job_id, payload::text, saved_by_member_id, saved_at
                  FROM event_unconfirmed_draft WHERE event_id = ?""", (r, n) -> {
            JsonNode payload = JSON.readTree(r.getString(4));
            return new DraftView(r.getObject(1, UUID.class), eventId, Long.toString(r.getLong(2)),
                    r.getObject(3, UUID.class), payload.get("equipmentDraft"), payload.get("venueDraft"),
                    r.getObject(5, UUID.class), r.getTimestamp(6).toInstant());
        }, eventId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    @Transactional
    public IdempotentResult<Receipt> save(UUID eventId, UUID accountId, String key, SaveCommand command) {
        members.requireChief(eventId, accountId);
        var event = jdbc.query("SELECT state, revision FROM event WHERE id = ? FOR UPDATE",
                (r, n) -> new Object[] {r.getString(1), r.getLong(2)}, eventId);
        if (event.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        Member chief = members.requireChiefLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, OPERATION, key,
                IdempotencyRequest.hash(eventId, command.expectedRevision(), command.sourceJobId(),
                        JSON.writeValueAsString(command.equipmentDraft()), JSON.writeValueAsString(command.venueDraft())),
                eventId, chief.getId(), chief.getParticipationRevision());
        return idempotency.run(request, Receipt.class, () -> {
            if (!"DRAFT".equals(event.getFirst()[0])) throw new BusinessException(ErrorCode.INVALID_STATE);
            var current = jdbc.query("SELECT id, revision FROM event_unconfirmed_draft WHERE event_id = ? FOR UPDATE",
                    (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getLong(2)}, eventId);
            long revision = current.isEmpty() ? 0 : (Long) current.getFirst()[1];
            if (revision != command.expectedRevision())
                throw new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(),
                        Map.of("target", "unconfirmedDraft"));
            requireReferences(eventId, command);
            ObjectNode payload = JSON.createObjectNode();
            payload.set("equipmentDraft", command.equipmentDraft());
            payload.set("venueDraft", command.venueDraft());
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            UUID id = current.isEmpty() ? UUID.randomUUID() : (UUID) current.getFirst()[0];
            long next = revision + 1;
            if (current.isEmpty())
                jdbc.update("""
                        INSERT INTO event_unconfirmed_draft (id, event_id, revision, source_job_id, payload,
                                                             saved_by_member_id, saved_at)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)""", id, eventId, next, command.sourceJobId(),
                        JSON.writeValueAsString(payload), chief.getId(), Timestamp.from(now));
            else
                jdbc.update("""
                        UPDATE event_unconfirmed_draft SET revision = ?, source_job_id = ?, payload = ?::jsonb,
                               saved_by_member_id = ?, saved_at = ? WHERE id = ?""", next, command.sourceJobId(),
                        JSON.writeValueAsString(payload), chief.getId(), Timestamp.from(now), id);
            Receipt receipt = new Receipt(id, Long.toString(next), Long.toString((Long) event.getFirst()[1]));
            return IdempotentResult.of(200, receipt, "UNCONFIRMED_DRAFT_SAVED", Map.of("draftId", id));
        });
    }

    /** 초안 안의 장비 참조와 근거 Job이 이 행사·이 초안 안에 있는지 본다. */
    private void requireReferences(UUID eventId, SaveCommand command) {
        if (command.sourceJobId() != null && jdbc.queryForList("SELECT id FROM ai_job WHERE id = ? AND event_id = ?",
                UUID.class, command.sourceJobId(), eventId).isEmpty())
            throw reference("sourceJobId");
        Set<String> tempKeys = new HashSet<>();
        Set<UUID> realIds = new HashSet<>();
        for (JsonNode asset : command.equipmentDraft().get("assets")) {
            JsonNode temp = asset.get("tempId");
            if (temp != null && temp.isString() && !tempKeys.add(temp.asString())) throw reference("assets.tempId");
            JsonNode id = asset.get("id");
            if (id != null && id.isString()) realIds.add(uuid(id.asString(), "assets.id"));
        }
        Set<UUID> refs = new HashSet<>(realIds);
        for (String ref : assetRefs(command.equipmentDraft())) {
            if (ref.startsWith("tmp-")) {
                if (!tempKeys.contains(ref)) throw reference("assetRef");
            } else {
                refs.add(uuid(ref, "assetRef"));
            }
        }
        if (refs.isEmpty()) return;
        Long known = jdbc.queryForObject("SELECT count(*) FROM equipment_asset WHERE event_id = ? AND id = ANY(?)",
                Long.class, eventId, refs.toArray(UUID[]::new));
        if (known == null || known != refs.size()) throw reference("assetRef");
    }

    /** 장비를 가리키는 자리: 구성 장비, 연결 양쪽, 장비 요구 항목(지정·호환·구성 장비), 예약 장비 */
    private static List<String> assetRefs(JsonNode equipment) {
        List<String> refs = new java.util.ArrayList<>();
        for (JsonNode asset : equipment.get("assets")) strings(asset.get("componentAssetIds"), refs);
        for (JsonNode c : equipment.get("connections")) {
            strings(c.get("fromAssetId"), refs);
            strings(c.get("toAssetId"), refs);
        }
        for (JsonNode requirement : equipment.get("requirements")) {
            JsonNode items = requirement.get("requirements");
            if (items == null) continue;
            for (JsonNode item : items) {
                strings(item.get("assetIds"), refs);
                strings(item.get("compatibleAssetIds"), refs);
                strings(item.get("componentAssetIds"), refs);
            }
        }
        for (JsonNode r : equipment.get("reservations")) strings(r.get("assetIds"), refs);
        return refs;
    }

    private static void strings(JsonNode node, List<String> out) {
        if (node == null) return;
        if (node.isString()) out.add(node.asString());
        else if (node.isArray()) for (JsonNode item : node) if (item.isString()) out.add(item.asString());
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw reference(field);
        }
    }

    private static BusinessException reference(String field) {
        return new BusinessException(ErrorCode.INVALID_REFERENCE, ErrorCode.INVALID_REFERENCE.getMessage(),
                Map.of("field", field));
    }
}

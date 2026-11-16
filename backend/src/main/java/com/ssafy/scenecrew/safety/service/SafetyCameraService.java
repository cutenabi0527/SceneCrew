package com.ssafy.scenecrew.safety.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

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
import com.ssafy.scenecrew.global.web.SignedCursor;
import com.ssafy.scenecrew.global.web.StrictJson;
import com.ssafy.scenecrew.safety.service.SafetyCommands.CameraCreate;
import com.ssafy.scenecrew.safety.service.SafetyCommands.CameraPatch;
import com.ssafy.scenecrew.safety.service.SafetyCommands.Coverage;
import com.ssafy.scenecrew.safety.service.SafetyViews.CameraPage;
import com.ssafy.scenecrew.safety.service.SafetyViews.CameraView;
import com.ssafy.scenecrew.safety.service.SafetyViews.Receipt;

/**
 * 안전 카메라 목록 EXT21·등록 EXT22·조회 EXT23·수정 EXT24. 규칙은 backend/docs/safety-api.md.
 *
 * <p>모두 CHIEF 전용이다. 쓰기는 RECRUITING·LIVE에서만 받는다. coverage는 지금 평면도 id·revision과 그 구역 프로필 revision을
 * 가리켜야 저장된다(다르면 409 VERSION_CONFLICT). 연결 참조나 coverage를 바꾸면 새 coverage가 지금 기준과 맞을 때만 UNKNOWN,
 * 연결 참조만 바꾸면 재보정 전까지 INVALID_MAPPING이다. 연결 상태와 관측 최신성은 따로 둔다(BE13).
 * 연결 참조는 형식만 검사하고 그 값으로 어디에도 접속하지 않는다. 미리보기 프레임·수집 경로는 이 서비스 밖이다.
 */
public class SafetyCameraService {
    static final String CREATE = "EXT22";
    static final String PATCH = "EXT24";
    /** V9-D43 행사당 카메라 상한 */
    public static final int MAX_CAMERAS_PER_EVENT = 20;
    /** 카메라 연결 업무의 묶음 키(plan LinkTaskRule.CAMERA_BUNDLE과 같다) */
    static final String CAMERA_LINK_BUNDLE = "camera-link";

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final Idempotency idempotency;
    private final EventWriteSupport events;
    private final SignedCursor cursor;

    public SafetyCameraService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency, EventWriteSupport events,
            byte[] signingKey) {
        this.jdbc = jdbc;
        this.members = members;
        this.idempotency = idempotency;
        this.events = events;
        this.cursor = new SignedCursor(signingKey, "safety-cameras");
    }

    /** EXT21. 이름(바이트 순)·id 순 */
    @Transactional(readOnly = true)
    public CameraPage list(UUID eventId, UUID accountId, String after, int limit) {
        Member member = members.require(eventId, accountId);
        SafetyMapService.requireChief(member);
        String scope = eventId + ":" + member.getId() + ":" + member.getParticipationRevision();
        SignedCursor.Position position = cursor.decode(scope, after);
        List<Object> args = new ArrayList<>(List.of(eventId));
        String where = "";
        if (position != null) {
            where = " AND (c.name COLLATE \"C\", c.id) > (CAST(? AS text) COLLATE \"C\", ?)";
            args.add(decode(position.sortKey()));
            args.add(position.id());
        }
        args.add(limit + 1);
        List<CameraView> rows = jdbc.query("SELECT " + SafetyViews.CAMERA_COLUMNS + " FROM safety_camera c WHERE c.event_id = ?"
                + where + " ORDER BY c.name COLLATE \"C\", c.id LIMIT ?", SafetyViews::camera, args.toArray());
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            CameraView last = rows.getLast();
            next = cursor.encode(scope, new SignedCursor.Position(encode(last.name()), last.id()));
        }
        return new CameraPage(List.copyOf(rows), next);
    }

    /** EXT23 */
    @Transactional(readOnly = true)
    public CameraView get(UUID cameraId, UUID accountId) {
        UUID eventId = eventOf(cameraId);
        SafetyMapService.requireChief(members.require(eventId, accountId));
        return jdbc.query("SELECT " + SafetyViews.CAMERA_COLUMNS + " FROM safety_camera c WHERE c.id = ?", SafetyViews::camera,
                cameraId).getFirst();
    }

    /** EXT22 */
    @Transactional
    public IdempotentResult<Receipt> create(UUID eventId, UUID accountId, String key, CameraCreate command) {
        EventRow event = events.lock(eventId);
        Member member = members.requireLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, CREATE, key, IdempotencyRequest.hash(eventId, command.canonical()),
                eventId, member.getId(), member.getParticipationRevision());
        return idempotency.run(request, Receipt.class, () -> {
            SafetyMapService.requireChief(member);
            EventWriteSupport.requireState(event, "RECRUITING", "LIVE");
            EventWriteSupport.requireRevision(event.revision(), command.expectedEventRevision(), "event");
            Integer count = jdbc.queryForObject("SELECT count(*) FROM safety_camera WHERE event_id = ?", Integer.class, eventId);
            if (count >= MAX_CAMERAS_PER_EVENT) throw state("CAMERA_LIMIT");
            requireUniqueName(eventId, command.name(), null);
            requireLinkTask(eventId, command.linkTaskId());
            // linkTaskId를 빼면 이미 있는 "객석 카메라 연결" 업무를 담당 업무로 둔다(V9-D60, #395). null이면 지정하지 않는다.
            UUID linkTask = command.linkTaskSet() ? command.linkTaskId() : cameraLinkTask(eventId);
            Instant now = EventWriteSupport.now();
            // 보정 전(coverage 없음) 카메라는 평면도 없이도 만들 수 있다. 인원은 보정한 뒤부터 센다(V9-D58).
            String coverage = command.coverage().isEmpty() ? "[]"
                    : EventWriteSupport.json(coverage(eventId, command.coverage(), member, now));
            String binding = command.sourceBindingRef();
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO safety_camera (id, event_id, name, source_binding_ref, enabled, revision, coverage,
                                               configured_by_member_id, configured_at, connection_state, stream_session_id,
                                               last_sequence, last_captured_at, last_received_at, last_processed_at,
                                               latest_observation_id, freshness, input_kind, link_task_id)
                    VALUES (?, ?, ?, ?, ?, 1, ?::jsonb, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, NULL, 'UNKNOWN', ?, ?)""",
                    id, eventId, command.name(), binding, command.enabled(), coverage, member.getId(),
                    SafetyMapService.utc(now), command.enabled() ? idleState(binding, null) : "DISABLED",
                    binding != null ? "COLLECTOR" : null, linkTask);
            long eventRevision = events.bump(eventId);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("name", command.name());
            changes.put("enabled", command.enabled());
            changes.put("zones", command.coverage().stream().map(c -> c.zoneId().toString()).toList());
            if (linkTask != null) changes.put("linkTaskId", linkTask.toString());
            events.log(eventId, "SAFETY_CAMERA_CREATED", member.getId(), "SAFETY_CAMERA", id, changes, now);
            return IdempotentResult.of(201, Receipt.of(id, 1, eventRevision), "SAFETY_CAMERA_CREATED",
                    Map.of("eventId", eventId.toString(), "cameraId", id.toString(), "eventRevision", Long.toString(eventRevision)));
        });
    }

    /** EXT24 */
    @Transactional
    public IdempotentResult<Receipt> patch(UUID cameraId, UUID accountId, String key, CameraPatch command) {
        UUID eventId = eventOf(cameraId);
        EventRow event = events.lock(eventId);
        Member member = members.requireLocked(eventId, accountId);
        var request = new IdempotencyRequest(accountId, PATCH, key, IdempotencyRequest.hash(eventId, cameraId, command.canonical()),
                eventId, member.getId(), member.getParticipationRevision());
        return idempotency.run(request, Receipt.class, () -> {
            SafetyMapService.requireChief(member);
            EventWriteSupport.requireState(event, "RECRUITING", "LIVE");
            var row = jdbc.queryForMap("""
                    SELECT name, source_binding_ref, enabled, revision, coverage::text AS coverage, connection_state, freshness,
                           link_id, link_task_id
                      FROM safety_camera WHERE id = ? FOR UPDATE""", cameraId);
            long revision = ((Number) row.get("revision")).longValue();
            EventWriteSupport.requireRevision(revision, command.expectedCameraRevision(), "camera");
            Instant now = EventWriteSupport.now();
            String name = command.name() != null ? command.name() : (String) row.get("name");
            if (command.name() != null) requireUniqueName(eventId, name, cameraId);
            String binding = command.sourceBindingSet() ? command.sourceBindingRef() : (String) row.get("source_binding_ref");
            boolean bindingChanged = !Objects.equals(binding, row.get("source_binding_ref"));
            UUID linkId = (UUID) row.get("link_id");
            UUID linkTask = command.linkTaskSet() ? command.linkTaskId() : (UUID) row.get("link_task_id");
            if (command.linkTaskSet()) requireLinkTask(eventId, linkTask);
            boolean enabled = command.enabled() != null ? command.enabled() : (Boolean) row.get("enabled");
            String coverage = command.coverage() != null
                    ? EventWriteSupport.json(coverage(eventId, command.coverage(), member, now))
                    : (String) row.get("coverage");

            String freshness = (String) row.get("freshness");
            if (command.coverage() != null) freshness = "UNKNOWN";           // 지금 기준으로 다시 보정했다.
            else if (bindingChanged && linkId == null) freshness = "INVALID_MAPPING";   // 다른 입력이 되었으므로 재보정 전에는 판정하지 않는다.
            String connection = (String) row.get("connection_state");
            if (!enabled) connection = "DISABLED";
            else if (bindingChanged || "DISABLED".equals(connection) || "UNLINKED".equals(connection))
                connection = idleState(binding, linkId);
            String inputKind = linkId != null ? "BROWSER" : binding != null ? "COLLECTOR" : null;

            // 연결 참조가 바뀌면 옛 입력 세션의 순서·촬영 시각을 이어 쓰지 않는다(옛 세션·늦은 결과가 최신값을 덮지 못하게,
            // 새 노트북 시계가 옛 노트북보다 늦어도 새 입력이 거절되지 않게).
            jdbc.update("""
                    UPDATE safety_camera SET name = ?, source_binding_ref = ?, enabled = ?, coverage = ?::jsonb, revision = ?,
                           configured_by_member_id = ?, configured_at = ?, connection_state = ?, freshness = ?,
                           input_kind = ?, link_task_id = ?,
                           stream_session_id = CASE WHEN ? THEN NULL ELSE stream_session_id END,
                           last_sequence = CASE WHEN ? THEN NULL ELSE last_sequence END,
                           last_captured_at = CASE WHEN ? THEN NULL ELSE last_captured_at END
                     WHERE id = ?""", name, binding, enabled, coverage, revision + 1, member.getId(),
                    SafetyMapService.utc(now), connection, freshness, inputKind, linkTask, bindingChanged, bindingChanged,
                    bindingChanged, cameraId);
            long eventRevision = events.bump(eventId);
            Map<String, Object> changes = new LinkedHashMap<>();
            if (command.name() != null) changes.put("name", name);
            if (command.enabled() != null) changes.put("enabled", enabled);
            if (bindingChanged) changes.put("sourceBindingChanged", true);
            if (command.linkTaskSet()) changes.put("linkTaskId", linkTask == null ? null : linkTask.toString());
            if (command.coverage() != null)
                changes.put("zones", command.coverage().stream().map(c -> c.zoneId().toString()).toList());
            events.log(eventId, "SAFETY_CAMERA_UPDATED", member.getId(), "SAFETY_CAMERA", cameraId, changes, now);
            return IdempotentResult.of(200, Receipt.of(cameraId, revision + 1, eventRevision), "SAFETY_CAMERA_UPDATED",
                    Map.of("eventId", eventId.toString(), "cameraId", cameraId.toString(),
                            "eventRevision", Long.toString(eventRevision)));
        });
    }

    /**
     * coverage를 지금 평면도·프로필에 묶어 검사하고 저장 모양(V9Coverage)으로 바꾼다.
     * 평면도가 없으면 409 INVALID_STATE(LAYOUT_REQUIRED), 구역 프로필이 없으면 409 INVALID_STATE(PROFILE_REQUIRED),
     * revision이 다르면 409 VERSION_CONFLICT(layout·profile), 다른 행사 구역은 422.
     */
    private List<Map<String, Object>> coverage(UUID eventId, List<Coverage> items, Member member, Instant now) {
        var layout = jdbc.query("SELECT id, revision FROM venue_layout WHERE event_id = ?",
                (r, n) -> new Object[] { r.getObject("id", UUID.class), r.getLong("revision") }, eventId)
                .stream().findFirst().orElseThrow(() -> state("LAYOUT_REQUIRED"));
        Map<UUID, Long> profiles = profileRevisions(eventId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Coverage c = items.get(i);
            Boolean zone = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM zone WHERE id = ? AND event_id = ?)",
                    Boolean.class, c.zoneId(), eventId);
            if (!Boolean.TRUE.equals(zone)) throw SafetyMapService.reference("coverage[" + i + "].zoneId");
            if (!c.layoutId().equals(layout[0]) || c.layoutRevision() != (Long) layout[1]) throw conflict("layout");
            Long profile = profiles.get(c.zoneId());
            if (profile == null) throw state("PROFILE_REQUIRED");
            if (c.profileRevision() != profile) throw conflict("profile");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("zoneId", c.zoneId().toString());
            m.put("layoutId", c.layoutId().toString());
            m.put("layoutRevision", Long.toString(c.layoutRevision()));
            m.put("profileRevision", Long.toString(c.profileRevision()));
            m.put("scope", c.scope());
            m.put("roi", SafetyMapService.points(c.roi()));
            m.put("effectiveAreaM2", c.effectiveAreaM2());
            m.put("calibrationEvidence", c.calibrationEvidence().stored(member.getId(), now));
            out.add(m);
        }
        return out;
    }

    private Map<UUID, Long> profileRevisions(UUID eventId) {
        Map<UUID, Long> out = new HashMap<>();
        jdbc.query("SELECT zone_id, revision FROM safety_zone_profile WHERE event_id = ?",
                rs -> { out.put(rs.getObject("zone_id", UUID.class), rs.getLong("revision")); }, eventId);
        return out;
    }

    /** 저장한 coverage가 지금 평면도·프로필 revision을 모두 가리키는지(지도 저장 뒤 무효화 판정에 쓴다). */
    static boolean coverageCurrent(JsonNode coverage, UUID layoutId, long layoutRevision, Map<UUID, Long> profileRevisions) {
        for (JsonNode c : coverage) {
            if (!layoutId.toString().equals(c.get("layoutId").asString())) return false;
            if (!Long.toString(layoutRevision).equals(c.get("layoutRevision").asString())) return false;
            Long profile = profileRevisions.get(UUID.fromString(c.get("zoneId").asString()));
            if (profile == null || !Long.toString(profile).equals(c.get("profileRevision").asString())) return false;
        }
        return true;
    }

    /** 입력이 아직 없으면 UNLINKED(등록 안됨), 서버 설정 경로나 웹 연결이 있으면 첫 프레임 전까지 CONNECTING */
    public static String idleState(String binding, UUID linkId) {
        return binding != null || linkId != null ? "CONNECTING" : "UNLINKED";
    }

    /** 계획 규칙이 만든 카메라 연결 업무(묶음 키 camera-link, LinkTaskRule). 없으면 null */
    private UUID cameraLinkTask(UUID eventId) {
        return jdbc.query("SELECT id FROM task WHERE event_id = ? AND bundle_key = ? ORDER BY start_at, id LIMIT 1",
                (r, n) -> r.getObject(1, UUID.class), eventId, CAMERA_LINK_BUNDLE).stream().findFirst().orElse(null);
    }

    /** 연결 담당 업무는 같은 행사의 업무만 가리킨다(없으면 422 INVALID_REFERENCE). null은 지정 없음. */
    private void requireLinkTask(UUID eventId, UUID taskId) {
        if (taskId == null) return;
        Boolean found = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM task WHERE id = ? AND event_id = ?)", Boolean.class,
                taskId, eventId);
        if (!Boolean.TRUE.equals(found)) throw SafetyMapService.reference("linkTaskId");
    }

    private void requireUniqueName(UUID eventId, String name, UUID self) {
        String key = UnicodeText.nameKey(name);
        for (var row : jdbc.queryForList("SELECT id, name FROM safety_camera WHERE event_id = ?", eventId)) {
            if (row.get("id").equals(self)) continue;
            if (UnicodeText.nameKey((String) row.get("name")).equals(key)) throw state("DUPLICATE_NAME");
        }
    }

    private UUID eventOf(UUID cameraId) {
        UUID eventId = jdbc.query("SELECT event_id FROM safety_camera WHERE id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, cameraId);
        if (eventId == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return eventId;
    }

    static BusinessException state(String reason) {
        return new BusinessException(ErrorCode.INVALID_STATE, ErrorCode.INVALID_STATE.getMessage(), Map.of("reason", reason));
    }

    static BusinessException conflict(String target) {
        return new BusinessException(ErrorCode.VERSION_CONFLICT, ErrorCode.VERSION_CONFLICT.getMessage(), Map.of("target", target));
    }

    private static String encode(String name) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw StrictJson.invalid("cursor", "FORMAT");
        }
    }
}

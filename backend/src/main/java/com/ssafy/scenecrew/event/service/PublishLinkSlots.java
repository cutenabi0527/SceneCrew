package com.ssafy.scenecrew.event.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.aijob.contract.plan.PlanContract;
import com.ssafy.scenecrew.equipment.service.EquipmentWriteService;
import com.ssafy.scenecrew.global.evidence.EvidenceInput;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.global.text.UnicodeText;
import com.ssafy.scenecrew.plan.ai.generate.LinkTaskRule;
import com.ssafy.scenecrew.safety.service.SafetyCameraService;

/**
 * 발행(029) 때 계획 생성 결과의 카메라·장비로 칸을 만든다(V9-D58·D59, #395).
 *
 * <ul>
 *   <li>미확정 초안의 출처 작업(source_job_id)이 이 행사의 READY 계획 생성이면 그 결과의 deviceDraft를, 아니면 가장 최근에 끝난
 *       READY 계획 생성의 deviceDraft를 쓴다. 계획 저장(028)은 출처를 남기지 않으므로, 계획을 여러 번 생성해 앞 결과를 저장했다면
 *       출처가 없을 때 마지막 결과의 칸이 만들어지는 한계가 있다(#395 리뷰). 없으면 아무것도 하지 않는다.</li>
 *   <li>카메라: 등록 안됨(UNLINKED)·보정 전(coverage [])·사용 중으로 만들고, 묶음 키 camera-link 업무를 연결 담당 업무로 둔다.</li>
 *   <li>장비: 기술 상태 미확인(UNKNOWN)으로 만들고, 근거는 확인 전 가정(AI 문서 추출)이다. 자동 관측 종류(QLXD4)가 있는 장비만
 *       그 종류와 묶음 키 equipment-link 업무를 둔다.</li>
 *   <li>같은 이름(공백·대소문자 무관)이 이미 있거나 행사 상한(카메라 20·장비 200)에 닿으면 건너뛰고 이름·이유를 서버 로그에 남긴다.
 *       이름은 등록 API 상한(40자)으로 자르므로 앞 40자가 같은 이름도 같은 이름으로 본다.</li>
 *   <li>운영 기록 SAFETY_CAMERA_CREATED·EQUIPMENT_REGISTERED(source=PLAN)를 남긴다. Event revision은 발행이 한 번만 올린다.</li>
 * </ul>
 * 호출자가 같은 트랜잭션에서 행사를 배타 잠금한 상태여야 한다.
 */
@Component
public class PublishLinkSlots {
    static final int NAME_MAX = 40;
    static final int MODEL_MAX = 80;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(PublishLinkSlots.class);

    private final JdbcTemplate jdbc;
    private final EventWriteSupport events;

    public PublishLinkSlots(JdbcTemplate jdbc, EventWriteSupport events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public record Created(int cameras, int assets) {
    }

    public Created save(UUID eventId, UUID chiefMemberId) {
        // 미확정 초안의 출처 작업이 READY 계획 생성이면 그것을, 아니면 가장 최근 READY 계획 생성을 쓴다.
        List<Object[]> latest = jdbc.query("""
                SELECT j.id, j.result::text FROM ai_job j
                  LEFT JOIN event_unconfirmed_draft d ON d.event_id = j.event_id AND d.source_job_id = j.id
                 WHERE j.event_id = ? AND j.kind = ? AND j.state = 'SUCCEEDED' AND j.result->>'status' = 'READY'
                 ORDER BY (d.id IS NOT NULL) DESC, j.finished_at DESC NULLS LAST, j.created_at DESC LIMIT 1""",
                (r, n) -> new Object[] { r.getObject(1, UUID.class), r.getString(2) }, eventId, PlanContract.KIND);
        if (latest.isEmpty()) return new Created(0, 0);
        JsonNode devices = MAPPER.readTree((String) latest.get(0)[1]).path(LinkTaskRule.DEVICE_DRAFT);
        if (!devices.isObject()) return new Created(0, 0);
        UUID jobId = (UUID) latest.get(0)[0];
        Instant now = EventWriteSupport.now();
        return new Created(cameras(eventId, chiefMemberId, devices.path("cameras"), now),
                assets(eventId, chiefMemberId, jobId, devices.path("assets"), now));
    }

    private int cameras(UUID eventId, UUID chief, JsonNode items, Instant now) {
        if (items.isEmpty()) return 0;
        UUID task = linkTask(eventId, LinkTaskRule.CAMERA_BUNDLE);
        Set<String> names = names("SELECT name FROM safety_camera WHERE event_id = ?", eventId);
        int created = 0;
        for (JsonNode item : items) {
            String name = clip(item.get("name").asString(), NAME_MAX);
            if (names.size() >= SafetyCameraService.MAX_CAMERAS_PER_EVENT) {
                skipped(eventId, "카메라", name, "LIMIT");
                continue;
            }
            if (!names.add(UnicodeText.nameKey(name))) {
                skipped(eventId, "카메라", name, "DUPLICATE_NAME");
                continue;
            }
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO safety_camera (id, event_id, name, source_binding_ref, enabled, revision, coverage,
                                               configured_by_member_id, configured_at, connection_state, freshness,
                                               input_kind, link_task_id)
                    VALUES (?, ?, ?, NULL, true, 1, '[]'::jsonb, ?, ?, 'UNLINKED', 'UNKNOWN', NULL, ?)""",
                    id, eventId, name, chief, utc(now), task);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("name", name);
            changes.put("source", "PLAN");
            changes.put("linkTaskId", task == null ? null : task.toString());
            events.log(eventId, "SAFETY_CAMERA_CREATED", chief, "SAFETY_CAMERA", id, changes, now);
            created++;
        }
        return created;
    }

    private int assets(UUID eventId, UUID chief, UUID jobId, JsonNode items, Instant now) {
        if (items.isEmpty()) return 0;
        UUID task = linkTask(eventId, LinkTaskRule.EQUIPMENT_BUNDLE);
        Set<String> names = names("SELECT name FROM equipment_asset WHERE event_id = ?", eventId);
        String evidence = EventWriteSupport.json(new EvidenceInput("SIMULATION_ASSUMPTION", "ai-job:" + jobId, null, null)
                .stored(chief, now));
        int created = 0;
        for (JsonNode item : items) {
            String name = clip(item.get("name").asString(), NAME_MAX);
            if (names.size() >= EquipmentWriteService.MAX_ASSETS_PER_EVENT) {
                skipped(eventId, "장비", name, "LIMIT");
                continue;
            }
            if (!names.add(UnicodeText.nameKey(name))) {
                skipped(eventId, "장비", name, "DUPLICATE_NAME");
                continue;
            }
            JsonNode model = item.path("model");
            String auto = item.path("autoObservationKind").isString() ? item.get("autoObservationKind").asString() : null;
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO equipment_asset (id, event_id, name, model, technical_state, state_evidence,
                                                 component_asset_ids, ports, verified_compatibility, storage_zone_id,
                                                 holder_member_id, revision, auto_observation_kind, link_task_id)
                    VALUES (?, ?, ?, ?, 'UNKNOWN', ?::jsonb, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, NULL, NULL, 1, ?, ?)""",
                    id, eventId, name, model.isString() ? clip(model.asString(), MODEL_MAX) : null, evidence, auto,
                    auto == null ? null : task);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("name", name);
            changes.put("technicalState", "UNKNOWN");
            changes.put("source", "PLAN");
            changes.put("kind", item.path("kind").isString() ? item.get("kind").asString() : null);
            changes.put("location", item.path("location").isString() ? item.get("location").asString() : null);
            changes.put("autoObservationKind", auto);
            events.log(eventId, "EQUIPMENT_REGISTERED", chief, "EQUIPMENT_ASSET", id, changes, now);
            created++;
        }
        return created;
    }

    private static void skipped(UUID eventId, String what, String name, String reason) {
        log.info("발행 칸 건너뜀: event={} {} \"{}\" reason={}", eventId, what, name, reason);
    }

    /** 묶음 키로 연결 업무를 찾는다. 총괄이 지웠으면 null(칸은 만들고 담당 업무는 나중에 지정) */
    private UUID linkTask(UUID eventId, String bundle) {
        return jdbc.query("SELECT id FROM task WHERE event_id = ? AND bundle_key = ? ORDER BY start_at, id LIMIT 1",
                (r, n) -> r.getObject(1, UUID.class), eventId, bundle).stream().findFirst().orElse(null);
    }

    private Set<String> names(String sql, UUID eventId) {
        Set<String> out = new HashSet<>();
        for (String name : jdbc.queryForList(sql, String.class, eventId)) out.add(UnicodeText.nameKey(name));
        return out;
    }

    static String clip(String value, int max) {
        String trimmed = value.strip();
        return trimmed.codePointCount(0, trimmed.length()) <= max ? trimmed
                : trimmed.substring(0, trimmed.offsetByCodePoints(0, max)).strip();
    }

    private static Object utc(Instant at) {
        return at.atOffset(java.time.ZoneOffset.UTC);
    }
}

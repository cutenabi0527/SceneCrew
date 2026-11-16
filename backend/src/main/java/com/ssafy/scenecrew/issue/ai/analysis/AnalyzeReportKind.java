package com.ssafy.scenecrew.issue.ai.analysis;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobError;
import com.ssafy.scenecrew.aijob.kind.AiJobKind;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;

/**
 * 보고 분석(ANALYZE_REPORT, 요청4/결과3·4). AI 쪽 계약은 ai/report-api/contract.py. #282 오세진 2026-11-05.
 * 결과4(V9-D48, #359)는 결과3에 운영 상황 추천 situation{primary, related}를 더한다. 이행 동안 결과3도 받는다.
 *
 * <ul>
 *   <li>입력: 보고 정규화 문장과 같은 revision의 허용 구역(10)·프로그램(20)·직무 그룹(31, 업무에서 산출한 허용 구역 포함).</li>
 *   <li>결과: 유형·상태(0.65 미만이면 null)와 구역 후보. 원문 개체·점수는 Report에 남기지 않는다.</li>
 *   <li>반영: Report.analysis에 {jobId, contextRevision, category, reportedState, zoneCandidates[, situation]}만 저장하고
 *       ai_job.result에는 reportId만 남긴다. 계획이 바뀌었으면 공통 처리가 STALE_CONTEXT로 끝낸다(반영 안 함).</li>
 * </ul>
 */
@Component
public class AnalyzeReportKind implements AiJobKind {
    public static final String KIND = "ANALYZE_REPORT";
    static final String RESULT_SCHEMA_VERSION = "3";
    static final String RESULT_SCHEMA_VERSION_SITUATION = "4";
    /** 제품 운영 상황 6종(V9-D03). 미분류는 별도 코드가 아니라 primary=null이다(V9-D48). */
    static final Set<String> SITUATIONS = Set.of("SCHEDULE_DELAY", "STAFF_GAP", "TASK_CONFLICT", "EQUIPMENT_ANOMALY",
            "PERFORMANCE_ORDER_CHANGE", "AUDIENCE_SAFETY");
    static final Set<String> CATEGORIES = Set.of("EQUIPMENT", "SUPPLY", "CONGESTION", "DELAY", "STAFF_GAP", "OTHER");
    static final Set<String> STATES = Set.of("OCCURRED", "RESOLVED_MENTION", "RECURRED", "UNSPECIFIED");
    static final Set<String> SOURCES = Set.of("ZONE", "PROGRAM", "ROLE");
    static final int MAX_ZONES = 10;
    static final int MAX_PROGRAMS = 20;
    static final int MAX_WORK_GROUPS = 31;
    /** AI 계약의 구역 이름 상한. 넘는 구역은 허용 목록에서 뺀다(AI가 요청 전체를 거절하지 않게). */
    static final int MAX_ZONE_NAME = 30;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public AnalyzeReportKind(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public String kind() { return KIND; }
    @Override public String path() { return "/internal/analyze-report"; }
    @Override public String resource() { return "REPORT"; }

    /**
     * 접수 때 저장할 payload. 보고가 가리킨 구역을 맨 앞에 두고 나머지는 이름 순으로 10개까지,
     * 프로그램은 시작 순 20개, 활성 직무 그룹은 이름 순 31개와 그 그룹 업무의 구역(10개까지)이다.
     */
    public Map<String, Object> input(UUID eventId, UUID reportId, String normalizedText, UUID reportZoneId) {
        List<Map<String, Object>> zones = jdbc.query("""
                SELECT id, name FROM zone WHERE event_id = ? AND char_length(btrim(name)) BETWEEN 1 AND ?
                 ORDER BY (id = ?) DESC, name, id LIMIT ?""",
                (r, n) -> zone(r.getObject(1, UUID.class), r.getString(2)), eventId, MAX_ZONE_NAME, reportZoneId,
                MAX_ZONES);
        List<Map<String, Object>> programs = jdbc.query("""
                SELECT id, name, zone_id FROM program WHERE event_id = ? AND btrim(name) <> ''
                 ORDER BY start_at, id LIMIT ?""", (r, n) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", r.getObject(1, UUID.class).toString());
            p.put("name", r.getString(2));
            UUID zone = r.getObject(3, UUID.class);
            p.put("zoneId", zone == null ? null : zone.toString());
            return p;
        }, eventId, MAX_PROGRAMS);
        List<Map<String, Object>> groups = jdbc.query("""
                SELECT g.id, g.name,
                       coalesce((SELECT array_agg(z::text) FROM (SELECT DISTINCT t.zone_id AS z FROM task t
                                  WHERE t.work_group_id = g.id ORDER BY 1 LIMIT ?) s), '{}') AS zone_ids
                  FROM work_group g WHERE g.event_id = ? AND g.active AND btrim(g.name) <> ''
                 ORDER BY g.name, g.id LIMIT ?""", (r, n) -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", r.getObject(1, UUID.class).toString());
            g.put("name", r.getString(2));
            g.put("zoneIds", List.of((String[]) r.getArray(3).getArray()));
            return g;
        }, MAX_ZONES, eventId, MAX_WORK_GROUPS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", reportId.toString());
        payload.put("normalizedText", normalizedText);
        payload.put("allowedZones", zones);
        payload.put("allowedPrograms", programs);
        payload.put("allowedWorkGroups", groups);
        return payload;
    }

    private static Map<String, Object> zone(UUID id, String name) {
        Map<String, Object> z = new LinkedHashMap<>();
        z.put("id", id.toString());
        z.put("name", name);
        return z;
    }

    @Override
    public AiJobOutcome read(AiJobEnvelope request, AiResponse response) {
        JsonNode body = MAPPER.readTree(response.body());
        Optional<String> identity = identityMismatch(request, body);
        if (identity.isPresent()) return invalid("요청과 다른 작업의 응답입니다: " + identity.get());
        if (body.has("error")) return AiJobOutcome.failed(aiError(body.get("error")));
        String version = text(body.get("resultSchemaVersion"));
        boolean withSituation = RESULT_SCHEMA_VERSION_SITUATION.equals(version);
        if (!RESULT_SCHEMA_VERSION.equals(version) && !withSituation)
            return invalid("resultSchemaVersion이 3 또는 4가 아닙니다.");
        String modelVersion = text(body.get("modelVersion"));
        if (modelVersion == null || modelVersion.isBlank()) return invalid("modelVersion이 없습니다.");
        JsonNode result = body.get("result");
        if (result == null || !result.isObject()) return invalid("result가 없습니다.");
        if (!"CURRENT".equals(text(result.get("analysisValidity")))) return invalid("analysisValidity가 CURRENT가 아닙니다.");
        String category = labelOrNull(result.get("category"), CATEGORIES);
        String state = labelOrNull(result.get("reportedState"), STATES);
        if ("!".equals(category) || "!".equals(state)) return invalid("유형·상태 값이 계약과 다릅니다.");
        ArrayNode candidates = candidates(result.get("zoneCandidates"), request);
        if (candidates == null) return invalid("zoneCandidates가 요청의 허용 목록·revision과 맞지 않습니다.");
        ObjectNode situation = null;
        if (withSituation) {
            situation = situation(result.get("situation"));
            if (situation == null) return invalid("situation이 계약(대표 1개 또는 null, 중복 없는 연관)과 맞지 않습니다.");
        }

        ObjectNode accepted = MAPPER.createObjectNode();
        accepted.put("reportId", request.payload().path("reportId").asString());
        putTextOrNull(accepted, "category", category);
        putTextOrNull(accepted, "reportedState", state);
        accepted.set("zoneCandidates", candidates);
        if (situation != null) accepted.set("situation", situation);
        return AiJobOutcome.succeeded(version, modelVersion, MAPPER.writeValueAsString(accepted));
    }

    /** 보고가 남아 있으면 분석 투영을 기록하고 ai_job.result에는 reportId만 남긴다. 보고가 없으면 반영하지 않는다. */
    @Override
    public AiJobOutcome apply(AiJob job, AiJobOutcome outcome) {
        JsonNode result = MAPPER.readTree(outcome.resultJson());
        UUID reportId = UUID.fromString(result.get("reportId").asString());
        ObjectNode analysis = MAPPER.createObjectNode();
        analysis.put("jobId", job.getId().toString());
        analysis.put("contextRevision", job.getContextRevision());
        analysis.set("category", result.get("category"));
        analysis.set("reportedState", result.get("reportedState"));
        analysis.set("zoneCandidates", result.get("zoneCandidates"));
        // 결과4의 상황은 이 보고의 AI 추천으로만 남긴다. 이슈의 확정 상황(operational_situation)은 총괄 정정만 쓴다.
        if (result.has("situation")) analysis.set("situation", result.get("situation"));
        int updated = jdbc.update("""
                UPDATE report r SET analysis = ?::jsonb FROM issue i
                 WHERE r.id = ? AND i.id = r.issue_id AND i.event_id = ?""",
                MAPPER.writeValueAsString(analysis), reportId, job.getEventId());
        if (updated == 0)
            return AiJobOutcome.failed(new AiJobError("REPORT_NOT_FOUND", "분석할 보고가 없습니다.", false, Map.of()));
        ObjectNode stored = MAPPER.createObjectNode();
        stored.put("reportId", reportId.toString());
        return outcome.withResultJson(MAPPER.writeValueAsString(stored));
    }

    /** 후보마다 source·zoneId·sourceRef가 요청의 허용 목록에서 나왔고 contextRevision이 요청과 같아야 한다. */
    private static ArrayNode candidates(JsonNode node, AiJobEnvelope request) {
        if (node == null || !node.isArray()) return null;
        JsonNode payload = request.payload();
        Set<String> zoneIds = ids(payload.path("allowedZones"));
        Map<String, String> programZones = new LinkedHashMap<>();
        for (JsonNode p : payload.path("allowedPrograms"))
            if (p.path("zoneId").isString()) programZones.put(p.get("id").asString(), p.get("zoneId").asString());
        Map<String, Set<String>> groupZones = new LinkedHashMap<>();
        for (JsonNode g : payload.path("allowedWorkGroups")) {
            Set<String> zs = new HashSet<>();
            for (JsonNode z : g.path("zoneIds")) zs.add(z.asString());
            groupZones.put(g.get("id").asString(), zs);
        }
        ArrayNode out = MAPPER.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode c : node) {
            String zone = text(c.get("zoneId"));
            String source = text(c.get("source"));
            String ref = text(c.get("sourceRef"));
            JsonNode revision = c.get("contextRevision");
            if (!c.isObject() || c.size() != 4 || zone == null || ref == null || source == null || !SOURCES.contains(source)
                    || revision == null || !revision.isIntegralNumber() || revision.asLong() != request.contextRevision()
                    || !seen.add(zone))
                return null;
            boolean allowed = switch (source) {
                case "ZONE" -> zoneIds.contains(zone) && zone.equals(ref);
                case "PROGRAM" -> zone.equals(programZones.get(ref));
                default -> groupZones.getOrDefault(ref, Set.of()).contains(zone);
            };
            if (!allowed) return null;
            ObjectNode copy = out.addObject();
            copy.put("zoneId", zone);
            copy.put("source", source);
            copy.put("sourceRef", ref);
            copy.put("contextRevision", revision.asLong());
        }
        return out;
    }

    /**
     * situation{primary: 6종|null, related: 6종[]}. related는 중복 없이, primary를 다시 넣지 않는다.
     * 다른 필드가 있거나 형식이 틀리면 null(계약 위반)이다. 저장할 때는 related 순서를 그대로 둔다.
     */
    static ObjectNode situation(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 2 || !node.has("primary") || !node.has("related"))
            return null;
        JsonNode primary = node.get("primary");
        String primaryType = null;
        if (!primary.isNull()) {
            primaryType = text(primary);
            if (primaryType == null || !SITUATIONS.contains(primaryType)) return null;
        }
        JsonNode related = node.get("related");
        if (!related.isArray()) return null;
        Set<String> seen = new HashSet<>();
        ObjectNode out = MAPPER.createObjectNode();
        putTextOrNull(out, "primary", primaryType);
        ArrayNode list = out.putArray("related");
        for (JsonNode item : related) {
            String type = text(item);
            if (type == null || !SITUATIONS.contains(type) || type.equals(primaryType) || !seen.add(type)) return null;
            list.add(type);
        }
        return out;
    }

    private static Set<String> ids(JsonNode items) {
        Set<String> ids = new HashSet<>();
        for (JsonNode item : items) ids.add(item.get("id").asString());
        return ids;
    }

    /** null이면 null, 허용 라벨이면 그 값, 그 밖이면 "!"(계약 위반 표시) */
    private static String labelOrNull(JsonNode node, Set<String> labels) {
        if (node == null || node.isNull()) return null;
        String value = text(node);
        return value != null && labels.contains(value) ? value : "!";
    }

    /** 성공·접수 후 실패 모두 요청의 식별 필드를 그대로 돌려줘야 한다. */
    private static Optional<String> identityMismatch(AiJobEnvelope request, JsonNode body) {
        if (!request.jobId().toString().equals(text(body.get("jobId")))) return Optional.of("jobId");
        JsonNode attempt = body.get("attempt");
        if (attempt == null || !attempt.isIntegralNumber() || attempt.asLong() != request.attempt()) return Optional.of("attempt");
        if (!request.requestSchemaVersion().equals(text(body.get("requestSchemaVersion")))) return Optional.of("requestSchemaVersion");
        if (!request.inputHash().equals(text(body.get("inputHash")))) return Optional.of("inputHash");
        JsonNode revision = body.get("contextRevision");
        if (revision == null || !revision.isIntegralNumber() || revision.asLong() != request.contextRevision())
            return Optional.of("contextRevision");
        return Optional.empty();
    }

    /** AI의 error{code,message,retryable,details}를 그대로 옮긴다. 형식이 틀리면 계약 위반이다. */
    private static AiJobError aiError(JsonNode error) {
        String code = text(error == null ? null : error.get("code"));
        JsonNode retryable = error == null ? null : error.get("retryable");
        if (code == null || code.isBlank() || retryable == null || !retryable.isBoolean())
            return AiJobError.invalidResult("AI 오류 응답 형식이 올바르지 않습니다.");
        String message = text(error.get("message"));
        Map<String, Object> details = new LinkedHashMap<>();
        JsonNode detailsNode = error.get("details");
        if (detailsNode != null && detailsNode.isObject())
            for (var entry : detailsNode.properties()) details.put(entry.getKey(), MAPPER.convertValue(entry.getValue(), Object.class));
        return new AiJobError(code, message == null ? "" : message, retryable.asBoolean(), details);
    }

    private static AiJobOutcome invalid(String message) {
        return AiJobOutcome.failed(AiJobError.invalidResult(message));
    }

    private static void putTextOrNull(ObjectNode target, String field, String value) {
        if (value == null) target.putNull(field);
        else target.put(field, value);
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }
}

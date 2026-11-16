package com.ssafy.scenecrew.change.ai;

import java.time.Instant;
import java.time.OffsetDateTime;
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
import com.ssafy.scenecrew.aijob.kind.ActiveLimit;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobError;
import com.ssafy.scenecrew.aijob.kind.AiJobKind;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import com.ssafy.scenecrew.change.service.ChangeSetService;
import com.ssafy.scenecrew.change.service.Operations;
import com.ssafy.scenecrew.global.error.BusinessException;

/**
 * AI 대응안 생성(GENERATE_CHANGE, 요청4/결과6). AI 쪽은 ai/change(/internal/generate-change)의 Multi-agent 흐름(#374).
 * 규칙은 backend/docs/change-generation.md. S15P31A202-343.
 *
 * <ul>
 *   <li>입력: 이슈(구역·보고 원문)·지시·운영 상황·기준 시각과 현재 계획 스냅샷(행사 시각·구역·공연·직무 그룹·업무·팀원).
 *       팀원은 id·팀원 번호·그룹·가용·예비 여부만 보내고 이름·계정은 넣지 않는다.</li>
 *   <li>검사(read): 식별 필드, 결과6 모양, READY/NEEDS_INFO 분기, 연산 형식(Operations.check), 근거 참조가 보낸 스냅샷 안인지.
 *       어기면 저장하지 않고 FAILED다.</li>
 *   <li>반영(apply): READY면 연산을 지금 계획에 미리 적용해 보는 기존 변경안 검사로 확인하고 AI 변경안(DRAFT)으로 저장해 changeSetId를 채운다.
 *       실행 중 행사 revision이 바뀌어도 버리지 않는다(requiresCurrentContext=false).
 *       검사를 통과하지 못하면 변경안을 남기지 않고 FAILED다. situation은 이슈의 확인된 운영 상황 행(V9OperationalSituation)이고 없으면 null이다.</li>
 *   <li>063 공개: V9ChangeGenerationResult{changeSetId, operations, rationale, questions, evidenceRefs, simulationRefs, situation}에
 *       참고 기록 alternatives(검토한 다른 방법)·coordination(AI 처리 기록)을 더한다(V9-D55).</li>
 * </ul>
 */
@Component
public class GenerateChangeKind implements AiJobKind {
    public static final String KIND = "GENERATE_CHANGE";
    static final String RESULT_SCHEMA_VERSION = "6";
    static final int MAX_REPORTS = 20;
    static final int MAX_TASKS = 200;
    static final int MAX_ZONES = 10;
    static final int MAX_PROGRAMS = 20;
    static final int MAX_WORK_GROUPS = 31;
    static final int MAX_MEMBERS = 31;
    static final int MAX_TEXT = 1000;
    static final int MAX_SHORT = 200;
    static final int MAX_ALTERNATIVES = 3;
    static final Set<String> RESULT_KEYS = Set.of("status", "operations", "rationale", "alternatives", "questions",
            "coordination", "evidenceRefs", "simulationRefs", "situation");
    static final Set<String> ROLES = Set.of("SUPERVISOR", "SCHEDULE", "STAFFING", "GUIDE", "REVIEW");
    static final Set<String> NODE_STATUSES = Set.of("SUCCEEDED", "NEEDS_INFO", "FAILED");
    /** 근거 종류 → 요청 스냅샷에서 id를 찾을 곳 */
    static final Map<String, String> EVIDENCE_SOURCES = Map.of("ZONE", "zones", "PROGRAM", "programs", "TASK", "tasks",
            "MEMBER", "members");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final ChangeSetService changeSets;

    public GenerateChangeKind(JdbcTemplate jdbc, ChangeSetService changeSets) {
        this.jdbc = jdbc;
        this.changeSets = changeSets;
    }

    @Override public String kind() { return KIND; }
    @Override public String path() { return "/internal/generate-change"; }
    @Override public String resource() { return "GMS"; }
    @Override public boolean external() { return true; }
    /** 같은 행사의 대응안 생성은 진행 중 1개(V9-D43) */
    @Override public ActiveLimit activeLimit() { return ActiveLimit.EVENT; }

    /**
     * 행사 revision이 바뀌어도 결과를 버리지 않는다(!324 리뷰). 대응안이 필요한 사고 상황에는 AI가 도는 동안에도 새 보고·이슈가
     * 들어와 revision이 계속 오르기 때문이다. 대신 apply가 지금 계획에 연산을 미리 적용해 보고(createFromAi), 저장한 변경안은
     * 기준 revision이 저장 시점 값이라 미리보기(069)·적용(070)이 다시 검사한다. 최초 배정(AssignInitialKind)과 같은 방식이다.
     */
    @Override public boolean requiresCurrentContext() { return false; }

    // ---- 입력 ------------------------------------------------------------------------------------------------

    /**
     * 접수 때 저장할 요청4 payload. 호출 전에 이슈·보고가 이 행사 것인지 확인했다.
     *
     * @param reportIds 보낼 보고. 비어 있으면 이슈의 최근 보고 20개(오래된 것부터)
     */
    public Map<String, Object> input(UUID eventId, UUID issueId, List<UUID> reportIds, String instruction, Instant now) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("id", issueId.toString());
        issue.put("zoneId", jdbc.queryForObject("SELECT zone_id FROM issue WHERE id = ?", UUID.class, issueId).toString());
        issue.put("reports", reports(issueId, reportIds));

        Map<String, Object> event = jdbc.queryForObject("SELECT open_at, close_at, teardown_end_at FROM event WHERE id = ?",
                (r, n) -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("openAt", time(r.getObject(1, OffsetDateTime.class)));
                    e.put("closeAt", time(r.getObject(2, OffsetDateTime.class)));
                    e.put("teardownEndAt", time(r.getObject(3, OffsetDateTime.class)));
                    return e;
                }, eventId);
        List<Map<String, Object>> zones = jdbc.query("SELECT id, name FROM zone WHERE event_id = ? ORDER BY name, id LIMIT ?",
                (r, n) -> item("id", r.getObject(1, UUID.class), "name", r.getString(2)), eventId, MAX_ZONES);
        List<Map<String, Object>> programs = jdbc.query("""
                SELECT id, zone_id, name, start_at, end_at FROM program WHERE event_id = ? ORDER BY start_at, id LIMIT ?""",
                (r, n) -> {
                    Map<String, Object> p = item("id", r.getObject(1, UUID.class), "zoneId", r.getObject(2, UUID.class));
                    p.put("name", r.getString(3));
                    p.put("startAt", time(r.getObject(4, OffsetDateTime.class)));
                    p.put("endAt", time(r.getObject(5, OffsetDateTime.class)));
                    return p;
                }, eventId, MAX_PROGRAMS);
        List<Map<String, Object>> groups = jdbc.query("""
                SELECT id, name FROM work_group WHERE event_id = ? AND active ORDER BY name, id LIMIT ?""",
                (r, n) -> item("id", r.getObject(1, UUID.class), "name", r.getString(2)), eventId, MAX_WORK_GROUPS);
        // 팀원 식별 정보(이름·계정)는 보내지 않는다. 담당을 맡을 수 있는 활성 팀원만.
        List<Map<String, Object>> members = jdbc.query("""
                SELECT id, crew_no, work_group_id, available, is_reserve FROM member
                 WHERE event_id = ? AND active AND event_role = 'WORKER' ORDER BY crew_no LIMIT ?""", (r, n) -> {
            Map<String, Object> m = item("id", r.getObject(1, UUID.class), "crewNo", r.getLong(2));
            UUID group = r.getObject(3, UUID.class);
            m.put("workGroupId", group == null ? null : group.toString());
            m.put("available", r.getBoolean(4));
            m.put("isReserve", r.getBoolean(5));
            return m;
        }, eventId, MAX_MEMBERS);
        List<Map<String, Object>> tasks = jdbc.query("""
                SELECT id, title, work_group_id, zone_id, program_id, assignee_id, start_at, end_at, status FROM task
                 WHERE event_id = ? ORDER BY start_at, id LIMIT ?""", (r, n) -> {
            Map<String, Object> t = item("id", r.getObject(1, UUID.class), "title", r.getString(2));
            t.put("workGroupId", r.getObject(3, UUID.class).toString());
            t.put("zoneId", r.getObject(4, UUID.class).toString());
            UUID program = r.getObject(5, UUID.class), assignee = r.getObject(6, UUID.class);
            t.put("programId", program == null ? null : program.toString());
            t.put("assigneeId", assignee == null ? null : assignee.toString());
            t.put("startAt", time(r.getObject(7, OffsetDateTime.class)));
            t.put("endAt", time(r.getObject(8, OffsetDateTime.class)));
            t.put("status", taskStatus(r.getString(9)));
            return t;
        }, eventId, MAX_TASKS);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("issue", issue);
        payload.put("instruction", instruction);
        payload.put("situation", situationHint(issueId));
        payload.put("now", now.toString());
        payload.put("event", event);
        payload.put("zones", zones);
        payload.put("programs", programs);
        payload.put("workGroups", groups);
        payload.put("members", members);
        payload.put("tasks", tasks);
        return payload;
    }

    private List<Map<String, Object>> reports(UUID issueId, List<UUID> reportIds) {
        String sql = "SELECT id, text, created_at FROM report WHERE issue_id = ?"
                + (reportIds.isEmpty() ? "" : " AND id = ANY(?)") + " ORDER BY created_at DESC, id DESC LIMIT ?";
        Object[] args = reportIds.isEmpty() ? new Object[] {issueId, MAX_REPORTS}
                : new Object[] {issueId, reportIds.toArray(UUID[]::new), MAX_REPORTS};
        List<Map<String, Object>> out = new java.util.ArrayList<>(jdbc.query(sql, (r, n) -> {
            Map<String, Object> report = item("id", r.getObject(1, UUID.class), "body", r.getString(2));
            report.put("reportedAt", time(r.getObject(3, OffsetDateTime.class)));
            return report;
        }, args));
        java.util.Collections.reverse(out);   // 오래된 보고부터
        return out;
    }

    /**
     * 모델에 주는 상황 유형. 총괄이 확인한 운영 상황이 있으면 그 값, 없으면 가장 최근 보고 분석(#297)의 추천이다. 둘 다 없으면 null.
     */
    private Map<String, Object> situationHint(UUID issueId) {
        var confirmed = jdbc.query("""
                SELECT primary_type, related_types::text FROM operational_situation
                 WHERE issue_id = ? AND confirmed_at IS NOT NULL ORDER BY confirmed_at DESC LIMIT 1""",
                (r, n) -> hint(r.getString(1), MAPPER.readTree(r.getString(2))), issueId);
        if (!confirmed.isEmpty()) return confirmed.getFirst();
        var suggested = jdbc.query("""
                SELECT (analysis -> 'situation')::text FROM report WHERE issue_id = ? AND analysis -> 'situation' IS NOT NULL
                 ORDER BY created_at DESC, id DESC LIMIT 1""", (r, n) -> MAPPER.readTree(r.getString(1)), issueId);
        if (suggested.isEmpty() || !suggested.getFirst().isObject()) return null;
        JsonNode s = suggested.getFirst();
        return hint(s.path("primary").isString() ? s.get("primary").asString() : null, s.path("related"));
    }

    private static Map<String, Object> hint(String primary, JsonNode related) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("primaryType", primary);
        List<String> types = new java.util.ArrayList<>();
        if (related != null && related.isArray()) related.forEach(t -> types.add(t.asString()));
        out.put("relatedTypes", types);
        return out;
    }

    /** DB 업무 상태 → AI 계약 상태(ai/change contract.TaskInput) */
    static String taskStatus(String db) {
        return switch (db) {
            case "WAITING" -> "TODO";
            case "CANCELED" -> "CANCELLED";
            default -> db;
        };
    }

    // ---- 응답 검사 --------------------------------------------------------------------------------------------

    @Override
    public AiJobOutcome read(AiJobEnvelope request, AiResponse response) {
        JsonNode body = MAPPER.readTree(response.body());
        Optional<String> identity = identityMismatch(request, body);
        if (identity.isPresent()) return invalid("요청과 다른 작업의 응답입니다: " + identity.get());
        if (body.has("error")) return AiJobOutcome.failed(aiError(body.get("error")));
        if (!RESULT_SCHEMA_VERSION.equals(text(body.get("resultSchemaVersion")))) return invalid("resultSchemaVersion이 6이 아닙니다.");
        String modelVersion = text(body.get("modelVersion"));
        if (modelVersion == null || modelVersion.isBlank()) return invalid("modelVersion이 없습니다.");
        JsonNode result = body.get("result");
        if (result == null || !result.isObject()) return invalid("result가 없습니다.");
        for (String key : result.propertyNames()) if (!RESULT_KEYS.contains(key)) return invalid("result." + key + "는 계약에 없는 필드입니다.");

        String status = text(result.get("status"));
        if (!"READY".equals(status) && !"NEEDS_INFO".equals(status)) return invalid("result.status가 READY·NEEDS_INFO가 아닙니다.");
        boolean ready = "READY".equals(status);

        JsonNode operations = result.get("operations");
        ArrayNode ops;
        if (operations == null || !operations.isArray()) return invalid("result.operations가 배열이 아닙니다.");
        if (ready) {
            try {
                ops = Operations.check(operations);
            } catch (BusinessException e) {
                return invalid("result.operations 형식이 변경 연산과 다릅니다.");
            }
        } else {
            if (!operations.isEmpty()) return invalid("NEEDS_INFO에는 연산이 없어야 합니다.");
            ops = MAPPER.createArrayNode();
        }

        String rationale = text(result.get("rationale"));
        if (rationale == null || length(rationale) > MAX_TEXT || (ready && rationale.isBlank())) {
            return invalid("result.rationale 형식이 올바르지 않습니다.");
        }
        ArrayNode questions = questions(result.get("questions"));
        if (questions == null) return invalid("result.questions 형식이 올바르지 않습니다.");
        if (ready != questions.isEmpty()) return invalid("READY는 질문이 없고 NEEDS_INFO는 질문이 하나 이상이어야 합니다.");
        ArrayNode alternatives = alternatives(result.get("alternatives"));
        if (alternatives == null) return invalid("result.alternatives 형식이 올바르지 않습니다.");
        ObjectNode coordination = coordination(result.get("coordination"));
        if (coordination == null) return invalid("result.coordination 형식이 올바르지 않습니다.");
        ArrayNode evidence;
        try {
            evidence = result.has("evidenceRefs") ? Operations.evidenceRefs(result.get("evidenceRefs")) : MAPPER.createArrayNode();
        } catch (BusinessException e) {
            return invalid("result.evidenceRefs 형식이 올바르지 않습니다.");
        }
        Optional<String> foreign = foreignEvidence(evidence, request.payload());
        if (foreign.isPresent()) return invalid("result.evidenceRefs에 보낸 계획에 없는 참조가 있습니다: " + foreign.get());
        JsonNode simulations = result.get("simulationRefs");
        if (simulations != null && (!simulations.isArray() || !simulations.isEmpty())) {
            return invalid("이번 범위의 대응안은 복구 계산(simulationRefs)을 쓰지 않습니다.");
        }

        ObjectNode accepted = MAPPER.createObjectNode();
        accepted.putNull("changeSetId");          // apply에서 변경안을 저장하고 채운다
        accepted.set("operations", ops);
        accepted.put("rationale", rationale);
        accepted.set("questions", questions);
        accepted.set("evidenceRefs", evidence);
        accepted.putArray("simulationRefs");
        accepted.putNull("situation");            // apply에서 DB의 운영 상황 행으로 채운다(AI가 보낸 값은 쓰지 않는다)
        accepted.set("alternatives", alternatives);
        accepted.set("coordination", coordination);
        return AiJobOutcome.succeeded(RESULT_SCHEMA_VERSION, modelVersion, MAPPER.writeValueAsString(accepted));
    }

    /** [{key, field, question}] 모두 비어 있지 않은 문자열 */
    private static ArrayNode questions(JsonNode node) {
        if (node == null) return MAPPER.createArrayNode();
        if (!node.isArray()) return null;
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode q : node) {
            if (!q.isObject() || q.size() != 3) return null;
            String key = text(q.get("key")), field = text(q.get("field")), question = text(q.get("question"));
            if (blankOrLong(key, MAX_SHORT) || blankOrLong(field, MAX_SHORT) || blankOrLong(question, MAX_TEXT)) return null;
            out.addObject().put("key", key).put("field", field).put("question", question);
        }
        return out;
    }

    /** [{summary}] 최대 3개. 검토한 다른 방법(참고 기록) */
    private static ArrayNode alternatives(JsonNode node) {
        if (node == null) return MAPPER.createArrayNode();
        if (!node.isArray() || node.size() > MAX_ALTERNATIVES) return null;
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode a : node) {
            if (!a.isObject() || a.size() != 1 || blankOrLong(text(a.get("summary")), MAX_SHORT)) return null;
            out.addObject().put("summary", a.get("summary").asString());
        }
        return out;
    }

    /**
     * AI 처리 기록 {graph[{taskId, parentTaskId, role, status}], redelegations[{taskId, reason}],
     * resolvedConflicts[{refs, resolution}], contributions[{role, summary}]}. 이 칸만 남긴다(프롬프트·원문은 받지 않는다).
     */
    private static ObjectNode coordination(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        for (String key : node.propertyNames()) {
            if (!Set.of("graph", "redelegations", "resolvedConflicts", "contributions").contains(key)) return null;
        }
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode graph = out.putArray("graph");
        for (JsonNode g : array(node.get("graph"))) {
            String taskId = text(g.get("taskId")), role = text(g.get("role")), status = text(g.get("status"));
            JsonNode parent = g.get("parentTaskId");
            if (!g.isObject() || blankOrLong(taskId, 40) || !ROLES.contains(role) || !NODE_STATUSES.contains(status)
                    || !(parent == null || parent.isNull() || (parent.isString() && !blankOrLong(parent.asString(), 40)))) {
                return null;
            }
            ObjectNode n = graph.addObject().put("taskId", taskId);
            if (parent == null || parent.isNull()) n.putNull("parentTaskId");
            else n.put("parentTaskId", parent.asString());
            n.put("role", role).put("status", status);
        }
        ArrayNode redelegations = out.putArray("redelegations");
        for (JsonNode r : array(node.get("redelegations"))) {
            if (blankOrLong(text(r.get("taskId")), 40) || blankOrLong(text(r.get("reason")), MAX_SHORT)) return null;
            redelegations.addObject().put("taskId", r.get("taskId").asString()).put("reason", r.get("reason").asString());
        }
        ArrayNode resolved = out.putArray("resolvedConflicts");
        for (JsonNode c : array(node.get("resolvedConflicts"))) {
            JsonNode refs = c.get("refs");
            if (refs == null || !refs.isArray() || refs.isEmpty() || blankOrLong(text(c.get("resolution")), MAX_SHORT)) return null;
            ObjectNode item = resolved.addObject();
            ArrayNode keptRefs = item.putArray("refs");
            for (JsonNode ref : refs) {
                if (blankOrLong(text(ref), 60)) return null;
                keptRefs.add(ref.asString());
            }
            item.put("resolution", c.get("resolution").asString());
        }
        ArrayNode contributions = out.putArray("contributions");
        for (JsonNode c : array(node.get("contributions"))) {
            if (!ROLES.contains(text(c.get("role"))) || blankOrLong(text(c.get("summary")), MAX_SHORT)) return null;
            contributions.addObject().put("role", c.get("role").asString()).put("summary", c.get("summary").asString());
        }
        return out;
    }

    private static Iterable<JsonNode> array(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) return List.of(MAPPER.nullNode());   // 배열이 아니면 아래 검사가 실패하게 한다
        return node;
    }

    /** 근거는 이번에 보낸 이슈·보고·스냅샷(구역·공연·업무·팀원) 안의 id여야 한다. 종류가 다르면 그 종류도 위반이다. */
    static Optional<String> foreignEvidence(ArrayNode evidence, JsonNode payload) {
        Map<String, Set<String>> known = new LinkedHashMap<>();
        Set<String> reports = new HashSet<>();
        for (JsonNode r : payload.path("issue").path("reports")) reports.add(r.path("id").asString());
        known.put("REPORT", reports);
        EVIDENCE_SOURCES.forEach((kind, list) -> {
            Set<String> ids = new HashSet<>();
            for (JsonNode x : payload.path(list)) ids.add(x.path("id").asString());
            known.put(kind, ids);
        });
        for (JsonNode ref : evidence) {
            String kind = ref.get("kind").asString(), id = ref.get("id").asString().toLowerCase();
            if (!known.containsKey(kind) || !known.get(kind).contains(id)) return Optional.of(kind + " " + id);
        }
        return Optional.empty();
    }

    // ---- 반영 -------------------------------------------------------------------------------------------------

    /**
     * READY면 연산을 AI 변경안(DRAFT)으로 저장하고 changeSetId를 채운다. 변경안 검사(미리 적용해 보기)를 통과하지 못하면
     * 변경안을 남기지 않고 계약 위반(FAILED)으로 끝낸다. 결과의 situation은 이슈의 확인된 운영 상황 행이다.
     * 공통 처리가 행사 revision·외부 AI 동의를 확인한 뒤 결과 기록과 같은 트랜잭션에서 부른다.
     */
    @Override
    public AiJobOutcome apply(AiJob job, AiJobOutcome outcome) {
        ObjectNode result = (ObjectNode) MAPPER.readTree(outcome.resultJson());
        UUID issueId = UUID.fromString(MAPPER.readTree(job.getInput()).path("issue").path("id").asString());
        if (!result.get("operations").isEmpty()) {
            try {
                UUID changeSetId = changeSets.createFromAi(job.getEventId(), issueId, job.getId(),
                        job.getRequestedByMemberId(), (ArrayNode) result.get("operations"), result.get("rationale").asString(),
                        (ArrayNode) result.get("evidenceRefs"));
                result.put("changeSetId", changeSetId.toString());
            } catch (BusinessException e) {
                Object field = e.getDetails() == null ? null : e.getDetails().get("field");
                return AiJobOutcome.failed(AiJobError.invalidResult("AI 연산이 변경안 검사를 통과하지 못했습니다: "
                        + e.getErrorCode().name() + (field == null ? "" : " " + field)));
            }
        }
        result.set("situation", situation(job.getEventId(), issueId));
        return outcome.withResultJson(MAPPER.writeValueAsString(result));
    }

    /** 이슈의 확인된 운영 상황 행(V9OperationalSituation). 없으면 null */
    private JsonNode situation(UUID eventId, UUID issueId) {
        var rows = jdbc.query("""
                SELECT id, primary_type, related_types::text, source_kind, source_refs::text, confirmed_by_member_id, confirmed_at
                  FROM operational_situation WHERE event_id = ? AND issue_id = ? AND confirmed_at IS NOT NULL
                 ORDER BY confirmed_at DESC LIMIT 1""", (r, n) -> {
            ObjectNode s = MAPPER.createObjectNode();
            s.put("id", r.getObject(1, UUID.class).toString());
            s.put("eventId", eventId.toString());
            s.put("issueId", issueId.toString());
            s.put("primaryType", r.getString(2));
            s.set("relatedTypes", MAPPER.readTree(r.getString(3)));
            s.put("sourceKind", r.getString(4));
            s.set("sourceRefs", MAPPER.readTree(r.getString(5)));
            UUID by = r.getObject(6, UUID.class);
            if (by == null) s.putNull("confirmedByMemberId");
            else s.put("confirmedByMemberId", by.toString());
            s.put("confirmedAt", r.getTimestamp(7).toInstant().toString());
            return (JsonNode) s;
        }, eventId, issueId);
        return rows.isEmpty() ? MAPPER.nullNode() : rows.getFirst();
    }

    // ---- 도우미 -----------------------------------------------------------------------------------------------

    private static Map<String, Object> item(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1 instanceof UUID u ? u.toString() : v1);
        m.put(k2, v2 instanceof UUID u ? u.toString() : v2);
        return m;
    }

    private static String time(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    private static boolean blankOrLong(String s, int max) {
        return s == null || s.isBlank() || length(s) > max;
    }

    /** 성공·접수 후 실패 모두 요청의 식별 필드를 그대로 돌려줘야 한다. 다르면 어느 필드인지 돌려준다. */
    private static Optional<String> identityMismatch(AiJobEnvelope request, JsonNode body) {
        if (!request.jobId().toString().equals(text(body.get("jobId")))) return Optional.of("jobId");
        JsonNode attempt = body.get("attempt");
        if (attempt == null || !attempt.isIntegralNumber() || attempt.asLong() != request.attempt()) return Optional.of("attempt");
        if (!request.requestSchemaVersion().equals(text(body.get("requestSchemaVersion")))) {
            return Optional.of("requestSchemaVersion");
        }
        if (!request.inputHash().equals(text(body.get("inputHash")))) return Optional.of("inputHash");
        JsonNode revision = body.get("contextRevision");
        if (revision == null || !revision.isIntegralNumber() || revision.asLong() != request.contextRevision()) {
            return Optional.of("contextRevision");
        }
        return Optional.empty();
    }

    /** AI가 보낸 error{code,message,retryable,details}를 그대로 옮긴다. 공개 코드 변환은 조회(063)에서 한다. */
    private static AiJobError aiError(JsonNode error) {
        String code = text(error == null ? null : error.get("code"));
        JsonNode retryable = error == null ? null : error.get("retryable");
        if (code == null || code.isBlank() || retryable == null || !retryable.isBoolean()) {
            return AiJobError.invalidResult("AI 오류 응답 형식이 올바르지 않습니다.");
        }
        String message = text(error.get("message"));
        Map<String, Object> details = new LinkedHashMap<>();
        JsonNode detailsNode = error.get("details");
        if (detailsNode != null && detailsNode.isObject()) {
            for (var entry : detailsNode.properties()) details.put(entry.getKey(), MAPPER.convertValue(entry.getValue(), Object.class));
        }
        return new AiJobError(code, message == null ? "" : message, retryable.asBoolean(), details);
    }

    private static AiJobOutcome invalid(String message) {
        return AiJobOutcome.failed(AiJobError.invalidResult(message));
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }
}

package com.ssafy.scenecrew.plan.ai.assign;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

/**
 * AI 최초 배정(ASSIGN_INITIAL, 요청4/결과1). AI 쪽 계약은 ai/assign/contract.py(#368). #267 오세진 2026-11-09.
 *
 * <ul>
 *   <li>입력(V9-D47): 그룹 하나의 미배정 묶음·업무 시간, 같은 그룹 배정 가능 팀원과 이미 맡은 시간, 총괄의 외부 AI 동의.</li>
 *   <li>결과: assignments·unassigned·reasons·generator(GMS|RULE)·coordination(선택). 묶음은 정확히 한 번만 나온다.</li>
 *   <li>반영: 묶음마다 다시 검사해 통과한 묶음만 처음 배정하고 처음 맡음 알림을 만든다. 하나라도 배정하면 Event.revision을 한 번 올린다.</li>
 * </ul>
 *
 * <p>외부 AI 동의가 없어도 접수를 막지 않고 allowExternalAi=false로 보낸다(AI 서버가 규칙으로 배정, V9-D47). 그래서 접수 관문이 보는
 * external()은 false다. 대신 external(job)은 입력의 allowExternalAi를 돌려줘, 외부 AI를 허용해 접수한 작업은 공통 관문이 AI 호출 직전·
 * 반영 직전에 현재 총괄의 동의를 다시 확인하고 철회됐으면 CONSENT_WITHDRAWN으로 끝낸다(V9-D43; 묶음은 미배정으로 남는다).
 * allowExternalAi=false로 보낸 요청에 generator=GMS로 답하면 계약 위반(INVALID_RESULT)이다.
 * 반영은 묶음마다 현재 상태를 다시 검사하므로, 다른 사람의 참여·가용 변경으로 revision이 바뀌어도 낡은 결과로 버리지 않는다.
 */
@Component
public class AssignInitialKind implements AiJobKind {
    public static final String KIND = "ASSIGN_INITIAL";
    static final String RESULT_SCHEMA_VERSION = "1";
    static final Set<String> GENERATORS = Set.of("GMS", "RULE");
    static final int REASON_MAX = 200;
    private static final Set<String> RESULT_FIELDS = Set.of("assignments", "unassigned", "reasons", "generator", "coordination");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final FirstBundleAssigner assigner;

    public AssignInitialKind(JdbcTemplate jdbc, FirstBundleAssigner assigner) {
        this.jdbc = jdbc;
        this.assigner = assigner;
    }

    @Override public String kind() { return KIND; }
    @Override public String path() { return "/internal/assign-initial"; }
    @Override public String resource() { return "GMS"; }
    @Override public ActiveLimit activeLimit() { return ActiveLimit.EVENT; }
    @Override public boolean requiresCurrentContext() { return false; }

    /** 이 작업이 외부 AI를 허용해 접수됐는지(ai_job.input의 allowExternalAi). 공통 관문의 호출·반영 직전 동의 재확인 대상을 정한다. */
    @Override
    public boolean external(AiJob job) {
        return MAPPER.readTree(job.getInput()).path("allowExternalAi").asBoolean(false);
    }

    @Override
    public AiJobOutcome read(AiJobEnvelope request, AiResponse response) {
        JsonNode body = MAPPER.readTree(response.body());
        Optional<String> identity = identityMismatch(request, body);
        if (identity.isPresent()) return invalid("요청과 다른 작업의 응답입니다: " + identity.get());
        if (body.has("error")) return AiJobOutcome.failed(aiError(body.get("error")));
        if (!RESULT_SCHEMA_VERSION.equals(text(body.get("resultSchemaVersion")))) return invalid("resultSchemaVersion이 1이 아닙니다.");
        String modelVersion = text(body.get("modelVersion"));
        if (modelVersion == null || modelVersion.isBlank()) return invalid("modelVersion이 없습니다.");
        JsonNode result = body.get("result");
        if (result == null || !result.isObject()) return invalid("result가 없습니다.");
        for (String field : result.propertyNames())
            if (!RESULT_FIELDS.contains(field)) return invalid("result에 모르는 필드가 있습니다: " + field);
        String generator = text(result.get("generator"));
        if (generator == null || !GENERATORS.contains(generator)) return invalid("generator가 GMS나 RULE이 아닙니다.");
        if ("GMS".equals(generator) && !request.payload().path("allowExternalAi").asBoolean(false))
            return invalid("외부 AI를 허용하지 않은 요청에 generator가 GMS입니다.");
        JsonNode coordination = result.get("coordination");
        if (coordination != null && !coordination.isNull() && !coordination.isObject())
            return invalid("coordination이 객체가 아닙니다.");

        JsonNode payload = request.payload();
        Set<String> bundles = new HashSet<>();
        for (JsonNode b : payload.path("bundles")) bundles.add(b.path("bundleKey").asString());
        Set<String> members = new HashSet<>();
        for (JsonNode m : payload.path("members")) members.add(m.path("memberId").asString());
        Set<String> seen = new HashSet<>();

        ArrayNode assignments = MAPPER.createArrayNode();
        JsonNode assignedNode = result.get("assignments");
        if (assignedNode == null || !assignedNode.isArray()) return invalid("assignments가 배열이 아닙니다.");
        for (JsonNode a : assignedNode) {
            String bundle = text(a.get("bundleKey"));
            String member = text(a.get("memberId"));
            if (!a.isObject() || a.size() != 2 || bundle == null || member == null || !bundles.contains(bundle)
                    || !members.contains(member) || !seen.add(bundle))
                return invalid("assignments가 요청의 묶음·팀원과 맞지 않습니다.");
            ObjectNode copy = assignments.addObject();
            copy.put("bundleKey", bundle);
            copy.put("memberId", member);
        }
        ArrayNode unassigned = MAPPER.createArrayNode();
        JsonNode unassignedNode = result.get("unassigned");
        if (unassignedNode == null || !unassignedNode.isArray()) return invalid("unassigned가 배열이 아닙니다.");
        for (JsonNode u : unassignedNode) {
            String bundle = text(u.get("bundleKey"));
            String code = text(u.get("code"));
            if (!u.isObject() || bundle == null || code == null || code.isBlank() || !bundles.contains(bundle)
                    || !seen.add(bundle))
                return invalid("unassigned가 요청의 묶음과 맞지 않습니다.");
            ObjectNode copy = unassigned.addObject();
            copy.put("bundleKey", bundle);
            copy.put("code", code);
        }
        if (!seen.equals(bundles)) return invalid("모든 묶음이 assignments나 unassigned에 한 번씩 나와야 합니다.");
        JsonNode reasons = result.get("reasons");
        if (reasons == null || !reasons.isArray()) return invalid("reasons가 배열이 아닙니다.");
        for (JsonNode r : reasons) {
            String bundle = text(r.get("bundleKey"));
            String reason = text(r.get("text"));
            if (bundle == null || !bundles.contains(bundle) || reason == null || reason.codePointCount(0, reason.length()) > REASON_MAX)
                return invalid("reasons가 계약과 맞지 않습니다.");
        }

        ObjectNode accepted = MAPPER.createObjectNode();
        accepted.put("workGroupId", payload.path("workGroupId").asString());
        accepted.set("assignments", assignments);
        accepted.set("unassigned", unassigned);
        accepted.put("generator", generator);
        return AiJobOutcome.succeeded(RESULT_SCHEMA_VERSION, modelVersion, MAPPER.writeValueAsString(accepted));
    }

    /**
     * 통과한 묶음만 처음 배정한다(V9-D47). 행사가 운영 중이 아니면 아무것도 배정하지 않는다. ai_job.result에는 실제로 배정한 묶음과
     * 배정하지 못한 묶음·사유(AI가 정한 사유 또는 재검사 사유)만 남긴다. 업무 제목·사람 이름은 남기지 않는다.
     */
    @Override
    public AiJobOutcome apply(AiJob job, AiJobOutcome outcome) {
        JsonNode result = MAPPER.readTree(outcome.resultJson());
        UUID eventId = job.getEventId();
        UUID group = UUID.fromString(result.get("workGroupId").asString());
        var event = jdbc.query("SELECT state FROM event WHERE id = ? FOR UPDATE", (r, n) -> r.getString(1), eventId);
        ArrayNode assigned = MAPPER.createArrayNode();
        ArrayNode unassigned = MAPPER.createArrayNode();
        for (JsonNode u : result.get("unassigned")) unassigned.add(u.deepCopy());
        boolean open = !event.isEmpty() && Set.of("RECRUITING", "LIVE").contains(event.getFirst());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (JsonNode a : result.get("assignments")) {
            String bundle = a.get("bundleKey").asString();
            UUID member = UUID.fromString(a.get("memberId").asString());
            Optional<String> rejected = open
                    ? assigner.assign(eventId, group, bundle, member, job.getId(), now)
                    : Optional.of("EVENT_NOT_OPEN");
            if (rejected.isEmpty()) {
                assigned.add(a.deepCopy());
            } else {
                ObjectNode u = unassigned.addObject();
                u.put("bundleKey", bundle);
                u.put("code", rejected.get());
            }
        }
        if (!assigned.isEmpty())
            jdbc.update("UPDATE event SET revision = revision + 1 WHERE id = ?", eventId);
        ObjectNode stored = MAPPER.createObjectNode();
        stored.put("workGroupId", group.toString());
        stored.put("generator", result.get("generator").asString());
        stored.set("assigned", assigned);
        stored.set("unassigned", unassigned);
        return outcome.withResultJson(MAPPER.writeValueAsString(stored));
    }

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

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }
}

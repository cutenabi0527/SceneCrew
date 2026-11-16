package com.ssafy.scenecrew.document.ai.extraction;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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
 * 문서 추출(EXTRACT_EVENT_INPUT, 요청4/결과3). 총괄이 올린 행사 입력 문서(PDF·스캔 PDF·TXT·MD)에서 행사 조건 후보를 뽑는다.
 * 실제 판독(pypdf·OCR·GMS)은 AI 서버(S15P31A202-284)가 하고, 여기서는 요청을 만들고 결과를 계약대로 검사한다. S15P31A202-236.
 *
 * <ul>
 *   <li>payload: documents[{documentId, name, mime, bytes, pageCount, sha256, url}]. 원본은 url(업무 서버 /internal)로 내려받는다.</li>
 *   <li>결과3: fields(키→문자열), missingRequired, conflictingValues[{field, values}], unreadableFiles[{documentId, code}],
 *       resultStatus(COMPLETE|PARTIAL). COMPLETE면 unreadableFiles가 비어 있어야 한다. PARTIAL이면 작업도 PARTIAL로 끝난다.</li>
 *   <li>선택 필드 floorPlanPages[{documentId, pageNumber}](#284, V9-D54): 평면도로 보이는 PDF 페이지 후보다. 이번 요청에 보낸
 *       PDF·그 쪽수 안이어야 하고(MD·TXT는 후보가 아니다) 같은 쪽이 두 번 나오면 안 된다. 순서는 AI가 준 그대로 둔다(계획 생성은 첫 유효 후보를 쓴다).
 *       없으면 저장하지 않고 063에도 키가 없다.</li>
 *   <li>선택 필드 programs·workGroups·scheduleItems(V9-D66): 공연 순서(시각 포함), 직무 그룹, 업무 일정표 행이다. 형식이 틀리면
 *       그 필드만 버린다. programs·workGroups는 063에 싣고, scheduleItems는 계획 생성 요청4에만 쓴다(GeneratePlanKind).</li>
 *   <li>결과는 DRAFT를 바꾸지 않는 후보다(ai/02). 사용자가 확인·수정해 DRAFT에 반영한다. 그래서 apply는 기본(반영 없음)이다.</li>
 *   <li>계획(Event.revision)이 바뀌었으면 공통 처리가 STALE_CONTEXT로 끝낸다(요청4 contextRevision, ai/02).</li>
 *   <li>외부 생성 AI(GMS)를 쓰므로 요청 계정의 외부 AI 동의가 필요하고, 계정당 진행 중 1개다(be/05, V9-D43).</li>
 * </ul>
 */
@Component
public class ExtractEventInputKind implements AiJobKind {
    public static final String KIND = "EXTRACT_EVENT_INPUT";
    static final String RESULT_SCHEMA_VERSION = "3";
    /** fields 키 형식. 키 목록은 AI(#284)와 맞추는 중이라 이름만 검사한다. 예: date, openAt, locationName */
    static final Pattern FIELD_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.]{0,63}");
    static final int MAX_VALUE_CODE_POINTS = 1000;
    static final Set<String> STATUSES = Set.of("COMPLETE", "PARTIAL");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override public String kind() { return KIND; }
    @Override public String path() { return "/internal/extract-event-input"; }
    @Override public String resource() { return "GMS"; }
    @Override public boolean external() { return true; }
    @Override public ActiveLimit activeLimit() { return ActiveLimit.ACCOUNT; }

    /** 서버가 검증한 최소 범위: 행사와 요청한 총괄 */
    @Override
    public JsonNode scope(AiJob job) {
        ObjectNode scope = MAPPER.createObjectNode();
        scope.put("eventId", job.getEventId().toString());
        scope.put("chiefMemberId", job.getRequestedByMemberId().toString());
        return scope;
    }

    @Override
    public AiJobOutcome read(AiJobEnvelope request, AiResponse response) {
        JsonNode body = MAPPER.readTree(response.body());
        Optional<String> identity = identityMismatch(request, body);
        if (identity.isPresent()) {
            return invalid("요청과 다른 작업의 응답입니다: " + identity.get());
        }
        if (body.has("error")) {
            return AiJobOutcome.failed(aiError(body.get("error")));
        }
        if (!RESULT_SCHEMA_VERSION.equals(text(body.get("resultSchemaVersion")))) {
            return invalid("resultSchemaVersion이 3이 아닙니다.");
        }
        String modelVersion = text(body.get("modelVersion"));
        if (modelVersion == null || modelVersion.isBlank()) {
            return invalid("modelVersion이 없습니다.");
        }
        JsonNode result = body.get("result");
        if (result == null || !result.isObject()) {
            return invalid("result가 없습니다.");
        }
        ObjectNode accepted = MAPPER.createObjectNode();

        JsonNode fields = result.get("fields");
        if (fields == null || !fields.isObject()) return invalid("result.fields가 객체가 아닙니다.");
        ObjectNode keptFields = accepted.putObject("fields");
        for (var entry : fields.properties()) {
            if (!FIELD_KEY.matcher(entry.getKey()).matches() || !boundedText(entry.getValue())) {
                return invalid("result.fields." + entry.getKey() + " 형식이 올바르지 않습니다.");
            }
            keptFields.put(entry.getKey(), entry.getValue().asString());
        }

        ArrayNode missing = keys(result.get("missingRequired"));
        if (missing == null) return invalid("result.missingRequired 형식이 올바르지 않습니다.");
        accepted.set("missingRequired", missing);

        ArrayNode conflicts = conflicts(result.get("conflictingValues"));
        if (conflicts == null) return invalid("result.conflictingValues 형식이 올바르지 않습니다.");
        accepted.set("conflictingValues", conflicts);

        Set<String> requested = new HashSet<>();
        Map<String, Integer> pdfPages = new HashMap<>();   // 평면도 후보가 될 수 있는 문서: 쪽수를 아는 PDF
        for (JsonNode d : request.payload().path("documents")) {
            String id = d.path("documentId").asString();
            requested.add(id);
            JsonNode pages = d.path("pageCount");
            if ("application/pdf".equals(d.path("mime").asString(null)) && pages.isIntegralNumber() && pages.asInt() > 0) {
                pdfPages.put(id, pages.asInt());
            }
        }
        ArrayNode unreadable = unreadable(result.get("unreadableFiles"), requested);
        if (unreadable == null) return invalid("result.unreadableFiles 형식이 올바르지 않거나 요청에 없는 문서입니다.");
        accepted.set("unreadableFiles", unreadable);

        if (result.has("floorPlanPages")) {
            ArrayNode floorPlans = floorPlanPages(result.get("floorPlanPages"), pdfPages);
            if (floorPlans == null) {
                return invalid("result.floorPlanPages 형식이 올바르지 않거나 요청한 PDF의 쪽이 아닙니다.");
            }
            accepted.set("floorPlanPages", floorPlans);
        }

        // 구조 결과(V9-D66). 형식이 틀리면 그 필드만 버린다(행사 조건 후보는 그대로 쓸 수 있다).
        ArrayNode programs = programs(result.get("programs"));
        if (programs != null) accepted.set("programs", programs);
        ArrayNode groups = workGroups(result.get("workGroups"));
        if (groups != null) accepted.set("workGroups", groups);
        ArrayNode schedule = scheduleItems(result.get("scheduleItems"), requested);
        if (schedule != null) accepted.set("scheduleItems", schedule);

        String status = text(result.get("resultStatus"));
        if (status == null || !STATUSES.contains(status)) return invalid("result.resultStatus가 COMPLETE·PARTIAL이 아닙니다.");
        if ("COMPLETE".equals(status) && !unreadable.isEmpty()) {
            return invalid("읽지 못한 파일이 있는데 resultStatus가 COMPLETE입니다.");
        }
        accepted.put("resultStatus", status);

        String json = MAPPER.writeValueAsString(accepted);
        return "PARTIAL".equals(status)
                ? AiJobOutcome.partial(RESULT_SCHEMA_VERSION, modelVersion, json)
                : AiJobOutcome.succeeded(RESULT_SCHEMA_VERSION, modelVersion, json);
    }

    static final int MAX_PROGRAMS = 20;
    static final int MAX_WORK_GROUPS = 31;
    static final int MAX_SCHEDULE_ITEMS = 50;
    private static final Pattern SCHEDULE_KEY = Pattern.compile("[A-Za-z0-9_-]{1,30}");

    /** [{title(1~20자), startAt, endAt}] 공연 순서. 시각은 오프셋이 있는 ISO이고 시작 &lt; 종료다. 없거나 틀리면 null */
    static ArrayNode programs(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > MAX_PROGRAMS) return null;
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode p : node) {
            if (!p.isObject() || p.size() != 3) return null;
            String title = text(p.get("title"));
            OffsetDateTime start = instant(p.get("startAt"));
            OffsetDateTime end = instant(p.get("endAt"));
            if (!name(title, 20) || start == null || end == null || !start.isBefore(end)) return null;
            // 원문 문자열을 그대로 둔다(OffsetDateTime.toString은 :00초를 생략해 다른 소비자가 읽는 모양이 달라진다)
            out.addObject().put("title", title).put("startAt", text(p.get("startAt"))).put("endAt", text(p.get("endAt")));
        }
        return out;
    }

    /** [{name(1~20자)}] 직무 그룹. 이름은 겹치지 않는다. 없거나 틀리면 null */
    static ArrayNode workGroups(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > MAX_WORK_GROUPS) return null;
        ArrayNode out = MAPPER.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode g : node) {
            String name = g.isObject() && g.size() == 1 ? text(g.get("name")) : null;
            if (!name(name, 20) || !seen.add(name)) return null;
            out.addObject().put("name", name);
        }
        return out;
    }

    /**
     * 업무 일정표 행(V9-D66, 요청4 scheduleItems와 같은 모양). 063에는 싣지 않고 계획 생성 요청에만 쓴다.
     * key는 겹치지 않고, 시각은 둘 다 있거나 둘 다 null이며, source 문서는 이번 요청에 보낸 문서다. 없거나 틀리면 null
     */
    static ArrayNode scheduleItems(JsonNode node, Set<String> requested) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > MAX_SCHEDULE_ITEMS) return null;
        ArrayNode out = MAPPER.createArrayNode();
        Set<String> keys = new HashSet<>();
        Set<String> allowed = Set.of("key", "title", "startAt", "endAt", "timeText", "workGroupName", "zoneName",
                "bundle", "source", "steps", "checklist");
        for (JsonNode i : node) {
            if (!i.isObject()) return null;
            for (var entry : i.properties()) if (!allowed.contains(entry.getKey())) return null;
            String key = text(i.get("key"));
            if (key == null || !SCHEDULE_KEY.matcher(key).matches() || !keys.add(key)) return null;
            String title = text(i.get("title"));
            if (!name(title, 100)) return null;
            JsonNode startNode = i.get("startAt");
            JsonNode endNode = i.get("endAt");
            if (startNode == null || endNode == null) return null;
            OffsetDateTime start = startNode.isNull() ? null : instant(startNode);
            OffsetDateTime end = endNode.isNull() ? null : instant(endNode);
            if ((start == null) != startNode.isNull() || (end == null) != endNode.isNull()) return null;
            JsonNode source = i.get("source");
            if (source == null || !source.isObject() || source.size() != 2) return null;
            String documentId = text(source.get("documentId"));
            JsonNode page = source.get("pageNumber");
            if (documentId == null || !requested.contains(documentId) || page == null || !page.isIntegralNumber()
                    || !page.canConvertToInt() || page.asInt() < 1) return null;
            ObjectNode kept = out.addObject();
            kept.put("key", key);
            kept.put("title", title);
            if (start == null) kept.putNull("startAt"); else kept.put("startAt", text(startNode));
            if (end == null) kept.putNull("endAt"); else kept.put("endAt", text(endNode));
            for (var optional : Map.of("timeText", 40, "workGroupName", 20, "zoneName", 30, "bundle", 40).entrySet()) {
                JsonNode value = i.get(optional.getKey());
                if (value == null || value.isNull()) {
                    kept.putNull(optional.getKey());
                } else if (name(text(value), optional.getValue())) {
                    kept.put(optional.getKey(), value.asString());
                } else {
                    return null;
                }
            }
            kept.putObject("source").put("documentId", documentId).put("pageNumber", page.asInt());
            for (var list : Map.of("steps", 120, "checklist", 80).entrySet()) {
                JsonNode values = i.get(list.getKey());
                if (values == null || values.isNull()) continue;
                if (!values.isArray() || values.isEmpty() || values.size() > 6) return null;
                ArrayNode keptValues = kept.putArray(list.getKey());
                for (JsonNode v : values) {
                    if (!name(text(v), list.getValue())) return null;
                    keptValues.add(v.asString());
                }
            }
        }
        return out;
    }

    /** 공백만이 아닌 1~max 코드포인트 문자열 */
    private static boolean name(String value, int max) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max;
    }

    private static OffsetDateTime instant(JsonNode node) {
        String value = text(node);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 문자열 키 목록. 각 키는 FIELD_KEY 형식이고 중복이 없다. */
    private static ArrayNode keys(JsonNode node) {
        if (node == null || !node.isArray()) return null;
        ArrayNode out = MAPPER.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode k : node) {
            String key = text(k);
            if (key == null || !FIELD_KEY.matcher(key).matches() || !seen.add(key)) return null;
            out.add(key);
        }
        return out;
    }

    /** [{field, values[1..]}]. 값은 문자열이다. */
    private static ArrayNode conflicts(JsonNode node) {
        if (node == null || !node.isArray()) return null;
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode c : node) {
            String field = text(c.get("field"));
            JsonNode values = c.get("values");
            if (!c.isObject() || field == null || !FIELD_KEY.matcher(field).matches() || values == null
                    || !values.isArray() || values.isEmpty()) return null;
            ObjectNode kept = out.addObject();
            kept.put("field", field);
            ArrayNode keptValues = kept.putArray("values");
            for (JsonNode v : values) {
                if (!boundedText(v)) return null;
                keptValues.add(v.asString());
            }
        }
        return out;
    }

    /** [{documentId, code}]. documentId는 이번 요청에 보낸 문서여야 한다. */
    private static ArrayNode unreadable(JsonNode node, Set<String> requested) {
        if (node == null || !node.isArray()) return null;
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode u : node) {
            String documentId = text(u.get("documentId"));
            String code = text(u.get("code"));
            if (!u.isObject() || documentId == null || !requested.contains(documentId) || code == null || code.isBlank()) {
                return null;
            }
            ObjectNode kept = out.addObject();
            kept.put("documentId", documentId);
            kept.put("code", code);
        }
        return out;
    }

    /**
     * [{documentId, pageNumber}]. documentId는 이번 요청에 보낸 PDF(쪽수를 아는 것)이고 pageNumber는 1 이상 그 쪽수 이하다.
     * MD·TXT는 평면도 후보가 아니다. 같은 문서·쪽은 한 번만 나온다. 순서는 그대로 둔다.
     */
    private static ArrayNode floorPlanPages(JsonNode node, Map<String, Integer> pdfPages) {
        if (node == null || !node.isArray()) return null;
        ArrayNode out = MAPPER.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode p : node) {
            if (!p.isObject() || p.size() != 2) return null;
            String documentId = text(p.get("documentId"));
            JsonNode page = p.get("pageNumber");
            if (documentId == null || !pdfPages.containsKey(documentId) || page == null || !page.isIntegralNumber()
                    || !page.canConvertToInt() || page.asInt() < 1 || page.asInt() > pdfPages.get(documentId)) return null;
            if (!seen.add(documentId + "#" + page.asInt())) return null;
            ObjectNode kept = out.addObject();
            kept.put("documentId", documentId);
            kept.put("pageNumber", page.asInt());
        }
        return out;
    }

    private static boolean boundedText(JsonNode node) {
        String value = text(node);
        return value != null && value.codePointCount(0, value.length()) <= MAX_VALUE_CODE_POINTS;
    }

    /** 성공·접수 후 실패 모두 요청의 식별 필드를 그대로 돌려줘야 한다. 다르면 어느 필드인지 돌려준다. */
    private static Optional<String> identityMismatch(AiJobEnvelope request, JsonNode body) {
        if (!request.jobId().toString().equals(text(body.get("jobId")))) return Optional.of("jobId");
        JsonNode attempt = body.get("attempt");
        if (attempt == null || !attempt.isIntegralNumber() || attempt.asLong() != request.attempt()) {
            return Optional.of("attempt");
        }
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
            for (var entry : detailsNode.properties()) {
                details.put(entry.getKey(), MAPPER.convertValue(entry.getValue(), Object.class));
            }
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

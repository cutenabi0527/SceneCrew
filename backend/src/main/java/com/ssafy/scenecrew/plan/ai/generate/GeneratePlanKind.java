package com.ssafy.scenecrew.plan.ai.generate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.validation.Validator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.contract.AiContractJson;
import com.ssafy.scenecrew.aijob.contract.ContractIssue;
import com.ssafy.scenecrew.aijob.contract.plan.PlanContract;
import com.ssafy.scenecrew.aijob.contract.plan.PlanFailure;
import com.ssafy.scenecrew.aijob.contract.plan.PlanOutcome;
import com.ssafy.scenecrew.aijob.contract.plan.PlanRequest;
import com.ssafy.scenecrew.aijob.contract.plan.PlanResponseReader;
import com.ssafy.scenecrew.aijob.contract.plan.PlanSuccess;
import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.domain.AiJobState;
import com.ssafy.scenecrew.aijob.kind.ActiveLimit;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobError;
import com.ssafy.scenecrew.aijob.kind.AiJobKind;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import com.ssafy.scenecrew.document.ai.extraction.ExtractEventInputKind;
import com.ssafy.scenecrew.document.ai.extraction.ExtractionProperties;
import com.ssafy.scenecrew.global.text.UnicodeText;

/**
 * 최초 계획 생성(GENERATE_PLAN, 요청4/결과5·결과6). AI 쪽은 ai/plan(/internal/generate-plan). S15P31A202-280·391.
 *
 * <ul>
 *   <li>입력: 현재 DRAFT의 행사 기본정보·구역·공연·활성 직무 그룹. Member·담당자 같은 개인 정보는 넣지 않는다.
 *       floorPlan(V9-D54, #391): 가장 최근에 끝난 문서 추출 결과3의 floorPlanPages에서 첫 유효 후보(원본이 남아 있고 만료 전,
 *       쪽수 안). 없으면 null이고, AI는 null이면 결과5, 있으면 결과6을 낸다. AI는 원본을 /internal/intake-documents로 받는다.
 *       scheduleItems(V9-D66): 같은 추출 결과의 업무 일정표 행. 없으면 키가 없다.</li>
 *   <li>검사: 기존 PlanResponseReader(형식·식별·참조·시간·묶음)를 그대로 쓴다. 계약 위반은 저장하지 않고 FAILED다.</li>
 *   <li>결과: 063 공개 모양(PlanPublicResult) {status, questions, plan}으로 바꿔 ai_job.result에 저장한다.
 *       plan은 028(PUT draft-plan) 본문의 그룹·구역·공연·업무·안내 모양이라 화면이 편집 후 그대로 저장할 수 있다.
 *       내부 협업 기록(coordination)·가정·묶음 요약은 공개하지 않는다.
 *       결과6의 평면도는 READY일 때만 venueDraft(V9VenueDraft)로 싣는다. 구역 이름은 같은 후보 zones의 id/tempId로,
 *       사각형(x, y, width, height)은 네 꼭짓점으로 바꾼다(V9-D54). 결과5·평면도 없음이면 키가 없다.</li>
 *   <li>반영: 없다. READY도 총괄이 화면에서 확인하고 028로 저장하기 전에는 DRAFT에 적용하지 않는다(AI07).
 *       계획이 바뀌었으면 공통 처리가 STALE_CONTEXT로 끝낸다. 다만 결과를 기록하기 직전(apply)에, AI가 비워 둔
 *       기존 그룹 안내·행사 안내를 지금 DRAFT 값으로 채운다. 028은 안내를 요청 값으로 덮어쓰므로 그대로 두면
 *       후보를 저장하는 순간 총괄이 써 둔 안내가 지워진다(#280 리뷰).</li>
 * </ul>
 */
@Component
public class GeneratePlanKind implements AiJobKind {
    public static final String KIND = PlanContract.KIND;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final PlanResponseReader reader;
    private final ExtractionProperties documents;

    public GeneratePlanKind(JdbcTemplate jdbc, Validator validator, ExtractionProperties documents) {
        this.jdbc = jdbc;
        this.reader = new PlanResponseReader(validator);
        this.documents = documents;
    }

    @Override public String kind() { return KIND; }
    @Override public String path() { return "/internal/generate-plan"; }
    @Override public String resource() { return "GMS"; }
    @Override public boolean external() { return true; }
    /** 같은 행사의 계획 생성은 진행 중 1개(V9-D43) */
    @Override public ActiveLimit activeLimit() { return ActiveLimit.EVENT; }

    /** 접수 때 저장할 요청4 payload. 호출 전에 기본정보 완결성을 통과했으므로 필수 값은 비어 있지 않다. */
    public Map<String, Object> input(UUID eventId) {
        Map<String, Object> event = jdbc.queryForObject("""
                SELECT name, description, date, prep_start_at, open_at, close_at, teardown_end_at, location_name, address,
                       total_capacity, expected_visitors, peak_visitors, desired_reserve_count, confirmed_input::text
                  FROM event WHERE id = ?""", (r, n) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", r.getString("name"));
            e.put("description", r.getString("description") == null ? "" : r.getString("description"));
            e.put("date", r.getObject("date", LocalDate.class).toString());
            e.put("prepStartAt", time(r.getObject("prep_start_at", OffsetDateTime.class)));
            e.put("openAt", time(r.getObject("open_at", OffsetDateTime.class)));
            e.put("closeAt", time(r.getObject("close_at", OffsetDateTime.class)));
            e.put("teardownEndAt", time(r.getObject("teardown_end_at", OffsetDateTime.class)));
            e.put("locationName", r.getString("location_name"));
            e.put("address", r.getString("address"));
            e.put("totalCapacity", r.getInt("total_capacity"));
            // signed64 그대로 보낸다(BIGINT). int로 줄이면 21억을 넘는 값이 깨진다.
            e.put("expectedVisitors", r.getObject("expected_visitors", Long.class));
            e.put("peakVisitors", r.getObject("peak_visitors", Long.class));
            e.put("desiredReserveCount", r.getInt("desired_reserve_count"));
            String confirmed = r.getString(14);
            e.put("confirmedInput", confirmed == null ? Map.of() : MAPPER.readTree(confirmed));
            return e;
        }, eventId);
        List<Map<String, Object>> zones = jdbc.query("""
                SELECT id, name, description FROM zone WHERE event_id = ? ORDER BY name, id LIMIT ?""", (r, n) -> {
            Map<String, Object> z = new LinkedHashMap<>();
            z.put("id", r.getObject("id", UUID.class).toString());
            z.put("name", r.getString("name"));
            z.put("description", r.getString("description"));
            return z;
        }, eventId, PlanContract.MAX_ZONES);
        List<Map<String, Object>> programs = jdbc.query("""
                SELECT id, zone_id, name, start_at, end_at FROM program WHERE event_id = ? ORDER BY start_at, id LIMIT ?""",
                (r, n) -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("id", r.getObject("id", UUID.class).toString());
                    UUID zone = r.getObject("zone_id", UUID.class);
                    p.put("zoneId", zone == null ? null : zone.toString());
                    p.put("name", r.getString("name"));
                    p.put("startAt", time(r.getObject("start_at", OffsetDateTime.class)));
                    p.put("endAt", time(r.getObject("end_at", OffsetDateTime.class)));
                    return p;
                }, eventId, PlanContract.MAX_PROGRAMS);
        List<Map<String, Object>> groups = jdbc.query("""
                SELECT id, name FROM work_group WHERE event_id = ? AND active ORDER BY name, id LIMIT ?""", (r, n) -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", r.getObject("id", UUID.class).toString());
            g.put("name", r.getString("name"));
            return g;
        }, eventId, PlanContract.MAX_WORK_GROUPS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("zones", zones);
        payload.put("programs", programs);
        payload.put("workGroups", groups);
        // 평면도가 없으면 키를 넣지 않는다. AI는 키 없음·null 모두 결과5로 답하고(!320), floorPlan을 모르는 이전 AI 서버도
        // 평면도 없는 요청은 그대로 받는다(배포 순서가 바뀌어도 평면도 없는 행사의 계획 생성은 깨지지 않는다).
        Map<String, Object> floorPlan = floorPlan(eventId);
        if (floorPlan != null) payload.put("floorPlan", floorPlan);
        // 업무 일정표 행(V9-D66)도 없으면 키를 넣지 않는다. 일정표를 모르는 이전 AI 서버도 일정표 없는 요청은 그대로 받는다.
        JsonNode schedule = scheduleItems(eventId, LocalDate.parse((String) event.get("date")));
        if (schedule != null) payload.put("scheduleItems", schedule);
        return payload;
    }

    /**
     * 업무 일정표 행(V9-D66). 가장 최근에 끝난(SUCCEEDED·PARTIAL) 문서 추출 결과3의 scheduleItems를 싣는다
     * (ExtractEventInputKind가 요청4와 같은 모양으로 검사해 저장했다). AI는 이 값으로 문서를 내려받지 않으므로 원본 만료와
     * 상관없다. 없으면 null이다.
     * 시각은 원문 표기(timeText "HH:MM~HH:MM")와 지금 공연 날짜(Asia/Seoul)로 다시 만든다. 문서에 날짜가 없어 추출 때
     * 시각을 정하지 못했거나 총괄이 날짜를 고친 뒤에도 원문 시각대로 업무가 생긴다(#410 리뷰). 표기가 그 모양이 아니면 그대로 둔다.
     */
    JsonNode scheduleItems(UUID eventId, LocalDate date) {
        String latest = latestExtraction(eventId);
        if (latest == null) return null;
        JsonNode items = MAPPER.readTree(latest).path("scheduleItems");
        if (!items.isArray() || items.isEmpty()) return null;
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode item : items) {
            ObjectNode copy = (ObjectNode) item.deepCopy();
            Matcher span = SPAN.matcher(item.path("timeText").asString(""));
            if (span.matches()) {
                String start = at(date, span.group(1), span.group(2));
                String end = at(date, span.group(3), span.group(4));
                if (start != null && end != null) {
                    copy.put("startAt", start);
                    copy.put("endAt", end);
                }
            }
            out.add(copy);
        }
        return out;
    }

    private static final Pattern SPAN = Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})\\s*[~\\-–]\\s*(\\d{1,2}):(\\d{2})\\s*$");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private static String at(LocalDate date, String hour, String minute) {
        int h = Integer.parseInt(hour), m = Integer.parseInt(minute);
        if (h > 23 || m > 59) return null;
        return ZonedDateTime.of(date, LocalTime.of(h, m), SEOUL).format(OFFSET);
    }

    private String latestExtraction(UUID eventId) {
        List<String> latest = jdbc.query("""
                SELECT result::text FROM ai_job
                 WHERE event_id = ? AND kind = ? AND state IN ('SUCCEEDED', 'PARTIAL') AND result IS NOT NULL
                 ORDER BY finished_at DESC NULLS LAST, created_at DESC LIMIT 1""",
                (r, n) -> r.getString(1), eventId, ExtractEventInputKind.KIND);
        return latest.isEmpty() ? null : latest.get(0);
    }

    /**
     * 평면도 페이지(V9-D54). 가장 최근에 끝난(SUCCEEDED·PARTIAL) 문서 추출의 floorPlanPages를 순서대로 보고, 같은 행사 PDF이고
     * 원본이 남아 있으며 만료 전이고 쪽수 안인 첫 후보를 고른다. MD·TXT는 쪽수가 없어 고르지 않는다(AI는 PDF·그림만 받는다).
     * 문서 주소 설정이 없거나 후보가 없으면 null이다.
     */
    Map<String, Object> floorPlan(UUID eventId) {
        if (!documents.configured()) return null;
        String latest = latestExtraction(eventId);
        if (latest == null) return null;
        for (JsonNode page : MAPPER.readTree(latest).path("floorPlanPages")) {
            UUID documentId = UUID.fromString(page.get("documentId").asString());
            int pageNumber = page.get("pageNumber").asInt();
            List<Map<String, Object>> found = jdbc.query("""
                    SELECT mime, sha256 FROM intake_document
                     WHERE id = ? AND event_id = ? AND storage_key IS NOT NULL AND expires_at > now()
                       AND mime = 'application/pdf' AND page_count IS NOT NULL AND page_count >= ?""", (r, n) -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("documentId", documentId.toString());
                f.put("pageNumber", pageNumber);
                f.put("mime", r.getString("mime"));
                f.put("sha256", r.getString("sha256"));
                f.put("url", documents.documentUrl(documentId));
                return f;
            }, documentId, eventId, pageNumber);
            if (!found.isEmpty()) return found.get(0);
        }
        return null;
    }

    @Override
    public AiJobOutcome read(AiJobEnvelope request, AiResponse response) {
        PlanRequest planRequest = new PlanRequest(request.jobId(), request.attempt(), request.requestSchemaVersion(),
                request.kind(), request.inputHash(), request.contextRevision(), request.deadlineAt(),
                AiContractJson.mapper().treeToValue(request.payload(), PlanRequest.Payload.class));
        PlanOutcome outcome = reader.read(planRequest, response.body());
        return switch (outcome.branch()) {
            case READY, NEEDS_INFO -> AiJobOutcome.succeeded(outcome.success().resultSchemaVersion(),
                    outcome.success().modelVersion(), MAPPER.writeValueAsString(
                            publicResult(outcome.success().result(), planRequest.payload().floorPlan(),
                                    planRequest.payload().event())));
            case FAILED -> {
                PlanFailure.ErrorBody e = outcome.failure().error();
                AiJobError error = new AiJobError(e.code().name(), e.message(), e.retryable(), e.details());
                yield outcome.jobState() == AiJobState.STALE_CONTEXT ? AiJobOutcome.staleContext(error)
                        : AiJobOutcome.failed(error);
            }
            case INVALID -> AiJobOutcome.failed(AiJobError.invalidResult("계획 응답이 계약과 다릅니다: "
                    + outcome.issues().stream().limit(5).map(ContractIssue::path).collect(Collectors.joining(", "))));
        };
    }

    /**
     * READY 후보의 비어 있는 안내를 지금 DRAFT 안내로 채운다. AI 입력에 기존 안내를 넣지 않아 AI 결과에는 대부분 안내가 없다.
     * 기존 그룹(id)의 안내가 비어 있으면 그 그룹의 현재 안내를, 행사 안내가 없으면 현재 행사 안내를 넣는다.
     * AI가 낸 안내는 그대로 둔다. 공통 처리가 행사 revision을 확인한 뒤 같은 트랜잭션에서 부르므로 입력 때와 같은 DRAFT다.
     */
    @Override
    public AiJobOutcome apply(AiJob job, AiJobOutcome outcome) {
        ObjectNode result = (ObjectNode) MAPPER.readTree(outcome.resultJson());
        if (!result.path("plan").isObject()) return outcome;
        ObjectNode plan = (ObjectNode) result.get("plan");
        Map<String, String> groupNotes = new HashMap<>();
        jdbc.query("SELECT id::text, instructions FROM work_group WHERE event_id = ?",
                (RowCallbackHandler) r -> groupNotes.put(r.getString(1), r.getString(2)), job.getEventId());
        for (JsonNode group : plan.path("workGroups")) {
            if (!group.has("id") || !group.path("instructions").asString("").isEmpty()) continue;
            String current = groupNotes.get(group.get("id").asString());
            if (current != null && !current.isEmpty()) ((ObjectNode) group).put("instructions", current);
        }
        ArrayNode instructions = (ArrayNode) plan.get("instructions");
        if (instructions.isEmpty()) {
            String current = jdbc.queryForObject("SELECT instructions FROM event WHERE id = ?", String.class,
                    job.getEventId());
            if (current != null && !current.isEmpty()) {
                ObjectNode node = instructions.addObject();
                node.put("scope", "EVENT");
                node.putNull("workGroupId");
                node.put("body", current);
            }
        }
        return outcome.withResultJson(MAPPER.writeValueAsString(result));
    }

    /**
     * 063 공개 결과(PlanPublicResult). READY는 plan과 빈 questions, NEEDS_INFO는 questions와 plan=null이다.
     * WORK_GROUP 안내는 그 그룹의 instructions로 옮기고 EVENT 안내만 상위 instructions에 둔다.
     * AI 계약이 같은 대상 안내를 하나로 막으므로(PlanResultChecks DUPLICATE) 그룹마다·행사에 안내는 많아야 하나다.
     */
    static ObjectNode publicResult(PlanSuccess.Result r) {
        return publicResult(r, null, null);
    }

    static ObjectNode publicResult(PlanSuccess.Result r, PlanRequest.FloorPlan floorPlan) {
        return publicResult(r, floorPlan, null);
    }

    /**
     * event가 있으면(실제 읽기) 카메라·장비 연결 업무 규칙을 적용하고, 공개 때 칸을 만들 카메라·장비 목록을 deviceDraft로 남긴다
     * (#395, LinkTaskRule). deviceDraft는 저장 결과에만 있고 063 응답(AiJobRead.PlanResult)에는 나가지 않는다.
     */
    static ObjectNode publicResult(PlanSuccess.Result r, PlanRequest.FloorPlan floorPlan, PlanRequest.Event event) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", r.status().name());
        ArrayNode questions = out.putArray("questions");
        for (PlanSuccess.Question q : r.questions()) {
            ObjectNode node = questions.addObject();
            node.put("key", q.key());
            node.put("field", q.field());
            node.put("question", q.question());
        }
        if (r.status() == PlanSuccess.Status.NEEDS_INFO) {
            out.putNull("plan");
            return out;
        }
        ObjectNode plan = out.putObject("plan");
        Map<String, String> groupNotes = new LinkedHashMap<>();
        String eventNote = null;
        for (PlanSuccess.Instruction i : r.instructions()) {
            if (i.scope() == PlanSuccess.InstructionScope.EVENT) eventNote = i.body();
            else groupNotes.put(i.targetId(), i.body());
        }
        ArrayNode groups = plan.putArray("workGroups");
        for (PlanSuccess.WorkGroup g : r.workGroups()) {
            ObjectNode node = groups.addObject();
            ref(node, g.id(), g.tempId());
            node.put("name", g.name());
            node.put("instructions", groupNotes.getOrDefault(g.ref(), ""));
        }
        ArrayNode zones = plan.putArray("zones");
        for (PlanSuccess.Zone z : r.zones()) {
            ObjectNode node = zones.addObject();
            ref(node, z.id(), z.tempId());
            node.put("name", z.name());
            node.put("description", z.description());
        }
        ArrayNode programs = plan.putArray("programs");
        for (PlanSuccess.Program p : r.programs()) {
            ObjectNode node = programs.addObject();
            ref(node, p.id(), p.tempId());
            node.put("name", p.name());
            node.put("zoneId", p.zoneId());
            node.put("startAt", p.startAt().toString());
            node.put("endAt", p.endAt().toString());
        }
        ArrayNode tasks = plan.putArray("tasks");
        for (PlanSuccess.Task t : r.tasks()) {
            ObjectNode node = tasks.addObject();
            node.put("tempId", t.tempId());
            node.put("workGroupId", t.workGroupId());
            node.put("bundleKey", t.bundleKey());
            node.put("zoneId", t.zoneId());
            node.put("programId", t.programId());
            node.put("title", t.title());
            node.put("startAt", t.startAt().toString());
            node.put("endAt", t.endAt().toString());
            ArrayNode steps = node.putArray("steps");
            t.steps().forEach(steps::add);
            ArrayNode checklist = node.putArray("checklist");
            for (PlanSuccess.ChecklistItem c : t.checklist()) {
                ObjectNode item = checklist.addObject();
                item.put("key", c.key());
                item.put("text", c.text());
                item.put("required", c.required());
            }
        }
        ArrayNode instructions = plan.putArray("instructions");
        if (eventNote != null) {
            ObjectNode node = instructions.addObject();
            node.put("scope", "EVENT");
            node.putNull("workGroupId");
            node.put("body", eventNote);
        }
        ObjectNode venue = venueDraft(r, floorPlan);
        if (venue != null) out.set("venueDraft", venue);
        if (event != null) {
            LinkTaskRule.add(plan, r, event);
            ObjectNode devices = LinkTaskRule.deviceDraft(r);
            if (devices != null) out.set(LinkTaskRule.DEVICE_DRAFT, devices);
        }
        return out;
    }

    /**
     * 결과6 평면도를 V9VenueDraft로 바꾼다(V9-D54). 구역 이름 → 같은 후보 zones의 id/tempId, 사각형 → 시계 방향 네 꼭짓점.
     * 읽은 구역이 없거나 결과5면 null이다. 검사(PlanResultChecks.venue)를 통과한 결과만 오므로 이름은 모두 후보에 있다.
     */
    static ObjectNode venueDraft(PlanSuccess.Result r, PlanRequest.FloorPlan floorPlan) {
        PlanSuccess.Layout layout = r.layout();
        if (layout == null || layout.zones().isEmpty() || floorPlan == null) return null;
        Map<String, String> refs = new HashMap<>();
        for (PlanSuccess.Zone z : r.zones()) refs.put(UnicodeText.nameKey(z.name()), z.ref());
        ObjectNode venue = MAPPER.createObjectNode();
        ObjectNode out = venue.putObject("layout");
        out.put("coordinateSystem", "normalized-image");
        out.put("width", layout.width().doubleValue());
        out.put("height", layout.height().doubleValue());
        ArrayNode polygons = out.putArray("zonePolygons");
        for (PlanSuccess.LayoutZone z : layout.zones()) {
            ObjectNode polygon = polygons.addObject();
            polygon.put("zoneId", refs.get(UnicodeText.nameKey(z.name())));
            double left = z.left(), top = z.top();
            double right = Math.min(1, z.right()), bottom = Math.min(1, z.bottom());
            ArrayNode points = polygon.putArray("points");
            points.addObject().put("x", left).put("y", top);
            points.addObject().put("x", right).put("y", top);
            points.addObject().put("x", right).put("y", bottom);
            points.addObject().put("x", left).put("y", bottom);
        }
        ObjectNode source = out.putObject("source");
        source.put("sourceKind", "AUTOMATIC_MEASUREMENT");
        source.put("sourceRef", "document:" + layout.source().documentId() + "#page=" + layout.source().pageNumber());
        source.putNull("observedAt");
        venue.putArray("profiles");
        return venue;
    }

    /** 기존 항목은 id, 새 항목은 tempId 하나만 넣는다(028 DraftGroup·DraftZone·DraftProgram). */
    private static void ref(ObjectNode node, String id, String tempId) {
        if (id != null) node.put("id", id);
        else node.put("tempId", tempId);
    }

    private static String time(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }
}

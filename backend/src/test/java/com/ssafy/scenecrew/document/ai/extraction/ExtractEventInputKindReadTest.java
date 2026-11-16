package com.ssafy.scenecrew.document.ai.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.domain.AiJobState;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;

/** 결과3·실패 응답 해석(#236). DB 없이 ExtractEventInputKind.read만 확인한다. */
class ExtractEventInputKindReadTest {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final UUID JOB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID DOC_A = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID DOC_B = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final String HASH = "a".repeat(64);

    private final ExtractEventInputKind kind = new ExtractEventInputKind();
    private final AiJobEnvelope request = new AiJobEnvelope(JOB, 1, "4", ExtractEventInputKind.KIND, HASH, 7,
            Instant.parse("2026-11-10T12:10:00Z"), null,
            MAPPER.readTree("{\"documents\":[{\"documentId\":\"" + DOC_A + "\"},{\"documentId\":\"" + DOC_B + "\"}]}"));

    @Test
    void completeResultIsSucceededAndKeepsOnlyContractFields() {
        ObjectNode body = success("COMPLETE");
        ((ObjectNode) body.get("result")).put("debug", "내부 정보");
        AiJobOutcome outcome = kind.read(request, response(body));

        assertThat(outcome.state()).isEqualTo(AiJobState.SUCCEEDED);
        assertThat(outcome.resultSchemaVersion()).isEqualTo("3");
        JsonNode result = MAPPER.readTree(outcome.resultJson());
        assertThat(result.propertyNames()).containsExactlyInAnyOrder("fields", "missingRequired", "conflictingValues",
                "unreadableFiles", "resultStatus");
        assertThat(result.get("fields").get("locationName").asString()).isEqualTo("합성 시연장");
        assertThat(result.get("missingRequired").get(0).asString()).isEqualTo("totalCapacity");
        assertThat(result.get("conflictingValues").get(0).get("values").size()).isEqualTo(2);
    }

    @Test
    void partialResultIsPartialWithUnreadableFile() {
        ObjectNode body = success("PARTIAL");
        ArrayNode unreadable = ((ObjectNode) body.get("result")).putArray("unreadableFiles");
        unreadable.addObject().put("documentId", DOC_B.toString()).put("code", "OCR_FAILED");
        AiJobOutcome outcome = kind.read(request, response(body));

        assertThat(outcome.state()).isEqualTo(AiJobState.PARTIAL);
        assertThat(MAPPER.readTree(outcome.resultJson()).get("unreadableFiles").get(0).get("code").asString())
                .isEqualTo("OCR_FAILED");
    }

    @Test
    void floorPlanPagesAreKeptInOrderAndCheckedAgainstRequestedDocuments() {
        AiJobEnvelope withPages = new AiJobEnvelope(JOB, 1, "4", ExtractEventInputKind.KIND, HASH, 7,
                Instant.parse("2026-11-10T12:10:00Z"), null, MAPPER.readTree("{\"documents\":[{\"documentId\":\"" + DOC_A
                        + "\",\"mime\":\"application/pdf\",\"pageCount\":3},{\"documentId\":\"" + DOC_B
                        + "\",\"mime\":\"text/markdown\",\"pageCount\":null}]}"));
        ObjectNode body = success("COMPLETE");
        ArrayNode pages = ((ObjectNode) body.get("result")).putArray("floorPlanPages");
        pages.addObject().put("documentId", DOC_A.toString()).put("pageNumber", 3);
        pages.addObject().put("documentId", DOC_A.toString()).put("pageNumber", 1);
        JsonNode kept = MAPPER.readTree(kind.read(withPages, response(body)).resultJson()).get("floorPlanPages");
        assertThat(kept.size()).isEqualTo(2);
        assertThat(kept.get(0).get("pageNumber").asInt()).isEqualTo(3);
        assertThat(kept.get(1).get("pageNumber").asInt()).isEqualTo(1);

        String[] bad = {
            "[{\"documentId\":\"" + DOC_A + "\",\"pageNumber\":4}]",                 // 쪽수 밖
            "[{\"documentId\":\"" + DOC_A + "\",\"pageNumber\":0}]",                 // 1 미만
            "[{\"documentId\":\"" + UUID.randomUUID() + "\",\"pageNumber\":1}]",     // 요청에 없던 문서
            "[{\"documentId\":\"" + DOC_B + "\",\"pageNumber\":1}]",                 // MD는 평면도 후보가 아니다
            "[{\"documentId\":\"" + DOC_A + "\",\"pageNumber\":1},{\"documentId\":\"" + DOC_A + "\",\"pageNumber\":1}]",
            "[{\"documentId\":\"" + DOC_A + "\",\"pageNumber\":\"1\"}]",
            "[{\"documentId\":\"" + DOC_A + "\",\"pageNumber\":1,\"png\":\"x\"}]",
            "{}"};
        for (String value : bad) {
            ObjectNode invalid = success("COMPLETE");
            ((ObjectNode) invalid.get("result")).set("floorPlanPages", MAPPER.readTree(value));
            assertInvalid(kind.read(withPages, response(invalid)));
        }
    }

    /** V9-D66: 공연 순서·직무 그룹·일정표 행은 형식이 맞으면 저장하고, 틀리면 그 필드만 버린다(결과는 그대로 성공) */
    @Test
    void structuredFieldsAreKeptOrDroppedOnTheirOwn() {
        ObjectNode body = success("COMPLETE");
        ObjectNode result = (ObjectNode) body.get("result");
        result.putArray("programs").addObject().put("title", "새벽을 여는 노래")
                .put("startAt", "2026-11-13T12:20:00+09:00").put("endAt", "2026-11-13T12:25:00+09:00");
        result.putArray("workGroups").addObject().put("name", "안전 통제");
        ObjectNode item = result.putArray("scheduleItems").addObject();
        item.put("key", "d1p7r1").put("title", "A구역 동선 점검").put("startAt", "2026-11-13T11:30:00+09:00")
                .put("endAt", "2026-11-13T11:50:00+09:00").put("timeText", "11:30~11:50").put("workGroupName", "안전 통제")
                .put("zoneName", "A구역").put("bundle", "안전 A");
        item.putObject("source").put("documentId", DOC_A.toString()).put("pageNumber", 7);
        item.putArray("steps").add("동선 확인").add("통제선 설치");
        item.putArray("checklist").add("통제선 확인");
        ObjectNode unclear = ((ArrayNode) result.get("scheduleItems")).addObject();
        unclear.put("key", "d1p7r2").put("title", "수신기 예비 점검").putNull("startAt").putNull("endAt")
                .put("timeText", "미정").putNull("workGroupName").putNull("zoneName").putNull("bundle");
        unclear.putObject("source").put("documentId", DOC_A.toString()).put("pageNumber", 7);

        AiJobOutcome outcome = kind.read(request, response(body));
        assertThat(outcome.state()).isEqualTo(AiJobState.SUCCEEDED);
        JsonNode kept = MAPPER.readTree(outcome.resultJson());
        assertThat(kept.get("programs").get(0).get("startAt").asString()).isEqualTo("2026-11-13T12:20:00+09:00");
        assertThat(kept.get("workGroups").get(0).get("name").asString()).isEqualTo("안전 통제");
        assertThat(kept.get("scheduleItems").size()).isEqualTo(2);
        assertThat(kept.get("scheduleItems").get(0).get("startAt").asString()).isEqualTo("2026-11-13T11:30:00+09:00");
        assertThat(kept.get("scheduleItems").get(1).get("startAt").isNull()).isTrue();
        assertThat(kept.get("scheduleItems").get(0).get("steps").size()).isEqualTo(2);
        assertThat(kept.get("scheduleItems").get(1).has("steps")).isFalse();
        // 063 공개 결과에는 scheduleItems가 없다
        JsonNode exposed = MAPPER.valueToTree(MAPPER.treeToValue(kept, com.ssafy.scenecrew.aijob.read.AiJobRead.ExtractionResult.class));
        assertThat(exposed.has("scheduleItems")).isFalse();
        assertThat(exposed.has("programs")).isTrue();
        assertThat(exposed.has("workGroups")).isTrue();

        String[][] bad = {
            {"programs", "[{\"title\":\"곡\",\"startAt\":\"2026-11-13T12:25:00+09:00\",\"endAt\":\"2026-11-13T12:20:00+09:00\"}]"},
            {"programs", "[{\"title\":\"" + "가".repeat(21) + "\",\"startAt\":\"2026-11-13T12:20:00+09:00\",\"endAt\":\"2026-11-13T12:25:00+09:00\"}]"},
            {"workGroups", "[{\"name\":\"안전\"},{\"name\":\"안전\"}]"},
            {"scheduleItems", "[{\"key\":\"bad key\",\"title\":\"x\",\"startAt\":null,\"endAt\":null,\"source\":{\"documentId\":\"" + DOC_A + "\",\"pageNumber\":1}}]"},
            {"scheduleItems", "[{\"key\":\"k1\",\"title\":\"x\",\"startAt\":null,\"endAt\":null,\"source\":{\"documentId\":\"" + UUID.randomUUID() + "\",\"pageNumber\":1}}]"},
            {"scheduleItems", "[{\"key\":\"k1\",\"title\":\"x\",\"startAt\":\"11:30\",\"endAt\":null,\"source\":{\"documentId\":\"" + DOC_A + "\",\"pageNumber\":1}}]"},
            {"scheduleItems", "[{\"key\":\"k1\",\"title\":\"x\",\"startAt\":null,\"endAt\":null,\"steps\":[\"" + "가".repeat(121) + "\"],\"source\":{\"documentId\":\"" + DOC_A + "\",\"pageNumber\":1}}]"},
        };
        for (String[] change : bad) {
            ObjectNode invalid = success("COMPLETE");
            ((ObjectNode) invalid.get("result")).set(change[0], MAPPER.readTree(change[1]));
            AiJobOutcome dropped = kind.read(request, response(invalid));
            assertThat(dropped.state()).as(change[1]).isEqualTo(AiJobState.SUCCEEDED);
            assertThat(MAPPER.readTree(dropped.resultJson()).has(change[0])).as(change[1]).isFalse();
        }
    }

    @Test
    void contractViolationsAreInvalidResult() {
        // COMPLETE인데 못 읽은 파일이 있음
        ObjectNode complete = success("COMPLETE");
        ((ObjectNode) complete.get("result")).putArray("unreadableFiles").addObject()
                .put("documentId", DOC_A.toString()).put("code", "OCR_FAILED");
        assertInvalid(kind.read(request, response(complete)));
        // 요청에 없던 문서
        ObjectNode foreign = success("PARTIAL");
        ((ObjectNode) foreign.get("result")).putArray("unreadableFiles").addObject()
                .put("documentId", UUID.randomUUID().toString()).put("code", "OCR_FAILED");
        assertInvalid(kind.read(request, response(foreign)));
        // fields 값이 문자열이 아님
        ObjectNode numeric = success("COMPLETE");
        ((ObjectNode) numeric.get("result").get("fields")).put("totalCapacity", 10);
        assertInvalid(kind.read(request, response(numeric)));
        // 키 형식
        ObjectNode badKey = success("COMPLETE");
        ((ObjectNode) badKey.get("result").get("fields")).put("장소", "x");
        assertInvalid(kind.read(request, response(badKey)));
        // 충돌 값이 비어 있음
        ObjectNode emptyConflict = success("COMPLETE");
        ((ObjectNode) emptyConflict.get("result")).putArray("conflictingValues").addObject().put("field", "date").putArray("values");
        assertInvalid(kind.read(request, response(emptyConflict)));
        // 결과 버전·상태
        ObjectNode version = success("COMPLETE");
        version.put("resultSchemaVersion", "2");
        assertInvalid(kind.read(request, response(version)));
        ObjectNode status = success("DONE");
        assertInvalid(kind.read(request, response(status)));
        // 식별 필드
        ObjectNode other = success("COMPLETE");
        other.put("contextRevision", 8);
        assertInvalid(kind.read(request, response(other)));
    }

    @Test
    void aiErrorIsKeptForPublicConversionLater() {
        ObjectNode body = identity();
        body.putObject("error").put("code", "AI_UNAVAILABLE").put("message", "GMS 오류").put("retryable", true)
                .putObject("details").put("reason", "GMS_5XX");
        AiJobOutcome outcome = kind.read(request, response(body));

        assertThat(outcome.state()).isEqualTo(AiJobState.FAILED);
        assertThat(outcome.error().code()).isEqualTo("AI_UNAVAILABLE");
        assertThat(outcome.error().retryable()).isTrue();
        assertThat(outcome.error().details()).containsEntry("reason", "GMS_5XX");
    }

    private ObjectNode success(String status) {
        ObjectNode body = identity();
        body.put("resultSchemaVersion", "3");
        body.put("modelVersion", "extract-gms-1");
        ObjectNode result = body.putObject("result");
        ObjectNode fields = result.putObject("fields");
        fields.put("date", "2026-11-18");
        fields.put("locationName", "합성 시연장");
        result.putArray("missingRequired").add("totalCapacity");
        ObjectNode conflict = result.putArray("conflictingValues").addObject();
        conflict.put("field", "openAt");
        conflict.putArray("values").add("18:00").add("18:30");
        result.putArray("unreadableFiles");
        result.put("resultStatus", status);
        return body;
    }

    private ObjectNode identity() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("jobId", JOB.toString());
        body.put("attempt", 1);
        body.put("requestSchemaVersion", "4");
        body.put("inputHash", HASH);
        body.put("contextRevision", 7);
        return body;
    }

    private static AiResponse response(JsonNode body) {
        return new AiResponse(200, MAPPER.writeValueAsString(body));
    }

    private static void assertInvalid(AiJobOutcome outcome) {
        assertThat(outcome.state()).isEqualTo(AiJobState.FAILED);
        assertThat(outcome.error().code()).isEqualTo("AI_UNAVAILABLE");
        assertThat(outcome.error().details()).containsEntry("reason", "INVALID_RESULT");
    }
}

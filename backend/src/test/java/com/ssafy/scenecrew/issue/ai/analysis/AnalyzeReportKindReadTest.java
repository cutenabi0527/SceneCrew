package com.ssafy.scenecrew.issue.ai.analysis;

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

/** 보고 분석 결과3·4·실패 응답 해석(ai/report-api/contract.py). DB 없이 AnalyzeReportKind.read만 확인한다. */
class AnalyzeReportKindReadTest {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final UUID JOB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final String REPORT = "22222222-2222-4222-8222-222222222222";
    static final String STAGE = "33333333-3333-4333-8333-333333333333";
    static final String BOOTH = "44444444-4444-4444-8444-444444444444";
    static final String PROGRAM = "55555555-5555-4555-8555-555555555555";
    static final String GROUP = "66666666-6666-4666-8666-666666666666";
    static final String GROUP_ZONE = "88888888-8888-4888-8888-888888888888";
    static final String HASH = "a".repeat(64);

    private final AnalyzeReportKind kind = new AnalyzeReportKind(null);
    private final AiJobEnvelope request = new AiJobEnvelope(JOB, 1, "4", AnalyzeReportKind.KIND, HASH, 7,
            Instant.parse("2026-11-05T12:00:15Z"), null, MAPPER.readTree("""
                    {"reportId":"%s","normalizedText":"1곡 음향 부스 마이크 소리가 안 나요",
                     "allowedZones":[{"id":"%s","name":"스테이지"},{"id":"%s","name":"음향 부스"}],
                     "allowedPrograms":[{"id":"%s","name":"1곡","zoneId":"%s"}],
                     "allowedWorkGroups":[{"id":"%s","name":"음향","zoneIds":["%s"]}]}"""
                    .formatted(REPORT, STAGE, BOOTH, PROGRAM, STAGE, GROUP, GROUP_ZONE)));

    @Test
    void successKeepsOnlyLabelsAndCandidatesForApply() {
        AiJobOutcome outcome = kind.read(request, response(success()));
        assertThat(outcome.state()).isEqualTo(AiJobState.SUCCEEDED);
        assertThat(outcome.resultSchemaVersion()).isEqualTo("3");
        JsonNode result = MAPPER.readTree(outcome.resultJson());
        assertThat(result.propertyNames()).containsExactly("reportId", "category", "reportedState", "zoneCandidates");
        assertThat(result.get("category").asString()).isEqualTo("EQUIPMENT");
        assertThat(result.get("reportedState").isNull()).isTrue();
        assertThat(result.get("zoneCandidates").size()).isEqualTo(3);
    }

    @Test
    void candidatesMustComeFromTheRequestAllowListAndRevision() {
        ObjectNode notAllowed = success();
        ((ObjectNode) candidates(notAllowed).get(0)).put("zoneId", "77777777-7777-4777-8777-777777777777");
        assertInvalid(kind.read(request, response(notAllowed)));

        ObjectNode wrongProgramZone = success();
        ((ObjectNode) candidates(wrongProgramZone).get(1)).put("zoneId", BOOTH);
        assertInvalid(kind.read(request, response(wrongProgramZone)));

        ObjectNode oldRevision = success();
        ((ObjectNode) candidates(oldRevision).get(0)).put("contextRevision", 6);
        assertInvalid(kind.read(request, response(oldRevision)));

        ObjectNode unknownLabel = success();
        ((ObjectNode) unknownLabel.get("result")).put("category", "FIRE");
        assertInvalid(kind.read(request, response(unknownLabel)));
    }

    @Test
    void identityMismatchAndAiErrorsAreFailures() {
        ObjectNode otherJob = success();
        otherJob.put("jobId", "99999999-9999-4999-8999-999999999999");
        assertInvalid(kind.read(request, response(otherJob)));

        ObjectNode failure = identity();
        failure.set("error", MAPPER.readTree("{\"code\":\"AI_UNAVAILABLE\",\"message\":\"busy\",\"retryable\":true,\"details\":{}}"));
        AiJobOutcome outcome = kind.read(request, response(failure));
        assertThat(outcome.state()).isEqualTo(AiJobState.FAILED);
        assertThat(outcome.error().code()).isEqualTo("AI_UNAVAILABLE");
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    void resultFourAddsSituationSuggestionWithNullAsUnclassified() {
        AiJobOutcome outcome = kind.read(request, response(withSituation(
                "{\"primary\":\"EQUIPMENT_ANOMALY\",\"related\":[\"SCHEDULE_DELAY\"]}")));
        assertThat(outcome.state()).isEqualTo(AiJobState.SUCCEEDED);
        assertThat(outcome.resultSchemaVersion()).isEqualTo("4");
        JsonNode result = MAPPER.readTree(outcome.resultJson());
        assertThat(result.propertyNames()).containsExactly("reportId", "category", "reportedState", "zoneCandidates",
                "situation");
        assertThat(result.get("situation").get("primary").asString()).isEqualTo("EQUIPMENT_ANOMALY");
        assertThat(result.get("situation").get("related").get(0).asString()).isEqualTo("SCHEDULE_DELAY");

        // V9-D48: 미분류는 별도 코드가 아니라 primary=null이다.
        JsonNode unclassified = MAPPER.readTree(kind.read(request,
                response(withSituation("{\"primary\":null,\"related\":[]}"))).resultJson());
        assertThat(unclassified.get("situation").get("primary").isNull()).isTrue();
        assertThat(unclassified.get("situation").get("related").size()).isZero();
    }

    @Test
    void resultFourRejectsSituationOutsideTheContract() {
        for (String bad : new String[] {
                "{\"primary\":\"UNCLASSIFIED\",\"related\":[]}",
                "{\"primary\":\"STAFF_GAP\",\"related\":[\"STAFF_GAP\"]}",
                "{\"primary\":null,\"related\":[\"STAFF_GAP\",\"STAFF_GAP\"]}",
                "{\"primary\":null,\"related\":[\"FIRE\"]}",
                "{\"primary\":null,\"related\":[],\"score\":0.9}",
                "{\"primary\":null}",
                "null"})
            assertInvalid(kind.read(request, response(withSituation(bad))));
        ObjectNode missing = success();
        missing.put("resultSchemaVersion", "4");
        assertInvalid(kind.read(request, response(missing)));
        ObjectNode unknownVersion = success();
        unknownVersion.put("resultSchemaVersion", "5");
        assertInvalid(kind.read(request, response(unknownVersion)));
    }

    private static ObjectNode withSituation(String situation) {
        ObjectNode body = success();
        body.put("resultSchemaVersion", "4");
        ((ObjectNode) body.get("result")).set("situation", MAPPER.readTree(situation));
        return body;
    }

    private static void assertInvalid(AiJobOutcome outcome) {
        assertThat(outcome.state()).isEqualTo(AiJobState.FAILED);
        assertThat(outcome.error().details().get("reason")).isEqualTo("INVALID_RESULT");
    }

    private static ArrayNode candidates(ObjectNode body) {
        return (ArrayNode) body.get("result").get("zoneCandidates");
    }

    private static ObjectNode identity() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("jobId", JOB.toString());
        body.put("attempt", 1);
        body.put("requestSchemaVersion", "4");
        body.put("inputHash", HASH);
        body.put("contextRevision", 7);
        return body;
    }

    private static ObjectNode success() {
        ObjectNode body = identity();
        body.put("resultSchemaVersion", "3");
        body.put("modelVersion", "report-v1");
        body.set("result", MAPPER.readTree("""
                {"category":"EQUIPMENT","reportedState":null,
                 "scores":{"category":{"label":"EQUIPMENT","score":0.9},"reportedState":{"label":"OCCURRED","score":0.4}},
                 "entities":[],
                 "zoneCandidates":[
                   {"zoneId":"%s","source":"ZONE","sourceRef":"%s","contextRevision":7},
                   {"zoneId":"%s","source":"PROGRAM","sourceRef":"%s","contextRevision":7},
                   {"zoneId":"%s","source":"ROLE","sourceRef":"%s","contextRevision":7}],
                 "analysisValidity":"CURRENT"}""".formatted(BOOTH, BOOTH, STAGE, PROGRAM, GROUP_ZONE, GROUP)));
        return body;
    }

    private static AiResponse response(JsonNode body) {
        return new AiResponse(200, MAPPER.writeValueAsString(body));
    }
}

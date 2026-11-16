package com.ssafy.scenecrew.change.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.domain.AiJobState;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;

/** #343 AI 대응안 결과6 해석. DB 없이 GenerateChangeKind.read만 확인한다(ai/change 결과 모양). */
class GenerateChangeKindReadTest {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final UUID JOB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final String ISSUE = "22222222-2222-4222-8222-222222222222", REPORT = "33333333-3333-4333-8333-333333333333",
            ZONE = "44444444-4444-4444-8444-444444444444", GROUP = "55555555-5555-4555-8555-555555555555",
            MEMBER = "66666666-6666-4666-8666-666666666666", TASK = "77777777-7777-4777-8777-777777777777";
    static final String HASH = "a".repeat(64);

    final GenerateChangeKind kind = new GenerateChangeKind(null, null);
    final AiJobEnvelope request = new AiJobEnvelope(JOB, 1, "4", GenerateChangeKind.KIND, HASH, 7,
            Instant.parse("2026-11-14T09:10:00Z"), null, MAPPER.readTree("""
                    {"issue":{"id":"%s","zoneId":"%s","reports":[{"id":"%s","body":"프로젝터 고장","reportedAt":"2026-11-14T08:58:00Z"}]},
                     "zones":[{"id":"%s","name":"무대"}],"programs":[],"workGroups":[{"id":"%s","name":"무대 운영"}],
                     "members":[{"id":"%s","crewNo":2}],"tasks":[{"id":"%s"}]}""".formatted(ISSUE, ZONE, REPORT, ZONE, GROUP, MEMBER, TASK)));

    ObjectNode success(Consumer<ObjectNode> change) {
        ObjectNode body = (ObjectNode) MAPPER.readTree("""
                {"jobId":"%s","attempt":1,"requestSchemaVersion":"4","inputHash":"%s","contextRevision":7,
                 "resultSchemaVersion":"6","modelVersion":"change-supervisor-gpt-4o",
                 "result":{"status":"READY",
                   "operations":[{"type":"ADD_TASK","payload":{"workGroupId":"%s","bundleKey":"fix","zoneId":"%s","programId":null,
                     "title":"장비 조치","startAt":"2026-11-14T09:05:00Z","endAt":"2026-11-14T09:25:00Z","steps":["고장 장비 확인"],
                     "checklist":[{"key":"checked","text":"점검 완료","required":true}],"tempId":"tmp-fix","assigneeId":"%s"}}],
                   "rationale":"프로젝터 조치","alternatives":[{"summary":"팀원 1에게 맡기기"}],"questions":[],
                   "coordination":{"graph":[{"taskId":"t1","parentTaskId":null,"role":"SUPERVISOR","status":"SUCCEEDED"}],
                     "redelegations":[],"resolvedConflicts":[{"refs":["%s","tmp-fix"],"resolution":"담당 변경"}],
                     "contributions":[{"role":"SUPERVISOR","summary":"일정·인력에게 맡김"}]},
                   "evidenceRefs":[{"kind":"REPORT","id":"%s","revision":null,"contentHash":null,"observedAt":null,"preconditions":[]},
                                   {"kind":"MEMBER","id":"%s","revision":null,"contentHash":null,"observedAt":null,"preconditions":[]}],
                   "simulationRefs":[],"situation":{"primaryType":"EQUIPMENT_ANOMALY"}}}"""
                .formatted(JOB, HASH, GROUP, ZONE, MEMBER, TASK, REPORT, MEMBER));
        change.accept((ObjectNode) body.get("result"));
        return body;
    }

    AiJobOutcome read(JsonNode body) { return kind.read(request, new AiResponse(200, body.toString())); }

    @Test void readyKeepsContractFieldsAndRecordsAndLeavesChangeSetForApply() {
        AiJobOutcome out = read(success(r -> { }));
        assertThat(out.state()).isEqualTo(AiJobState.SUCCEEDED);
        assertThat(out.resultSchemaVersion()).isEqualTo("6");
        JsonNode r = MAPPER.readTree(out.resultJson());
        assertThat(r.propertyNames()).containsExactlyInAnyOrder("changeSetId", "operations", "rationale", "questions",
                "evidenceRefs", "simulationRefs", "situation", "alternatives", "coordination");
        assertThat(r.get("changeSetId").isNull()).isTrue();
        assertThat(r.get("situation").isNull()).isTrue();          // AI가 보낸 상황은 쓰지 않고 apply에서 DB 값으로 채운다
        assertThat(r.get("operations").get(0).get("payload").get("tempId").asString()).isEqualTo("tmp-fix");
        assertThat(r.get("coordination").get("resolvedConflicts").get(0).get("refs").size()).isEqualTo(2);
        assertThat(r.get("evidenceRefs").size()).isEqualTo(2);
    }

    @Test void needsInfoHasOnlyQuestions() {
        AiJobOutcome out = read(success(r -> {
            r.put("status", "NEEDS_INFO").put("rationale", "");
            r.putArray("operations");
            r.putArray("questions").addObject().put("key", "zone").put("field", "issue.zoneId").put("question", "어느 구역인가요?");
        }));
        assertThat(out.state()).isEqualTo(AiJobState.SUCCEEDED);
        assertThat(MAPPER.readTree(out.resultJson()).get("questions").size()).isEqualTo(1);
    }

    @Test void contractViolationsAreNotStored() {
        List<Consumer<ObjectNode>> bad = List.of(
                r -> r.put("debug", "프롬프트"),                                                     // 계약 밖 필드
                r -> r.put("status", "DONE"),
                r -> r.putArray("operations"),                                                       // READY인데 연산 없음
                r -> ((ObjectNode) r.get("operations").get(0)).put("type", "RESERVE_EQUIPMENT"),     // 형식 위반
                r -> r.put("rationale", " "),
                r -> r.putArray("questions").addObject().put("key", "k").put("field", "f").put("question", "?"),  // READY에 질문
                r -> { r.put("status", "NEEDS_INFO"); r.putArray("questions").addObject().put("key", "k").put("field", "f").put("question", "?"); },
                r -> { ArrayNode a = r.putArray("alternatives"); for (int i = 0; i < 4; i++) a.addObject().put("summary", "대안"); },
                r -> ((ObjectNode) r.get("coordination")).put("prompt", "원문"),
                r -> ((ObjectNode) r.get("coordination").get("graph").get(0)).put("role", "PLANNING"),
                r -> ((ObjectNode) r.get("evidenceRefs").get(1)).put("id", UUID.randomUUID().toString()),   // 보낸 계획에 없는 팀원
                r -> ((ObjectNode) r.get("evidenceRefs").get(0)).put("kind", "TASK"),                      // 보고 id를 업무로
                r -> r.putArray("simulationRefs").addObject().put("simulationId", UUID.randomUUID().toString()));
        for (Consumer<ObjectNode> change : bad) {
            AiJobOutcome out = read(success(change));
            assertThat(out.state()).as(success(change).toString()).isEqualTo(AiJobState.FAILED);
            assertThat(out.error().details()).containsEntry("reason", "INVALID_RESULT");
        }
        ObjectNode otherJob = success(r -> { });
        otherJob.put("jobId", UUID.randomUUID().toString());
        assertThat(read(otherJob).state()).isEqualTo(AiJobState.FAILED);
        ObjectNode version = success(r -> { });
        version.put("resultSchemaVersion", "5");
        assertThat(read(version).state()).isEqualTo(AiJobState.FAILED);
    }

    @Test void aiErrorsKeepCodeAndRetryRule() {
        ObjectNode failure = (ObjectNode) MAPPER.readTree("""
                {"jobId":"%s","attempt":1,"requestSchemaVersion":"4","inputHash":"%s","contextRevision":7,
                 "error":{"code":"AI_REVIEW_FAILED","message":"검토 실패","retryable":false,"details":{"reason":"REVIEW_REJECTED"}}}"""
                .formatted(JOB, HASH));
        AiJobOutcome out = read(failure);
        assertThat(out.state()).isEqualTo(AiJobState.FAILED);
        assertThat(out.error().code()).isEqualTo("AI_REVIEW_FAILED");
        assertThat(out.error().details()).containsEntry("reason", "REVIEW_REJECTED");
    }

    @Test void taskStatusFollowsAiContract() {
        assertThat(GenerateChangeKind.taskStatus("WAITING")).isEqualTo("TODO");
        assertThat(GenerateChangeKind.taskStatus("CANCELED")).isEqualTo("CANCELLED");
        assertThat(GenerateChangeKind.taskStatus("IN_PROGRESS")).isEqualTo("IN_PROGRESS");
    }
}

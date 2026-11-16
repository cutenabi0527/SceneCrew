package com.ssafy.scenecrew.aijob.contract.plan;

import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.READER;
import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.modified;
import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.request;
import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.task;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.ssafy.scenecrew.aijob.contract.plan.PlanOutcome.Branch;

import tools.jackson.databind.node.ObjectNode;

/**
 * AI 결과를 엄격하게 읽는지 확인한다. 하나라도 통과하면 계약과 다른 값이 저장될 수 있다.
 * MR !34 검수에서 AI 쪽은 attempt=true를 통과시켰다.
 */
class PlanStrictJsonTest {

    private static Branch readReady(Consumer<ObjectNode> change) {
        return READER.read(request("request.json"), modified("success-ready.json", change)).branch();
    }

    @Test
    void booleanIsNotNumber() {
        assertThat(readReady(r -> r.put("attempt", true))).isEqualTo(Branch.INVALID);
    }

    @Test
    void numericStringIsNotNumber() {
        assertThat(readReady(r -> r.put("contextRevision", "12"))).isEqualTo(Branch.INVALID);
    }

    @Test
    void fractionIsNotInteger() {
        assertThat(readReady(r -> r.put("attempt", 1.5))).isEqualTo(Branch.INVALID);
    }

    @Test
    void numberIsNotString() {
        assertThat(readReady(r -> r.put("modelVersion", 5))).isEqualTo(Branch.INVALID);
    }

    @Test
    void numberIsNotEnum() {
        assertThat(readReady(r -> ((ObjectNode) r.get("result")).put("status", 0))).isEqualTo(Branch.INVALID);
    }

    @Test
    void numberIsNotTime() {
        // 원래 시각과 같은 epoch 숫자를 넣어, 시간 구간 검사가 아니라 읽기 단계에서 거절되는지 확인한다.
        long startAt = Instant.parse("2026-11-13T23:00:00Z").getEpochSecond();
        long endAt = Instant.parse("2026-11-14T00:30:00Z").getEpochSecond();
        PlanOutcome outcome = READER.read(request("request.json"), modified("success-ready.json", r -> {
            assertThat(task(r, 0).get("startAt").asString()).isEqualTo("2026-11-13T23:00:00Z");
            assertThat(task(r, 0).get("endAt").asString()).isEqualTo("2026-11-14T00:30:00Z");
            task(r, 0).put("startAt", startAt).put("endAt", endAt);
        }));

        assertThat(outcome.branch()).isEqualTo(Branch.INVALID);
        assertThat(outcome.issues()).extracting("code").containsOnly("SCHEMA");
    }

    @Test
    void timeNeedsOffset() {
        assertThat(readReady(r -> task(r, 0).put("startAt", "2026-11-13T23:00:00"))).isEqualTo(Branch.INVALID);
    }

    @Test
    void unknownFieldIsRejected() {
        assertThat(readReady(r -> r.put("rawPrompt", "..."))).isEqualTo(Branch.INVALID);
        assertThat(readReady(r -> task(r, 0).put("priority", 1))).isEqualTo(Branch.INVALID);
    }

    @Test
    void missingRequiredFieldIsRejected() {
        assertThat(readReady(r -> r.remove("inputHash"))).isEqualTo(Branch.INVALID);
    }

    @Test
    void assigneeMustStayNull() {
        assertThat(readReady(r -> task(r, 0).put("assigneeId", "55555555-5555-4555-8555-555555555555")))
                .isEqualTo(Branch.INVALID);
    }

    @Test
    void duplicateKeyIsRejected() {
        String json = "{\"attempt\":1,\"attempt\":2}";
        assertThat(READER.read(request("request.json"), json).branch()).isEqualTo(Branch.INVALID);
    }

    @Test
    void nonObjectIsRejected() {
        assertThat(READER.read(request("request.json"), "[]").branch()).isEqualTo(Branch.INVALID);
        assertThat(READER.read(request("request.json"), "not json").branch()).isEqualTo(Branch.INVALID);
    }
}

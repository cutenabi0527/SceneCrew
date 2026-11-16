package com.ssafy.scenecrew.aijob.contract.plan;

import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.READER;
import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.modified;
import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.request;
import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.task;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.ssafy.scenecrew.aijob.contract.AiContractJson;
import com.ssafy.scenecrew.aijob.contract.plan.PlanOutcome.Branch;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 형식은 맞지만 내용이 요청과 맞지 않는 AI 결과를 BE 재검사가 거르는지 확인한다. */
class PlanResultChecksTest {
    private static final String UNKNOWN_ZONE = "33333333-3333-4333-8333-333333333399";

    private static PlanOutcome readReady(Consumer<ObjectNode> change) {
        return READER.read(request("request.json"), modified("success-ready.json", change));
    }

    private static PlanOutcome readFailure(String file, Consumer<ObjectNode> change) {
        return READER.read(request("request.json"), modified(file, change));
    }

    private static void assertInvalid(PlanOutcome outcome, String code) {
        assertThat(outcome.branch()).isEqualTo(Branch.INVALID);
        assertThat(outcome.canApplyToDraft()).isFalse();
        assertThat(outcome.issues()).extracting("code").contains(code);
    }

    @Test
    void echoMustMatchRequest() {
        assertInvalid(readReady(r -> r.put("inputHash", "b".repeat(64))), "ECHO_MISMATCH");
        assertInvalid(readReady(r -> r.put("attempt", 2)), "ECHO_MISMATCH");
        assertInvalid(readReady(r -> r.put("contextRevision", 11)), "ECHO_MISMATCH");
    }

    @Test
    void existingIdMustComeFromRequest() {
        assertInvalid(readReady(r -> ((ObjectNode) r.get("result").get("zones").get(0)).put("id", UNKNOWN_ZONE)),
                "INVALID_REFERENCE");
    }

    @Test
    void taskMustReferToResultItems() {
        assertInvalid(readReady(r -> task(r, 0).put("workGroupId", "tmp-wg-unknown")), "INVALID_REFERENCE");
    }

    @Test
    void taskMustStayWithinEventWindow() {
        assertInvalid(readReady(r -> task(r, 0).put("startAt", "2026-11-13T20:00:00Z")), "TIME_OUT_OF_RANGE");
    }

    @Test
    void taskStartMustBeBeforeEnd() {
        assertInvalid(readReady(r -> task(r, 0).put("endAt", "2026-11-13T23:00:00Z")), "TIME_OUT_OF_RANGE");
    }

    @Test
    void bundleSummaryMustMatchTasks() {
        assertInvalid(readReady(r -> ((ObjectNode) r.get("result").get("bundles").get(0)).put("taskCount", 99)),
                "BUNDLE_MISMATCH");
    }

    @Test
    void zoneAndGroupNamesAreUnique() {
        assertInvalid(readReady(r -> ((ObjectNode) r.get("result").get("zones").get(1)).put("name", "메인홀")),
                "DUPLICATE");
    }

    @Test
    void itemNeedsExactlyOneOfIdOrTempId() {
        assertInvalid(readReady(r -> ((ObjectNode) r.get("result").get("workGroups").get(0)).putNull("tempId")),
                "SCHEMA");
    }

    @Test
    void readyMustNotCarryQuestions() {
        assertInvalid(readReady(r -> ((ArrayNode) r.get("result").get("questions")).addObject()
                .put("key", "q").put("field", "payload.zones").put("question", "구역은?")), "SCHEMA");
    }

    @Test
    void needsInfoMustNotCarryPartialPlan() {
        PlanOutcome outcome = READER.read(request("request.json"), modified("success-needs-info.json", r -> {
            ObjectNode ready = (ObjectNode) AiContractJson.mapper().readTree(PlanContractFixtures.json("success-ready.json"));
            ((ArrayNode) r.get("result").get("tasks")).add(task(ready, 0));
        }));
        assertInvalid(outcome, "SCHEMA");
    }

    @Test
    void retryableMustMatchCode() {
        assertInvalid(readFailure("failure-timeout.json", r -> ((ObjectNode) r.get("error")).put("retryable", true)),
                "SCHEMA");
    }

    @Test
    void reviewFailureNeedsKnownReason() {
        assertInvalid(readFailure("failure-review-budget.json",
                r -> ((ObjectNode) r.get("error").get("details")).put("reason", "SOMETHING")), "SCHEMA");
    }
}

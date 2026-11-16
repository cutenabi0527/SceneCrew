package com.ssafy.scenecrew.aijob.contract.plan;

import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.READER;
import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.json;
import static com.ssafy.scenecrew.aijob.contract.plan.PlanContractFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import com.ssafy.scenecrew.aijob.contract.AiContractJson;
import com.ssafy.scenecrew.aijob.contract.plan.PlanOutcome.Branch;
import com.ssafy.scenecrew.aijob.domain.AiJobState;

import tools.jackson.core.JacksonException;

/** 재영님 ai/plan 예시 13개를 BE가 같은 분기로 판정하는지 확인한다. */
class PlanExamplesTest {

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
            "request.json, success-ready.json, READY, SUCCEEDED, false",
            "request.json, success-needs-info.json, NEEDS_INFO, SUCCEEDED, false",
            "request-missing-zones.json, success-needs-info-precheck.json, NEEDS_INFO, SUCCEEDED, false",
            "request.json, failure-review-budget.json, FAILED, FAILED, false",
            "request.json, failure-timeout.json, FAILED, FAILED, false",
            "request.json, failure-context-changed.json, FAILED, STALE_CONTEXT, false",
            "request.json, failure-unavailable.json, FAILED, FAILED, true",
            "request-invalid-reserve.json, failure-invalid-request.json, FAILED, FAILED, false",
    })
    void examplesFollowAiBranches(String requestFile, String responseFile, Branch branch, AiJobState state,
            boolean retryable) {
        PlanOutcome outcome = READER.read(request(requestFile), json(responseFile));

        assertThat(outcome.issues()).isEmpty();
        assertThat(outcome.branch()).isEqualTo(branch);
        assertThat(outcome.jobState()).isEqualTo(state);
        assertThat(outcome.retryable()).isEqualTo(retryable);
        assertThat(outcome.canApplyToDraft()).isEqualTo(branch == Branch.READY);
    }

    @Test
    void readyResultKeepsPlanCandidates() {
        PlanOutcome outcome = READER.read(request("request.json"), json("success-ready.json"));

        PlanSuccess.Result result = outcome.success().result();
        assertThat(result.workGroups()).isNotEmpty();
        assertThat(result.tasks()).isNotEmpty().allSatisfy(t -> assertThat(t.assigneeId()).isNull());
    }

    @Test
    void needsInfoHasOnlyQuestions() {
        PlanOutcome outcome = READER.read(request("request.json"), json("success-needs-info.json"));

        PlanSuccess.Result result = outcome.success().result();
        assertThat(result.questions()).isNotEmpty();
        assertThat(result.tasks()).isEmpty();
    }

    @Test
    void malformedRequestCannotBeRead() {
        // 필수 필드(inputHash, deadlineAt)가 없는 요청. BE는 이런 요청을 만들 수 없다.
        assertThatThrownBy(() -> request("request-malformed.json")).isInstanceOf(JacksonException.class);
    }

    @Test
    void preAcceptanceFailureIsNotLinkedToOurJob() {
        // 식별 필드가 모두 null인 접수 전 실패는 어느 작업의 결과인지 알 수 없으므로 INVALID다.
        PlanOutcome outcome = READER.read(request("request.json"), json("failure-pre-acceptance.json"));

        assertThat(outcome.branch()).isEqualTo(Branch.INVALID);
        assertThat(outcome.issues()).extracting("code").containsOnly("ECHO_MISMATCH");
    }

    @Test
    void requestSerializesWithContractFieldNames() {
        PlanRequest req = request("request.json");
        String written = AiContractJson.mapper().writeValueAsString(req);

        assertThat(written).contains("\"jobId\":\"11111111-1111-4111-8111-111111111111\"",
                "\"contextRevision\":12", "\"deadlineAt\":\"2026-11-01T03:10:00Z\"", "\"date\":\"2026-11-14\"");
        assertThat(AiContractJson.mapper().readValue(written, PlanRequest.class)).isEqualTo(req);
    }
}

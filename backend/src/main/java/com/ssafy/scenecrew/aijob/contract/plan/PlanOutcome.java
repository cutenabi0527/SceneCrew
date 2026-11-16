package com.ssafy.scenecrew.aijob.contract.plan;

import java.util.List;

import com.ssafy.scenecrew.aijob.contract.ContractIssue;
import com.ssafy.scenecrew.aijob.domain.AiJobState;

/**
 * AI 응답을 요청과 대조한 결과.
 *
 * | 분기 | 뜻 | AiJob 상태 | DRAFT 반영 |
 * | READY | 계획 후보 | SUCCEEDED | 총괄이 편집·반영 가능 |
 * | NEEDS_INFO | 추가 질문만 | SUCCEEDED | 불가, 조건 보완 후 새 생성 |
 * | FAILED | AI가 보낸 실패 | 코드별(FAILED/STALE_CONTEXT) | 불가, 기존 초안 유지 |
 * | INVALID | 계약 위반 응답 | FAILED | 불가, 저장하지 않음 |
 */
public record PlanOutcome(Branch branch, PlanSuccess success, PlanFailure failure, List<ContractIssue> issues) {

    public enum Branch { READY, NEEDS_INFO, FAILED, INVALID }

    static PlanOutcome of(PlanSuccess success) {
        Branch branch = success.result().status() == PlanSuccess.Status.READY ? Branch.READY : Branch.NEEDS_INFO;
        return new PlanOutcome(branch, success, null, List.of());
    }

    static PlanOutcome of(PlanFailure failure) {
        return new PlanOutcome(Branch.FAILED, null, failure, List.of());
    }

    static PlanOutcome invalid(List<ContractIssue> issues) {
        return new PlanOutcome(Branch.INVALID, null, null, List.copyOf(issues));
    }

    public AiJobState jobState() {
        return switch (branch) {
            case READY, NEEDS_INFO -> AiJobState.SUCCEEDED;
            case FAILED -> failure.error().code().jobState();
            case INVALID -> AiJobState.FAILED;
        };
    }

    /** AI_UNAVAILABLE 실패만 남은 마감 안에서 같은 jobId로 다시 보낼 수 있다. */
    public boolean retryable() {
        return branch == Branch.FAILED && failure.error().code().retryable();
    }

    public boolean canApplyToDraft() {
        return branch == Branch.READY;
    }
}

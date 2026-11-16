package com.ssafy.scenecrew.aijob.contract.plan;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.ssafy.scenecrew.aijob.contract.CodePoints;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * AI → BE 실패 응답. 식별 필드는 요청 그대로 돌려준다.
 * 요청을 해석하지 못한 접수 전 실패(INVALID_REQUEST)만 식별 필드가 null일 수 있다.
 */
public record PlanFailure(
        @JsonProperty(required = true) @Pattern(regexp = PlanContract.UUID) String jobId,
        @JsonProperty(required = true) @Min(1) Integer attempt,
        @JsonProperty(required = true) @Pattern(regexp = "^4$") String requestSchemaVersion,
        @JsonProperty(required = true) @Pattern(regexp = PlanContract.SHA256) String inputHash,
        @JsonProperty(required = true) @Min(0) Long contextRevision,
        @JsonProperty(required = true) @NotNull @Valid ErrorBody error) {

    public record ErrorBody(
            @JsonProperty(required = true) @NotNull PlanErrorCode code,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 200) String message,
            @JsonProperty(required = true) @NotNull Boolean retryable,
            Map<String, Object> details) {

        public ErrorBody {
            details = details == null ? Map.of() : details;
        }
    }
}

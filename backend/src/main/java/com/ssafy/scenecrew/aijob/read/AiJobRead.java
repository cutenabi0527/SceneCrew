package com.ssafy.scenecrew.aijob.read;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import tools.jackson.databind.JsonNode;

/**
 * SC-API-063 응답(계약 V9JobRead). 모든 종류가 같은 바깥 모양이고 result만 종류마다 다르다.
 * QUEUED·RUNNING은 result·error가 null, SUCCEEDED는 result만, FAILED는 error만 있다.
 *
 * @param attempt 공개 재시도 횟수. DecimalBigInt라 10진 문자열이다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"jobId", "kind", "state", "attempt", "result", "error"})
public record AiJobRead(UUID jobId, String kind, String state, String attempt, Object result, PublicError error) {

    /** 공개 실패. 내부 원인(details·message)은 내보내지 않는다(BE08 V7-D17). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"code", "retryable"})
    public record PublicError(String code, boolean retryable) { }

    /**
     * 계약 ExtractionPublicResult(#236). 값은 AI 결과를 ExtractEventInputKind가 검사해 남긴 그대로다.
     * fields는 키→문자열, conflictingValues는 [{field, values}], unreadableFiles는 [{documentId, code}]다.
     * floorPlanPages([{documentId, pageNumber}], #284·V9-D54)는 선택 필드라 AI가 주지 않았으면 키를 싣지 않는다.
     * programs([{title, startAt, endAt}])·workGroups([{name}])(V9-D66)도 선택 필드다. 저장 결과의 scheduleItems는
     * 계획 생성 요청에만 쓰므로 내보내지 않는다.
     */
    @JsonIgnoreProperties({"scheduleItems"})
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"fields", "missingRequired", "conflictingValues", "unreadableFiles", "floorPlanPages",
            "programs", "workGroups", "resultStatus"})
    public record ExtractionResult(JsonNode fields, JsonNode missingRequired, JsonNode conflictingValues,
            JsonNode unreadableFiles, @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode floorPlanPages,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode programs,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode workGroups,
            String resultStatus) { }

    /**
     * 계획 생성(#280) 공개 결과 PlanPublicResult. READY는 plan(028 저장 모양)·빈 questions, NEEDS_INFO는 questions·plan=null.
     * venueDraft(V9VenueDraft, V9-D54·#391)는 결과6 READY에 평면도를 읽었을 때만 있고, 아니면 키를 싣지 않는다.
     * 저장 결과의 deviceDraft(공개 때 만들 카메라·장비 목록, #395)는 내보내지 않는다.
     */
    @JsonIgnoreProperties({"deviceDraft"})
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"status", "questions", "plan", "venueDraft"})
    public record PlanResult(String status, JsonNode questions, JsonNode plan,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode venueDraft) { }

    /**
     * AI 대응안(#343) 공개 결과 V9ChangeGenerationResult와 참고 기록(V9-D55). READY면 changeSetId가 저장된 AI 변경안이고,
     * NEEDS_INFO면 changeSetId=null·operations=[]·questions만 있다. situation은 이슈의 확인된 운영 상황 행 또는 null.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"changeSetId", "operations", "rationale", "questions", "evidenceRefs", "simulationRefs", "situation",
            "alternatives", "coordination"})
    public record ChangeResult(UUID changeSetId, JsonNode operations, String rationale, JsonNode questions,
            JsonNode evidenceRefs, JsonNode simulationRefs, JsonNode situation, JsonNode alternatives, JsonNode coordination) { }

    /** 계약 V9TranscriptionResult */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"audioId", "text", "durationSec", "segments", "pipeline", "fallbackReason"})
    public record TranscriptionResult(UUID audioId, String text, BigDecimal durationSec, JsonNode segments,
            String pipeline, String fallbackReason) { }
}

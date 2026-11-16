package com.ssafy.scenecrew.aijob.kind;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * BE → AI 공통 요청4. 성공·실패 응답은 jobId/attempt/requestSchemaVersion/inputHash/contextRevision을 그대로 돌려준다.
 * payload는 종류(kind)마다 다르며 접수할 때 ai_job.input에 저장한 값이다.
 * scope는 서버가 검증한 최소 범위(예: 전사의 eventId·uploaderMemberId)로, 종류가 정하지 않으면 보내지 않는다(AiJobKind.scope).
 */
public record AiJobEnvelope(
        UUID jobId,
        int attempt,
        String requestSchemaVersion,
        String kind,
        String inputHash,
        long contextRevision,
        Instant deadlineAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode scope,
        JsonNode payload) {
}

package com.ssafy.scenecrew.equipment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.change.dto.ChangeResponses.DiffItem;

/**
 * 복구 계산 공개 모양(최종 계약 V9RecoverySimulation·V9RecoveryCandidate·FinalRecoveryPointer·FinalRecoveryJobResult).
 * 저장은 jsonb라 키 순서가 바뀌므로, 저장한 후보를 이 레코드로 다시 읽어 계약 순서로 내보낸다.
 */
public final class RecoveryViews {
    private RecoveryViews() { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"kind", "id", "revision", "contentHash", "observedAt", "preconditions"})
    public record EvidenceRef(String kind, UUID id, String revision, String contentHash, String observedAt,
            List<String> preconditions) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"key", "description", "confirmed"})
    public record Assumption(String key, String description, boolean confirmed) { }

    /** V9ReservationWrite. 후보의 점유 구간과 연산 본문에 쓴다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"assetIds", "programId", "taskId", "startAt", "endAt"})
    public record ReservationWrite(List<UUID> assetIds, UUID programId, UUID taskId, Instant startAt, Instant endAt) { }

    /** 예약 연산 본문. 연산마다 쓰는 키만 내보낸다(RESERVE: reservation·evidenceRefs, UPDATE: reservationId·expectedRevision·after·evidenceRefs). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"reservation", "reservationId", "expectedRevision", "after", "evidenceRefs"})
    public record Payload(ReservationWrite reservation, UUID reservationId, String expectedRevision, ReservationWrite after,
            List<EvidenceRef> evidenceRefs) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"type", "payload"})
    public record Operation(String type, Payload payload) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"programId", "description"})
    public record ProgramImpact(UUID programId, String description) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"candidateId", "status", "reasons", "evidenceRefs", "equipmentIds", "componentIds", "steps",
            "occupancy", "readyAt", "requiredReadyAt", "slackSec", "shortfallSec", "programImpacts", "conflicts",
            "operations", "preview"})
    public record Candidate(String candidateId, String status, List<String> reasons, List<EvidenceRef> evidenceRefs,
            List<UUID> equipmentIds, List<UUID> componentIds, List<JsonNode> steps, List<ReservationWrite> occupancy,
            Instant readyAt, Instant requiredReadyAt, BigDecimal slackSec, BigDecimal shortfallSec,
            List<ProgramImpact> programImpacts, List<String> conflicts, List<Operation> operations,
            List<DiffItem> preview) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"id", "eventId", "issueId", "targetAssetIds", "createdByMemberId", "baseRevision", "inputHash",
            "evidenceRefs", "policyVersion", "assumptions", "calculatedAt", "state", "candidates", "expiresAt"})
    public record Simulation(UUID id, UUID eventId, UUID issueId, List<UUID> targetAssetIds, UUID createdByMemberId,
            String baseRevision, String inputHash, List<EvidenceRef> evidenceRefs, String policyVersion,
            List<Assumption> assumptions, Instant calculatedAt, String state, List<Candidate> candidates,
            Instant expiresAt) { }

    /** FinalRecoveryPointer(Q1) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"simulationId", "calculatedAt", "expiresAt", "expired"})
    public record Pointer(UUID simulationId, Instant calculatedAt, Instant expiresAt, boolean expired) { }

    /** FinalRecoveryJobResult. SIMULATE_RECOVERY 작업의 result다. 후보 내용은 담지 않는다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"simulationId", "calculatedAt", "expiresAt"})
    public record JobResult(UUID simulationId, Instant calculatedAt, Instant expiresAt) { }
}

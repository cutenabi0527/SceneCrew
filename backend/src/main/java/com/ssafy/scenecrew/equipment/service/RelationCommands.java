package com.ssafy.scenecrew.equipment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ssafy.scenecrew.global.evidence.EvidenceInput;

/** 장비 관계 쓰기 요청(R1 connections·requirements·dependencies). 형식 검사는 RelationRequests가 끝낸 값이다. */
public final class RelationCommands {
    private RelationCommands() { }

    /** FinalConfirmation: 총괄이 무엇을 확인했는지. 운영 기록에 남긴다. */
    public record Confirmation(String summary, String sourceKind, Instant observedAt) {
        String canonical() {
            return summary + "\u0000" + sourceKind + "\u0000" + observedAt;
        }
    }

    /** expectedRevision은 교체(PATCH) 때만 있다. 추가면 null */
    public record ConnectionWrite(UUID fromAssetId, String fromPort, UUID toAssetId, String toPort,
            long expectedEventRevision, Long expectedRevision, Confirmation confirmation) {
        String canonical() {
            return fromAssetId + "|" + fromPort + "|" + toAssetId + "|" + toPort + "|" + expectedEventRevision + "|"
                    + expectedRevision + "|" + confirmation.canonical();
        }
    }

    public record RequirementItem(List<UUID> assetIds, List<UUID> compatibleAssetIds, int quantity,
            List<UUID> componentAssetIds, List<String> requiredPortKeys) { }

    /** V9SchedulePolicy. FIXED면 변경 허용 구간이 없다. */
    public record SchedulePolicy(String mode, Instant allowedStartAt, Instant allowedEndAt, EvidenceInput evidence) { }

    public record RequirementWrite(UUID programId, List<UUID> taskIds, List<RequirementItem> requirements, Instant readyBy,
            SchedulePolicy schedulePolicy, EvidenceInput timeEvidence, long expectedEventRevision, Long expectedRevision,
            Confirmation confirmation) {
        String canonical() {
            return programId + "|" + taskIds + "|" + requirements + "|" + readyBy + "|" + schedulePolicy.mode() + "|"
                    + schedulePolicy.allowedStartAt() + "|" + schedulePolicy.allowedEndAt() + "|"
                    + schedulePolicy.evidence().canonical() + "|" + timeEvidence.canonical() + "|" + expectedEventRevision
                    + "|" + expectedRevision + "|" + confirmation.canonical();
        }
    }

    /** V9TypedRef */
    public record TypedRef(String kind, UUID id) { }

    public record DependencyWrite(TypedRef from, TypedRef to, BigDecimal lagSec, EvidenceInput evidence,
            long expectedEventRevision, Confirmation confirmation) {
        String canonical() {
            return from + "|" + to + "|" + lagSec.stripTrailingZeros().toPlainString() + "|" + evidence.canonical() + "|"
                    + expectedEventRevision + "|" + confirmation.canonical();
        }
    }
}

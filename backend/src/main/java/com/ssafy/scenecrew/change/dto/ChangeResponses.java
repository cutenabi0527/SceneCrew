package com.ssafy.scenecrew.change.dto;

import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * #283 변경안·변경 확인 응답(!128 V9ChangeSetView·V9DiffItem·FinalEvidenceSnapshot·AcknowledgmentView).
 * revision 계열은 DecimalBigInt라 10진 문자열이다.
 */
public final class ChangeResponses {
    private ChangeResponses() { }

    /**
     * V9DiffItem. 예약 연산 항목만 before·after·evidenceRefs를 담는다(추가는 before가 JSON null). 다른 연산은 네 키만 보낸다.
     * before·after는 V9EquipmentReservation 모양이고, 저장한 diff를 다시 읽어도 JSON null과 생략이 구분된다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"operationIndex", "type", "targetId", "summary", "before", "after", "evidenceRefs"})
    public record DiffItem(int operationIndex, String type, UUID targetId, String summary,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode before,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode after,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode evidenceRefs) {
        private static final List<String> RESERVATION_KEYS = List.of("id", "eventId", "assetIds", "programId", "taskId",
                "startAt", "endAt", "status", "sourceChangeSetId", "revision");
        private static final List<String> EVIDENCE_KEYS = List.of("kind", "id", "revision", "contentHash", "observedAt",
                "preconditions");

        /** 저장(jsonb)이 키 순서를 바꾸므로 읽을 때 계약 순서로 다시 맞춘다. */
        public DiffItem {
            before = ordered(before, RESERVATION_KEYS);
            after = ordered(after, RESERVATION_KEYS);
            if (evidenceRefs != null && evidenceRefs.isArray()) {
                ArrayNode refs = JsonNodeFactory.instance.arrayNode();
                for (JsonNode ref : evidenceRefs) refs.add(ordered(ref, EVIDENCE_KEYS));
                evidenceRefs = refs;
            }
        }

        public DiffItem(int operationIndex, String type, UUID targetId, String summary) {
            this(operationIndex, type, targetId, summary, null, null, null);
        }

        private static JsonNode ordered(JsonNode node, List<String> keys) {
            if (node == null || !node.isObject()) return node;
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (String key : keys) if (node.has(key)) out.set(key, node.get(key));
            for (String key : node.propertyNames()) if (!out.has(key)) out.set(key, node.get(key));
            return out;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "eventId", "status", "revision", "baseRevision", "mode", "operations", "rationale",
            "sourceJobId", "issueId", "previewHash", "diff", "eventRevision", "evidenceRefs", "simulationRefs",
            "evidenceSnapshot", "alternatives", "coordination"})
    public record ChangeSetView(UUID id, UUID eventId, String status, String revision, String baseRevision, String mode,
            JsonNode operations, String rationale,
            @JsonInclude(JsonInclude.Include.ALWAYS) UUID sourceJobId,
            @JsonInclude(JsonInclude.Include.ALWAYS) UUID issueId,
            @JsonInclude(JsonInclude.Include.ALWAYS) String previewHash,
            JsonNode diff, String eventRevision, JsonNode evidenceRefs, JsonNode simulationRefs,
            @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode evidenceSnapshot,
            // AI 변경안만: 만든 대응안 작업(064)의 검토한 다른 방법·AI 처리 기록(V9-D55, 참고 기록). 수동 변경안은 키가 없다.
            JsonNode alternatives, JsonNode coordination) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChangeSetPage(List<ChangeSetView> items, String nextCursor) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"taskId", "title", "changeKinds", "removed", "changedAt"})
    public record AckTarget(UUID taskId, String title, List<String> changeKinds, boolean removed, String changedAt) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"id", "participationRevision", "state", "targetsHash", "targets", "acknowledgedAt"})
    public record AcknowledgmentView(UUID id, String participationRevision, String state, String targetsHash,
            List<AckTarget> targets, String acknowledgedAt) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AcknowledgmentPage(List<AcknowledgmentView> items, String nextCursor) { }
}

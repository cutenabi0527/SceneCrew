package com.ssafy.scenecrew.equipment.dto;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.equipment.service.RelationCommands.Confirmation;
import com.ssafy.scenecrew.equipment.service.RelationCommands.ConnectionWrite;
import com.ssafy.scenecrew.equipment.service.RelationCommands.DependencyWrite;
import com.ssafy.scenecrew.equipment.service.RelationCommands.RequirementItem;
import com.ssafy.scenecrew.equipment.service.RelationCommands.RequirementWrite;
import com.ssafy.scenecrew.equipment.service.RelationCommands.SchedulePolicy;
import com.ssafy.scenecrew.equipment.service.RelationCommands.TypedRef;
import com.ssafy.scenecrew.global.evidence.EvidenceInput;
import com.ssafy.scenecrew.global.web.StrictJson;

/**
 * 장비 관계 본문(FinalConnectionsWrite·FinalRequirementsWrite·FinalDependenciesWrite와 Replace)의 형식 검사.
 * 교체(PATCH)는 쓸 수 있는 칸을 모두 받는다(빠진 칸·null은 400). 계약에 없는 상한은 V9-D43이다.
 */
public final class RelationRequests {
    static final int MAX_ITEMS = 20;
    static final int MAX_TASKS = 20;
    static final int MAX_ASSETS = 20;
    static final int MAX_PORT_KEYS = 32;
    /** 요구 수량 상한. 행사당 장비 상한(200)보다 많이 요구할 수 없다. */
    static final int MAX_QUANTITY = 200;
    static final Set<String> SOURCE_KINDS = EvidenceInput.SOURCE_KINDS;

    private RelationRequests() { }

    /** replace=true면 expectedRevision이 필수다. */
    public static ConnectionWrite connection(JsonNode body, boolean replace) {
        Set<String> fields = new HashSet<>(Set.of("fromAssetId", "fromPort", "toAssetId", "toPort", "expectedEventRevision",
                "confirmation"));
        if (replace) fields.add("expectedRevision");
        StrictJson.object(body, "", fields, fields);
        return new ConnectionWrite(StrictJson.uuid(body.get("fromAssetId"), "fromAssetId"),
                StrictJson.name(body.get("fromPort"), "fromPort", EquipmentRequests.NAME_MAX),
                StrictJson.uuid(body.get("toAssetId"), "toAssetId"),
                StrictJson.name(body.get("toPort"), "toPort", EquipmentRequests.NAME_MAX),
                StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision"),
                replace ? StrictJson.revision(body.get("expectedRevision"), "expectedRevision") : null,
                confirmation(body.get("confirmation")));
    }

    public static RequirementWrite requirement(JsonNode body, boolean replace) {
        Set<String> fields = new HashSet<>(Set.of("programId", "taskIds", "requirements", "readyBy", "schedulePolicy",
                "timeEvidence", "expectedEventRevision", "confirmation"));
        if (replace) fields.add("expectedRevision");
        StrictJson.object(body, "", fields, fields);
        UUID program = StrictJson.nullableUuid(body.get("programId"), "programId");
        List<UUID> tasks = StrictJson.uuids(body.get("taskIds"), "taskIds", MAX_TASKS);
        if (program == null && tasks.isEmpty()) throw StrictJson.invalid("taskIds", "TARGET_REQUIRED");
        List<RequirementItem> items = StrictJson.list(body.get("requirements"), "requirements", MAX_ITEMS, RelationRequests::item);
        if (items.isEmpty()) throw StrictJson.invalid("requirements", "REQUIRED");
        return new RequirementWrite(program, tasks, items, StrictJson.time(body.get("readyBy"), "readyBy"),
                policy(body.get("schedulePolicy")), EvidenceInput.parseFull(body.get("timeEvidence"), "timeEvidence"),
                StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision"),
                replace ? StrictJson.revision(body.get("expectedRevision"), "expectedRevision") : null,
                confirmation(body.get("confirmation")));
    }

    /** 운영 의존은 추가·교체 본문이 같다(revision 없음). */
    public static DependencyWrite dependency(JsonNode body) {
        Set<String> fields = Set.of("from", "to", "lagSec", "evidence", "expectedEventRevision", "confirmation");
        StrictJson.object(body, "", fields, fields);
        double lag = StrictJson.number(body.get("lagSec"), "lagSec");
        if (lag < 0) throw StrictJson.invalid("lagSec", "RANGE");
        return new DependencyWrite(ref(body.get("from"), "from"), ref(body.get("to"), "to"),
                body.get("lagSec").decimalValue(), EvidenceInput.parseFull(body.get("evidence"), "evidence"),
                StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision"),
                confirmation(body.get("confirmation")));
    }

    private static RequirementItem item(JsonNode node, String path) {
        Set<String> fields = Set.of("assetIds", "compatibleAssetIds", "quantity", "componentAssetIds", "requiredPortKeys");
        StrictJson.object(node, path, fields, fields);
        List<UUID> assets = StrictJson.uuids(node.get("assetIds"), path + ".assetIds", MAX_ASSETS);
        List<UUID> compatible = StrictJson.uuids(node.get("compatibleAssetIds"), path + ".compatibleAssetIds", MAX_ASSETS);
        if (assets.isEmpty() && compatible.isEmpty()) throw StrictJson.invalid(path + ".assetIds", "REQUIRED");
        return new RequirementItem(assets, compatible,
                StrictJson.integer(node.get("quantity"), path + ".quantity", 1, MAX_QUANTITY),
                StrictJson.uuids(node.get("componentAssetIds"), path + ".componentAssetIds", MAX_ASSETS),
                StrictJson.texts(node.get("requiredPortKeys"), path + ".requiredPortKeys", MAX_PORT_KEYS,
                        EquipmentRequests.NAME_MAX));
    }

    /** CHANGE_ALLOWED는 허용 구간(시작 < 종료)이 필요하다. 없는 조건을 변경 허용으로 해석하지 않는다(BE13). */
    private static SchedulePolicy policy(JsonNode node) {
        Set<String> fields = Set.of("mode", "allowedStartAt", "allowedEndAt", "evidence");
        StrictJson.object(node, "schedulePolicy", fields, fields);
        String mode = StrictJson.enumValue(node.get("mode"), "schedulePolicy.mode", Set.of("FIXED", "CHANGE_ALLOWED"));
        Instant start = StrictJson.nullableTime(node.get("allowedStartAt"), "schedulePolicy.allowedStartAt");
        Instant end = StrictJson.nullableTime(node.get("allowedEndAt"), "schedulePolicy.allowedEndAt");
        if ("CHANGE_ALLOWED".equals(mode)) {
            if (start == null || end == null) throw StrictJson.invalid("schedulePolicy.allowedStartAt", "REQUIRED");
            if (!start.isBefore(end)) throw StrictJson.invalid("schedulePolicy.allowedEndAt", "ORDER");
        }
        return new SchedulePolicy(mode, start, end, EvidenceInput.parseFull(node.get("evidence"), "schedulePolicy.evidence"));
    }

    private static TypedRef ref(JsonNode node, String path) {
        StrictJson.object(node, path, Set.of("kind", "id"), Set.of("kind", "id"));
        return new TypedRef(StrictJson.enumValue(node.get("kind"), path + ".kind", Set.of("PROGRAM", "TASK")),
                StrictJson.uuid(node.get("id"), path + ".id"));
    }

    private static Confirmation confirmation(JsonNode node) {
        Set<String> fields = Set.of("summary", "sourceKind", "observedAt");
        StrictJson.object(node, "confirmation", fields, fields);
        return new Confirmation(StrictJson.text(node.get("summary"), "confirmation.summary", EvidenceInput.TEXT_MAX),
                StrictJson.enumValue(node.get("sourceKind"), "confirmation.sourceKind", SOURCE_KINDS),
                StrictJson.nullableTime(node.get("observedAt"), "confirmation.observedAt"));
    }
}

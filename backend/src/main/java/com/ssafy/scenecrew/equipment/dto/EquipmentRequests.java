package com.ssafy.scenecrew.equipment.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.equipment.service.EquipmentCommands.AssetCreate;
import com.ssafy.scenecrew.equipment.service.EquipmentCommands.Check;
import com.ssafy.scenecrew.equipment.service.EquipmentCommands.Port;
import com.ssafy.scenecrew.equipment.service.EquipmentCommands.StateRecord;
import com.ssafy.scenecrew.global.evidence.EvidenceInput;
import com.ssafy.scenecrew.global.web.StrictJson;

/**
 * 장비 쓰기 본문(V9AssetCreate·V9EquipmentObservationWrite·V9EquipmentCheckWrite)의 형식 검사.
 * 계약에 없는 상한은 V9-D43을 따른다. 넘으면 400 INVALID_REQUEST(details.fields).
 */
public final class EquipmentRequests {
    /** V9-D43 장비·포트 이름 */
    static final int NAME_MAX = 40;
    /** V9-D43 모델명 */
    static final int MODEL_MAX = 80;
    static final int MAX_PORTS = 32;
    static final int MAX_COMPONENTS = 20;
    static final Set<String> TECHNICAL_STATES = Set.of("UNKNOWN", "CHECK_REQUIRED", "VERIFIED_USABLE", "UNUSABLE");
    static final Set<String> CHECK_KINDS = Set.of("HANDOVER", "INSTALLATION", "RETEST");

    private EquipmentRequests() { }

    public static AssetCreate assetCreate(JsonNode body) {
        StrictJson.object(body, "", Set.of("expectedEventRevision", "name", "model", "componentAssetIds", "ports",
                "storageZoneId", "evidence"), Set.of("expectedEventRevision", "name", "componentAssetIds", "ports", "evidence"));
        long revision = StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision");
        String name = StrictJson.name(body.get("name"), "name", NAME_MAX);
        String model = null;
        if (body.has("model") && !body.get("model").isNull()) model = StrictJson.name(body.get("model"), "model", MODEL_MAX);
        List<UUID> components = StrictJson.uuids(body.get("componentAssetIds"), "componentAssetIds", MAX_COMPONENTS);
        List<Port> ports = StrictJson.list(body.get("ports"), "ports", MAX_PORTS, EquipmentRequests::port);
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < ports.size(); i++) {
            if (!keys.add(ports.get(i).key())) throw StrictJson.invalid("ports[" + i + "].key", "DUPLICATE");
        }
        UUID zone = body.has("storageZoneId") ? StrictJson.nullableUuid(body.get("storageZoneId"), "storageZoneId") : null;
        EvidenceInput evidence = EvidenceInput.parse(body.get("evidence"), "evidence");
        return new AssetCreate(revision, name, model, components, ports, zone, evidence);
    }

    public static StateRecord stateRecord(JsonNode body) {
        StrictJson.object(body, "", Set.of("expectedEventRevision", "expectedAssetRevision", "taskId", "technicalState",
                "evidence"), Set.of("expectedEventRevision", "expectedAssetRevision", "technicalState", "evidence"));
        long eventRevision = StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision");
        long assetRevision = StrictJson.revision(body.get("expectedAssetRevision"), "expectedAssetRevision");
        UUID task = body.has("taskId") ? StrictJson.nullableUuid(body.get("taskId"), "taskId") : null;
        String state = StrictJson.enumValue(body.get("technicalState"), "technicalState", TECHNICAL_STATES);
        return new StateRecord(eventRevision, assetRevision, task, state, EvidenceInput.parse(body.get("evidence"), "evidence"));
    }

    public static Check check(JsonNode body) {
        Set<String> fields = Set.of("expectedEventRevision", "assetId", "kind", "passed", "evidence");
        StrictJson.object(body, "", fields, fields);
        return new Check(StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision"),
                StrictJson.uuid(body.get("assetId"), "assetId"),
                StrictJson.enumValue(body.get("kind"), "kind", CHECK_KINDS),
                StrictJson.bool(body.get("passed"), "passed"),
                EvidenceInput.parse(body.get("evidence"), "evidence"));
    }

    private static Port port(JsonNode node, String path) {
        StrictJson.object(node, path, Set.of("key", "kind"), Set.of("key", "kind"));
        return new Port(StrictJson.name(node.get("key"), path + ".key", NAME_MAX),
                StrictJson.name(node.get("kind"), path + ".kind", NAME_MAX));
    }
}

package com.ssafy.scenecrew.equipment.service;

import java.util.List;
import java.util.UUID;

import com.ssafy.scenecrew.global.evidence.EvidenceInput;

/** 장비 쓰기 요청(EXT02·04·09). 형식 검사는 EquipmentRequests가 끝낸 값이다. */
public final class EquipmentCommands {
    private EquipmentCommands() { }

    public record Port(String key, String kind) { }

    /** EXT02 V9AssetCreate. model·storageZoneId는 생략하면 null */
    public record AssetCreate(long expectedEventRevision, String name, String model, List<UUID> componentAssetIds,
            List<Port> ports, UUID storageZoneId, EvidenceInput evidence) {
        String canonical() {
            return expectedEventRevision + "|" + name + "|" + model + "|" + componentAssetIds + "|" + ports + "|"
                    + storageZoneId + "|" + evidence.canonical();
        }
    }

    /** EXT04 V9EquipmentObservationWrite */
    public record StateRecord(long expectedEventRevision, long expectedAssetRevision, UUID taskId, String technicalState,
            EvidenceInput evidence) {
        String canonical() {
            return expectedEventRevision + "|" + expectedAssetRevision + "|" + taskId + "|" + technicalState + "|"
                    + evidence.canonical();
        }
    }

    /** EXT09 V9EquipmentCheckWrite */
    public record Check(long expectedEventRevision, UUID assetId, String kind, boolean passed, EvidenceInput evidence) {
        String canonical() {
            return expectedEventRevision + "|" + assetId + "|" + kind + "|" + passed + "|" + evidence.canonical();
        }
    }
}

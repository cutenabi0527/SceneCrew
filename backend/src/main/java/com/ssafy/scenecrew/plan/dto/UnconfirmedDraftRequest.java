package com.ssafy.scenecrew.plan.dto;

import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.global.evidence.EvidenceInput;
import com.ssafy.scenecrew.global.web.StrictJson;
import com.ssafy.scenecrew.plan.service.UnconfirmedDraftService.SaveCommand;

/**
 * Q3-A 저장 본문(FinalUnconfirmedDraftWrite) 전체의 형식 검사. 계약 v9-327의 V9EquipmentDraft·V9VenueDraft와
 * 하위 V9Draft* 스키마(필수 키·추가 키 금지·id/tempId 중 하나·enum·const·최솟값·points 3개 이상)를 그대로 본다.
 *
 * <p>초안은 받은 JSON을 그대로 저장하고 FE가 조회 응답을 같은 계약 스키마로 다시 검사한다. 여기서 놓친 위반은 저장된 뒤
 * 웹에서 초안을 열 수 없게 만들므로, 계약보다 느슨한 곳이 없어야 한다. 시각은 FE 검사(초 단위 필수·Z 또는 ±hh:mm)와
 * 같은 모양만 받는다. 근거(V9EvidenceInput)는 다른 API와 같은 EvidenceInput 규칙(공백만 거절, 1000자 상한)을 쓴다.
 * 오류는 400 INVALID_REQUEST이고 details.fields[0]에 경로와 이유를 담는다(StrictJson).
 */
public final class UnconfirmedDraftRequest {
    private static final String TEMP_ID = "tmp-[A-Za-z0-9_-]{1,40}";
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    /** FE asUtcTimestamp와 같은 표기. 실제 날짜·시각 검사는 StrictJson.time이 한다. */
    private static final String UTC = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})";
    private static final Set<String> BODY = Set.of("expectedRevision", "equipmentDraft", "venueDraft", "sourceJobId");
    private static final Set<String> EQUIPMENT = Set.of("assets", "connections", "requirements", "reservations",
            "dependencies");
    private static final Set<String> VENUE = Set.of("layout", "profiles");
    private static final Set<String> ASSET = Set.of("name", "model", "componentAssetIds", "ports");
    private static final Set<String> PORT = Set.of("key", "kind");
    private static final Set<String> CONNECTION = Set.of("fromAssetId", "fromPort", "toAssetId", "toPort", "evidence");
    private static final Set<String> REQUIREMENT = Set.of("programId", "taskIds", "requirements", "readyBy",
            "schedulePolicy", "timeEvidence");
    private static final Set<String> REQUIREMENT_ITEM = Set.of("assetIds", "compatibleAssetIds", "quantity",
            "componentAssetIds", "requiredPortKeys");
    private static final Set<String> SCHEDULE_POLICY = Set.of("mode", "allowedStartAt", "allowedEndAt", "evidence");
    private static final Set<String> RESERVATION = Set.of("assetIds", "programId", "taskId", "startAt", "endAt");
    private static final Set<String> DEPENDENCY = Set.of("from", "to", "lagSec", "evidence");
    private static final Set<String> DEPENDENCY_END = Set.of("kind", "id");
    private static final Set<String> LAYOUT = Set.of("coordinateSystem", "width", "height", "zonePolygons", "source");
    private static final Set<String> POLYGON = Set.of("zoneId", "points");
    private static final Set<String> POINT = Set.of("x", "y");
    private static final Set<String> PROFILE = Set.of("zoneId", "expectedProfileRevision", "effectiveAreaM2",
            "areaEvidence", "riskRule", "minimumCrewRule");
    private static final Set<String> RULE = Set.of("version", "unit", "condition", "evidence");
    private static final Set<String> CREW_RULE = Set.of("workGroupId", "minimumCount", "startAt", "endAt", "evidence");

    private UnconfirmedDraftRequest() { }

    public static SaveCommand parse(JsonNode body) {
        StrictJson.object(body, "", BODY, BODY);
        long revision = StrictJson.revision(body.get("expectedRevision"), "expectedRevision");
        UUID sourceJobId = StrictJson.nullableUuid(body.get("sourceJobId"), "sourceJobId");
        JsonNode equipment = body.get("equipmentDraft");
        equipment(equipment, "equipmentDraft");
        JsonNode venue = body.get("venueDraft");
        venue(venue, "venueDraft");
        return new SaveCommand(revision, (ObjectNode) equipment, (ObjectNode) venue, sourceJobId);
    }

    private static void equipment(JsonNode node, String path) {
        StrictJson.object(node, path, EQUIPMENT, EQUIPMENT);
        each(node.get("assets"), path + ".assets", UnconfirmedDraftRequest::asset);
        each(node.get("connections"), path + ".connections", UnconfirmedDraftRequest::connection);
        each(node.get("requirements"), path + ".requirements", UnconfirmedDraftRequest::requirement);
        each(node.get("reservations"), path + ".reservations", UnconfirmedDraftRequest::reservation);
        each(node.get("dependencies"), path + ".dependencies", UnconfirmedDraftRequest::dependency);
    }

    private static void venue(JsonNode node, String path) {
        StrictJson.object(node, path, VENUE, VENUE);
        JsonNode layout = node.get("layout");
        if (!layout.isNull()) layout(layout, path + ".layout");
        each(node.get("profiles"), path + ".profiles", UnconfirmedDraftRequest::profile);
    }

    /** V9DraftAsset: id와 tempId 중 정확히 하나 */
    private static void asset(JsonNode node, String path) {
        StrictJson.object(node, path, union(ASSET, Set.of("id", "tempId")), ASSET);
        if (node.has("id") == node.has("tempId")) throw StrictJson.invalid(path, "ID_OR_TEMP_ID");
        if (node.has("id")) StrictJson.uuid(node.get("id"), path + ".id");
        else if (!node.get("tempId").isString() || !node.get("tempId").asString().matches(TEMP_ID))
            throw StrictJson.invalid(path + ".tempId", "FORMAT");
        nonEmpty(node.get("name"), path + ".name");
        if (!node.get("model").isNull()) nonEmpty(node.get("model"), path + ".model");
        refs(node.get("componentAssetIds"), path + ".componentAssetIds", 0);
        each(node.get("ports"), path + ".ports", (port, at) -> {
            StrictJson.object(port, at, PORT, PORT);
            nonEmpty(port.get("key"), at + ".key");
            nonEmpty(port.get("kind"), at + ".kind");
        });
    }

    private static void connection(JsonNode node, String path) {
        StrictJson.object(node, path, CONNECTION, CONNECTION);
        ref(node.get("fromAssetId"), path + ".fromAssetId");
        nonEmpty(node.get("fromPort"), path + ".fromPort");
        ref(node.get("toAssetId"), path + ".toAssetId");
        nonEmpty(node.get("toPort"), path + ".toPort");
        evidence(node.get("evidence"), path + ".evidence");
    }

    private static void requirement(JsonNode node, String path) {
        StrictJson.object(node, path, REQUIREMENT, REQUIREMENT);
        nullableRef(node.get("programId"), path + ".programId");
        refs(node.get("taskIds"), path + ".taskIds", 0);
        each(node.get("requirements"), path + ".requirements", (item, at) -> {
            StrictJson.object(item, at, REQUIREMENT_ITEM, REQUIREMENT_ITEM);
            refs(item.get("assetIds"), at + ".assetIds", 0);
            refs(item.get("compatibleAssetIds"), at + ".compatibleAssetIds", 0);
            StrictJson.integer(item.get("quantity"), at + ".quantity", 1, Integer.MAX_VALUE);
            refs(item.get("componentAssetIds"), at + ".componentAssetIds", 0);
            each(item.get("requiredPortKeys"), at + ".requiredPortKeys", UnconfirmedDraftRequest::nonEmpty);
        });
        utc(node.get("readyBy"), path + ".readyBy");
        JsonNode policy = node.get("schedulePolicy");
        String at = path + ".schedulePolicy";
        StrictJson.object(policy, at, SCHEDULE_POLICY, SCHEDULE_POLICY);
        StrictJson.enumValue(policy.get("mode"), at + ".mode", Set.of("FIXED", "CHANGE_ALLOWED"));
        nullableUtc(policy.get("allowedStartAt"), at + ".allowedStartAt");
        nullableUtc(policy.get("allowedEndAt"), at + ".allowedEndAt");
        evidence(policy.get("evidence"), at + ".evidence");
        evidence(node.get("timeEvidence"), path + ".timeEvidence");
    }

    private static void reservation(JsonNode node, String path) {
        StrictJson.object(node, path, RESERVATION, RESERVATION);
        refs(node.get("assetIds"), path + ".assetIds", 1);
        nullableRef(node.get("programId"), path + ".programId");
        nullableRef(node.get("taskId"), path + ".taskId");
        utc(node.get("startAt"), path + ".startAt");
        utc(node.get("endAt"), path + ".endAt");
    }

    private static void dependency(JsonNode node, String path) {
        StrictJson.object(node, path, DEPENDENCY, DEPENDENCY);
        for (String end : new String[] {"from", "to"}) {
            JsonNode value = node.get(end);
            String at = path + "." + end;
            StrictJson.object(value, at, DEPENDENCY_END, DEPENDENCY_END);
            StrictJson.enumValue(value.get("kind"), at + ".kind", Set.of("PROGRAM", "TASK"));
            ref(value.get("id"), at + ".id");
        }
        if (number(node.get("lagSec"), path + ".lagSec") < 0) throw StrictJson.invalid(path + ".lagSec", "RANGE");
        evidence(node.get("evidence"), path + ".evidence");
    }

    private static void layout(JsonNode node, String path) {
        StrictJson.object(node, path, LAYOUT, LAYOUT);
        JsonNode system = node.get("coordinateSystem");
        if (!system.isString() || !"normalized-image".equals(system.asString()))
            throw StrictJson.invalid(path + ".coordinateSystem", "ENUM");
        positive(node.get("width"), path + ".width");
        positive(node.get("height"), path + ".height");
        each(node.get("zonePolygons"), path + ".zonePolygons", (polygon, at) -> {
            StrictJson.object(polygon, at, POLYGON, POLYGON);
            ref(polygon.get("zoneId"), at + ".zoneId");
            JsonNode points = polygon.get("points");
            each(points, at + ".points", (point, p) -> {
                StrictJson.object(point, p, POINT, POINT);
                unit(point.get("x"), p + ".x");
                unit(point.get("y"), p + ".y");
            });
            if (points.size() < 3) throw StrictJson.invalid(at + ".points", "RANGE");
        });
        evidence(node.get("source"), path + ".source");
    }

    private static void profile(JsonNode node, String path) {
        StrictJson.object(node, path, PROFILE, PROFILE);
        ref(node.get("zoneId"), path + ".zoneId");
        StrictJson.nullableRevision(node.get("expectedProfileRevision"), path + ".expectedProfileRevision");
        if (!node.get("effectiveAreaM2").isNull()) positive(node.get("effectiveAreaM2"), path + ".effectiveAreaM2");
        if (!node.get("areaEvidence").isNull()) evidence(node.get("areaEvidence"), path + ".areaEvidence");
        JsonNode risk = node.get("riskRule");
        if (!risk.isNull()) {
            String at = path + ".riskRule";
            StrictJson.object(risk, at, RULE, RULE);
            nonEmpty(risk.get("version"), at + ".version");
            nonEmpty(risk.get("unit"), at + ".unit");
            nonEmpty(risk.get("condition"), at + ".condition");
            evidence(risk.get("evidence"), at + ".evidence");
        }
        JsonNode crew = node.get("minimumCrewRule");
        if (!crew.isNull()) {
            String at = path + ".minimumCrewRule";
            StrictJson.object(crew, at, CREW_RULE, CREW_RULE);
            StrictJson.uuid(crew.get("workGroupId"), at + ".workGroupId");
            StrictJson.integer(crew.get("minimumCount"), at + ".minimumCount", 0, Integer.MAX_VALUE);
            utc(crew.get("startAt"), at + ".startAt");
            utc(crew.get("endAt"), at + ".endAt");
            evidence(crew.get("evidence"), at + ".evidence");
        }
    }

    // ---- 공통 ----------------------------------------------------------------------------------------------

    private interface Item {
        void check(JsonNode node, String path);
    }

    private static void each(JsonNode node, String path, Item item) {
        if (node == null || !node.isArray()) throw StrictJson.invalid(path, "TYPE");
        for (int i = 0; i < node.size(); i++) item.check(node.get(i), path + "[" + i + "]");
    }

    /** 장비·프로그램·업무·구역 참조: 실제 UUID 또는 같은 초안의 tmp- 키 */
    private static void ref(JsonNode node, String path) {
        if (node == null || !node.isString()) throw StrictJson.invalid(path, "TYPE");
        String value = node.asString();
        if (!value.matches(TEMP_ID) && !value.matches(UUID_FORMAT)) throw StrictJson.invalid(path, "FORMAT");
    }

    private static void nullableRef(JsonNode node, String path) {
        if (node == null) throw StrictJson.invalid(path, "REQUIRED");
        if (!node.isNull()) ref(node, path);
    }

    private static void refs(JsonNode node, String path, int minItems) {
        each(node, path, UnconfirmedDraftRequest::ref);
        if (node.size() < minItems) throw StrictJson.invalid(path, "RANGE");
    }

    /** 계약 minLength:1 (코드포인트) */
    private static void nonEmpty(JsonNode node, String path) {
        if (node == null || !node.isString()) throw StrictJson.invalid(path, "TYPE");
        if (node.asString().isEmpty()) throw StrictJson.invalid(path, "BLANK");
    }

    private static void utc(JsonNode node, String path) {
        if (node == null || !node.isString() || !node.asString().matches(UTC)) throw StrictJson.invalid(path, "FORMAT");
        StrictJson.time(node, path);
    }

    private static void nullableUtc(JsonNode node, String path) {
        if (node == null) throw StrictJson.invalid(path, "REQUIRED");
        if (!node.isNull()) utc(node, path);
    }

    /** V9EvidenceInput. observedAt은 FE와 같은 시각 표기도 본다. */
    private static void evidence(JsonNode node, String path) {
        EvidenceInput.parse(node, path);
        if (!node.get("observedAt").isNull()) utc(node.get("observedAt"), path + ".observedAt");
    }

    private static double number(JsonNode node, String path) {
        return StrictJson.number(node, path);
    }

    private static void positive(JsonNode node, String path) {
        if (number(node, path) <= 0) throw StrictJson.invalid(path, "RANGE");
    }

    private static void unit(JsonNode node, String path) {
        double value = number(node, path);
        if (value < 0 || value > 1) throw StrictJson.invalid(path, "RANGE");
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var out = new java.util.HashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }
}

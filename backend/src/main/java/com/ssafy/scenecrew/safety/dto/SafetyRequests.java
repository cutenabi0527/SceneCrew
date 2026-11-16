package com.ssafy.scenecrew.safety.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.global.evidence.EvidenceInput;
import com.ssafy.scenecrew.global.web.StrictJson;
import com.ssafy.scenecrew.safety.service.SafetyCommands.CameraCreate;
import com.ssafy.scenecrew.safety.service.SafetyCommands.CameraPatch;
import com.ssafy.scenecrew.safety.service.SafetyCommands.Coverage;
import com.ssafy.scenecrew.safety.service.SafetyCommands.CrewRule;
import com.ssafy.scenecrew.safety.service.SafetyCommands.MapWrite;
import com.ssafy.scenecrew.safety.service.SafetyCommands.Point;
import com.ssafy.scenecrew.safety.service.SafetyCommands.Polygon;
import com.ssafy.scenecrew.safety.service.SafetyCommands.Profile;
import com.ssafy.scenecrew.safety.service.SafetyCommands.Rule;

/**
 * 안전 쓰기 본문(V9SafetyMapWrite·V9CameraCreate·V9CameraPatch)의 형식 검사. 계약에 없는 상한은 V9-D43이다.
 * 넘으면 400 INVALID_REQUEST(details.fields). 길이는 코드포인트로 센다.
 */
public final class SafetyRequests {
    static final int NAME_MAX = 40;
    static final int RULE_LABEL_MAX = 40;
    static final int MIN_POINTS = 3;
    static final int MAX_POINTS = 50;
    /** 행사 구역 상한(10)과 같다. */
    static final int MAX_ZONES = 10;
    static final int MAX_COVERAGE = 10;
    /** 행사 총인원 상한(31)을 넘는 최소 유지 인원은 지킬 수 없다. */
    static final int MAX_CREW = 31;
    static final int BINDING_MAX = 100;
    /** 불투명 연결 참조. 주소(://)·계정(@)·공백 모양을 받지 않는다(V9-D43). */
    static final String BINDING = "[A-Za-z0-9._:-]+";
    static final Set<String> SCOPES = Set.of("FULL", "PARTIAL", "UNDETERMINED");

    private SafetyRequests() { }

    public static MapWrite map(JsonNode body) {
        Set<String> fields = Set.of("expectedEventRevision", "expectedLayoutRevision", "layout", "profiles");
        StrictJson.object(body, "", fields, fields);
        JsonNode layout = body.get("layout");
        Set<String> layoutFields = Set.of("coordinateSystem", "width", "height", "zonePolygons", "source");
        StrictJson.object(layout, "layout", layoutFields, layoutFields);
        if (!layout.get("coordinateSystem").isString() || !"normalized-image".equals(layout.get("coordinateSystem").asString()))
            throw StrictJson.invalid("layout.coordinateSystem", "ENUM");
        BigDecimal width = positive(layout.get("width"), "layout.width");
        BigDecimal height = positive(layout.get("height"), "layout.height");
        List<Polygon> polygons = StrictJson.list(layout.get("zonePolygons"), "layout.zonePolygons", MAX_ZONES,
                SafetyRequests::polygon);
        Set<UUID> polygonZones = new HashSet<>();
        for (int i = 0; i < polygons.size(); i++)
            if (!polygonZones.add(polygons.get(i).zoneId()))
                throw StrictJson.invalid("layout.zonePolygons[" + i + "].zoneId", "DUPLICATE");
        EvidenceInput source = EvidenceInput.parse(layout.get("source"), "layout.source");
        List<Profile> profiles = StrictJson.list(body.get("profiles"), "profiles", MAX_ZONES, SafetyRequests::profile);
        Set<UUID> profileZones = new HashSet<>();
        for (int i = 0; i < profiles.size(); i++) {
            UUID zone = profiles.get(i).zoneId();
            if (!profileZones.add(zone)) throw StrictJson.invalid("profiles[" + i + "].zoneId", "DUPLICATE");
            // 구역 프로필은 평면도의 그 구역 도형에 묶인다(BE13 SafetyZoneProfile).
            if (!polygonZones.contains(zone)) throw StrictJson.invalid("profiles[" + i + "].zoneId", "NO_POLYGON");
        }
        return new MapWrite(StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision"),
                StrictJson.nullableRevision(body.get("expectedLayoutRevision"), "expectedLayoutRevision"),
                width, height, polygons, source, profiles);
    }

    /**
     * 웹 연결 카메라(V9-D58)는 sourceBindingRef를 빼거나 null로, 보정 전이면 coverage를 빼거나 []로 보낸다.
     * linkTaskId는 연결 담당 업무다. 빼면 서버가 기존 카메라 연결 업무를 자동 지정하고, null이면 지정하지 않는다(#395).
     */
    public static CameraCreate camera(JsonNode body) {
        StrictJson.object(body, "", Set.of("expectedEventRevision", "name", "sourceBindingRef", "enabled", "coverage", "linkTaskId"),
                Set.of("expectedEventRevision", "name", "enabled"));
        JsonNode binding = body.get("sourceBindingRef");
        JsonNode coverage = body.get("coverage");
        return new CameraCreate(StrictJson.revision(body.get("expectedEventRevision"), "expectedEventRevision"),
                StrictJson.name(body.get("name"), "name", NAME_MAX), binding == null || binding.isNull() ? null : binding(binding),
                StrictJson.bool(body.get("enabled"), "enabled"),
                coverage == null || coverage.isArray() && coverage.isEmpty() ? List.of() : coverage(coverage),
                body.has("linkTaskId"), body.has("linkTaskId") ? StrictJson.nullableUuid(body.get("linkTaskId"), "linkTaskId") : null);
    }

    /**
     * 보내지 않은 칸은 그대로 둔다. 바꿀 칸이 하나도 없으면 400(minProperties 2).
     * sourceBindingRef·linkTaskId만 null로 비울 수 있고(웹 연결 카메라로 바꾸기·담당 업무 해제), 그 밖의 null은 400.
     */
    public static CameraPatch patch(JsonNode body) {
        StrictJson.object(body, "", Set.of("expectedCameraRevision", "name", "sourceBindingRef", "enabled", "coverage", "linkTaskId"),
                Set.of("expectedCameraRevision"));
        if (body.size() < 2) throw StrictJson.invalid("body", "NO_CHANGE");
        JsonNode binding = body.get("sourceBindingRef");
        return new CameraPatch(StrictJson.revision(body.get("expectedCameraRevision"), "expectedCameraRevision"),
                body.has("name") ? StrictJson.name(body.get("name"), "name", NAME_MAX) : null,
                body.has("sourceBindingRef"), binding == null || binding.isNull() ? null : binding(binding),
                body.has("enabled") ? StrictJson.bool(body.get("enabled"), "enabled") : null,
                body.has("coverage") ? coverage(body.get("coverage")) : null,
                body.has("linkTaskId"), body.has("linkTaskId") ? StrictJson.nullableUuid(body.get("linkTaskId"), "linkTaskId") : null);
    }

    private static List<Coverage> coverage(JsonNode node) {
        List<Coverage> items = StrictJson.list(node, "coverage", MAX_COVERAGE, SafetyRequests::coverageItem);
        if (items.isEmpty()) throw StrictJson.invalid("coverage", "REQUIRED");
        // 한 카메라가 같은 구역을 두 영역으로 나눠 적으면 같은 사람을 두 번 셀 수 있다(BE13 겹침 단순 합산 금지).
        Set<UUID> zones = new HashSet<>();
        for (int i = 0; i < items.size(); i++)
            if (!zones.add(items.get(i).zoneId())) throw StrictJson.invalid("coverage[" + i + "].zoneId", "DUPLICATE");
        return items;
    }

    private static Coverage coverageItem(JsonNode node, String path) {
        Set<String> fields = Set.of("zoneId", "layoutId", "layoutRevision", "profileRevision", "scope", "roi",
                "effectiveAreaM2", "calibrationEvidence");
        StrictJson.object(node, path, fields, fields);
        return new Coverage(StrictJson.uuid(node.get("zoneId"), path + ".zoneId"),
                StrictJson.uuid(node.get("layoutId"), path + ".layoutId"),
                StrictJson.revision(node.get("layoutRevision"), path + ".layoutRevision"),
                StrictJson.revision(node.get("profileRevision"), path + ".profileRevision"),
                StrictJson.enumValue(node.get("scope"), path + ".scope", SCOPES),
                points(node.get("roi"), path + ".roi"),
                nullablePositive(node.get("effectiveAreaM2"), path + ".effectiveAreaM2"),
                EvidenceInput.parse(node.get("calibrationEvidence"), path + ".calibrationEvidence"));
    }

    private static Polygon polygon(JsonNode node, String path) {
        StrictJson.object(node, path, Set.of("zoneId", "points"), Set.of("zoneId", "points"));
        return new Polygon(StrictJson.uuid(node.get("zoneId"), path + ".zoneId"), points(node.get("points"), path + ".points"));
    }

    private static Profile profile(JsonNode node, String path) {
        Set<String> fields = Set.of("zoneId", "expectedProfileRevision", "effectiveAreaM2", "areaEvidence", "riskRule",
                "minimumCrewRule");
        StrictJson.object(node, path, fields, fields);
        BigDecimal area = nullablePositive(node.get("effectiveAreaM2"), path + ".effectiveAreaM2");
        EvidenceInput areaEvidence = node.get("areaEvidence").isNull() ? null
                : EvidenceInput.parse(node.get("areaEvidence"), path + ".areaEvidence");
        // 면적과 그 근거는 함께 있거나 함께 없다. 근거 없는 실면적으로 밀집도를 계산하지 않는다(BE13).
        if ((area == null) != (areaEvidence == null)) throw StrictJson.invalid(path + ".areaEvidence", "AREA_EVIDENCE");
        return new Profile(StrictJson.uuid(node.get("zoneId"), path + ".zoneId"),
                StrictJson.nullableRevision(node.get("expectedProfileRevision"), path + ".expectedProfileRevision"),
                area, areaEvidence,
                node.get("riskRule").isNull() ? null : rule(node.get("riskRule"), path + ".riskRule"),
                node.get("minimumCrewRule").isNull() ? null : crew(node.get("minimumCrewRule"), path + ".minimumCrewRule"));
    }

    private static Rule rule(JsonNode node, String path) {
        Set<String> fields = Set.of("version", "unit", "condition", "evidence");
        StrictJson.object(node, path, fields, fields);
        return new Rule(StrictJson.text(node.get("version"), path + ".version", RULE_LABEL_MAX),
                StrictJson.text(node.get("unit"), path + ".unit", RULE_LABEL_MAX),
                StrictJson.text(node.get("condition"), path + ".condition", EvidenceInput.TEXT_MAX),
                EvidenceInput.parse(node.get("evidence"), path + ".evidence"));
    }

    private static CrewRule crew(JsonNode node, String path) {
        Set<String> fields = Set.of("workGroupId", "minimumCount", "startAt", "endAt", "evidence");
        StrictJson.object(node, path, fields, fields);
        Instant start = StrictJson.time(node.get("startAt"), path + ".startAt");
        Instant end = StrictJson.time(node.get("endAt"), path + ".endAt");
        if (!start.isBefore(end)) throw StrictJson.invalid(path + ".endAt", "ORDER");
        return new CrewRule(StrictJson.uuid(node.get("workGroupId"), path + ".workGroupId"),
                StrictJson.integer(node.get("minimumCount"), path + ".minimumCount", 0, MAX_CREW), start, end,
                EvidenceInput.parse(node.get("evidence"), path + ".evidence"));
    }

    private static List<Point> points(JsonNode node, String path) {
        List<Point> points = StrictJson.list(node, path, MAX_POINTS, SafetyRequests::point);
        if (points.size() < MIN_POINTS) throw StrictJson.invalid(path, "LIMIT");
        return points;
    }

    private static Point point(JsonNode node, String path) {
        StrictJson.object(node, path, Set.of("x", "y"), Set.of("x", "y"));
        return new Point(unit(node.get("x"), path + ".x"), unit(node.get("y"), path + ".y"));
    }

    /** 0~1 범위 수 */
    private static BigDecimal unit(JsonNode node, String path) {
        StrictJson.number(node, path);
        BigDecimal value = node.decimalValue();
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) throw StrictJson.invalid(path, "RANGE");
        return value;
    }

    private static BigDecimal positive(JsonNode node, String path) {
        StrictJson.number(node, path);
        BigDecimal value = node.decimalValue();
        if (value.signum() <= 0) throw StrictJson.invalid(path, "RANGE");
        return value;
    }

    private static BigDecimal nullablePositive(JsonNode node, String path) {
        if (node == null) throw StrictJson.invalid(path, "REQUIRED");
        return node.isNull() ? null : positive(node, path);
    }

    private static String binding(JsonNode node) {
        if (node == null || !node.isString()) throw StrictJson.invalid("sourceBindingRef", "TYPE");
        String value = node.asString();
        if (value.isEmpty() || value.length() > BINDING_MAX) throw StrictJson.invalid("sourceBindingRef", "LENGTH");
        if (!value.matches(BINDING)) throw StrictJson.invalid("sourceBindingRef", "FORMAT");
        return value;
    }
}

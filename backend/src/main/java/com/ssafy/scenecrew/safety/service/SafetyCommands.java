package com.ssafy.scenecrew.safety.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ssafy.scenecrew.global.evidence.EvidenceInput;

/** 안전 지도·카메라 쓰기 요청(EXT11·22·24). 형식 검사는 SafetyRequests가 끝낸 값이다. */
public final class SafetyCommands {
    private SafetyCommands() { }

    /** 정규화 이미지 좌표(0~1) */
    public record Point(BigDecimal x, BigDecimal y) { }

    public record Polygon(UUID zoneId, List<Point> points) { }

    /** V9RuleWrite */
    public record Rule(String version, String unit, String condition, EvidenceInput evidence) {
        String canonical() {
            return version + "\u0000" + unit + "\u0000" + condition + "\u0000" + evidence.canonical();
        }
    }

    /** V9MinimumCrewRuleWrite */
    public record CrewRule(UUID workGroupId, int minimumCount, Instant startAt, Instant endAt, EvidenceInput evidence) {
        String canonical() {
            return workGroupId + "|" + minimumCount + "|" + startAt + "|" + endAt + "|" + evidence.canonical();
        }
    }

    /** V9ProfileWrite. 면적·근거·규칙은 null이면 미확인이다. */
    public record Profile(UUID zoneId, Long expectedProfileRevision, BigDecimal effectiveAreaM2, EvidenceInput areaEvidence,
            Rule riskRule, CrewRule minimumCrewRule) {
        String canonical() {
            return zoneId + "|" + expectedProfileRevision + "|" + plain(effectiveAreaM2) + "|"
                    + (areaEvidence == null ? null : areaEvidence.canonical()) + "|"
                    + (riskRule == null ? null : riskRule.canonical()) + "|"
                    + (minimumCrewRule == null ? null : minimumCrewRule.canonical());
        }
    }

    /** V9SafetyMapWrite */
    public record MapWrite(long expectedEventRevision, Long expectedLayoutRevision, BigDecimal width, BigDecimal height,
            List<Polygon> zonePolygons, EvidenceInput source, List<Profile> profiles) {
        String canonical() {
            StringBuilder out = new StringBuilder().append(expectedEventRevision).append('|').append(expectedLayoutRevision)
                    .append('|').append(plain(width)).append('|').append(plain(height)).append('|');
            for (Polygon p : zonePolygons) {
                out.append(p.zoneId()).append(':');
                for (Point point : p.points()) out.append(plain(point.x())).append(',').append(plain(point.y())).append(';');
                out.append('|');
            }
            out.append(source.canonical()).append('|');
            for (Profile p : profiles) out.append(p.canonical()).append('|');
            return out.toString();
        }
    }

    /** V9CoverageWrite */
    public record Coverage(UUID zoneId, UUID layoutId, long layoutRevision, long profileRevision, String scope,
            List<Point> roi, BigDecimal effectiveAreaM2, EvidenceInput calibrationEvidence) {
        String canonical() {
            StringBuilder out = new StringBuilder().append(zoneId).append('|').append(layoutId).append('|')
                    .append(layoutRevision).append('|').append(profileRevision).append('|').append(scope).append('|');
            for (Point point : roi) out.append(plain(point.x())).append(',').append(plain(point.y())).append(';');
            return out.append('|').append(plain(effectiveAreaM2)).append('|').append(calibrationEvidence.canonical()).toString();
        }
    }

    /** V9CameraCreate */
    public record CameraCreate(long expectedEventRevision, String name, String sourceBindingRef, boolean enabled,
            List<Coverage> coverage, boolean linkTaskSet, UUID linkTaskId) {
        String canonical() {
            StringBuilder out = new StringBuilder().append(expectedEventRevision).append('|').append(name).append('|')
                    .append(sourceBindingRef).append('|').append(enabled).append('|');
            for (Coverage c : coverage) out.append(c.canonical()).append("||");
            return out.append('|').append(linkTaskSet ? String.valueOf(linkTaskId) : "-").toString();
        }
    }

    /**
     * V9CameraPatch. null은 "보내지 않음(그대로 둠)"이다. sourceBindingRef·linkTaskId는 보냈는지(…Set)를 따로 두어
     * null로 비우는 요청과 구별한다.
     */
    public record CameraPatch(long expectedCameraRevision, String name, boolean sourceBindingSet, String sourceBindingRef,
            Boolean enabled, List<Coverage> coverage, boolean linkTaskSet, UUID linkTaskId) {
        String canonical() {
            StringBuilder out = new StringBuilder().append(expectedCameraRevision).append('|').append(name).append('|')
                    .append(sourceBindingSet ? String.valueOf(sourceBindingRef) : "-").append('|').append(enabled).append('|');
            if (coverage == null) out.append("-");
            else for (Coverage c : coverage) out.append(c.canonical()).append("||");
            return out.append('|').append(linkTaskSet ? String.valueOf(linkTaskId) : "-").toString();
        }
    }

    static String plain(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }
}

package com.ssafy.scenecrew.global.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.global.web.StrictJson;

/**
 * 계약 V9EvidenceInput(쓰기)과 저장·응답 모양 V9Evidence(읽기)의 변환. 장비·안전 API가 함께 쓴다.
 *
 * <p>클라이언트는 출처 종류·출처 참조·관측 시각(모르면 null)·메모만 보낸다. 기록 시각·확인자·확인 여부는 서버가 정한다.
 * 확인 여부는 출처가 시뮬레이션 가정이 아닐 때 true이고, 그때 확인자는 요청한 Member다. 가정은 확인된 사실이 아니므로
 * confirmed=false, confirmedByMemberId=null로 남긴다(BE13 "가정은 실제 확인 사실과 별도 표시").
 * 메모(note)는 V9Evidence에 칸이 없으므로 저장 근거에 넣지 않고 operation_log에만 남긴다.
 */
public record EvidenceInput(String sourceKind, String sourceRef, Instant observedAt, String note) {
    public static final Set<String> SOURCE_KINDS = Set.of("MANUAL_CONFIRMATION", "REHEARSAL_MEASUREMENT",
            "AUTOMATIC_MEASUREMENT", "SIMULATION_ASSUMPTION");
    /** V9-D43 근거 출처 참조·메모 상한(코드포인트) */
    public static final int TEXT_MAX = 1000;

    public static EvidenceInput parse(JsonNode node, String path) {
        StrictJson.object(node, path, Set.of("sourceKind", "sourceRef", "observedAt", "note"),
                Set.of("sourceKind", "sourceRef", "observedAt"));
        String kind = StrictJson.enumValue(node.get("sourceKind"), StrictJson.join(path, "sourceKind"), SOURCE_KINDS);
        String ref = StrictJson.text(node.get("sourceRef"), StrictJson.join(path, "sourceRef"), TEXT_MAX);
        Instant observedAt = StrictJson.nullableTime(node.get("observedAt"), StrictJson.join(path, "observedAt"));
        String note = node.has("note") ? StrictJson.text(node.get("note"), StrictJson.join(path, "note"), TEXT_MAX) : null;
        return new EvidenceInput(kind, ref, observedAt, note);
    }

    /**
     * 계약이 쓰기 본문에 V9Evidence 전체 모양을 요구하는 칸(장비 요구의 timeEvidence·schedulePolicy.evidence, 운영 의존의 evidence).
     * 6개 키의 형식은 모두 검사하되, 기록 시각·확인자·확인 여부는 클라이언트 값을 믿지 않고 저장할 때 서버가 다시 정한다(BE13).
     */
    public static EvidenceInput parseFull(JsonNode node, String path) {
        Set<String> keys = Set.of("sourceKind", "sourceRef", "observedAt", "recordedAt", "confirmedByMemberId", "confirmed");
        StrictJson.object(node, path, keys, keys);
        String kind = StrictJson.enumValue(node.get("sourceKind"), StrictJson.join(path, "sourceKind"), SOURCE_KINDS);
        String ref = StrictJson.text(node.get("sourceRef"), StrictJson.join(path, "sourceRef"), TEXT_MAX);
        Instant observedAt = StrictJson.nullableTime(node.get("observedAt"), StrictJson.join(path, "observedAt"));
        StrictJson.time(node.get("recordedAt"), StrictJson.join(path, "recordedAt"));
        StrictJson.nullableUuid(node.get("confirmedByMemberId"), StrictJson.join(path, "confirmedByMemberId"));
        StrictJson.bool(node.get("confirmed"), StrictJson.join(path, "confirmed"));
        return new EvidenceInput(kind, ref, observedAt, null);
    }

    /** 저장·응답용 V9Evidence. 키 순서는 계약과 같다. */
    public Map<String, Object> stored(UUID actorMemberId, Instant recordedAt) {
        boolean confirmed = !"SIMULATION_ASSUMPTION".equals(sourceKind);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceKind", sourceKind);
        out.put("sourceRef", sourceRef);
        out.put("observedAt", observedAt == null ? null : observedAt.toString());
        out.put("recordedAt", recordedAt.toString());
        out.put("confirmedByMemberId", confirmed ? actorMemberId.toString() : null);
        out.put("confirmed", confirmed);
        return out;
    }

    /** 요청 비교(같은 키 재요청)용 정규 문자열 */
    public String canonical() {
        return sourceKind + "\u0000" + sourceRef + "\u0000" + observedAt + "\u0000" + note;
    }
}

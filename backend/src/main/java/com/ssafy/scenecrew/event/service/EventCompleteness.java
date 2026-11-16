package com.ssafy.scenecrew.event.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.text.UnicodeText;

/**
 * 행사 기본정보 완결성 검사(V7-D11, be/04 "기본정보 완결성 검사"). publish(029)가 쓰고, 계획 생성 접수도 같은 검사를 쓴다.
 * 위반을 모두 모아 422 EVENT_INCOMPLETE와 details.fields[{field, reason}]로 돌려준다. DRAFT 부분 저장(024)은 검사하지 않는다.
 *
 * <ul>
 *   <li>MISSING: 필수 8필드(date·prepStartAt·openAt·closeAt·teardownEndAt·locationName·address·totalCapacity)가 비어 있음</li>
 *   <li>BLANK: locationName·address가 공백뿐</li>
 *   <li>ORDER: prepStartAt ≤ openAt &lt; closeAt ≤ teardownEndAt 위반. 뒤쪽 필드에 표시한다.</li>
 *   <li>DATE_MISMATCH: prepStartAt·teardownEndAt이 행사 현지(Asia/Seoul) 날짜로 date와 다름(하루 행사)</li>
 *   <li>RANGE: 1 ≤ totalCapacity ≤ 31, 0 ≤ desiredReserveCount &lt; totalCapacity, 관객 수는 값이 있으면 0 이상</li>
 * </ul>
 */
public final class EventCompleteness {
    /** 행사 현지 시간대. 서비스 기준(be/04) */
    public static final ZoneId EVENT_ZONE = ZoneId.of("Asia/Seoul");
    /** 총괄·팀원·예비를 합한 운영 인원 상한(P01) */
    public static final long MAX_CAPACITY = 31;

    private EventCompleteness() { }

    /** 검사할 기본정보 */
    public record Fields(LocalDate date, Instant prepStartAt, Instant openAt, Instant closeAt, Instant teardownEndAt,
            String locationName, String address, Long totalCapacity, Long expectedVisitors, Long peakVisitors,
            long desiredReserveCount) { }

    /** 위반이 있으면 422 EVENT_INCOMPLETE */
    public static void require(Fields f) {
        require(check(f));
    }

    /** 모은 위반(완결성·공개 계획 검사)이 있으면 422 EVENT_INCOMPLETE와 details.fields */
    static void require(List<Map<String, String>> violations) {
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.EVENT_INCOMPLETE, ErrorCode.EVENT_INCOMPLETE.getMessage(),
                    Map.of("fields", List.copyOf(violations)));
        }
    }

    /** 위반 목록. 필드 순서대로, 한 필드에 여러 위반이 있으면 모두 넣는다. */
    public static List<Map<String, String>> check(Fields f) {
        List<Map<String, String>> out = new ArrayList<>();
        missing(out, "date", f.date());
        missing(out, "prepStartAt", f.prepStartAt());
        missing(out, "openAt", f.openAt());
        missing(out, "closeAt", f.closeAt());
        missing(out, "teardownEndAt", f.teardownEndAt());
        text(out, "locationName", f.locationName());
        text(out, "address", f.address());
        missing(out, "totalCapacity", f.totalCapacity());

        if (f.prepStartAt() != null && f.openAt() != null && f.prepStartAt().isAfter(f.openAt())) add(out, "openAt", "ORDER");
        if (f.openAt() != null && f.closeAt() != null && !f.openAt().isBefore(f.closeAt())) add(out, "closeAt", "ORDER");
        if (f.closeAt() != null && f.teardownEndAt() != null && f.closeAt().isAfter(f.teardownEndAt()))
            add(out, "teardownEndAt", "ORDER");

        if (f.date() != null) {
            sameDate(out, "prepStartAt", f.prepStartAt(), f.date());
            sameDate(out, "teardownEndAt", f.teardownEndAt(), f.date());
        }

        Long capacity = f.totalCapacity();
        if (capacity != null && (capacity < 1 || capacity > MAX_CAPACITY)) add(out, "totalCapacity", "RANGE");
        if (f.desiredReserveCount() < 0 || capacity != null && f.desiredReserveCount() >= capacity)
            add(out, "desiredReserveCount", "RANGE");
        if (f.expectedVisitors() != null && f.expectedVisitors() < 0) add(out, "expectedVisitors", "RANGE");
        if (f.peakVisitors() != null && f.peakVisitors() < 0) add(out, "peakVisitors", "RANGE");
        return out;
    }

    private static void missing(List<Map<String, String>> out, String field, Object value) {
        if (value == null) add(out, field, "MISSING");
    }

    private static void text(List<Map<String, String>> out, String field, String value) {
        if (value == null) add(out, field, "MISSING");
        else if (UnicodeText.isBlank(value)) add(out, field, "BLANK");
    }

    private static void sameDate(List<Map<String, String>> out, String field, Instant at, LocalDate date) {
        if (at != null && !at.atZone(EVENT_ZONE).toLocalDate().equals(date)) add(out, field, "DATE_MISMATCH");
    }

    private static void add(List<Map<String, String>> out, String field, String reason) {
        out.add(Map.of("field", field, "reason", reason));
    }
}

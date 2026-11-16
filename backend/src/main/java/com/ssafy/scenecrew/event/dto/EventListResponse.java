package com.ssafy.scenecrew.event.dto;

import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * SC-API-021 응답 EventPage. 계약상 모든 필드가 필수라 값이 없어도 null로 보낸다.
 * 정수형 계약 값(revision·인원)은 DecimalBigInt라 숫자가 아니라 10진 문자열이다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EventListResponse(List<EventItem> items, String nextCursor) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"id", "name", "state", "revision", "date", "prepStartAt", "openAt", "closeAt", "teardownEndAt",
            "locationName", "address", "totalCapacity", "expectedVisitors", "peakVisitors", "desiredReserveCount",
            "myParticipation", "description", "instructions"})
    public record EventItem(UUID id, String name, String state, String revision, String date,
            String prepStartAt, String openAt, String closeAt, String teardownEndAt,
            String locationName, String address, String totalCapacity, String expectedVisitors, String peakVisitors,
            String desiredReserveCount, Participation myParticipation, String description, String instructions) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Participation(UUID memberId, String participationRevision, boolean active, String eventRole,
            UUID workGroupId) { }
}

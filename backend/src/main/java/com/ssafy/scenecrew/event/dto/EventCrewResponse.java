package com.ssafy.scenecrew.event.dto;

import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** SC-API-036 WorkGroupPage·SC-API-037 MemberPage. */
public final class EventCrewResponse {
    private EventCrewResponse() { }

    /** nextCursor는 계약상 항상 null(그룹은 최대 31개라 한 번에 준다). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkGroupPage(List<WorkGroup> items, String nextCursor) { }

    /**
     * instructions·active는 계약상 선택 필드다. 총괄에게는 모두 보내고,
     * 팀원에게는 자기 그룹의 안내만 보내며 active는 보내지 않는다(활성 그룹만 받으므로).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "name", "instructions", "active"})
    public record WorkGroup(UUID id, String name, String instructions, Boolean active) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MemberPage(List<Member> items, String nextCursor) { }

    /** 계약 Member의 필수 필드 전부. participationRevision은 DecimalBigInt(10진 문자열). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"memberId", "displayName", "workGroupId", "eventRole", "active", "available", "isReserve",
            "participationRevision"})
    public record Member(UUID memberId, String displayName, UUID workGroupId, String eventRole, boolean active,
            boolean available, @JsonProperty("isReserve") boolean isReserve, String participationRevision) { }
}

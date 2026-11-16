package com.ssafy.scenecrew.invite.dto;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public record JoinResult(Participation participation, String eventRevision, String initialAssignment) {
    // 총괄의 그룹 null도 응답 필수 필드이므로 전역 null 제외 설정과 관계없이 포함한다.
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Participation(UUID memberId, String participationRevision, boolean active,
                                String eventRole, UUID workGroupId) { }
}

package com.ssafy.scenecrew.plan.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * SC-API-028 초안 저장 요청(DraftPlanWrite)을 형식 검사한 값. 형식은 컨트롤러가, 참조·이름·시간 같은 의미 검사는
 * {@link DraftPlanService}가 행사를 잠근 뒤 한다.
 *
 * @param eventInstructions 행사 공통 안내(EVENT). 없으면 null(빈 안내로 저장)
 */
public record DraftPlanCommand(long expectedEventRevision, List<Group> workGroups, List<Zone> zones,
        List<Program> programs, List<Task> tasks, String eventInstructions) {

    /** 기존 항목이면 id, 새 항목이면 tempId. 둘 중 하나만 있다. 참조 값도 같은 모양이다. */
    public record Ref(UUID id, String tempId) {
        public static Ref existing(UUID id) { return new Ref(id, null); }
        public static Ref temp(String tempId) { return new Ref(null, tempId); }
        public boolean isTemp() { return tempId != null; }
    }

    public record Group(Ref key, String name, String instructions) { }

    /** description은 null이면 설명 없음이다(전체 저장에서 누락·null은 삭제). */
    public record Zone(Ref key, String name, String description) { }

    /** zone은 null일 수 있다. */
    public record Program(Ref key, String name, Ref zone, Instant startAt, Instant endAt) { }

    /** program은 null일 수 있다. */
    public record Task(Ref key, Ref workGroup, String bundleKey, Ref zone, Ref program, String title,
            Instant startAt, Instant endAt, List<String> steps, List<ChecklistItem> checklist) { }

    public record ChecklistItem(String key, String text, boolean required) { }

    /** 요청을 고쳐야 하는 오류(400 INVALID_REQUEST). details.fields는 @Valid 실패와 같은 모양이다. */
    public static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.getMessage(),
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}

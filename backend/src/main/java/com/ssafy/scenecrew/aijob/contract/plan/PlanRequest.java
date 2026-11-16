package com.ssafy.scenecrew.aijob.contract.plan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.ssafy.scenecrew.aijob.contract.CodePoints;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * BE → AI 최초 계획 생성 요청(요청4). 현재 DRAFT의 행사·구역·프로그램·기존 직무 그룹만 보낸다.
 * Member·담당자 같은 개인 정보는 넣지 않는다. floorPlan은 평면도 페이지(V9-D54)이고 없으면 null이다.
 */
public record PlanRequest(
        @JsonProperty(required = true) @NotNull UUID jobId,
        @JsonProperty(required = true) @NotNull @Min(1) Integer attempt,
        @JsonProperty(required = true) @NotNull @Pattern(regexp = "^4$") String requestSchemaVersion,
        @JsonProperty(required = true) @NotNull @Pattern(regexp = "^GENERATE_PLAN$") String kind,
        @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.SHA256) String inputHash,
        @JsonProperty(required = true) @NotNull @Min(0) Long contextRevision,
        @JsonProperty(required = true) @NotNull Instant deadlineAt,
        @JsonProperty(required = true) @NotNull @Valid Payload payload) {

    public record Payload(
            @JsonProperty(required = true) @NotNull @Valid Event event,
            @JsonProperty(required = true) @NotNull @Size(max = PlanContract.MAX_ZONES) List<@NotNull @Valid Zone> zones,
            @JsonProperty(required = true) @NotNull @Size(max = PlanContract.MAX_PROGRAMS) List<@NotNull @Valid Program> programs,
            @Size(max = PlanContract.MAX_WORK_GROUPS) List<@NotNull @Valid WorkGroup> workGroups,
            // 실제 요청(GeneratePlanKind.input)은 평면도가 없으면 null을 명시해 보낸다. 이 record는 읽기·예시 생성용이라 null이면 생략한다.
            @JsonInclude(JsonInclude.Include.NON_NULL) @Valid FloorPlan floorPlan,
            // 문서 추출이 읽은 업무 일정표 행(V9-D66). 없으면 키를 넣지 않는다. AI는 원문 시각대로 업무를 만든다.
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max = PlanContract.MAX_SCHEDULE_ITEMS)
            List<@NotNull @Valid ScheduleItem> scheduleItems) {

        public Payload {
            workGroups = workGroups == null ? List.of() : workGroups;
        }

        public Payload(Event event, List<Zone> zones, List<Program> programs, List<WorkGroup> workGroups) {
            this(event, zones, programs, workGroups, null, null);
        }

        public Payload(Event event, List<Zone> zones, List<Program> programs, List<WorkGroup> workGroups,
                FloorPlan floorPlan) {
            this(event, zones, programs, workGroups, floorPlan, null);
        }
    }

    /**
     * 업무 일정표 한 행(V9-D66, ai/plan/contract.py ScheduleItem). 시각을 확정할 수 없으면 startAt·endAt이 null이다.
     * source는 원문 문서·쪽이다. AI는 이 값으로 문서를 내려받지 않는다(근거 표시용).
     */
    public record ScheduleItem(
            @JsonProperty(required = true) @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{1,30}$") String key,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 100) String title,
            @JsonProperty(required = true) Instant startAt,
            @JsonProperty(required = true) Instant endAt,
            @Size(min = 1) @CodePoints(max = 40) String timeText,
            @Size(min = 1) @CodePoints(max = 20) String workGroupName,
            @Size(min = 1) @CodePoints(max = 30) String zoneName,
            @Size(min = 1) @CodePoints(max = 40) String bundle,
            @JsonProperty(required = true) @NotNull @Valid ScheduleSource source,
            // 원문 수행 절차·점검 항목(V9-D66). 있으면 그 업무의 steps·checklist가 된다. 없으면 키가 없다.
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 6)
            List<@NotBlank @CodePoints(max = 120) String> steps,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 6)
            List<@NotBlank @CodePoints(max = 80) String> checklist) {
    }

    public record ScheduleSource(
            @JsonProperty(required = true) @NotNull UUID documentId,
            @JsonProperty(required = true) @NotNull @Min(1) Integer pageNumber) {
    }

    /**
     * 평면도 페이지(V9-D54, S15P31A202-391). 문서 추출 결과3 floorPlanPages의 첫 유효 후보다.
     * AI는 url(업무 서버 /internal)에서 원본 PDF를 내려받아 sha256을 확인하고 pageNumber 쪽을 다시 이미지로 만든다.
     */
    public record FloorPlan(
            @JsonProperty(required = true) @NotNull UUID documentId,
            @JsonProperty(required = true) @NotNull @Min(1) Integer pageNumber,
            @JsonProperty(required = true) @NotBlank String mime,
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.SHA256) String sha256,
            @JsonProperty(required = true) @NotBlank String url) {
    }

    public record Event(
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 20) String name,
            @JsonProperty(required = true) @NotNull @CodePoints(max = 1000) String description,
            @JsonProperty(required = true) @NotNull LocalDate date,
            @JsonProperty(required = true) @NotNull Instant prepStartAt,
            @JsonProperty(required = true) @NotNull Instant openAt,
            @JsonProperty(required = true) @NotNull Instant closeAt,
            @JsonProperty(required = true) @NotNull Instant teardownEndAt,
            @JsonProperty(required = true) @NotNull @CodePoints(max = 1000) String locationName,
            @JsonProperty(required = true) @NotNull @CodePoints(max = 1000) String address,
            @JsonProperty(required = true) @NotNull @Min(1) @Max(PlanContract.MAX_MEMBERS) Integer totalCapacity,
            // DB·024 공개 API는 signed64다. 32비트로 줄이면 21억을 넘는 값에서 요청이 깨진다(#280 리뷰).
            @Min(0) Long expectedVisitors,
            @Min(0) Long peakVisitors,
            @JsonProperty(required = true) @NotNull @Min(0) @Max(PlanContract.MAX_MEMBERS) Integer desiredReserveCount,
            Map<String, Object> confirmedInput) {

        public Event {
            confirmedInput = confirmedInput == null ? Map.of() : confirmedInput;
        }
    }

    public record Zone(
            @JsonProperty(required = true) @NotNull UUID id,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 30) String name,
            @CodePoints(max = 1000) String description) {
    }

    public record Program(
            @JsonProperty(required = true) @NotNull UUID id,
            UUID zoneId,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 20) String name,
            @JsonProperty(required = true) @NotNull Instant startAt,
            @JsonProperty(required = true) @NotNull Instant endAt) {
    }

    public record WorkGroup(
            @JsonProperty(required = true) @NotNull UUID id,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 20) String name) {
    }
}

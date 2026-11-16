package com.ssafy.scenecrew.aijob.contract.plan;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.ssafy.scenecrew.aijob.contract.CodePoints;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * AI → BE 최초 계획 성공 응답(결과5·결과6). result.status가 READY면 계획 후보, NEEDS_INFO면 질문만 있다.
 * 결과6은 결과5에 선택 필드 venueDraft(평면도 초안, V9-D45)를 더한 것이다. 이번 범위는 layout만 생산하고
 * venueDraft.profiles는 생산하지 않는다. 결과5·6 모두 선택 필드 cameras·equipmentDraft.assets·workGroups[].purpose를 가질 수 있다
 * (V9-D58·D59·D61, #395). equipmentDraft는 assets만 있다. 형식 검사는 이 어노테이션으로, 참조·시간·묶음·평면도 검사는 PlanResultChecks로 한다.
 */
public record PlanSuccess(
        @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.UUID) String jobId,
        @JsonProperty(required = true) @NotNull @Min(1) Integer attempt,
        @JsonProperty(required = true) @NotNull @Pattern(regexp = "^4$") String requestSchemaVersion,
        @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.SHA256) String inputHash,
        @JsonProperty(required = true) @NotNull @Min(0) Long contextRevision,
        @JsonProperty(required = true) @NotNull @Pattern(regexp = "^[56]$") String resultSchemaVersion,
        @JsonProperty(required = true) @NotBlank @CodePoints(max = 200) String modelVersion,
        @JsonProperty(required = true) @NotNull @Valid Result result) {

    public enum Status { READY, NEEDS_INFO }

    public enum InstructionScope { EVENT, WORK_GROUP }

    public enum AgentRole { SUPERVISOR, PLANNING, SCHEDULE, STAFFING, GUIDE, REVIEW }

    public enum AgentTaskStatus { SUCCEEDED, NEEDS_INFO, FAILED }

    public record Result(
            @JsonProperty(required = true) @NotNull Status status,
            @JsonProperty(required = true) @NotNull @Size(max = PlanContract.MAX_WORK_GROUPS) List<@NotNull @Valid WorkGroup> workGroups,
            @JsonProperty(required = true) @NotNull @Size(max = PlanContract.MAX_ZONES) List<@NotNull @Valid Zone> zones,
            @JsonProperty(required = true) @NotNull @Size(max = PlanContract.MAX_PROGRAMS) List<@NotNull @Valid Program> programs,
            @JsonProperty(required = true) @NotNull @Size(max = PlanContract.MAX_TASKS) List<@NotNull @Valid Task> tasks,
            @JsonProperty(required = true) @NotNull List<@NotNull @Valid Instruction> instructions,
            @JsonProperty(required = true) @NotNull List<@NotNull @Valid Bundle> bundles,
            @JsonProperty(required = true) @NotNull List<@NotNull @Valid Assumption> assumptions,
            @JsonProperty(required = true) @NotNull List<@NotNull @Valid Unresolved> unresolved,
            @JsonProperty(required = true) @NotNull List<@NotNull @Valid Question> questions,
            @JsonProperty(required = true) @NotNull @Valid Coordination coordination,
            @Valid VenueDraft venueDraft,
            @Valid EquipmentDraft equipmentDraft,
            @Size(max = PlanContract.MAX_CAMERAS) List<@NotNull @Valid Camera> cameras) {

        public Result(Status status, List<WorkGroup> workGroups, List<Zone> zones, List<Program> programs, List<Task> tasks,
                List<Instruction> instructions, List<Bundle> bundles, List<Assumption> assumptions,
                List<Unresolved> unresolved, List<Question> questions, Coordination coordination) {
            this(status, workGroups, zones, programs, tasks, instructions, bundles, assumptions, unresolved, questions,
                    coordination, null, null, null);
        }

        public Result {
            cameras = cameras == null ? List.of() : cameras;
        }

        /** 문서에서 뽑은 장비. 키가 없으면 빈 목록 */
        public List<Asset> assets() {
            return equipmentDraft == null ? List.of() : equipmentDraft.assets();
        }

        /** 읽은 평면도. 결과5·평면도 없음(venueDraft 또는 layout이 null)이면 null */
        public Layout layout() {
            return venueDraft == null ? null : venueDraft.layout();
        }
    }

    public enum Purpose { SAFETY, EQUIPMENT }

    public enum AutoObservationKind { QLXD4 }

    /** 문서에 적힌 객석 카메라(V9-D58). zoneName은 같은 결과 zones의 이름이다(PlanResultChecks.devices). */
    public record Camera(
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 100) String name,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 30) String zoneName) {
    }

    /** 장비 초안. 이번 범위는 assets만 생산한다(V9-D45·D59). */
    public record EquipmentDraft(
            @JsonProperty(required = true) @NotNull @Size(max = PlanContract.MAX_ASSETS) List<@NotNull @Valid Asset> assets) {
    }

    /** 문서에 적힌 장비 한 대. autoObservationKind는 모델명이 QLXD4인 무선 수신기만 QLXD4다(V9-D59). */
    public record Asset(
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 100) String name,
            @CodePoints(max = 100) String kind,
            @CodePoints(max = 100) String model,
            @CodePoints(max = 200) String location,
            AutoObservationKind autoObservationKind) {
    }

    /** 결과6 평면도 초안. profiles는 이번 범위에서 생산하지 않으므로 비어 있어야 한다. */
    public record VenueDraft(
            @Valid Layout layout,
            @Size(max = 0) List<Object> profiles) {
    }

    /**
     * 평면도에서 읽은 구역 배치(ai/plan contract.VenueLayout). 좌표는 페이지 그림 기준 0~1 정규화 값이다.
     * source는 읽은 평면도 문서·쪽(요청 floorPlan과 같아야 한다), width·height는 AI가 다시 그린 그림의 픽셀 크기(가로세로 비율용)다.
     * 숫자는 Number로 받는다. AI 계약 JSON은 정수→실수 변환을 막으므로 Double이면 0·1·1200 같은 정수 표기가 거절된다.
     */
    public record Layout(
            @JsonProperty(required = true) @NotNull @Valid Source source,
            @JsonProperty(required = true) @NotNull @DecimalMin(value = "0", inclusive = false) Number width,
            @JsonProperty(required = true) @NotNull @DecimalMin(value = "0", inclusive = false) Number height,
            @JsonProperty(required = true) @NotNull @Size(min = 1, max = PlanContract.MAX_ZONES) List<@NotNull @Valid LayoutZone> zones) {
    }

    /** 평면도를 읽은 문서·쪽. 요청 floorPlan의 documentId·pageNumber와 같다(PlanResultChecks.venue) */
    public record Source(
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.UUID) String documentId,
            @JsonProperty(required = true) @NotNull @Min(1) Integer pageNumber) {
    }

    /** 구역 하나의 사각형. name은 같은 결과의 zones 이름과 맞춘다. */
    public record LayoutZone(
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 30) String name,
            @JsonProperty(required = true) @NotNull @DecimalMin("0") @DecimalMax("1") Number x,
            @JsonProperty(required = true) @NotNull @DecimalMin("0") @DecimalMax("1") Number y,
            @JsonProperty(required = true) @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Number width,
            @JsonProperty(required = true) @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Number height) {

        public double left() { return x.doubleValue(); }
        public double top() { return y.doubleValue(); }
        public double right() { return x.doubleValue() + width.doubleValue(); }
        public double bottom() { return y.doubleValue() + height.doubleValue(); }
    }

    /**
     * id(요청에 있던 기존 그룹)와 tempId(새 그룹) 중 정확히 하나를 갖는다.
     * purpose는 연결 업무를 넣을 그룹 표시(SAFETY·EQUIPMENT, 선택 필드, #395)이며 BE 규칙이 그룹을 고를 때만 쓴다.
     */
    public record WorkGroup(
            @Pattern(regexp = PlanContract.UUID) String id,
            @Pattern(regexp = PlanContract.TEMP_ID) String tempId,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 20) String name,
            Purpose purpose) {

        public WorkGroup(String id, String tempId, String name) {
            this(id, tempId, name, null);
        }

        public String ref() {
            return id != null ? id : tempId;
        }
    }

    public record Zone(
            @Pattern(regexp = PlanContract.UUID) String id,
            @Pattern(regexp = PlanContract.TEMP_ID) String tempId,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 30) String name,
            @CodePoints(max = 1000) String description) {

        public String ref() {
            return id != null ? id : tempId;
        }
    }

    public record Program(
            @Pattern(regexp = PlanContract.UUID) String id,
            @Pattern(regexp = PlanContract.TEMP_ID) String tempId,
            @Pattern(regexp = PlanContract.REF) String zoneId,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 20) String name,
            @JsonProperty(required = true) @NotNull Instant startAt,
            @JsonProperty(required = true) @NotNull Instant endAt) {

        public String ref() {
            return id != null ? id : tempId;
        }
    }

    /** 업무 후보. 담당자는 AI가 정하지 않으므로 assigneeId는 항상 null이다. */
    public record Task(
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.TEMP_ID) String tempId,
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.REF) String workGroupId,
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.KEY) String bundleKey,
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.REF) String zoneId,
            @Pattern(regexp = PlanContract.REF) String programId,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 100) String title,
            @JsonProperty(required = true) @NotNull Instant startAt,
            @JsonProperty(required = true) @NotNull Instant endAt,
            @JsonProperty(required = true) @NotNull @Size(min = 1, max = 6) List<@NotBlank @CodePoints(max = 120) String> steps,
            @Size(max = 6) List<@NotNull @Valid ChecklistItem> checklist,
            @Null Object assigneeId) {

        public Task {
            checklist = checklist == null ? List.of() : checklist;
        }
    }

    public record ChecklistItem(
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.KEY) String key,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 80) String text,
            @JsonProperty(required = true) @NotNull Boolean required) {
    }

    /** EVENT 안내는 targetId가 null, WORK_GROUP 안내는 결과의 그룹을 가리킨다. */
    public record Instruction(
            @JsonProperty(required = true) @NotNull InstructionScope scope,
            @Pattern(regexp = PlanContract.REF) String targetId,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 1000) String body) {
    }

    /** 한 사람이 맡을 업무 묶음의 요약. tasks를 bundleKey로 집계한 값과 같아야 한다. */
    public record Bundle(
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.KEY) String bundleKey,
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.REF) String workGroupId,
            @JsonProperty(required = true) @NotNull @Min(1) Integer taskCount,
            @JsonProperty(required = true) @NotNull Instant startAt,
            @JsonProperty(required = true) @NotNull Instant endAt) {
    }

    public record Question(
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.KEY) String key,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 200) String field,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 200) String question) {
    }

    public record Assumption(
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.KEY) String key,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 200) String statement,
            List<@NotNull String> refs) {

        public Assumption {
            refs = refs == null ? List.of() : refs;
        }
    }

    public record Unresolved(
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.KEY) String key,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 200) String description,
            List<@NotNull String> refs) {

        public Unresolved {
            refs = refs == null ? List.of() : refs;
        }
    }

    /** AI 내부 협업 기록(역할·재위임·근거·충돌 해결·기여). 원시 대화는 받지 않는다. */
    public record Coordination(
            List<@NotNull @Valid AgentTaskNode> graph,
            List<@NotNull @Valid Redelegation> redelegations,
            List<@NotNull String> evidenceRefs,
            List<@NotNull @Valid ResolvedConflict> resolvedConflicts,
            List<@NotNull @Valid Contribution> contributions) {

        public Coordination {
            graph = graph == null ? List.of() : graph;
            redelegations = redelegations == null ? List.of() : redelegations;
            evidenceRefs = evidenceRefs == null ? List.of() : evidenceRefs;
            resolvedConflicts = resolvedConflicts == null ? List.of() : resolvedConflicts;
            contributions = contributions == null ? List.of() : contributions;
        }
    }

    public record AgentTaskNode(
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.KEY) String taskId,
            @Pattern(regexp = PlanContract.KEY) String parentTaskId,
            @JsonProperty(required = true) @NotNull AgentRole role,
            @JsonProperty(required = true) @NotNull AgentTaskStatus status) {
    }

    public record Redelegation(
            @JsonProperty(required = true) @NotNull @Pattern(regexp = PlanContract.KEY) String taskId,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 200) String reason) {
    }

    public record ResolvedConflict(
            @JsonProperty(required = true) @NotNull @Size(min = 1) List<@NotNull String> refs,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 200) String resolution) {
    }

    public record Contribution(
            @JsonProperty(required = true) @NotNull AgentRole role,
            @JsonProperty(required = true) @NotBlank @CodePoints(max = 200) String summary) {
    }
}

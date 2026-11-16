package com.ssafy.scenecrew.aijob.contract.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ssafy.scenecrew.aijob.contract.ContractIssue;
import com.ssafy.scenecrew.global.text.UnicodeText;

/**
 * 형식 검사를 통과한 AI 응답을 요청 기준으로 다시 검사한다. ai/plan/contract.py의 모델 검사와 result_issues를 옮겼다.
 * AI 쪽 검사를 믿지 않고 BE가 같은 기준으로 한 번 더 확인해, 계약을 벗어난 결과가 저장되지 않게 한다.
 */
final class PlanResultChecks {
    static final Set<String> REVIEW_FAILED_REASONS = Set.of("REVIEW_REJECTED", "BUDGET_EXHAUSTED", "INVALID_ARTIFACT");

    private PlanResultChecks() {
    }

    // ---- 식별 대조 ----

    static List<ContractIssue> echo(PlanRequest req, String jobId, Integer attempt, String requestSchemaVersion,
            String inputHash, Long contextRevision) {
        List<ContractIssue> out = new ArrayList<>();
        echo(out, "jobId", req.jobId().toString(), jobId);
        echo(out, "attempt", req.attempt(), attempt);
        echo(out, "requestSchemaVersion", req.requestSchemaVersion(), requestSchemaVersion);
        echo(out, "inputHash", req.inputHash(), inputHash);
        echo(out, "contextRevision", req.contextRevision(), contextRevision);
        return out;
    }

    private static void echo(List<ContractIssue> out, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            out.add(new ContractIssue("response." + field, "ECHO_MISMATCH", "요청의 값을 그대로 반환해야 합니다."));
        }
    }

    // ---- 실패 응답 ----

    static List<ContractIssue> failure(PlanFailure res) {
        List<ContractIssue> out = new ArrayList<>();
        PlanFailure.ErrorBody error = res.error();
        if (error.retryable() != error.code().retryable()) {
            out.add(new ContractIssue("response.error.retryable", "SCHEMA",
                    error.code() + "의 retryable은 " + error.code().retryable() + "입니다."));
        }
        if (error.code() == PlanErrorCode.AI_REVIEW_FAILED
                && !REVIEW_FAILED_REASONS.contains(String.valueOf(error.details().get("reason")))) {
            out.add(new ContractIssue("response.error.details.reason", "SCHEMA",
                    "AI_REVIEW_FAILED는 details.reason이 " + REVIEW_FAILED_REASONS + " 중 하나여야 합니다."));
        }
        boolean missingIdentity = res.jobId() == null || res.attempt() == null || res.requestSchemaVersion() == null
                || res.inputHash() == null || res.contextRevision() == null;
        if (missingIdentity && error.code() != PlanErrorCode.INVALID_REQUEST) {
            out.add(new ContractIssue("response", "SCHEMA", "접수 뒤 실패는 식별 필드를 모두 반환해야 합니다."));
        }
        return out;
    }

    // ---- 성공 응답 ----

    static List<ContractIssue> success(PlanRequest req, PlanSuccess.Result result) {
        List<ContractIssue> out = new ArrayList<>(shape(result));
        if (!out.isEmpty()) {
            return out;
        }
        out.addAll(references(req, result));
        return out;
    }

    /** 사각형 끝이 1을 넘는지 볼 때의 부동소수 여유 */
    static final double EDGE_TOLERANCE = 1e-9;

    /**
     * 결과6 평면도(V9-D45·V9-D54). 결과5에는 venueDraft가 없다. 평면도를 보내지 않았거나 NEEDS_INFO면 layout이 없어야 하고,
     * layout.source는 요청 floorPlan의 문서·쪽과 같아야 한다(AI venue_issues와 같은 검사).
     * 구역 이름은 같은 결과 zones에 있고 한 번만 나오며, 사각형은 페이지(0~1) 안이다.
     */
    static List<ContractIssue> venue(PlanRequest req, String resultSchemaVersion, PlanSuccess.Result r) {
        List<ContractIssue> out = new ArrayList<>();
        if (PlanContract.RESULT_SCHEMA_VERSION.equals(resultSchemaVersion)) {
            if (r.venueDraft() != null) {
                out.add(new ContractIssue("result.venueDraft", "SCHEMA", "결과5에는 venueDraft가 없습니다."));
            }
            return out;
        }
        PlanSuccess.Layout layout = r.layout();
        if (layout == null) {
            return out;
        }
        PlanRequest.FloorPlan floorPlan = req.payload().floorPlan();
        if (floorPlan == null) {
            out.add(new ContractIssue("result.venueDraft.layout", "SCHEMA", "평면도를 보내지 않은 요청에는 평면도 초안이 없어야 합니다."));
        } else if (!floorPlan.documentId().toString().equals(layout.source().documentId())
                || !floorPlan.pageNumber().equals(layout.source().pageNumber())) {
            out.add(new ContractIssue("result.venueDraft.layout.source", "INVALID_REFERENCE", "요청의 평면도 문서·쪽과 다릅니다."));
        }
        if (r.status() == PlanSuccess.Status.NEEDS_INFO) {
            out.add(new ContractIssue("result.venueDraft.layout", "SCHEMA", "NEEDS_INFO에는 평면도 초안을 넣지 않습니다."));
        }
        Set<String> names = r.zones().stream().map(z -> UnicodeText.nameKey(z.name())).collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < layout.zones().size(); i++) {
            PlanSuccess.LayoutZone z = layout.zones().get(i);
            String path = "result.venueDraft.layout.zones[" + i + "]";
            String key = UnicodeText.nameKey(z.name());
            if (!names.contains(key)) {
                out.add(new ContractIssue(path + ".name", "INVALID_REFERENCE", "같은 결과의 구역 이름이 아닙니다."));
            } else if (!seen.add(key)) {
                out.add(new ContractIssue(path + ".name", "DUPLICATE", "같은 구역이 두 번 나옵니다."));
            }
            if (z.right() > 1 + EDGE_TOLERANCE || z.bottom() > 1 + EDGE_TOLERANCE) {
                out.add(new ContractIssue(path, "SCHEMA", "사각형이 평면도(0~1) 밖으로 나갑니다."));
            }
        }
        return out;
    }

    /**
     * 카메라·장비 선택 필드(결과5·6, V9-D58·D59·D61, #395). AI device_issues와 같은 검사다.
     * NEEDS_INFO에는 싣지 않고, 카메라 zoneName은 같은 결과 zones의 이름이며, 카메라·장비 이름은 겹치지 않는다.
     */
    static List<ContractIssue> devices(PlanSuccess.Result r) {
        List<ContractIssue> out = new ArrayList<>();
        if (r.status() == PlanSuccess.Status.NEEDS_INFO) {
            if (!r.cameras().isEmpty() || r.equipmentDraft() != null) {
                out.add(new ContractIssue("result.cameras", "SCHEMA", "NEEDS_INFO에는 카메라·장비 초안을 싣지 않습니다."));
            }
            return out;
        }
        Set<String> zones = r.zones().stream().map(z -> UnicodeText.nameKey(z.name())).collect(Collectors.toSet());
        Set<String> cameras = new HashSet<>();
        for (int i = 0; i < r.cameras().size(); i++) {
            PlanSuccess.Camera c = r.cameras().get(i);
            String path = "result.cameras[" + i + "]";
            if (!zones.contains(UnicodeText.nameKey(c.zoneName()))) {
                out.add(new ContractIssue(path + ".zoneName", "INVALID_REFERENCE", "결과의 구역 이름이 아닙니다."));
            }
            if (!cameras.add(UnicodeText.nameKey(c.name()))) {
                out.add(new ContractIssue(path + ".name", "DUPLICATE", "같은 이름의 카메라가 이미 있습니다."));
            }
        }
        Set<String> assets = new HashSet<>();
        for (int i = 0; i < r.assets().size(); i++) {
            if (!assets.add(UnicodeText.nameKey(r.assets().get(i).name()))) {
                out.add(new ContractIssue("result.equipmentDraft.assets[" + i + "].name", "DUPLICATE",
                        "같은 이름의 장비가 이미 있습니다."));
            }
        }
        return out;
    }

    /** contract.py의 모델 검사: id/tempId 하나만, 안내 대상, READY/NEEDS_INFO 분기 */
    private static List<ContractIssue> shape(PlanSuccess.Result r) {
        List<ContractIssue> out = new ArrayList<>();
        oneRef(out, r.workGroups(), "result.workGroups", PlanSuccess.WorkGroup::id, PlanSuccess.WorkGroup::tempId);
        oneRef(out, r.zones(), "result.zones", PlanSuccess.Zone::id, PlanSuccess.Zone::tempId);
        oneRef(out, r.programs(), "result.programs", PlanSuccess.Program::id, PlanSuccess.Program::tempId);
        for (int i = 0; i < r.instructions().size(); i++) {
            PlanSuccess.Instruction ins = r.instructions().get(i);
            if ((ins.scope() == PlanSuccess.InstructionScope.EVENT) != (ins.targetId() == null)) {
                out.add(new ContractIssue("result.instructions[" + i + "]", "SCHEMA",
                        "EVENT 안내는 targetId가 null, WORK_GROUP 안내는 그룹 참조가 필요합니다."));
            }
        }
        boolean hasPlan = !(r.workGroups().isEmpty() && r.zones().isEmpty() && r.programs().isEmpty()
                && r.tasks().isEmpty() && r.instructions().isEmpty() && r.bundles().isEmpty());
        if (r.status() == PlanSuccess.Status.NEEDS_INFO) {
            if (r.questions().isEmpty()) {
                out.add(new ContractIssue("result.questions", "SCHEMA", "NEEDS_INFO는 질문이 하나 이상 필요합니다."));
            }
            if (hasPlan) {
                out.add(new ContractIssue("result", "SCHEMA", "NEEDS_INFO는 부분 계획을 반환하지 않습니다. 계획 배열을 비워야 합니다."));
            }
        } else {
            if (!r.questions().isEmpty()) {
                out.add(new ContractIssue("result.questions", "SCHEMA", "READY에는 질문을 넣지 않습니다."));
            }
            if (r.workGroups().isEmpty() || r.tasks().isEmpty()) {
                out.add(new ContractIssue("result", "SCHEMA", "READY는 직무 그룹과 업무가 하나 이상 필요합니다."));
            }
        }
        return out;
    }

    private static <T> void oneRef(List<ContractIssue> out, List<T> items, String path,
            Function<T, String> id, Function<T, String> tempId) {
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            if ((id.apply(item) == null) == (tempId.apply(item) == null)) {
                out.add(new ContractIssue(path + "[" + i + "]", "SCHEMA",
                        "id(기존 DRAFT 항목)와 tempId(새 항목) 중 정확히 하나가 필요합니다."));
            }
        }
    }

    /** contract.py의 result_issues: 참조·이름 중복·시간·묶음·요약 일치 */
    private static List<ContractIssue> references(PlanRequest req, PlanSuccess.Result r) {
        PlanRequest.Event event = req.payload().event();
        List<ContractIssue> out = new ArrayList<>();

        Set<String> zones = refs(out, r.zones(), "result.zones", PlanSuccess.Zone::id, PlanSuccess.Zone::ref,
                ids(req.payload().zones(), z -> z.id().toString()));
        Set<String> programs = refs(out, r.programs(), "result.programs", PlanSuccess.Program::id, PlanSuccess.Program::ref,
                ids(req.payload().programs(), p -> p.id().toString()));
        Set<String> groups = refs(out, r.workGroups(), "result.workGroups", PlanSuccess.WorkGroup::id, PlanSuccess.WorkGroup::ref,
                ids(req.payload().workGroups(), g -> g.id().toString()));
        // 이름 유일은 구역·직무 그룹만. 프로그램 이름은 같은 행사에서 겹칠 수 있다.
        // 028과 같이 공백을 빼고 대소문자를 무시해 비교한다(UnicodeText.nameKey). 원문 비교면 READY인데 저장할 수 없는 후보가 생긴다.
        duplicates(out, r.zones(), "result.zones", z -> UnicodeText.nameKey(z.name()), "name");
        duplicates(out, r.workGroups(), "result.workGroups", g -> UnicodeText.nameKey(g.name()), "name");
        duplicates(out, r.tasks(), "result.tasks", PlanSuccess.Task::tempId, "tempId");
        tempIdsOnce(out, r);

        for (int i = 0; i < r.programs().size(); i++) {
            PlanSuccess.Program p = r.programs().get(i);
            String path = "result.programs[" + i + "]";
            if (p.zoneId() != null && !zones.contains(p.zoneId())) {
                out.add(new ContractIssue(path + ".zoneId", "INVALID_REFERENCE", "결과의 구역 목록에 없는 참조입니다."));
            }
            window(out, path, p.startAt(), p.endAt(), event.openAt(), event.closeAt(), "운영");
        }

        Map<String, PlanSuccess.Program> byProgram = new HashMap<>();
        r.programs().forEach(p -> byProgram.put(p.ref(), p));
        Map<String, List<Integer>> byBundle = new LinkedHashMap<>();
        for (int i = 0; i < r.tasks().size(); i++) {
            PlanSuccess.Task t = r.tasks().get(i);
            String path = "result.tasks[" + i + "]";
            reference(out, path + ".workGroupId", t.workGroupId(), groups);
            reference(out, path + ".zoneId", t.zoneId(), zones);
            reference(out, path + ".programId", t.programId(), programs);
            window(out, path, t.startAt(), t.endAt(), event.prepStartAt(), event.teardownEndAt(), "준비~정리");
            programLink(out, path, t, byProgram.get(t.programId()));
            duplicates(out, t.checklist(), path + ".checklist", PlanSuccess.ChecklistItem::key, "key");
            byBundle.computeIfAbsent(t.bundleKey(), k -> new ArrayList<>()).add(i);
        }

        bundles(out, r, byBundle);
        instructions(out, r, groups);
        return out;
    }

    /** 기존 id는 요청에 있는 항목만 허용한다. tempId는 결과 안에서 새로 정의한다. */
    private static <T> Set<String> refs(List<ContractIssue> out, List<T> items, String path,
            Function<T, String> id, Function<T, String> ref, Set<String> known) {
        for (int i = 0; i < items.size(); i++) {
            String value = id.apply(items.get(i));
            if (value != null && !known.contains(value)) {
                out.add(new ContractIssue(path + "[" + i + "].id", "INVALID_REFERENCE",
                        "요청의 현재 DRAFT에 없는 id입니다. 새 항목은 tempId를 씁니다."));
            }
        }
        duplicates(out, items, path, ref, "id/tempId");
        return items.stream().map(ref).collect(Collectors.toSet());
    }

    /** tempId는 결과 전체에서 한 번만 나온다. 028이 요청 전체에서 한 번만 받으므로 그룹·구역·공연·업무 사이 중복도 막는다. */
    private static void tempIdsOnce(List<ContractIssue> out, PlanSuccess.Result r) {
        Set<String> seen = new HashSet<>();
        tempIds(out, seen, r.workGroups(), "result.workGroups", PlanSuccess.WorkGroup::tempId);
        tempIds(out, seen, r.zones(), "result.zones", PlanSuccess.Zone::tempId);
        tempIds(out, seen, r.programs(), "result.programs", PlanSuccess.Program::tempId);
        tempIds(out, seen, r.tasks(), "result.tasks", PlanSuccess.Task::tempId);
    }

    private static <T> void tempIds(List<ContractIssue> out, Set<String> seen, List<T> items, String path,
            Function<T, String> tempId) {
        for (int i = 0; i < items.size(); i++) {
            String value = tempId.apply(items.get(i));
            if (value != null && !seen.add(value)) {
                out.add(new ContractIssue(path + "[" + i + "].tempId", "DUPLICATE",
                        "tempId " + value + "이(가) 결과 전체에서 중복됩니다."));
            }
        }
    }

    private static <T> Set<String> ids(List<T> items, Function<T, String> id) {
        return items.stream().map(id).collect(Collectors.toSet());
    }

    private static void reference(List<ContractIssue> out, String path, String ref, Set<String> known) {
        if (ref != null && !known.contains(ref)) {
            out.add(new ContractIssue(path, "INVALID_REFERENCE", "결과 목록에 없는 참조입니다."));
        }
    }

    private static <T> void duplicates(List<ContractIssue> out, List<T> items, String path, Function<T, String> key,
            String label) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String k = key.apply(items.get(i));
            if (!seen.add(k)) {
                out.add(new ContractIssue(path + "[" + i + "]", "DUPLICATE", label + " " + k + "이(가) 중복됩니다."));
            }
        }
    }

    private static void window(List<ContractIssue> out, String path, Instant startAt, Instant endAt,
            Instant from, Instant to, String label) {
        if (!startAt.isBefore(endAt)) {
            out.add(new ContractIssue(path, "TIME_OUT_OF_RANGE", "startAt이 endAt보다 앞서야 합니다."));
        } else if (startAt.isBefore(from) || endAt.isAfter(to)) {
            out.add(new ContractIssue(path, "TIME_OUT_OF_RANGE", "행사 " + label + " 구간을 벗어납니다."));
        }
    }

    /** 프로그램을 참조한 업무는 프로그램 구역에 있고 프로그램 시간과 일부라도 겹친다. 준비·정리 업무는 programId 없이 둔다. */
    private static void programLink(List<ContractIssue> out, String path, PlanSuccess.Task t, PlanSuccess.Program p) {
        if (p == null) {
            return;
        }
        if (p.zoneId() != null && !t.zoneId().equals(p.zoneId())) {
            out.add(new ContractIssue(path + ".zoneId", "INVALID_REFERENCE", "참조한 프로그램의 구역과 다릅니다."));
        }
        if (!(t.startAt().isBefore(p.endAt()) && p.startAt().isBefore(t.endAt()))) {
            out.add(new ContractIssue(path, "TIME_OUT_OF_RANGE",
                    "참조한 프로그램 시간과 겹치지 않습니다. 준비·정리 업무는 programId 없이 둡니다."));
        }
    }

    /** 한 묶음은 한 사람이 맡으므로 같은 그룹이고 시간이 겹치지 않는다. bundles 요약은 tasks 집계와 같다. */
    private static void bundles(List<ContractIssue> out, PlanSuccess.Result r, Map<String, List<Integer>> byBundle) {
        Map<String, BundleKey> expected = new HashMap<>();
        byBundle.forEach((key, indexes) -> {
            List<PlanSuccess.Task> tasks = indexes.stream().map(r.tasks()::get).toList();
            if (tasks.stream().map(PlanSuccess.Task::workGroupId).distinct().count() > 1) {
                out.add(new ContractIssue("result.tasks[" + indexes.get(0) + "].bundleKey", "BUNDLE_MISMATCH",
                        "묶음 " + key + "의 업무가 서로 다른 직무 그룹에 있습니다."));
            }
            List<Integer> ordered = indexes.stream()
                    .sorted(Comparator.comparing(i -> r.tasks().get(i).startAt())).toList();
            for (int n = 1; n < ordered.size(); n++) {
                PlanSuccess.Task a = r.tasks().get(ordered.get(n - 1));
                PlanSuccess.Task b = r.tasks().get(ordered.get(n));
                if (b.startAt().isBefore(a.endAt())) {
                    out.add(new ContractIssue("result.tasks[" + ordered.get(n) + "]", "TIME_OVERLAP",
                            "묶음 " + key + " 안에서 " + a.tempId() + "와 시간이 겹칩니다. 한 사람이 전담할 수 없습니다."));
                }
            }
            Instant start = tasks.stream().map(PlanSuccess.Task::startAt).min(Instant::compareTo).orElseThrow();
            Instant end = tasks.stream().map(PlanSuccess.Task::endAt).max(Instant::compareTo).orElseThrow();
            expected.put(key, new BundleKey(tasks.get(0).workGroupId(), tasks.size(), start, end));
        });

        duplicates(out, r.bundles(), "result.bundles", PlanSuccess.Bundle::bundleKey, "bundleKey");
        Map<String, BundleKey> actual = new HashMap<>();
        r.bundles().forEach(b -> actual.put(b.bundleKey(),
                new BundleKey(b.workGroupId(), b.taskCount(), b.startAt(), b.endAt())));
        if (!actual.equals(expected)) {
            out.add(new ContractIssue("result.bundles", "BUNDLE_MISMATCH",
                    "bundles 요약이 tasks의 bundleKey·workGroupId 집계와 다릅니다."));
        }
    }

    private record BundleKey(String workGroupId, int taskCount, Instant startAt, Instant endAt) {
    }

    /** 안내는 EVENT 1개, 그룹별 1개까지다. */
    private static void instructions(List<ContractIssue> out, PlanSuccess.Result r, Set<String> groups) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < r.instructions().size(); i++) {
            PlanSuccess.Instruction ins = r.instructions().get(i);
            String path = "result.instructions[" + i + "]";
            if (ins.scope() == PlanSuccess.InstructionScope.WORK_GROUP && !groups.contains(ins.targetId())) {
                out.add(new ContractIssue(path + ".targetId", "INVALID_REFERENCE", "결과의 직무 그룹이 아닙니다."));
            }
            if (!seen.add(ins.scope() + "|" + ins.targetId())) {
                out.add(new ContractIssue(path, "DUPLICATE", "같은 대상의 안내가 이미 있습니다."));
            }
        }
    }
}

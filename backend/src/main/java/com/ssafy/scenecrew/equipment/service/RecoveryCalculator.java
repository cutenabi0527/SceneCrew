package com.ssafy.scenecrew.equipment.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.ssafy.scenecrew.change.dto.ChangeResponses.DiffItem;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.Assumption;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.Candidate;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.EvidenceRef;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.Operation;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.Payload;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.ProgramImpact;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.ReservationWrite;

/**
 * 복구 대안 계산 규칙 recovery-rule-v1(V9-D44). DB를 읽지 않는 순수 계산이라 같은 입력이면 같은 후보·순서가 나온다.
 *
 * <p>고장 장비마다 그 장비를 assetIds·compatibleAssetIds에 둔 필요 조건만 본다. 후보는 그 조건에 명시된 같은 행사 장비이고
 * 이름·모델로 추측하지 않는다(BE13). 이동·설치·재시험 시간은 저장된 곳이 없어 0으로 채우지 않으므로 readyAt·slackSec·shortfallSec은
 * null이고, 겹침이 없는 후보도 FEASIBLE이 아니라 CONDITIONAL이다. 계산은 아무것도 바꾸지 않는다.
 */
public final class RecoveryCalculator {
    public static final String POLICY_VERSION = "recovery-rule-v1";
    static final String TIME_UNKNOWN = "이동·설치·재시험 시간이 저장돼 있지 않아 준비 완료 시각을 확인해야 합니다.";
    static final String NOT_VERIFIED = "장비 상태가 사용 가능으로 확인되지 않았습니다. 사용 전 점검이 필요합니다.";
    static final String UNUSABLE = "장비가 사용 불가 상태입니다.";
    static final String CONFLICT = "같은 시간에 이 장비나 구성품을 쓰는 다른 예약이 있습니다.";
    static final String CONTAINS_TARGET = "고장 장비를 구성품으로 포함합니다.";
    static final String OTHER_TARGET_REMAINS = "같은 예약에 다른 고장 장비가 남아 있습니다. 그 장비의 대안도 함께 정해야 합니다.";
    static final String WINDOW_UNKNOWN = "필요 조건에 연결된 업무·공연 시간이 없어 점유 구간을 알 수 없습니다.";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(KST);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** PostgreSQL uuid 정렬(바이트 순)과 같은 순서. UUID.compareTo는 부호 있는 비교라 DB와 다르다. */
    static final Comparator<UUID> DB_ORDER = Comparator.comparing(UUID::toString);
    private static final List<String> RANK = List.of("FEASIBLE", "CONDITIONAL", "UNDETERMINED", "INFEASIBLE");

    private RecoveryCalculator() { }

    public record Asset(UUID id, String name, String technicalState, List<UUID> components, long revision) { }

    public record Item(List<UUID> assetIds, List<UUID> compatibleAssetIds) { }

    /** windowStart·windowEnd는 연결된 업무(취소 제외)·공연 시간의 최소 시작·최대 끝이다. 없으면 null */
    public record Requirement(UUID id, long revision, UUID programId, String programName, List<UUID> taskIds,
            List<Item> items, Instant readyBy, Instant windowStart, Instant windowEnd) { }

    /** PLANNED·ACTIVE 예약만 넣는다. */
    public record Reservation(UUID id, long revision, String status, Instant startAt, Instant endAt, List<UUID> assetIds,
            UUID programId, UUID taskId, UUID sourceChangeSetId) { }

    public record Input(UUID eventId, List<UUID> targetAssetIds, Map<UUID, Asset> assets, List<Requirement> requirements,
            List<Reservation> reservations, String inputHash) { }

    public record Result(String state, List<Assumption> assumptions, List<Candidate> candidates) { }

    public static Result calculate(Input in) {
        Set<UUID> targets = sorted(in.targetAssetIds());
        List<Assumption> assumptions = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (UUID target : targets) {
            Asset broken = in.assets().get(target);
            List<Requirement> uses = in.requirements().stream()
                    .filter(r -> r.items().stream().anyMatch(i -> i.assetIds().contains(target)
                            || i.compatibleAssetIds().contains(target)))
                    .sorted(Comparator.comparing(Requirement::id, DB_ORDER)).toList();
            if (uses.isEmpty()) {
                assumptions.add(new Assumption("NO_REQUIREMENT:" + target,
                        broken.name() + "을(를) 쓰는 필요 조건이 없어 대체 후보를 찾지 않았습니다.", false));
                continue;
            }
            for (Requirement req : uses) {
                Set<UUID> pool = new TreeSet<>(DB_ORDER);
                for (Item item : req.items()) {
                    if (!item.assetIds().contains(target) && !item.compatibleAssetIds().contains(target)) continue;
                    pool.addAll(item.assetIds());
                    pool.addAll(item.compatibleAssetIds());
                }
                pool.removeAll(targets);
                pool.removeIf(id -> !in.assets().containsKey(id));
                if (pool.isEmpty()) {
                    assumptions.add(new Assumption("NO_COMPATIBLE_ASSET:" + req.id(),
                            "필요 조건에 " + broken.name() + "을(를) 대신할 장비가 명시돼 있지 않습니다.", false));
                    continue;
                }
                for (UUID alt : pool) {
                    String candidateId = req.id() + "." + alt;
                    if (seen.add(candidateId)) candidates.add(candidate(in, targets, broken, req, in.assets().get(alt), candidateId));
                }
            }
        }
        // 등급 → 공연 영향 수 → 확인할 사유 수(점검 필요가 뒤) → 연산 수 → 장비 id. 모르는 값으로 순위를 만들지 않는다(V9-D30).
        candidates.sort(Comparator.<Candidate>comparingInt(c -> RANK.indexOf(c.status()))
                .thenComparingInt(c -> c.programImpacts().size())
                .thenComparingInt(c -> c.reasons().size())
                .thenComparingInt(c -> c.operations().size())
                .thenComparing(c -> c.equipmentIds().getFirst().toString())
                .thenComparing(Candidate::candidateId));
        // 화면에 그대로 보이는 후보 ID는 정렬 순서의 짧은 이름이다. 같은 입력이면 같은 순서라 같은 ID가 나온다.
        List<Candidate> named = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) named.add(renamed(candidates.get(i), "candidate-" + (i + 1)));
        candidates = named;
        if (!candidates.isEmpty())
            assumptions.add(new Assumption("MOVE_INSTALL_RETEST_TIME_UNKNOWN", TIME_UNKNOWN, false));
        return new Result(candidates.isEmpty() ? "NEEDS_INFO" : "READY", assumptions, candidates);
    }

    private static Candidate renamed(Candidate c, String id) {
        return new Candidate(id, c.status(), c.reasons(), c.evidenceRefs(), c.equipmentIds(), c.componentIds(), c.steps(),
                c.occupancy(), c.readyAt(), c.requiredReadyAt(), c.slackSec(), c.shortfallSec(), c.programImpacts(),
                c.conflicts(), c.operations(), c.preview());
    }

    private static Candidate candidate(Input in, Set<UUID> targets, Asset broken, Requirement req, Asset alt,
            String candidateId) {
        List<EvidenceRef> evidence = List.of(
                new EvidenceRef("EQUIPMENT_REQUIREMENT", req.id(), Long.toString(req.revision()), null, null, List.of()),
                new EvidenceRef("EQUIPMENT_ASSET", alt.id(), Long.toString(alt.revision()), null, null, List.of()));
        List<ProgramImpact> impacts = req.programId() == null ? List.of()
                : List.of(new ProgramImpact(req.programId(), req.programName() + " 장비를 " + broken.name() + "에서 "
                        + alt.name() + "(으)로 바꿉니다."));
        if (req.windowStart() == null)
            return new Candidate(candidateId, "UNDETERMINED", List.of(WINDOW_UNKNOWN), evidence, List.of(alt.id()),
                    alt.components(), List.of(), List.of(), null, req.readyBy(), null, null, impacts, List.of(), List.of(),
                    List.of());

        // 고장 장비가 이 구간에 이미 잡혀 있으면 그 예약에서 장비만 바꾼다. 없으면 대체 장비를 새로 예약한다.
        List<Reservation> held = in.reservations().stream()
                .filter(r -> r.assetIds().contains(broken.id()) && overlaps(r, req.windowStart(), req.windowEnd()))
                .sorted(Comparator.comparing(Reservation::id, DB_ORDER)).toList();
        List<ReservationWrite> occupancy = new ArrayList<>();
        List<Operation> ops = new ArrayList<>();
        if (held.isEmpty()) {
            ReservationWrite w = new ReservationWrite(List.of(alt.id()), req.programId(),
                    req.taskIds().size() == 1 ? req.taskIds().getFirst() : null, req.windowStart(), req.windowEnd());
            occupancy.add(w);
            ops.add(new Operation("RESERVE_EQUIPMENT", new Payload(w, null, null, null, evidence)));
        } else {
            for (Reservation r : held) {
                Set<UUID> assets = sorted(r.assetIds());
                assets.remove(broken.id());
                assets.add(alt.id());
                ReservationWrite w = new ReservationWrite(List.copyOf(assets), r.programId(), r.taskId(), r.startAt(),
                        r.endAt());
                occupancy.add(w);
                ops.add(new Operation("UPDATE_EQUIPMENT_RESERVATION",
                        new Payload(null, r.id(), Long.toString(r.revision()), w, evidence)));
            }
        }

        Set<UUID> expanded = expand(in.assets(), alt.id());
        List<String> reasons = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        String status;
        if ("UNUSABLE".equals(alt.technicalState())) {
            status = "INFEASIBLE";
            reasons.add(UNUSABLE);
        } else if (expanded.stream().anyMatch(targets::contains)) {
            status = "INFEASIBLE";
            reasons.add(CONTAINS_TARGET);
        } else {
            Set<UUID> updated = new LinkedHashSet<>(held.stream().map(Reservation::id).toList());
            for (Reservation r : in.reservations()) {
                if (updated.contains(r.id())) continue;
                Set<UUID> theirs = new TreeSet<>(DB_ORDER);
                for (UUID a : r.assetIds()) theirs.addAll(expand(in.assets(), a));
                if (theirs.stream().noneMatch(expanded::contains)) continue;
                if (occupancy.stream().anyMatch(w -> r.startAt().isBefore(w.endAt()) && r.endAt().isAfter(w.startAt())))
                    conflicts.add(r.id().toString());
            }
            if (!conflicts.isEmpty()) {
                status = "INFEASIBLE";
                reasons.add(CONFLICT);
            } else {
                status = "CONDITIONAL";
                reasons.add(TIME_UNKNOWN);
                if (!"VERIFIED_USABLE".equals(alt.technicalState())) reasons.add(NOT_VERIFIED);
                // 장비만 바꾼 예약에 다른 고장 장비가 남으면 이 후보만으로는 복구되지 않는다는 것을 알린다.
                if (occupancy.stream().anyMatch(w -> w.assetIds().stream().anyMatch(a -> targets.contains(a))))
                    reasons.add(OTHER_TARGET_REMAINS);
            }
        }
        // 실행할 수 없는 후보는 변경안으로 만들 연산을 주지 않는다.
        if ("INFEASIBLE".equals(status)) ops = List.of();
        List<DiffItem> preview = preview(in, ops, candidateId);
        return new Candidate(candidateId, status, reasons, evidence, List.of(alt.id()), alt.components(), List.of(),
                occupancy, null, req.readyBy(), null, null, impacts, conflicts, ops, preview);
    }

    /**
     * 후보 연산의 예상 결과. 변경안(066)을 만들면 서버가 미리보기(069)에서 다시 계산한다. 새 예약 id는 계산용 값이라
     * 변경안의 실제 id와 다르다.
     */
    private static List<DiffItem> preview(Input in, List<Operation> ops, String candidateId) {
        List<DiffItem> out = new ArrayList<>();
        Map<UUID, Reservation> byId = new LinkedHashMap<>();
        for (Reservation r : in.reservations()) byId.put(r.id(), r);
        for (int i = 0; i < ops.size(); i++) {
            Operation op = ops.get(i);
            JsonNode evidence = MAPPER.valueToTree(op.payload().evidenceRefs());
            if ("RESERVE_EQUIPMENT".equals(op.type())) {
                ReservationWrite w = op.payload().reservation();
                UUID id = UUID.nameUUIDFromBytes((in.inputHash() + "#" + candidateId + "#" + i).getBytes(StandardCharsets.UTF_8));
                out.add(new DiffItem(i, op.type(), id, "장비 예약: " + names(in, w.assetIds()) + " (" + HM.format(w.startAt())
                        + "~" + HM.format(w.endAt()) + ")", MAPPER.nullNode(),
                        view(id, in.eventId(), w, "PLANNED", null, 1), evidence));
            } else {
                Reservation r = byId.get(op.payload().reservationId());
                ReservationWrite before = new ReservationWrite(r.assetIds(), r.programId(), r.taskId(), r.startAt(), r.endAt());
                ReservationWrite w = op.payload().after();
                out.add(new DiffItem(i, op.type(), r.id(), "장비 예약 변경: " + HM.format(r.startAt()) + "~"
                        + HM.format(r.endAt()) + " → " + names(in, w.assetIds()) + " (" + HM.format(w.startAt()) + "~"
                        + HM.format(w.endAt()) + ")",
                        view(r.id(), in.eventId(), before, r.status(), r.sourceChangeSetId(), r.revision()),
                        view(r.id(), in.eventId(), w, r.status(), r.sourceChangeSetId(), r.revision() + 1), evidence));
            }
        }
        return out;
    }

    /** V9EquipmentReservation 모양(EXT05와 같다) */
    private static ObjectNode view(UUID id, UUID eventId, ReservationWrite w, String status, UUID source, long revision) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("id", id.toString());
        o.put("eventId", eventId.toString());
        var assets = o.putArray("assetIds");
        sorted(w.assetIds()).forEach(a -> assets.add(a.toString()));
        putUuid(o, "programId", w.programId());
        putUuid(o, "taskId", w.taskId());
        o.put("startAt", w.startAt().toString());
        o.put("endAt", w.endAt().toString());
        o.put("status", status);
        putUuid(o, "sourceChangeSetId", source);
        o.put("revision", Long.toString(revision));
        return o;
    }

    private static void putUuid(ObjectNode o, String key, UUID value) {
        if (value == null) o.putNull(key);
        else o.put(key, value.toString());
    }

    private static String names(Input in, List<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) names.add(in.assets().containsKey(id) ? in.assets().get(id).name() : "알 수 없음");
        return String.join(", ", names);
    }

    private static boolean overlaps(Reservation r, Instant start, Instant end) {
        return r.startAt().isBefore(end) && r.endAt().isAfter(start);
    }

    static Set<UUID> sorted(java.util.Collection<UUID> ids) {
        Set<UUID> out = new TreeSet<>(DB_ORDER);
        out.addAll(ids);
        return out;
    }

    /** 장비와 그 구성품(한 단계) */
    static Set<UUID> expand(Map<UUID, Asset> assets, UUID id) {
        Set<UUID> out = new TreeSet<>(DB_ORDER);
        out.add(id);
        Asset a = assets.get(id);
        if (a != null) out.addAll(a.components());
        return out;
    }
}

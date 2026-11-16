package com.ssafy.scenecrew.equipment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ssafy.scenecrew.equipment.service.RecoveryCalculator;
import com.ssafy.scenecrew.equipment.service.RecoveryCalculator.Asset;
import com.ssafy.scenecrew.equipment.service.RecoveryCalculator.Input;
import com.ssafy.scenecrew.equipment.service.RecoveryCalculator.Item;
import com.ssafy.scenecrew.equipment.service.RecoveryCalculator.Requirement;
import com.ssafy.scenecrew.equipment.service.RecoveryCalculator.Reservation;
import com.ssafy.scenecrew.equipment.service.RecoveryViews.Candidate;

import static org.assertj.core.api.Assertions.assertThat;

/** 복구 계산 규칙 recovery-rule-v1(V9-D44)의 상태·연산·정렬·재현성 */
class RecoveryCalculatorTest {
    static final Instant T9 = Instant.parse("2026-11-14T09:00:00Z"), T10 = Instant.parse("2026-11-14T10:00:00Z"),
            T11 = Instant.parse("2026-11-14T11:00:00Z");
    final UUID event = id(0), target = id(1), ok = id(2), check = id(3), bad = id(4), busy = id(5), kit = id(6),
            cable = id(7), pin = id(8), withPin = id(9), program = id(20), task = id(21), req = id(30);

    static UUID id(int n) { return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", n)); }

    Map<UUID, Asset> assets() {
        Map<UUID, Asset> m = new LinkedHashMap<>();
        m.put(target, new Asset(target, "무선 마이크 1", "UNUSABLE", List.of(), 3));
        m.put(ok, new Asset(ok, "예비 마이크", "VERIFIED_USABLE", List.of(), 1));
        m.put(check, new Asset(check, "점검 필요 마이크", "CHECK_REQUIRED", List.of(), 1));
        m.put(bad, new Asset(bad, "고장 예비", "UNUSABLE", List.of(), 1));
        m.put(busy, new Asset(busy, "다른 공연 마이크", "VERIFIED_USABLE", List.of(), 1));
        m.put(kit, new Asset(kit, "마이크 세트", "VERIFIED_USABLE", List.of(target), 1));
        m.put(cable, new Asset(cable, "케이블", "VERIFIED_USABLE", List.of(), 1));
        m.put(pin, new Asset(pin, "핀", "VERIFIED_USABLE", List.of(), 1));
        m.put(withPin, new Asset(withPin, "핀 포함 세트", "VERIFIED_USABLE", List.of(pin), 1));
        return m;
    }

    Requirement requirement(Instant start, Instant end) {
        return new Requirement(req, 2, program, "1부 공연", List.of(task),
                List.of(new Item(List.of(target), List.of(ok, check, bad, busy, kit, withPin))), T9, start, end);
    }

    List<Reservation> reservations() {
        return List.of(
                new Reservation(id(40), 1, "PLANNED", T9, T10, List.of(target, cable), program, null, null),
                new Reservation(id(41), 1, "ACTIVE", Instant.parse("2026-11-14T09:30:00Z"), T11, List.of(busy), null, null, null),
                new Reservation(id(42), 1, "PLANNED", T9, T10, List.of(pin), null, null, null),
                // 끝과 시작이 맞닿는 예약은 겹치지 않는다.
                new Reservation(id(43), 1, "PLANNED", T10, T11, List.of(ok), null, null, null));
    }

    Input input(List<Requirement> reqs, List<Reservation> reservations) {
        return new Input(event, List.of(target), assets(), reqs, reservations, "a".repeat(64));
    }

    @Test void statusesOperationsAndOrder() {
        var result = RecoveryCalculator.calculate(input(List.of(requirement(T9, T10)), reservations()));
        assertThat(result.state()).isEqualTo("READY");
        Map<UUID, Candidate> byAsset = new LinkedHashMap<>();
        for (Candidate c : result.candidates()) byAsset.put(c.equipmentIds().getFirst(), c);
        // 조건부 → 불가 순서, 같은 등급은 장비 id 순
        assertThat(result.candidates().stream().map(c -> c.equipmentIds().getFirst()).toList())
                .containsExactly(ok, check, bad, busy, kit, withPin);
        assertThat(byAsset.get(ok).status()).isEqualTo("CONDITIONAL");
        assertThat(byAsset.get(ok).reasons()).hasSize(1);
        assertThat(byAsset.get(check).status()).isEqualTo("CONDITIONAL");
        assertThat(byAsset.get(check).reasons()).hasSize(2);
        assertThat(byAsset.get(bad).status()).isEqualTo("INFEASIBLE");
        assertThat(byAsset.get(busy).status()).isEqualTo("INFEASIBLE");
        assertThat(byAsset.get(busy).conflicts()).containsExactly(id(41).toString());
        assertThat(byAsset.get(kit).status()).isEqualTo("INFEASIBLE"); // 고장 장비를 구성품으로 포함
        assertThat(byAsset.get(withPin).conflicts()).containsExactly(id(42).toString()); // 구성품 예약과 겹침
        for (UUID infeasible : List.of(bad, busy, kit, withPin)) {
            assertThat(byAsset.get(infeasible).operations()).isEmpty();
            assertThat(byAsset.get(infeasible).preview()).isEmpty();
        }
        // 고장 장비가 잡힌 예약에서 장비만 바꾼다(케이블은 유지).
        Candidate best = byAsset.get(ok);
        assertThat(best.operations()).hasSize(1);
        var op = best.operations().getFirst();
        assertThat(op.type()).isEqualTo("UPDATE_EQUIPMENT_RESERVATION");
        assertThat(op.payload().reservationId()).isEqualTo(id(40));
        assertThat(op.payload().expectedRevision()).isEqualTo("1");
        assertThat(op.payload().after().assetIds()).containsExactly(ok, cable);
        assertThat(best.preview().getFirst().before().get("assetIds").size()).isEqualTo(2);
        assertThat(best.preview().getFirst().after().get("revision").asString()).isEqualTo("2");
        assertThat(best.readyAt()).isNull();
        assertThat(best.slackSec()).isNull();
        assertThat(best.requiredReadyAt()).isEqualTo(T9);
        assertThat(best.programImpacts()).hasSize(1);
        assertThat(result.assumptions().stream().map(a -> a.key())).contains("MOVE_INSTALL_RETEST_TIME_UNKNOWN");
    }

    @Test void reservesWhenTargetIsNotHeld() {
        var result = RecoveryCalculator.calculate(input(List.of(requirement(T9, T10)), List.of()));
        Candidate first = result.candidates().getFirst();
        assertThat(first.operations().getFirst().type()).isEqualTo("RESERVE_EQUIPMENT");
        var write = first.operations().getFirst().payload().reservation();
        assertThat(write.assetIds()).containsExactly(ok);
        assertThat(write.taskId()).isEqualTo(task);
        assertThat(write.startAt()).isEqualTo(T9);
        assertThat(write.endAt()).isEqualTo(T10);
        assertThat(first.preview().getFirst().before().isNull()).isTrue();
        assertThat(first.operations().getFirst().payload().evidenceRefs()).hasSize(2);
    }

    @Test void needsInfoAndUndetermined() {
        var none = RecoveryCalculator.calculate(input(List.of(), List.of()));
        assertThat(none.state()).isEqualTo("NEEDS_INFO");
        assertThat(none.candidates()).isEmpty();
        assertThat(none.assumptions().getFirst().key()).isEqualTo("NO_REQUIREMENT:" + target);
        assertThat(none.assumptions().getFirst().confirmed()).isFalse();
        var unknown = RecoveryCalculator.calculate(input(List.of(requirement(null, null)), List.of()));
        assertThat(unknown.candidates()).allMatch(c -> c.status().equals("UNDETERMINED") && c.operations().isEmpty());
    }

    @Test void verifiedBeforeCheckRequiredAndShortIds() {
        // 점검 필요 장비의 id가 더 작아도, 사유가 적은 사용 가능 장비가 먼저다.
        var assets = assets();
        assets.put(ok, new Asset(ok, "예비 마이크", "CHECK_REQUIRED", List.of(), 1));
        assets.put(check, new Asset(check, "점검 필요 마이크", "VERIFIED_USABLE", List.of(), 1));
        var result = RecoveryCalculator.calculate(new Input(event, List.of(target), assets, List.of(requirement(T9, T10)),
                reservations(), "a".repeat(64)));
        assertThat(result.candidates().get(0).equipmentIds()).containsExactly(check);
        assertThat(result.candidates().stream().map(Candidate::candidateId))
                .containsExactly("candidate-1", "candidate-2", "candidate-3", "candidate-4", "candidate-5", "candidate-6");
    }

    @Test void assetOrderMatchesPostgres() {
        // UUID.compareTo는 부호 있는 비교라 f로 시작하는 id를 앞에 둔다. DB(바이트 순)는 뒤에 둔다.
        UUID high = UUID.fromString("f0000000-0000-4000-8000-000000000001");
        var assets = assets();
        assets.put(high, new Asset(high, "예비 마이크 F", "VERIFIED_USABLE", List.of(), 1));
        Requirement r = new Requirement(req, 2, program, "1부 공연", List.of(task),
                List.of(new Item(List.of(target), List.of(high))), T9, T9, T10);
        var result = RecoveryCalculator.calculate(new Input(event, List.of(target), assets, List.of(r), reservations(),
                "a".repeat(64)));
        var after = result.candidates().getFirst().preview().getFirst().after().get("assetIds");
        assertThat(after.get(0).asString()).isEqualTo(cable.toString());
        assertThat(after.get(1).asString()).isEqualTo(high.toString());
        assertThat(result.candidates().getFirst().operations().getFirst().payload().after().assetIds())
                .containsExactly(cable, high);
    }

    @Test void warnsWhenAnotherBrokenAssetStaysInReservation() {
        // 리뷰 수정: 케이블도 고장이면, 마이크만 바꾼 예약에 고장 케이블이 남는다는 사유가 붙는다.
        var assets = assets();
        var result = RecoveryCalculator.calculate(new Input(event, List.of(target, cable), assets, List.of(requirement(T9, T10)),
                reservations(), "a".repeat(64)));
        Candidate best = result.candidates().getFirst();
        assertThat(best.equipmentIds()).containsExactly(ok);
        assertThat(best.operations().getFirst().payload().after().assetIds()).contains(cable);
        assertThat(best.reasons()).contains("같은 예약에 다른 고장 장비가 남아 있습니다. 그 장비의 대안도 함께 정해야 합니다.");
    }

    @Test void sameInputSameResult() {
        var a = RecoveryCalculator.calculate(input(List.of(requirement(T9, T10)), reservations()));
        List<Reservation> shuffled = new ArrayList<>(reservations());
        Collections.reverse(shuffled);
        var b = RecoveryCalculator.calculate(input(List.of(requirement(T9, T10)), shuffled));
        assertThat(b).isEqualTo(a);
    }
}

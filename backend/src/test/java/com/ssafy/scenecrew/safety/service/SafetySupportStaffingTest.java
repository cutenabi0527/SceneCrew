package com.ssafy.scenecrew.safety.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.plan.domain.TaskStatus;
import com.ssafy.scenecrew.safety.service.SafetySupportStaffing.Assignment;
import com.ssafy.scenecrew.safety.service.SafetySupportStaffing.Minimum;
import com.ssafy.scenecrew.safety.service.SafetySupportStaffing.Window;
import com.ssafy.scenecrew.safety.service.SafetyViews.CameraView;
import com.ssafy.scenecrew.safety.service.SafetyViews.ObservationView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafetySupportStaffingTest {
    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
    private final UUID source = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID group = UUID.randomUUID();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final Window support = new Window(NOW.minusSeconds(60), NOW.plusSeconds(60));

    @Test
    void explicitZeroAllowsEveryoneToLeaveButMissingRuleAndPartialRuleDoNot() {
        var assignments = List.of(task(first, source, TaskStatus.WAITING, support),
                task(second, source, TaskStatus.WAITING, support));
        assertThat(retains(new Minimum(0, support), assignments, List.of(first, second))).isTrue();
        assertThat(retains(null, assignments, List.of(first, second))).isFalse();
        assertThat(retains(new Minimum(0, new Window(NOW, support.end())), assignments, List.of(first, second))).isFalse();
        assertThat(retains(new Minimum(1, support), assignments, List.of(first))).isTrue();
        assertThat(retains(new Minimum(1, support), assignments, List.of(first, second))).isFalse();
    }

    @Test
    void minimumIsCheckedAtEachBoundaryAndAWorkerIsCountedOnce() {
        var half = new Window(support.start(), NOW);
        var assignments = List.of(task(first, source, TaskStatus.WAITING, half),
                task(first, source, TaskStatus.WAITING, support));
        assertThat(retains(new Minimum(2, support), assignments, List.of())).isFalse();
        assertThat(retains(new Minimum(1, support), List.of(assignments.getFirst()), List.of())).isFalse();
        assertThat(retains(new Minimum(1, support), assignments, List.of())).isTrue();
        var ambiguous = List.of(assignments.getLast(), task(first, target, TaskStatus.WAITING, half));
        assertThat(retains(new Minimum(1, support), ambiguous, List.of())).isFalse();
    }

    @Test
    void candidateNeedsAWholeWaitingPlacementAndNoConcurrentOrOverdueWork() {
        var origin = task(first, source, TaskStatus.WAITING, support);
        var longPlacement = task(first, source, TaskStatus.WAITING, new Window(support.start(), support.end().plusSeconds(1)));
        var overdue = task(first, target, TaskStatus.IN_PROGRESS, new Window(NOW.minusSeconds(120), support.start()));
        assertThat(eligible(origin, List.of(origin))).isTrue();
        assertThat(eligible(longPlacement, List.of(longPlacement))).isFalse();
        assertThat(eligible(origin, List.of(origin, overdue))).isFalse();
        assertThat(eligible(origin, List.of(origin, task(first, target, TaskStatus.WAITING, support)))).isFalse();
    }

    @Test
    void observationDistinguishesZeroFromUnknownAndTestFromAi() {
        assertThat(sourceOf("FRESH", "FULL", "0", "[]", "fixture-model", true))
                .isEqualTo(SafetySupportPreparation.ObservationSource.AI);
        assertThat(sourceOf("FRESH", "FULL", "2", "[\"TEST_VALUE\"]", "test-stub", true))
                .isEqualTo(SafetySupportPreparation.ObservationSource.TEST);
        assertThatThrownBy(() -> sourceOf("FRESH", "FULL", "null", "[]", "fixture-model", true))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getDetails()).containsEntry("reason", "SUPPORT_OBSERVATION_QUALITY"));
        assertThatThrownBy(() -> sourceOf("FRESH", "PARTIAL", "2", "[]", "fixture-model", true))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getDetails()).containsEntry("reason", "SUPPORT_OBSERVATION_QUALITY"));
    }

    @Test
    void staleMappingAndOldSessionAreRejectedDespiteAStoredFreshObservation() {
        for (String freshness : List.of("STALE", "INVALID_MAPPING")) {
            assertThatThrownBy(() -> sourceOf(freshness, "FULL", "2", "[]", "fixture-model", true))
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getDetails()).containsEntry("reason", "SUPPORT_OBSERVATION_UNAVAILABLE"));
        }
        assertThatThrownBy(() -> sourceOf("FRESH", "FULL", "2", "[]", "fixture-model", false))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getDetails()).containsEntry("reason", "SUPPORT_OBSERVATION_UNAVAILABLE"));
    }

    private SafetySupportPreparation.ObservationSource sourceOf(String freshness, String scope, String count,
            String reasons, String model, boolean currentSession) {
        var event = UUID.randomUUID();
        var cameraId = UUID.randomUUID();
        var observationId = UUID.randomUUID();
        var session = UUID.randomUUID();
        var coverage = SafetyViews.json("""
                [{"zoneId":"%s","scope":"%s"}]
                """.formatted(target, scope));
        var estimates = SafetyViews.json("""
                [{"zoneId":"%s","estimatedCount":%s,"qualityReasons":%s}]
                """.formatted(target, count, reasons));
        var camera = new CameraView(cameraId, event, "camera", "fixture", true, "1", coverage,
                first, NOW, "CONNECTED", session, "1", NOW, NOW, NOW, observationId, freshness, "COLLECTOR", null, null, null);
        var observation = new ObservationView(observationId, event, cameraId, "1",
                currentSession ? session : UUID.randomUUID(), "1", NOW, "COLLECTOR_CLOCK",
                NOW, "fixture", coverage, "READY", "FRESH", estimates, model, NOW, NOW.plusSeconds(600));
        return SafetySupportPreparation.observationSource(camera, observation, target, NOW);
    }

    private Assignment task(UUID member, UUID zone, TaskStatus status, Window window) {
        return new Assignment(UUID.randomUUID(), member, 2, true, group, zone, status, window);
    }

    private boolean eligible(Assignment task, List<Assignment> assignments) {
        return SafetySupportStaffing.eligible(task, assignments, source, group, support, NOW);
    }

    private boolean retains(Minimum minimum, List<Assignment> assignments, List<UUID> leaving) {
        return SafetySupportStaffing.retainsMinimum(minimum, assignments, leaving, source, group, support);
    }
}

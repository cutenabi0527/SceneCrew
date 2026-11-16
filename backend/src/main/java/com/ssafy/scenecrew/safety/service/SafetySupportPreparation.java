package com.ssafy.scenecrew.safety.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.change.service.ChangeSetService.NewChangeSet;
import com.ssafy.scenecrew.change.service.Operations;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.plan.domain.TaskStatus;
import com.ssafy.scenecrew.safety.collector.CameraAnalyzer;
import com.ssafy.scenecrew.safety.collector.CameraFrameService;
import com.ssafy.scenecrew.safety.collector.StubCrowdEstimator;
import com.ssafy.scenecrew.safety.service.SafetySupportStaffing.Assignment;
import com.ssafy.scenecrew.safety.service.SafetySupportStaffing.Minimum;
import com.ssafy.scenecrew.safety.service.SafetySupportStaffing.Window;
import com.ssafy.scenecrew.safety.service.SafetyViews.CameraView;
import com.ssafy.scenecrew.safety.service.SafetyViews.ObservationView;
import com.ssafy.scenecrew.safety.service.SafetyViews.ProfileView;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Candidate checks and ordinary ChangeSet operation generation.
 * Approval re-runs preparation in its write transaction with the server-owned selection.
 */
@Component
public class SafetySupportPreparation {
    private final JdbcTemplate jdbc;
    private final EventWriteSupport events;
    private final CurrentMembers members;

    public SafetySupportPreparation(JdbcTemplate jdbc, EventWriteSupport events, CurrentMembers members) {
        this.jdbc = jdbc;
        this.events = events;
        this.members = members;
    }

    public record Request(UUID eventId, UUID observationId, UUID sourceZoneId, UUID targetZoneId,
            UUID workGroupId, Instant startAt, Instant endAt, long expectedEventRevision) { }

    public record Candidate(UUID memberId, long participationRevision, UUID sourceTaskId) { }

    public record Selection(Request request, List<Candidate> candidates) {
        public Selection {
            candidates = List.copyOf(candidates);
        }
    }

    enum ObservationSource { AI, TEST }

    private record Context(List<Assignment> assignments, List<Candidate> eligible, Minimum minimum, Window support) { }

    @Transactional
    public List<Candidate> candidates(UUID accountId, Request request) {
        Context context = inspect(accountId, request);
        return context.eligible().stream()
                .filter(candidate -> retainsMinimum(request, context, List.of(candidate.memberId())))
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public NewChangeSet prepare(UUID accountId, Request request, List<Candidate> selected) {
        Context context = inspect(accountId, request);
        if (selected == null || selected.isEmpty() || selected.stream().anyMatch(candidate -> !context.eligible().contains(candidate)))
            throw state("SUPPORT_CANDIDATE_CHANGED");
        var memberIds = selected.stream().map(Candidate::memberId).distinct().toList();
        if (memberIds.size() != selected.size()) throw state("SUPPORT_CANDIDATE_DUPLICATE");
        if (!retainsMinimum(request, context, memberIds)) throw state("SUPPORT_MINIMUM_CREW");
        return new NewChangeSet("MANUAL", operations(request, selected), null, null, "안전 인력 지원",
                request.expectedEventRevision(), SafetyViews.MAPPER.createArrayNode());
    }

    private Context inspect(UUID accountId, Request request) {
        var event = events.lock(request.eventId());
        members.requireChiefLocked(request.eventId(), accountId);
        EventWriteSupport.requireState(event, "RECRUITING", "LIVE");
        EventWriteSupport.requireRevision(event.revision(), request.expectedEventRevision(), "event");
        var support = new Window(request.startAt(), request.endAt());
        if (!support.valid() || request.sourceZoneId().equals(request.targetZoneId()))
            throw state("SUPPORT_WINDOW_OR_ZONE");

        var profiles = jdbc.query("SELECT " + SafetyViews.PROFILE_COLUMNS
                + " FROM safety_zone_profile p WHERE p.event_id=?", SafetyViews::profile, request.eventId());
        requireObservation(request, profiles);
        Instant now = EventWriteSupport.now();
        if (!support.contains(now)) throw state("SUPPORT_WINDOW_NOT_CURRENT");

        Minimum minimum = minimum(profiles, request);
        if (minimum == null || !minimum.window().covers(support)) throw state("SUPPORT_CREW_RULE_UNAVAILABLE");
        var assignments = assignments(request.eventId(), request.workGroupId());
        var eligible = assignments.stream()
                .filter(task -> SafetySupportStaffing.eligible(task, assignments, request.sourceZoneId(),
                        request.workGroupId(), support, now))
                .map(task -> new Candidate(task.memberId(), task.round(), task.taskId())).toList();
        return new Context(assignments, eligible, minimum, support);
    }

    private static boolean retainsMinimum(Request request, Context context, List<UUID> leaving) {
        return SafetySupportStaffing.retainsMinimum(context.minimum(), context.assignments(), leaving,
                request.sourceZoneId(), request.workGroupId(), context.support());
    }

    private void requireObservation(Request request, List<ProfileView> profiles) {
        // Collector writes do not take Event locks. Lock the camera before reading its latest
        // observation. Keep the observation read non-locking because sweep can update it first.
        CameraView camera = jdbc.query("SELECT " + SafetyViews.CAMERA_COLUMNS + """
                 FROM safety_camera c WHERE c.event_id=? AND c.latest_observation_id=? FOR SHARE OF c
                """, SafetyViews::camera, request.eventId(), request.observationId())
                .stream().findFirst().orElseThrow(() -> state("SUPPORT_OBSERVATION_UNAVAILABLE"));
        ObservationView observation = jdbc.query("SELECT " + SafetyViews.OBSERVATION_COLUMNS + """
                 FROM safety_observation o WHERE o.event_id=? AND o.id=?
                """, SafetyViews::observation, request.eventId(), request.observationId())
                .stream().findFirst().orElseThrow(() -> state("SUPPORT_OBSERVATION_UNAVAILABLE"));
        var layout = jdbc.query("SELECT " + SafetyViews.LAYOUT_COLUMNS + " FROM venue_layout l WHERE l.event_id=?",
                SafetyViews::layout, request.eventId()).stream().findFirst()
                .orElseThrow(() -> state("SUPPORT_OBSERVATION_MAPPING"));
        Map<UUID, Long> revisions = new HashMap<>();
        for (ProfileView profile : profiles) revisions.put(profile.zoneId(), Long.parseLong(profile.revision()));
        if (!SafetyCameraService.coverageCurrent(camera.coverage(), layout.id(), Long.parseLong(layout.revision()), revisions))
            throw state("SUPPORT_OBSERVATION_MAPPING");
        if (observationSource(camera, observation, request.targetZoneId(), EventWriteSupport.now()) == ObservationSource.TEST)
            throw state("SUPPORT_TEST_OBSERVATION");
    }

    static ObservationSource observationSource(CameraView camera, ObservationView observation, UUID zoneId, Instant now) {
        if (!camera.enabled() || !"CONNECTED".equals(camera.connectionState()) || !"FRESH".equals(camera.freshness())
                || !"FRESH".equals(observation.freshness()) || !camera.id().equals(observation.cameraId())
                || !observation.id().equals(camera.latestObservationId())
                || !camera.revision().equals(observation.cameraRevision())
                || observation.streamSessionId() == null || !observation.streamSessionId().equals(camera.streamSessionId())
                || !CameraFrameService.CAPTURE_TIME_BASIS.equals(observation.captureTimeBasis())
                || !observation.coverage().equals(camera.coverage())
                || observation.capturedAt() == null || observation.processedAt() == null
                || observation.capturedAt().isBefore(now.minus(CameraAnalyzer.STALE_AFTER))
                || !observation.processedAt().isAfter(now.minus(CameraAnalyzer.STALE_AFTER))
                || !observation.metadataExpiresAt().isAfter(now)
                || camera.lastReceivedAt() == null
                || !camera.lastReceivedAt().isAfter(now.minus(CameraAnalyzer.OFFLINE_AFTER)))
            throw state("SUPPORT_OBSERVATION_UNAVAILABLE");

        JsonNode coverage = zoneEntry(camera.coverage(), zoneId);
        JsonNode estimate = zoneEntry(observation.estimates(), zoneId);
        if (!"FULL".equals(coverage.path("scope").asString("")) || !estimate.path("estimatedCount").isNumber())
            throw state("SUPPORT_OBSERVATION_QUALITY");

        JsonNode reasons = estimate.path("qualityReasons");
        if (!reasons.isArray() || observation.modelVersion() == null || observation.modelVersion().isBlank())
            throw state("SUPPORT_OBSERVATION_QUALITY");
        boolean testModel = StubCrowdEstimator.MODEL_VERSION.equals(observation.modelVersion());
        boolean testReason = reasons.size() == 1 && StubCrowdEstimator.TEST_VALUE.equals(reasons.get(0).asString(""));
        if (testModel && testReason) return ObservationSource.TEST;
        if (testModel || !reasons.isEmpty()) throw state("SUPPORT_OBSERVATION_QUALITY");
        return ObservationSource.AI;
    }

    private static JsonNode zoneEntry(JsonNode entries, UUID zoneId) {
        JsonNode found = null;
        for (JsonNode entry : entries) {
            if (!zoneId.toString().equals(entry.path("zoneId").asString(""))) continue;
            if (found != null) throw state("SUPPORT_OBSERVATION_QUALITY");
            found = entry;
        }
        if (found == null) throw state("SUPPORT_OBSERVATION_QUALITY");
        return found;
    }

    static Minimum minimum(List<ProfileView> profiles, Request request) {
        JsonNode rule = profiles.stream().filter(profile -> profile.zoneId().equals(request.sourceZoneId()))
                .map(ProfileView::minimumCrewRule).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (rule == null || !request.workGroupId().toString().equals(rule.path("workGroupId").asString(""))
                || !rule.path("minimumCount").isIntegralNumber() || !rule.get("minimumCount").canConvertToInt())
            return null;
        int count = rule.get("minimumCount").asInt();
        if (count < 0 || count > 31) return null;
        try {
            return new Minimum(count, new Window(Instant.parse(rule.path("startAt").asString("")),
                    Instant.parse(rule.path("endAt").asString(""))));
        } catch (java.time.format.DateTimeParseException invalid) {
            return null;
        }
    }

    List<Assignment> assignments(UUID eventId, UUID groupId) {
        return jdbc.query("""
                SELECT t.id, t.zone_id, t.work_group_id, t.status, t.start_at, t.end_at,
                       m.id AS member_id, m.participation_revision, m.available
                FROM task t
                JOIN member m ON m.id=t.assignee_id AND m.event_id=t.event_id
                JOIN account a ON a.id=m.account_id
                JOIN work_group g ON g.id=m.work_group_id AND g.event_id=m.event_id
                WHERE t.event_id=? AND m.work_group_id=? AND g.active AND m.active AND m.event_role='WORKER'
                  AND a.status='ACTIVE' AND a.signup_completed AND t.status IN ('WAITING', 'IN_PROGRESS')
                ORDER BY t.id
                """, (r, n) -> new Assignment(r.getObject("id", UUID.class), r.getObject("member_id", UUID.class),
                r.getLong("participation_revision"), r.getBoolean("available"), r.getObject("work_group_id", UUID.class),
                r.getObject("zone_id", UUID.class), TaskStatus.valueOf(r.getString("status")),
                new Window(SafetyViews.instant(r, "start_at"), SafetyViews.instant(r, "end_at"))), eventId, groupId);
    }

    private static ArrayNode operations(Request request, List<Candidate> selected) {
        ArrayNode operations = SafetyViews.MAPPER.createArrayNode();
        for (Candidate candidate : selected) {
            payload(operations, "ASSIGN_TASK").put("taskId", candidate.sourceTaskId().toString())
                    .put("fromMemberId", candidate.memberId().toString()).putNull("toMemberId");
        }
        for (Candidate candidate : selected) {
            ObjectNode add = payload(operations, "ADD_TASK");
            add.put("workGroupId", request.workGroupId().toString());
            add.put("zoneId", request.targetZoneId().toString());
            add.put("bundleKey", "safety-support-" + candidate.sourceTaskId());
            add.put("assigneeId", candidate.memberId().toString());
            add.putNull("programId");
            add.put("title", "안전 인력 지원");
            add.put("startAt", request.startAt().toString());
            add.put("endAt", request.endAt().toString());
            add.put("tempId", "tmp-" + candidate.sourceTaskId());
            add.putArray("steps").add("지원 구역으로 이동해 안전 업무를 수행합니다.");
            add.putArray("checklist").addObject().put("key", "arrive").put("text", "도착").put("required", true);
        }
        return Operations.check(operations);
    }

    private static ObjectNode payload(ArrayNode operations, String type) {
        return operations.addObject().put("type", type).putObject("payload");
    }

    private static BusinessException state(String reason) {
        return new BusinessException(ErrorCode.INVALID_STATE, ErrorCode.INVALID_STATE.getMessage(), Map.of("reason", reason));
    }
}

package com.ssafy.scenecrew.safety.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.ssafy.scenecrew.plan.domain.TaskStatus;

final class SafetySupportStaffing {
    private SafetySupportStaffing() { }

    record Window(Instant start, Instant end) {
        boolean valid() {
            return start != null && end != null && start.isBefore(end);
        }

        boolean contains(Instant at) {
            return !at.isBefore(start) && at.isBefore(end);
        }

        boolean covers(Window other) {
            return !start.isAfter(other.start) && !end.isBefore(other.end);
        }

        boolean overlaps(Window other) {
            return start.isBefore(other.end) && other.start.isBefore(end);
        }
    }

    record Assignment(UUID taskId, UUID memberId, long round, boolean available, UUID groupId,
            UUID zoneId, TaskStatus status, Window window) { }

    record Minimum(int count, Window window) { }

    static boolean eligible(Assignment origin, List<Assignment> assignments, UUID zoneId, UUID groupId,
            Window support, Instant now) {
        // Only a whole WAITING assignment inside the support window can be released by this slice.
        return origin.available() && origin.status() == TaskStatus.WAITING
                && origin.zoneId().equals(zoneId) && origin.groupId().equals(groupId)
                && origin.window().contains(now) && support.covers(origin.window())
                && assignments.stream().noneMatch(other -> other.memberId().equals(origin.memberId())
                        && !other.taskId().equals(origin.taskId())
                        && (other.status() == TaskStatus.IN_PROGRESS || other.window().overlaps(support)));
    }

    static boolean retainsMinimum(Minimum minimum, List<Assignment> assignments, List<UUID> leaving,
            UUID zoneId, UUID groupId, Window support) {
        if (minimum == null || !minimum.window().covers(support)) return false;
        var remaining = assignments.stream().filter(task -> !leaving.contains(task.memberId())).toList();
        var boundaries = Stream.concat(Stream.of(support.start()), remaining.stream()
                .flatMap(task -> Stream.of(task.window().start(), task.window().end()))
                .filter(support::contains)).distinct();
        return boundaries.allMatch(at -> countAt(remaining, zoneId, groupId, at) >= minimum.count());
    }

    static long countAt(List<Assignment> remaining, UUID zoneId, UUID groupId, Instant at) {
        return remaining.stream()
                .filter(task -> task.zoneId().equals(zoneId) && task.groupId().equals(groupId)
                        && task.window().contains(at))
                .filter(task -> remaining.stream().noneMatch(other -> other.memberId().equals(task.memberId())
                        && !other.zoneId().equals(zoneId)
                        && (other.window().contains(at) || other.status() == TaskStatus.IN_PROGRESS)))
                .map(Assignment::memberId).distinct().count();
    }
}

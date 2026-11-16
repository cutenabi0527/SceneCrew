package com.ssafy.scenecrew.safety.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.safety.collector.LatestFrames;
import com.ssafy.scenecrew.safety.collector.LatestFrames.Frame;

/**
 * 카메라 현재 프레임(Q2 snapshot, #349). 총괄만, 메모리의 최신 1장만 돌려준다(녹화·과거 프레임 없음, V9-D16).
 *
 * <p>프레임이 없거나 카메라 연결이 끊겼으면 503 SNAPSHOT_UNAVAILABLE이다. 오래된 프레임을 현재 화면처럼 주지 않는다.
 * 프레임을 고른 뒤 응답 직전에 현재 권한을 한 번 더 확인한다(권한 회수와 겹친 요청은 최대 1장).
 */
public class SafetySnapshotService {
    /** 받은 지 이 시간 안이면 FRESH, 그 뒤 연결이 끊기기 전까지는 STALE이다. */
    public static final Duration FRESH_FOR = Duration.ofSeconds(6);
    private static final Set<String> OPEN_EVENT_STATES = Set.of("RECRUITING", "LIVE");

    private final JdbcTemplate jdbc;
    private final CurrentMembers members;
    private final LatestFrames frames;
    private final Clock clock;
    private final Duration frameTtl;

    /** @param frames 카메라 수신이 꺼져 있으면 null(늘 NO_FRAME) */
    public SafetySnapshotService(JdbcTemplate jdbc, CurrentMembers members, LatestFrames frames, Clock clock, Duration frameTtl) {
        this.jdbc = jdbc;
        this.members = members;
        this.frames = frames;
        this.clock = clock;
        this.frameTtl = frameTtl;
    }

    public record Snapshot(byte[] jpeg, Instant capturedAt, Instant receivedAt, String freshness) {
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID cameraId, UUID accountId) {
        var row = jdbc.query("""
                SELECT c.event_id, c.enabled, c.connection_state, c.stream_session_id, e.state
                  FROM safety_camera c JOIN event e ON e.id = c.event_id WHERE c.id = ?""", (rs, n) -> new Object[] {
                rs.getObject("event_id", UUID.class), rs.getBoolean("enabled"), rs.getString("connection_state"),
                rs.getObject("stream_session_id", UUID.class), rs.getString("state") }, cameraId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        UUID eventId = (UUID) row[0];
        SafetyMapService.requireChief(members.require(eventId, accountId));
        if (!OPEN_EVENT_STATES.contains((String) row[4])) throw SafetyCameraService.state("EVENT_CLOSED");
        if (!(Boolean) row[1]) throw SafetyCameraService.state("CAMERA_DISABLED");
        if ("OFFLINE".equals(row[2])) throw unavailable("OFFLINE");

        Frame frame = frames == null ? null : frames.get(cameraId);
        Instant now = clock.instant();
        // 연결 참조가 바뀌어 세션이 비었거나 다른 세션이면 옛 입력의 화면이다.
        if (frame == null || !frame.streamSessionId().equals(row[3]) || frame.receivedAt().isBefore(now.minus(frameTtl)))
            throw unavailable("NO_FRAME");
        String freshness = frame.receivedAt().isBefore(now.minus(FRESH_FOR)) ? "STALE" : "FRESH";

        // 응답 직전 권한 재확인
        SafetyMapService.requireChief(members.require(eventId, accountId));
        return new Snapshot(frame.jpeg(), frame.capturedAt(), frame.receivedAt(), freshness);
    }

    private static BusinessException unavailable(String reason) {
        return new BusinessException(ErrorCode.SNAPSHOT_UNAVAILABLE, ErrorCode.SNAPSHOT_UNAVAILABLE.getMessage(),
                Map.of("reason", reason));
    }
}

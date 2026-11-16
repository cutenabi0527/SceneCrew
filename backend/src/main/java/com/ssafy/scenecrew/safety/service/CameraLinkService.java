package com.ssafy.scenecrew.safety.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.global.web.StrictJson;
import com.ssafy.scenecrew.safety.collector.CameraAnalyzer;
import com.ssafy.scenecrew.safety.collector.CameraFrameService;
import com.ssafy.scenecrew.safety.collector.LatestFrames;
import com.ssafy.scenecrew.safety.collector.LatestFrames.Frame;

/**
 * 카메라 웹 연결(#393, V9-D58·D60). 로그인한 사람의 브라우저가 카메라 화면을 2초마다 1장씩 보낸다.
 *
 * <ul>
 *   <li>시작 자격: CHIEF, 또는 그 카메라의 연결 담당 업무(link_task_id)를 맡은 WORKER이고 업무가 WAITING·IN_PROGRESS.</li>
 *   <li>유지 자격: 연결한 사람이 참여 중이고 CHIEF이거나 아직 그 업무의 담당자(업무 완료 뒤에도 유지, 취소된 업무는 아님),
 *       행사가 RECRUITING·LIVE. 프레임마다 다시 본다.</li>
 *   <li>점유: 한 카메라는 한 브라우저(link_id)만 보낸다. 보내는 중(CONNECTED)이거나 연결한 지 15초 안인 칸은 takeover=true일 때만 가져가고,
 *       그 뒤 이전 브라우저의 프레임은 409 CAMERA_LINK_TAKEN이다.</li>
 *   <li>프레임 시각은 서버가 받은 시각이다(브라우저 시계를 믿지 않는다). 메모리 1장 보관·분석은 수집기 경로와 같다(V9-D09).</li>
 * </ul>
 */
public class CameraLinkService {
    public static final String CAPTURE_TIME_BASIS = "SERVER_RECEIVED";
    private static final Set<String> OPEN_EVENT_STATES = Set.of("RECRUITING", "LIVE");
    private static final Set<String> START_TASK_STATES = Set.of("WAITING", "IN_PROGRESS");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventWriteSupport events;
    private final LatestFrames frames;
    private final Clock clock;

    public CameraLinkService(JdbcTemplate jdbc, PlatformTransactionManager manager, EventWriteSupport events, LatestFrames frames,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.events = events;
        this.frames = frames;
        this.clock = clock;
    }

    public record LinkCommand(long expectedCameraRevision, boolean takeover) {
    }

    /** 연결 시작 응답. linkId는 이 브라우저만 받는다(프레임 헤더 X-SceneCrew-Link). */
    public record Link(UUID linkId, String cameraRevision) {
    }

    public record Unlink(String cameraRevision) {
    }

    /** 프레임 수신 확인 */
    public record Ack(Instant receivedAt, String sequence) {
    }

    public record FrameHeaders(String link, String streamSession, String sequence) {
    }

    /** 카메라 한 줄과 그 행사·연결 담당 업무 */
    private record Row(UUID id, UUID eventId, String eventState, boolean enabled, long revision, String binding, UUID linkId,
            UUID linkMemberId, Instant linkedAt, String connectionState, UUID streamSessionId, Long lastSequence,
            UUID taskAssignee, String taskStatus) {
    }

    /** 요청한 사람의 이 행사 Member. 참여하지 않았으면(또는 나갔으면) active=false */
    private record Caller(UUID id, String role, boolean active) {
        boolean chief() {
            return active && "CHIEF".equals(role);
        }
    }

    /** 연결 시작·넘겨받기 */
    public Link link(UUID cameraId, UUID accountId, LinkCommand command) {
        Link link = tx.execute(status -> {
            Row camera = lock(cameraId);
            Caller caller = caller(camera.eventId(), accountId);
            if (!caller.active()) throw new BusinessException(ErrorCode.NOT_FOUND);
            boolean assignee = caller.id().equals(camera.taskAssignee()) && START_TASK_STATES.contains(camera.taskStatus());
            if (!caller.chief() && !assignee) throw new BusinessException(ErrorCode.CAMERA_LINK_FORBIDDEN);
            if (!OPEN_EVENT_STATES.contains(camera.eventState())) throw SafetyCameraService.state("EVENT_CLOSED");
            if (!camera.enabled()) throw new BusinessException(ErrorCode.CAMERA_DISABLED);
            EventWriteSupport.requireRevision(camera.revision(), command.expectedCameraRevision(), "camera");
            // 보내는 중이거나, 막 연결해 첫 프레임을 기다리는 중(연결 뒤 15초 안)이면 넘겨받기 확인을 요구한다.
            // 15초가 지나도 프레임이 없으면 끊긴 칸으로 보고 누구나 바로 연결한다(V9-D58).
            Instant now = EventWriteSupport.now();
            boolean sending = camera.linkId() != null && ("CONNECTED".equals(camera.connectionState())
                    || camera.linkedAt() != null && camera.linkedAt().isAfter(now.minus(CameraAnalyzer.OFFLINE_AFTER)));
            if (sending && !command.takeover()) throw new BusinessException(ErrorCode.CAMERA_LINK_TAKEN);

            UUID linkId = UUID.randomUUID();
            long revision = camera.revision() + 1;
            // 새 입력이므로 이전 입력의 세션·순서·촬영 시각을 이어 쓰지 않는다
            jdbc.update("""
                    UPDATE safety_camera SET input_kind = 'BROWSER', link_id = ?, link_member_id = ?, linked_at = ?,
                           connection_state = 'CONNECTING', stream_session_id = NULL, last_sequence = NULL,
                           last_captured_at = NULL, revision = ?
                     WHERE id = ?""", linkId, caller.id(), utc(now), revision, cameraId);
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("inputKind", "BROWSER");
            changes.put("takeover", sending);
            events.log(camera.eventId(), "SAFETY_CAMERA_LINKED", caller.id(), "SAFETY_CAMERA", cameraId, changes, now);
            return new Link(linkId, Long.toString(revision));
        });
        frames.discardCamera(cameraId);   // 이전 입력의 화면을 새 연결의 화면처럼 보이지 않게
        return link;
    }

    /** 연결 해제. 연결한 사람 또는 CHIEF. 등록 안됨(서버 설정 경로가 있으면 그 경로 대기)으로 돌린다. */
    public Unlink unlink(UUID cameraId, UUID accountId) {
        Unlink unlink = tx.execute(status -> {
            Row camera = lock(cameraId);
            Caller caller = caller(camera.eventId(), accountId);
            if (!caller.active()) throw new BusinessException(ErrorCode.NOT_FOUND);
            if (camera.linkId() == null) throw new BusinessException(ErrorCode.CAMERA_UNLINKED);
            if (!caller.chief() && !caller.id().equals(camera.linkMemberId()))
                throw new BusinessException(ErrorCode.CAMERA_LINK_FORBIDDEN);
            String state = camera.enabled() ? SafetyCameraService.idleState(camera.binding(), null) : "DISABLED";
            long revision = camera.revision() + 1;
            Instant now = EventWriteSupport.now();
            jdbc.update("""
                    UPDATE safety_camera SET input_kind = ?, link_id = NULL, link_member_id = NULL, linked_at = NULL,
                           connection_state = ?, stream_session_id = NULL, last_sequence = NULL, last_captured_at = NULL,
                           revision = ?
                     WHERE id = ?""", camera.binding() != null ? "COLLECTOR" : null, state, revision, cameraId);
            events.log(camera.eventId(), "SAFETY_CAMERA_UNLINKED", caller.id(), "SAFETY_CAMERA", cameraId, Map.of(), now);
            return new Unlink(Long.toString(revision));
        });
        frames.discardCamera(cameraId);
        return unlink;
    }

    /** 프레임 한 장. 헤더 형식 → 본문 → (트랜잭션) 연결·자격·순서 확인과 상태 갱신 → 메모리 1장 보관 */
    public Ack frame(UUID cameraId, UUID accountId, FrameHeaders headers, CameraFrameService.Body reader) {
        UUID link = uuid(headers.link(), "X-SceneCrew-Link");
        UUID session = uuid(headers.streamSession(), "X-SceneCrew-Stream-Session");
        long sequence = sequence(headers.sequence());
        byte[] body = CameraFrameService.readJpeg(reader, StrictJson::invalid);

        Instant now = clock.instant();
        Frame frame = tx.execute(status -> {
            Row camera = lock(cameraId);
            Caller caller = caller(camera.eventId(), accountId);
            if (caller.id() == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            if (camera.linkId() == null) throw new BusinessException(ErrorCode.CAMERA_UNLINKED);
            if (!camera.linkId().equals(link) || !caller.id().equals(camera.linkMemberId()))
                throw new BusinessException(ErrorCode.CAMERA_LINK_TAKEN);
            boolean keeps = caller.chief() || caller.active() && caller.id().equals(camera.taskAssignee())
                    && camera.taskStatus() != null && !"CANCELED".equals(camera.taskStatus());
            if (!keeps || !OPEN_EVENT_STATES.contains(camera.eventState()))
                throw new BusinessException(ErrorCode.CAMERA_LINK_FORBIDDEN);
            if (!camera.enabled()) throw new BusinessException(ErrorCode.CAMERA_DISABLED);
            if (session.equals(camera.streamSessionId()) && camera.lastSequence() != null && sequence <= camera.lastSequence())
                throw new BusinessException(ErrorCode.CAMERA_FRAME_OUTDATED, ErrorCode.CAMERA_FRAME_OUTDATED.getMessage(),
                        Map.of("reason", "SEQUENCE"));
            jdbc.update("""
                    UPDATE safety_camera SET connection_state = 'CONNECTED', stream_session_id = ?, last_sequence = ?,
                           last_captured_at = ?, last_received_at = ?
                     WHERE id = ?""", session, sequence, utc(now), utc(now), cameraId);
            return new Frame(camera.eventId(), cameraId, session, sequence, now, CAPTURE_TIME_BASIS, now, body,
                    CameraFrameService.sha256(body));
        });
        frames.put(frame);
        return new Ack(now, Long.toString(sequence));
    }

    private Row lock(UUID cameraId) {
        List<Row> rows = jdbc.query("""
                SELECT c.id, c.event_id, e.state AS event_state, c.enabled, c.revision, c.source_binding_ref, c.link_id,
                       c.link_member_id, c.linked_at, c.connection_state, c.stream_session_id, c.last_sequence,
                       t.assignee_id AS task_assignee, t.status AS task_status
                  FROM safety_camera c JOIN event e ON e.id = c.event_id
                  LEFT JOIN task t ON t.id = c.link_task_id
                 WHERE c.id = ? FOR UPDATE OF c""", (r, n) -> {
            var linked = r.getTimestamp("linked_at");
            long last = r.getLong("last_sequence");
            Long lastSequence = r.wasNull() ? null : last;
            return new Row(r.getObject("id", UUID.class), r.getObject("event_id", UUID.class), r.getString("event_state"),
                    r.getBoolean("enabled"), r.getLong("revision"), r.getString("source_binding_ref"),
                    r.getObject("link_id", UUID.class), r.getObject("link_member_id", UUID.class),
                    linked == null ? null : linked.toInstant(), r.getString("connection_state"),
                    r.getObject("stream_session_id", UUID.class), lastSequence, r.getObject("task_assignee", UUID.class),
                    r.getString("task_status"));
        }, cameraId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    /** 나간 참여자도 찾아야 \"유지 자격 상실(403)\"과 \"참여한 적 없음(404)\"을 구별한다. */
    private Caller caller(UUID eventId, UUID accountId) {
        return jdbc.query("SELECT id, event_role, active FROM member WHERE event_id = ? AND account_id = ?",
                (r, n) -> new Caller(r.getObject("id", UUID.class), r.getString("event_role"), r.getBoolean("active")),
                eventId, accountId).stream().findFirst().orElse(new Caller(null, null, false));
    }

    private static UUID uuid(String value, String field) {
        if (value == null) throw StrictJson.invalid(field, "REQUIRED");
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value.toLowerCase())) throw StrictJson.invalid(field, "FORMAT");
            return id;
        } catch (IllegalArgumentException e) {
            throw StrictJson.invalid(field, "FORMAT");
        }
    }

    private static long sequence(String value) {
        if (value == null) throw StrictJson.invalid("X-SceneCrew-Sequence", "REQUIRED");
        if (!value.matches("[1-9][0-9]{0,17}")) throw StrictJson.invalid("X-SceneCrew-Sequence", "FORMAT");
        return Long.parseLong(value);
    }

    private static Object utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
}

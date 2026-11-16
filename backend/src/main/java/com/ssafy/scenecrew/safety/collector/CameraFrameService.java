package com.ssafy.scenecrew.safety.collector;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.safety.collector.CameraCollectors.Collector;
import com.ssafy.scenecrew.safety.collector.LatestFrames.Frame;

/**
 * 현장 노트북의 웹캠 프로그램(camera/webcam.py, #318)이 보낸 프레임을 받는다(#349).
 *
 * <p>순서: 수집기 인증 → 헤더 형식 → 수집기에 허용된 연결 식별자인지 → 본문 → 행사 상태 → 연결 식별자로 카메라 찾기(행 잠금) → 순서 확인 →
 * 카메라 연결 상태 갱신 → 메모리에 최신 1장 보관. 프레임은 DB·디스크에 쓰지 않는다(V9-D09).
 *
 * <p>순서 규칙: 같은 스트림 세션에서는 번호가 지난 번호보다 커야 하고, 어떤 경우든 찍은 시각이 지난 프레임보다 앞서면 안 된다.
 * 웹캠을 다시 연결하면 새 세션이 1번부터 시작하므로, 세션이 바뀐 경우에는 시각만 본다.
 */
public class CameraFrameService {
    public static final String CAPTURE_TIME_BASIS = "COLLECTOR_CLOCK";
    /** 프레임 한 장 상한. 1280x960 JPEG는 보통 100~300KB다. */
    public static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    /** 수집기 시계가 서버보다 이만큼 넘게 빠르면 받지 않는다. */
    static final Duration FUTURE_SKEW = Duration.ofSeconds(30);
    /** 이보다 오래전에 찍은 프레임은 받지 않는다(현재 화면이 아니다). */
    static final Duration MAX_AGE = Duration.ofSeconds(30);
    private static final Set<String> OPEN_EVENT_STATES = Set.of("RECRUITING", "LIVE");
    static final String SOURCE = "[A-Za-z0-9._:-]{1,100}";

    private final CameraCollectors collectors;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LatestFrames frames;
    private final Clock clock;

    public CameraFrameService(CameraCollectors collectors, JdbcTemplate jdbc, PlatformTransactionManager manager,
            LatestFrames frames, Clock clock) {
        this.collectors = collectors;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.frames = frames;
        this.clock = clock;
    }

    /** 수신 확인. 카메라 id·토큰은 돌려주지 않는다. */
    public record Ack(Instant receivedAt, String sequence) {
    }

    public record Headers(String authorization, String source, String streamSession, String sequence, String capturedAt,
            String captureTimeBasis) {
    }

    /** 본문 읽기. 인증을 먼저 끝낸 뒤 최대 limit 바이트까지만 읽는다(인증 없는 큰 요청을 메모리에 올리지 않게). */
    @FunctionalInterface
    public interface Body {
        byte[] read(int limit) throws IOException;
    }

    public Ack ingest(String collectorId, Headers headers, Body reader) {
        Collector collector = authenticate(collectorId, headers.authorization());
        String source = headers.source();
        if (source == null || !source.matches(SOURCE)) throw invalid("X-SceneCrew-Source", source == null ? "REQUIRED" : "FORMAT");
        // 등록 설정에 없는 연결 식별자는 같은 행사 카메라라도 받지 않는다
        if (!collector.sources().contains(source)) throw new BusinessException(ErrorCode.COLLECTOR_SCOPE_FORBIDDEN);
        UUID session = uuid(headers.streamSession());
        long sequence = sequence(headers.sequence());
        Instant capturedAt = time(headers.capturedAt());
        if (!CAPTURE_TIME_BASIS.equals(headers.captureTimeBasis()))
            throw invalid("X-SceneCrew-Capture-Time-Basis", headers.captureTimeBasis() == null ? "REQUIRED" : "UNSUPPORTED");
        byte[] body = readJpeg(reader, CameraFrameService::invalid);

        Instant now = clock.instant();
        if (capturedAt.isAfter(now.plus(FUTURE_SKEW))) throw invalid("X-SceneCrew-Captured-At", "FUTURE");
        if (capturedAt.isBefore(now.minus(MAX_AGE))) throw outdated("TOO_OLD");

        Frame frame = tx.execute(status -> accept(collector, source, session, sequence, capturedAt, now, body));
        frames.put(frame);
        return new Ack(now, Long.toString(sequence));
    }

    /**
     * 본문을 최대 MAX_FRAME_BYTES까지 읽고 JPEG 모양(앞 3바이트)만 확인한다. 웹 연결 경로(#393)도 같이 쓴다.
     * 형식 오류는 invalid(field, reason)로 만든다(내부 경로와 공개 경로의 오류 코드가 다르다). 크기 초과는 413.
     */
    public static byte[] readJpeg(Body reader, BiFunction<String, String, BusinessException> invalid) {
        byte[] body;
        try {
            body = reader.read(MAX_FRAME_BYTES + 1);
        } catch (IOException e) {
            throw invalid.apply("body", "READ_FAILED");
        }
        if (body == null || body.length == 0) throw invalid.apply("body", "REQUIRED");
        if (body.length > MAX_FRAME_BYTES) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        if (body.length < 3 || (body[0] & 0xFF) != 0xFF || (body[1] & 0xFF) != 0xD8 || (body[2] & 0xFF) != 0xFF)
            throw invalid.apply("body", "NOT_JPEG");
        return body;
    }

    private Frame accept(Collector collector, String source, UUID session, long sequence, Instant capturedAt, Instant now,
            byte[] body) {
        String eventState = jdbc.query("SELECT state FROM event WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null,
                collector.eventId());
        if (eventState == null || !OPEN_EVENT_STATES.contains(eventState)) throw new BusinessException(ErrorCode.COLLECTOR_EVENT_CLOSED);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, enabled, link_id, stream_session_id, last_sequence, last_captured_at
                  FROM safety_camera WHERE event_id = ? AND source_binding_ref = ? FOR UPDATE""", collector.eventId(), source);
        if (rows.isEmpty()) throw unmapped(source, "NOT_FOUND");
        // 같은 연결 식별자를 두 카메라에 등록하면 어느 카메라의 화면인지 정할 수 없다.
        if (rows.size() > 1) throw unmapped(source, "DUPLICATE");
        var row = rows.getFirst();
        UUID cameraId = (UUID) row.get("id");
        if (!Boolean.TRUE.equals(row.get("enabled"))) throw new BusinessException(ErrorCode.CAMERA_DISABLED);
        // 웹으로 연결된 카메라는 이 경로의 입력을 받지 않는다(V9-D60). 예비 경로를 쓰려면 웹에서 연결을 해제한다.
        if (row.get("link_id") != null) throw new BusinessException(ErrorCode.INPUT_LINKED_ELSEWHERE);

        UUID lastSession = (UUID) row.get("stream_session_id");
        Number lastSequence = (Number) row.get("last_sequence");
        Instant lastCaptured = instant(row.get("last_captured_at"));
        if (session.equals(lastSession) && lastSequence != null && sequence <= lastSequence.longValue())
            throw outdated("SEQUENCE");
        if (lastCaptured != null && capturedAt.isBefore(lastCaptured)) throw outdated("CAPTURED_AT");

        jdbc.update("""
                UPDATE safety_camera SET connection_state = 'CONNECTED', input_kind = 'COLLECTOR', stream_session_id = ?, last_sequence = ?,
                       last_captured_at = ?, last_received_at = ?
                 WHERE id = ?""", session, sequence, capturedAt.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), cameraId);
        return new Frame(collector.eventId(), cameraId, session, sequence, capturedAt, CAPTURE_TIME_BASIS, now, body, sha256(body));
    }

    private Collector authenticate(String collectorId, String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()) : null;
        // 없는 수집기와 틀린 토큰을 구별해 알려 주지 않는다
        return collectors.find(collectorId)
                .filter(c -> token != null && !token.isEmpty() && c.accepts(token))
                .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTOR_UNAUTHENTICATED));
    }

    private static UUID uuid(String value) {
        if (value == null) throw invalid("X-SceneCrew-Stream-Session", "REQUIRED");
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value.toLowerCase())) throw invalid("X-SceneCrew-Stream-Session", "FORMAT");
            return id;
        } catch (IllegalArgumentException e) {
            throw invalid("X-SceneCrew-Stream-Session", "FORMAT");
        }
    }

    private static long sequence(String value) {
        if (value == null) throw invalid("X-SceneCrew-Sequence", "REQUIRED");
        if (!value.matches("[1-9][0-9]{0,17}")) throw invalid("X-SceneCrew-Sequence", "FORMAT");
        return Long.parseLong(value);
    }

    private static Instant time(String value) {
        if (value == null) throw invalid("X-SceneCrew-Captured-At", "REQUIRED");
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid("X-SceneCrew-Captured-At", "FORMAT");
        }
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp t) return t.toInstant();
        if (value instanceof OffsetDateTime o) return o.toInstant();
        return (Instant) value;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_COLLECTOR_REQUEST, ErrorCode.INVALID_COLLECTOR_REQUEST.getMessage(),
                Map.of("field", field, "reason", reason));
    }

    private static BusinessException outdated(String reason) {
        return new BusinessException(ErrorCode.CAMERA_FRAME_OUTDATED, ErrorCode.CAMERA_FRAME_OUTDATED.getMessage(),
                Map.of("reason", reason));
    }

    private static BusinessException unmapped(String source, String reason) {
        return new BusinessException(ErrorCode.CAMERA_SOURCE_UNMAPPED, ErrorCode.CAMERA_SOURCE_UNMAPPED.getMessage(),
                Map.of("source", source, "reason", reason));
    }
}

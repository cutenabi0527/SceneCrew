package com.ssafy.scenecrew.safety.collector;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.safety.collector.CrowdEstimator.EstimateException;
import com.ssafy.scenecrew.safety.collector.CrowdEstimator.ZoneCount;
import com.ssafy.scenecrew.safety.collector.LatestFrames.Frame;

/**
 * 최신 프레임 → 밀집도 추정 → 관측 저장(#349), 그리고 연결 상태·최신성 갱신과 오래된 관측 정리.
 *
 * <p>카메라마다 interval(기본 10초)에 한 번, 그때 메모리에 있는 최신 프레임 1장만 분석한다. 밀린 프레임을 쌓아 두고 차례로 처리하지 않는다.
 * 한 카메라의 분석이 끝나기 전에는 그 카메라의 다음 분석을 시작하지 않는다. 이미 분석한 프레임(같은 세션·번호)은 새 프레임이 올 때까지
 * 다시 보내지 않는다(웹캠이 멈췄을 때 같은 사진으로 AI 자원을 쓰지 않게). 그 사이 최신성은 sweep()이 STALE로 바꾼다.
 *
 * <p>저장 규칙(BE13·ai/11)
 * <ul>
 *   <li>카메라 coverage에 있는 구역만 estimates에 넣는다. 비추지 않는 구역은 빠지므로 지도에서 미관측이다(0명으로 채우지 않음).</li>
 *   <li>추정 인원을 믿을 수 없으면 null, 범위가 PARTIAL이면 PARTIAL_COVERAGE, UNDETERMINED면 인원 null이다.</li>
 *   <li>밀도는 같은 범위의 확인된 유효 면적이 있을 때만 인원 ÷ 면적이다. 위험 기준은 아직 판정하지 않는다(UNASSESSED).</li>
 *   <li>카메라 범위가 무효(INVALID_MAPPING)면 AI를 부르지 않고 인원 null·NEEDS_REVIEW로 남긴다.</li>
 *   <li>분석하는 사이 카메라 설정·세션이 바뀌었거나 더 늦게 찍은 관측이 이미 있으면 결과를 버린다(옛 결과가 최신값을 덮지 않게).</li>
 * </ul>
 */
public class CameraAnalyzer implements DisposableBean {
    /** 이 시간 동안 프레임이 없으면 연결 끊김(OFFLINE)이다. */
    public static final Duration OFFLINE_AFTER = Duration.ofSeconds(15);
    /** 이 시간 동안 분석 결과가 없으면 최신성이 STALE이다. */
    public static final Duration STALE_AFTER = Duration.ofSeconds(30);
    /** 이보다 오래 받은 프레임은 메모리에서 지운다(분석·미리보기 모두 쓰지 않음). */
    public static final Duration FRAME_TTL = Duration.ofSeconds(60);
    /** 관측(구역 집계·품질 메타데이터) 보관 기간(BE13) */
    public static final Duration RETENTION = Duration.ofDays(30);
    static final String PARTIAL_COVERAGE = "PARTIAL_COVERAGE";
    static final String UNDETERMINED_SCOPE = "UNDETERMINED_SCOPE";
    static final String INVALID_MAPPING = "INVALID_MAPPING";
    static final String NOT_RETURNED = "NOT_RETURNED";
    private static final Logger log = LoggerFactory.getLogger(CameraAnalyzer.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LatestFrames frames;
    private final CrowdEstimator estimator;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration interval;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Instant> lastStarted = new ConcurrentHashMap<>();
    /** 카메라별 마지막으로 분석을 시작한 프레임(세션·번호) */
    private final Map<UUID, String> lastAnalyzed = new ConcurrentHashMap<>();
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public CameraAnalyzer(LatestFrames frames, CrowdEstimator estimator, JdbcTemplate jdbc, PlatformTransactionManager manager,
            Clock clock, Duration interval) {
        this.frames = frames;
        this.estimator = estimator;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
        this.interval = interval;
    }

    /** 주기 작업 한 번: 오래된 프레임을 지우고, 분석할 때가 된 카메라의 분석을 시작한다. */
    public void tick() {
        Instant now = clock.instant();
        for (Frame frame : frames.all()) {
            if (frame.receivedAt().isBefore(now.minus(FRAME_TTL))) {
                frames.discard(frame);
                continue;
            }
            UUID camera = frame.cameraId();
            String key = frame.streamSessionId() + ":" + frame.sequence();
            if (key.equals(lastAnalyzed.get(camera))) continue;   // 새 프레임이 아직 없다
            Instant last = lastStarted.get(camera);
            if (last != null && now.isBefore(last.plus(interval))) continue;
            if (!running.add(camera)) continue;
            lastStarted.put(camera, now);
            lastAnalyzed.put(camera, key);
            executor.submit(() -> {
                try {
                    analyze(frame);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    log.warn("카메라 {} 프레임 분석 실패", camera, e);
                } finally {
                    running.remove(camera);
                }
            });
        }
    }

    /**
     * 프레임 1장을 분석해 관측으로 저장한다. 저장했으면 관측 id, 버렸으면 null.
     * 같은 프레임(카메라·세션·번호)은 한 번만 저장된다.
     */
    public UUID analyze(Frame frame) throws InterruptedException {
        var camera = jdbc.query("""
                SELECT c.enabled, c.revision, c.coverage::text AS coverage, c.freshness, c.stream_session_id, e.state
                  FROM safety_camera c JOIN event e ON e.id = c.event_id WHERE c.id = ?""", (rs, n) -> new CameraRow(
                rs.getBoolean("enabled"), rs.getLong("revision"), JSON.readTree(rs.getString("coverage")), rs.getString("freshness"),
                rs.getObject("stream_session_id", UUID.class), rs.getString("state")), frame.cameraId())
                .stream().findFirst().orElse(null);
        if (camera == null || !camera.enabled() || !frame.streamSessionId().equals(camera.session())
                || !Set.of("RECRUITING", "LIVE").contains(camera.eventState())) {
            frames.discard(frame);   // 카메라가 없어졌거나 꺼졌거나 다른 입력으로 바뀌었다
            return null;
        }

        // 보정 전(coverage 없음) 카메라는 인원을 세지 않고 0명으로도 채우지 않는다(V9-D58). 프레임은 보정 화면을 위해 남긴다.
        if (camera.coverage().isEmpty()) return null;

        List<CrowdEstimator.Zone> zones = new ArrayList<>();
        for (JsonNode c : camera.coverage())
            zones.add(new CrowdEstimator.Zone(UUID.fromString(c.get("zoneId").asString()), c.get("scope").asString(), c.get("roi")));

        boolean invalidMapping = INVALID_MAPPING.equals(camera.freshness());
        CrowdEstimator.Result result = null;
        if (!invalidMapping) {
            try {
                result = estimator.estimate(new CrowdEstimator.Request(frame.eventId(), frame.cameraId(), frame.streamSessionId(),
                        frame.sequence(), frame.capturedAt(), frame.inputHash(), frame.jpeg(), zones));
            } catch (EstimateException e) {
                log.warn("카메라 {} 밀집도 추정 실패: {}", frame.cameraId(), e.getMessage());
                return null;
            }
        }
        List<Map<String, Object>> estimates = estimates(camera.coverage(), result);
        boolean allCounted = estimates.stream().allMatch(e -> e.get("estimatedCount") != null);
        String state = !invalidMapping && allCounted ? "READY" : "NEEDS_REVIEW";
        String freshness = invalidMapping ? INVALID_MAPPING : "FRESH";
        String modelVersion = result == null ? null : result.modelVersion();
        Instant processedAt = clock.instant();
        return tx.execute(status -> save(frame, camera, estimates, state, freshness, modelVersion, processedAt));
    }

    private UUID save(Frame frame, CameraRow read, List<Map<String, Object>> estimates, String state, String freshness,
            String modelVersion, Instant processedAt) {
        var now = jdbc.queryForList("""
                SELECT c.revision, c.stream_session_id, c.enabled, c.freshness, o.captured_at AS latest_captured_at,
                       o.stream_session_id AS latest_session_id
                  FROM safety_camera c LEFT JOIN safety_observation o ON o.id = c.latest_observation_id
                 WHERE c.id = ? FOR UPDATE OF c""", frame.cameraId());
        if (now.isEmpty()) return null;
        var row = now.getFirst();
        if (((Number) row.get("revision")).longValue() != read.revision() || !frame.streamSessionId().equals(row.get("stream_session_id"))
                || !Boolean.TRUE.equals(row.get("enabled")) || !read.freshness().equals(row.get("freshness")))
            return null;   // 분석하는 사이 설정·입력이 바뀌었다
        // 같은 입력 세션 안에서 더 늦게 찍은 관측이 이미 있으면 버린다. 다른 세션(재연결·연결 참조 변경)의 늦은 결과는
        // 위의 세션 확인에서 이미 걸러지므로, 노트북 시계 차이로 새 입력을 버리지 않게 세션이 같을 때만 비교한다.
        Object latest = row.get("latest_captured_at");
        if (latest != null && frame.streamSessionId().equals(row.get("latest_session_id"))
                && !frame.capturedAt().isAfter(instant(latest))) return null;

        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO safety_observation (id, event_id, camera_id, camera_revision, stream_session_id, sequence, captured_at,
                                                capture_time_basis, received_at, input_hash, coverage, state, freshness, estimates,
                                                model_version, processed_at, metadata_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (camera_id, stream_session_id, sequence) DO NOTHING""",
                id, frame.eventId(), frame.cameraId(), read.revision(), frame.streamSessionId(), frame.sequence(),
                utc(frame.capturedAt()), frame.captureTimeBasis(), utc(frame.receivedAt()), frame.inputHash(),
                JSON.writeValueAsString(read.coverage()), state, freshness, JSON.writeValueAsString(estimates), modelVersion, utc(processedAt),
                utc(frame.receivedAt().plus(RETENTION)));
        if (inserted == 0) return null;
        jdbc.update("UPDATE safety_camera SET latest_observation_id = ?, last_processed_at = ?, freshness = ? WHERE id = ?",
                id, utc(processedAt), freshness, frame.cameraId());
        return id;
    }

    /** coverage 순서대로 구역별 추정을 만든다. result가 null이면(범위 무효) 모두 인원 null이다. */
    static List<Map<String, Object>> estimates(JsonNode coverage, CrowdEstimator.Result result) {
        Map<String, ZoneCount> counts = new LinkedHashMap<>();
        if (result != null) for (ZoneCount z : result.zones()) counts.put(z.zoneId().toString(), z);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode c : coverage) {
            String zoneId = c.get("zoneId").asString();
            String scope = c.get("scope").asString();
            Set<String> reasons = new LinkedHashSet<>();
            Double count = null;
            if (result == null) {
                reasons.add(INVALID_MAPPING);
            } else {
                ZoneCount z = counts.get(zoneId);
                if (z == null) reasons.add(NOT_RETURNED);
                else {
                    reasons.addAll(z.qualityReasons());
                    count = z.estimatedCount();
                }
                if ("PARTIAL".equals(scope)) reasons.add(PARTIAL_COVERAGE);
                if ("UNDETERMINED".equals(scope)) {
                    reasons.add(UNDETERMINED_SCOPE);
                    count = null;   // 어디까지 보이는지 모르면 수를 내지 않는다
                }
            }
            JsonNode area = c.get("effectiveAreaM2");
            Double density = count != null && area != null && area.isNumber() && area.asDouble() > 0 ? count / area.asDouble() : null;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("zoneId", zoneId);
            e.put("estimatedCount", count);
            e.put("densityPerM2", density);
            e.put("qualityReasons", List.copyOf(reasons));
            e.put("riskLevel", "UNASSESSED");
            e.put("ruleRef", null);
            out.add(e);
        }
        return out;
    }

    /**
     * 연결 상태·최신성 갱신. 프레임이 OFFLINE_AFTER 동안 없으면 OFFLINE, 분석이 STALE_AFTER 동안 없으면
     * 카메라와 그 최신 관측을 STALE로 바꾼다(지도가 옛 값을 최신으로 보이지 않게).
     */
    public void sweep() {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE safety_camera SET connection_state = 'OFFLINE'
                     WHERE connection_state = 'CONNECTED' AND last_received_at < ?""", utc(now.minus(OFFLINE_AFTER)));
            jdbc.update("""
                    UPDATE safety_observation SET freshness = 'STALE'
                     WHERE freshness = 'FRESH' AND id IN (SELECT latest_observation_id FROM safety_camera
                                                           WHERE freshness = 'FRESH' AND last_processed_at < ?)""",
                    utc(now.minus(STALE_AFTER)));
            jdbc.update("UPDATE safety_camera SET freshness = 'STALE' WHERE freshness = 'FRESH' AND last_processed_at < ?",
                    utc(now.minus(STALE_AFTER)));
        });
    }

    /** 보관 기간(30일)이 지난 관측을 지운다. 지운 수를 돌려준다. */
    public int cleanup(int batchSize) {
        Instant now = clock.instant();
        Integer deleted = tx.execute(status -> {
            List<UUID> ids = jdbc.queryForList("SELECT id FROM safety_observation WHERE metadata_expires_at < ? ORDER BY metadata_expires_at LIMIT ?",
                    UUID.class, utc(now), batchSize);
            if (ids.isEmpty()) return 0;
            UUID[] array = ids.toArray(UUID[]::new);
            jdbc.update(con -> {
                var ps = con.prepareStatement("UPDATE safety_camera SET latest_observation_id = NULL WHERE latest_observation_id = ANY (?)");
                ps.setArray(1, con.createArrayOf("uuid", array));
                return ps;
            });
            return jdbc.update(con -> {
                var ps = con.prepareStatement("DELETE FROM safety_observation WHERE id = ANY (?)");
                ps.setArray(1, con.createArrayOf("uuid", array));
                return ps;
            });
        });
        return deleted == null ? 0 : deleted;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    private static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp t) return t.toInstant();
        if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
        return (Instant) value;
    }

    private static Object utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    private record CameraRow(boolean enabled, long revision, JsonNode coverage, String freshness, UUID session, String eventState) {
    }
}

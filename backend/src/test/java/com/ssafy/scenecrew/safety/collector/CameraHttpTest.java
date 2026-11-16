package com.ssafy.scenecrew.safety.collector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.safety.collector.LatestFrames.Frame;
import com.ssafy.scenecrew.safety.service.SafetySnapshotService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 카메라 프레임 수신(내부 경로)·현재 프레임 Q2·분석과 관측 저장(#349). 실제 소켓·제품 Security·PostgreSQL.
 * 수집기 등록은 운영과 같은 JSON 설정으로 넣고, 주기 작업은 끈 채 CameraAnalyzer를 직접 부른다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import(CameraHttpTest.Beans.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CameraHttpTest {
    static final String WEB = "https://web.example.invalid";
    static final String TOKEN = "camera-token-" + UUID.randomUUID();
    static final String OTHER_TOKEN = "camera-token-other-" + UUID.randomUUID();
    static final UUID EVENT = UUID.randomUUID(), OTHER_EVENT = UUID.randomUUID();
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    /** 가장 작은 JPEG 모양(SOI·APP0 시작). 서버는 앞 3바이트만 보고 그대로 보관한다. */
    static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, (byte) 0xFF, (byte) 0xD9 };

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled", () -> "true");
        r.add("scenecrew.auth.signing-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer", () -> "camera-test");
        r.add("scenecrew.auth.audience", () -> "camera-test");
        r.add("scenecrew.auth.web-origins", () -> WEB);
        r.add("scenecrew.auth.android-origins", () -> "https://android.example.invalid");
        r.add("scenecrew.safety.camera.enabled", () -> "true");
        r.add("scenecrew.safety.camera.scheduler.enabled", () -> "false");
        r.add("scenecrew.safety.camera.registrations", () -> """
                [{"collectorId":"camera-laptop","eventId":"%s","tokenSha256":"%s",
                  "sources":["cam-01","cam-off","cam-twice","cam-99"]},
                 {"collectorId":"camera-other","eventId":"%s","tokenSha256":"%s","sources":["cam-01"]}]
                """.formatted(EVENT, CameraCollectors.sha256(TOKEN), OTHER_EVENT, CameraCollectors.sha256(OTHER_TOKEN)));
    }

    @AfterAll
    static void stop() {
        REDIS.stop();
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired LatestFrames frames;
    @Autowired CameraAnalyzer analyzer;
    @Autowired PlatformTransactionManager manager;
    @Autowired CurrentMembers members;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    final List<UUID> accounts = new ArrayList<>();
    UUID chief, worker, outsider, stage, lobby, hall;
    String chiefCookie, workerCookie, outsiderCookie;

    @BeforeEach
    void fixture() throws Exception {
        for (UUID e : List.of(EVENT, OTHER_EVENT))
            jdbc.update("INSERT INTO event (id, name, state, revision, last_input_modified_at) VALUES (?, '카메라 시험', 'LIVE', 1, now())"
                    + " ON CONFLICT (id) DO UPDATE SET state = 'LIVE'", e);
        chief = account();
        worker = account();
        outsider = account();
        member(chief, "CHIEF", 1);
        member(worker, "WORKER", 2);
        chiefCookie = login(chief);
        workerCookie = login(worker);
        outsiderCookie = login(outsider);
        stage = zone("무대 앞");
        lobby = zone("로비");
        hall = zone("객석 뒤");   // 어느 카메라도 비추지 않는 구역
    }

    @AfterEach
    void cleanup() {
        for (UUID e : List.of(EVENT, OTHER_EVENT)) {
            jdbc.update("UPDATE safety_camera SET latest_observation_id = NULL WHERE event_id = ?", e);
            jdbc.update("DELETE FROM safety_observation WHERE event_id = ?", e);
            jdbc.queryForList("SELECT id FROM safety_camera WHERE event_id = ?", UUID.class, e).forEach(frames::discardCamera);
            jdbc.update("DELETE FROM safety_camera WHERE event_id = ?", e);
            jdbc.update("DELETE FROM zone WHERE event_id = ?", e);
            jdbc.update("DELETE FROM member WHERE event_id = ?", e);
        }
        for (UUID a : accounts) {
            jdbc.update("DELETE FROM auth_identity WHERE account_id = ?", a);
            jdbc.update("DELETE FROM account WHERE id = ?", a);
        }
        accounts.clear();
    }

    // ---- 수신 --------------------------------------------------------------------------------------------

    @Test
    void frameUpdatesConnectionAndChiefSeesTheSameBytes() throws Exception {
        UUID camera = camera("cam-01", true);
        UUID session = UUID.randomUUID();
        Instant captured = Instant.now().minusMillis(500);
        var sent = send(frame(session, 1, captured), JPEG);
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(202);
        assertThat(json.readTree(sent.body()).propertyNames()).containsExactlyInAnyOrder("receivedAt", "sequence");
        assertThat(json.readTree(sent.body()).get("sequence").asString()).isEqualTo("1");

        var row = jdbc.queryForMap("SELECT connection_state, stream_session_id, last_sequence, last_captured_at, last_received_at"
                + " FROM safety_camera WHERE id = ?", camera);
        assertThat(row.get("connection_state")).isEqualTo("CONNECTED");
        assertThat(row.get("stream_session_id")).isEqualTo(session);
        assertThat(((Number) row.get("last_sequence")).longValue()).isEqualTo(1);
        assertThat(row.get("last_received_at")).isNotNull();

        var snap = http.send(HttpRequest.newBuilder(URI.create(url("/api/v1/safety-cameras/" + camera + "/snapshot")))
                .header("Cookie", chiefCookie).header("Accept", "image/jpeg, application/json").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(snap.statusCode()).isEqualTo(200);
        assertThat(snap.body()).isEqualTo(JPEG);
        assertThat(snap.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(snap.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat(Instant.parse(snap.headers().firstValue("X-Captured-At").orElseThrow())).isEqualTo(captured);
        assertThat(snap.headers().firstValue("X-Received-At")).isPresent();
        assertThat(snap.headers().firstValue("X-Frame-Freshness")).hasValue("FRESH");

        // 팀원 403, 참여하지 않은 사람 404, 로그인 없음 401
        error(snapshot(camera, workerCookie), 403, "FORBIDDEN");
        error(snapshot(camera, outsiderCookie), 404, "NOT_FOUND");
        assertThat(http.send(HttpRequest.newBuilder(URI.create(url("/api/v1/safety-cameras/" + camera + "/snapshot"))).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void framesKeepOrderWithinASessionAndAcrossReconnects() throws Exception {
        camera("cam-01", true);
        UUID first = UUID.randomUUID();
        Instant t = Instant.now().minusSeconds(10);
        assertThat(send(frame(first, 1, t), JPEG).statusCode()).isEqualTo(202);
        assertThat(send(frame(first, 2, t.plusSeconds(2)), JPEG).statusCode()).isEqualTo(202);
        // 같은 세션의 지난 번호
        reason(send(frame(first, 2, t.plusSeconds(3)), JPEG), 409, "CAMERA_FRAME_OUTDATED", "SEQUENCE");
        reason(send(frame(first, 1, t.plusSeconds(4)), JPEG), 409, "CAMERA_FRAME_OUTDATED", "SEQUENCE");
        // 다시 연결한 새 세션은 1번부터지만, 이미 받은 프레임보다 먼저 찍은 것은 받지 않는다(옛 세션의 늦은 전송)
        UUID second = UUID.randomUUID();
        reason(send(frame(second, 1, t.plusSeconds(1)), JPEG), 409, "CAMERA_FRAME_OUTDATED", "CAPTURED_AT");
        assertThat(send(frame(second, 1, t.plusSeconds(5)), JPEG).statusCode()).isEqualTo(202);
        // 너무 오래된 프레임은 현재 화면이 아니다
        reason(send(frame(UUID.randomUUID(), 1, Instant.now().minusSeconds(31)), JPEG), 409, "CAMERA_FRAME_OUTDATED", "TOO_OLD");
    }

    @Test
    void rejectsBadCollectorsSourcesAndFrames() throws Exception {
        camera("cam-01", true);
        camera("cam-off", false);
        camera("cam-twice", true);
        camera("cam-twice", true);
        camera("cam-02", true);   // 이 행사 카메라지만 수집기 설정에 없는 연결 식별자
        UUID s = UUID.randomUUID();
        Instant now = Instant.now();

        error(send(frame(s, 1, now).setHeader("Authorization", "Bearer wrong"), JPEG), 401, "COLLECTOR_UNAUTHENTICATED");
        error(send(frame("camera-laptop", null, "cam-01", s, 1, now), JPEG), 401, "COLLECTOR_UNAUTHENTICATED");
        error(send(frame("nobody", TOKEN, "cam-01", s, 1, now), JPEG), 401, "COLLECTOR_UNAUTHENTICATED");
        // 다른 행사의 수집기는 이 행사 카메라를 찾지 못한다
        reason(send(frame("camera-other", OTHER_TOKEN, "cam-01", s, 1, now), JPEG), 422, "CAMERA_SOURCE_UNMAPPED", "NOT_FOUND");
        reason(send(frame("camera-laptop", TOKEN, "cam-99", s, 1, now), JPEG), 422, "CAMERA_SOURCE_UNMAPPED", "NOT_FOUND");
        reason(send(frame("camera-laptop", TOKEN, "cam-twice", s, 1, now), JPEG), 422, "CAMERA_SOURCE_UNMAPPED", "DUPLICATE");
        error(send(frame("camera-laptop", TOKEN, "cam-off", s, 1, now), JPEG), 409, "CAMERA_DISABLED");
        // 같은 행사 카메라라도 수집기에 허용되지 않은 연결 식별자는 403
        error(send(frame("camera-laptop", TOKEN, "cam-02", s, 1, now), JPEG), 403, "COLLECTOR_SCOPE_FORBIDDEN");

        field(send(frame(s, 1, now), "not a jpeg".getBytes()), "body", "NOT_JPEG");
        field(send(frame(s, 1, now), new byte[0]), "body", "REQUIRED");
        field(send(frame("camera-laptop", TOKEN, "rtsp://cam", s, 1, now), JPEG), "X-SceneCrew-Source", "FORMAT");
        field(send(frame(s, 0, now), JPEG), "X-SceneCrew-Sequence", "FORMAT");
        field(send(frame(s, 1, now.plusSeconds(60)), JPEG), "X-SceneCrew-Captured-At", "FUTURE");
        field(send(frame(s, 1, now).setHeader("X-SceneCrew-Captured-At", "yesterday"), JPEG), "X-SceneCrew-Captured-At", "FORMAT");
        field(send(frame(s, 1, now).setHeader("X-SceneCrew-Stream-Session", "abc"), JPEG), "X-SceneCrew-Stream-Session", "FORMAT");
        field(send(frame(s, 1, now).setHeader("X-SceneCrew-Capture-Time-Basis", "RECEIVED"), JPEG), "X-SceneCrew-Capture-Time-Basis",
                "UNSUPPORTED");
        byte[] large = new byte[CameraFrameService.MAX_FRAME_BYTES + 1];
        System.arraycopy(JPEG, 0, large, 0, 3);
        error(send(frame(s, 1, now), large), 413, "PAYLOAD_TOO_LARGE");

        jdbc.update("UPDATE event SET state = 'ENDED' WHERE id = ?", EVENT);
        error(send(frame(s, 1, now), JPEG), 409, "COLLECTOR_EVENT_CLOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_camera WHERE event_id = ? AND last_sequence IS NOT NULL",
                Integer.class, EVENT)).isZero();
    }

    // ---- 현재 프레임 ----------------------------------------------------------------------------------------

    @Test
    void snapshotNeverShowsAnOldOrMissingFrameAsCurrent() throws Exception {
        UUID camera = camera("cam-01", true);
        reason(snapshot(camera, chiefCookie), 503, "SNAPSHOT_UNAVAILABLE", "NO_FRAME");
        assertThat(snapshot(camera, chiefCookie).headers().firstValue("Cache-Control").orElse("")).contains("no-store");

        UUID session = UUID.randomUUID();
        assertThat(send(frame(session, 1, Instant.now()), JPEG).statusCode()).isEqualTo(202);
        Frame frame = frames.get(camera);
        // 받은 지 6초가 지나면 STALE로 알리고, 60초가 지나면 주지 않는다
        var later = snapshotAt(Duration.ofSeconds(7), camera);
        assertThat(later.freshness()).isEqualTo("STALE");
        assertThatThrownBy(() -> snapshotAt(Duration.ofSeconds(61), camera)).isInstanceOf(BusinessException.class)
                .hasMessage("지금 보여 줄 수 있는 카메라 화면이 없습니다.");

        // 연결 참조를 바꾸면 세션이 비워지므로 옛 입력의 화면은 주지 않는다
        jdbc.update("UPDATE safety_camera SET stream_session_id = NULL WHERE id = ?", camera);
        reason(snapshot(camera, chiefCookie), 503, "SNAPSHOT_UNAVAILABLE", "NO_FRAME");
        jdbc.update("UPDATE safety_camera SET stream_session_id = ? WHERE id = ?", frame.streamSessionId(), camera);
        // 연결이 끊겼으면 OFFLINE, 카메라를 끄거나 행사가 끝나면 409
        jdbc.update("UPDATE safety_camera SET connection_state = 'OFFLINE' WHERE id = ?", camera);
        reason(snapshot(camera, chiefCookie), 503, "SNAPSHOT_UNAVAILABLE", "OFFLINE");
        jdbc.update("UPDATE safety_camera SET enabled = false, connection_state = 'DISABLED' WHERE id = ?", camera);
        reason(snapshot(camera, chiefCookie), 409, "INVALID_STATE", "CAMERA_DISABLED");
        jdbc.update("UPDATE safety_camera SET enabled = true, connection_state = 'CONNECTED' WHERE id = ?", camera);
        jdbc.update("UPDATE event SET state = 'ENDED' WHERE id = ?", EVENT);
        reason(snapshot(camera, chiefCookie), 409, "INVALID_STATE", "EVENT_CLOSED");
        error(snapshot(UUID.randomUUID(), chiefCookie), 404, "NOT_FOUND");
    }

    // ---- 분석·관측 저장 ------------------------------------------------------------------------------------

    @Test
    void analysisStoresOnlyCoveredZonesAndMarksTestValues() throws Exception {
        UUID camera = camera("cam-01", true);
        assertThat(send(frame(UUID.randomUUID(), 1, Instant.now()), JPEG).statusCode()).isEqualTo(202);
        Frame frame = frames.get(camera);

        UUID id = analyzer.analyze(frame);
        assertThat(id).isNotNull();
        var row = jdbc.queryForMap("SELECT state, freshness, model_version, estimates::text AS estimates, input_hash, sequence,"
                + " capture_time_basis, metadata_expires_at, received_at FROM safety_observation WHERE id = ?", id);
        assertThat(row.get("model_version")).isEqualTo(StubCrowdEstimator.MODEL_VERSION);
        assertThat(row.get("freshness")).isEqualTo("FRESH");
        assertThat(row.get("state")).isEqualTo("NEEDS_REVIEW");   // 로비는 범위 미확정이라 인원이 없다
        assertThat(row.get("input_hash")).isEqualTo(CameraFrameService.sha256(JPEG));
        assertThat(row.get("capture_time_basis")).isEqualTo("COLLECTOR_CLOCK");
        assertThat(((java.sql.Timestamp) row.get("metadata_expires_at")).toInstant())
                .isEqualTo(((java.sql.Timestamp) row.get("received_at")).toInstant().plus(Duration.ofDays(30)));

        JsonNode estimates = json.readTree((String) row.get("estimates"));
        assertThat(estimates).hasSize(2);   // 객석 뒤는 비추지 않으므로 아예 없다(0명이 아님)
        JsonNode s = estimates.get(0), l = estimates.get(1);
        assertThat(s.propertyNames()).containsExactlyInAnyOrder("zoneId", "estimatedCount", "densityPerM2", "qualityReasons",
                "riskLevel", "ruleRef");
        assertThat(s.get("zoneId").asString()).isEqualTo(stage.toString());
        assertThat(s.get("estimatedCount").isNumber()).isTrue();
        assertThat(s.get("densityPerM2").asDouble()).isEqualTo(s.get("estimatedCount").asDouble() / 40);
        assertThat(texts(s.get("qualityReasons"))).containsExactly("TEST_VALUE", "PARTIAL_COVERAGE");
        assertThat(s.get("riskLevel").asString()).isEqualTo("UNASSESSED");
        assertThat(s.get("ruleRef").isNull()).isTrue();
        assertThat(l.get("zoneId").asString()).isEqualTo(lobby.toString());
        assertThat(l.get("estimatedCount").isNull()).isTrue();
        assertThat(l.get("densityPerM2").isNull()).isTrue();
        assertThat(texts(l.get("qualityReasons"))).contains("UNDETERMINED_SCOPE");

        var cam = jdbc.queryForMap("SELECT latest_observation_id, freshness, last_processed_at FROM safety_camera WHERE id = ?", camera);
        assertThat(cam.get("latest_observation_id")).isEqualTo(id);
        assertThat(cam.get("freshness")).isEqualTo("FRESH");
        assertThat(cam.get("last_processed_at")).isNotNull();

        // 지도에 카메라의 최신 관측으로 나온다
        var map = json.readTree(get("/api/v1/events/" + EVENT + "/safety-map", chiefCookie).body());
        assertThat(map.get("observations")).hasSize(1);
        assertThat(map.get("observations").get(0).get("id").asString()).isEqualTo(id.toString());
        assertThat(map.get("observations").get(0).get("modelVersion").asString()).isEqualTo("test-stub");

        // 같은 프레임은 다시 저장하지 않는다
        assertThat(analyzer.analyze(frame)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_observation WHERE camera_id = ?", Integer.class, camera)).isEqualTo(1);
    }

    @Test
    void invalidMappingSkipsTheModelAndLeavesCountsEmpty() throws Exception {
        UUID camera = camera("cam-01", true);
        jdbc.update("UPDATE safety_camera SET freshness = 'INVALID_MAPPING' WHERE id = ?", camera);
        assertThat(send(frame(UUID.randomUUID(), 1, Instant.now()), JPEG).statusCode()).isEqualTo(202);
        AtomicInteger calls = new AtomicInteger();
        var analyzer = analyzer(r -> {
            calls.incrementAndGet();
            throw new AssertionError("범위가 무효면 모델을 부르지 않는다");
        }, Duration.ZERO);

        UUID id = analyzer.analyze(frames.get(camera));
        assertThat(calls).hasValue(0);
        var row = jdbc.queryForMap("SELECT state, freshness, model_version, estimates::text AS estimates FROM safety_observation WHERE id = ?", id);
        assertThat(row.get("state")).isEqualTo("NEEDS_REVIEW");
        assertThat(row.get("freshness")).isEqualTo("INVALID_MAPPING");
        assertThat(row.get("model_version")).isNull();
        for (JsonNode e : json.readTree((String) row.get("estimates"))) {
            assertThat(e.get("estimatedCount").isNull()).isTrue();
            assertThat(texts(e.get("qualityReasons"))).containsExactly("INVALID_MAPPING");
        }
        assertThat(jdbc.queryForObject("SELECT freshness FROM safety_camera WHERE id = ?", String.class, camera)).isEqualTo("INVALID_MAPPING");
    }

    @Test
    void resultIsDroppedWhenTheCameraChangesWhileTheModelRuns() throws Exception {
        UUID camera = camera("cam-01", true);
        assertThat(send(frame(UUID.randomUUID(), 1, Instant.now()), JPEG).statusCode()).isEqualTo(202);
        var analyzer = analyzer(r -> {
            jdbc.update("UPDATE safety_camera SET revision = revision + 1 WHERE id = ?", camera);   // 그 사이 재보정
            return new StubCrowdEstimator().estimate(r);
        }, Duration.ZERO);
        assertThat(analyzer.analyze(frames.get(camera))).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_observation WHERE camera_id = ?", Integer.class, camera)).isZero();
    }

    @Test
    void failedEstimateLeavesNoObservation() throws Exception {
        UUID camera = camera("cam-01", true);
        assertThat(send(frame(UUID.randomUUID(), 1, Instant.now()), JPEG).statusCode()).isEqualTo(202);
        var analyzer = analyzer(r -> {
            throw new CrowdEstimator.EstimateException("밀집도 AI 연결 실패", null);
        }, Duration.ZERO);
        assertThat(analyzer.analyze(frames.get(camera))).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_observation WHERE camera_id = ?", Integer.class, camera)).isZero();
        assertThat(frames.get(camera)).isNotNull();   // 미리보기는 계속된다
    }

    @Test
    void sameFrameIsNotSentToTheModelAgainUntilANewOneArrives() throws Exception {
        UUID camera = camera("cam-01", true);
        assertThat(send(frame(UUID.randomUUID(), 1, Instant.now()), JPEG).statusCode()).isEqualTo(202);
        AtomicInteger calls = new AtomicInteger();
        var analyzer = analyzer(r -> {
            calls.incrementAndGet();
            return new StubCrowdEstimator().estimate(r);
        }, Duration.ZERO, Duration.ZERO);   // 분석 간격 0: 매 tick이 분석할 때다
        analyzer.tick();
        await(() -> calls.get() == 1 && jdbc.queryForObject(
                "SELECT count(*) FROM safety_observation WHERE camera_id = ?", Integer.class, camera) == 1);
        for (int i = 0; i < 5; i++) {
            analyzer.tick();   // 웹캠이 멈춰 같은 프레임만 남아 있다
            Thread.sleep(20);
        }
        assertThat(calls).hasValue(1);

        Frame first = frames.get(camera);
        assertThat(send(frame(first.streamSessionId(), 2, Instant.now()), JPEG).statusCode()).isEqualTo(202);
        await(() -> {
            analyzer.tick();   // 앞 분석이 막 끝나는 중이면 다음 tick에서 시작한다
            return calls.get() == 2;
        });
        assertThat(calls).hasValue(2);
    }

    @Test
    void observationFromANewLaptopWithASlowerClockIsKept() throws Exception {
        UUID camera = camera("cam-01", true);
        assertThat(send(frame(UUID.randomUUID(), 1, Instant.now()), JPEG).statusCode()).isEqualTo(202);
        assertThat(analyzer.analyze(frames.get(camera))).isNotNull();
        // 연결 참조를 바꾸면(EXT24) 세션·번호·촬영 시각을 비운다. 새 노트북 시계는 옛 노트북보다 20초 늦다
        jdbc.update("UPDATE safety_camera SET stream_session_id = NULL, last_sequence = NULL, last_captured_at = NULL WHERE id = ?", camera);
        assertThat(send(frame(UUID.randomUUID(), 1, Instant.now().minusSeconds(20)), JPEG).statusCode()).isEqualTo(202);
        assertThat(analyzer.analyze(frames.get(camera))).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_observation WHERE camera_id = ?", Integer.class, camera)).isEqualTo(2);
    }

    @Test
    void sweepMarksSilentCamerasOfflineAndOldResultsStale() throws Exception {
        UUID camera = camera("cam-01", true);
        assertThat(send(frame(UUID.randomUUID(), 1, Instant.now()), JPEG).statusCode()).isEqualTo(202);
        UUID id = analyzer.analyze(frames.get(camera));

        analyzer(new StubCrowdEstimator(), Duration.ofSeconds(10)).sweep();   // 아직은 그대로
        assertThat(jdbc.queryForObject("SELECT connection_state FROM safety_camera WHERE id = ?", String.class, camera)).isEqualTo("CONNECTED");
        assertThat(jdbc.queryForObject("SELECT freshness FROM safety_camera WHERE id = ?", String.class, camera)).isEqualTo("FRESH");

        analyzer(new StubCrowdEstimator(), Duration.ofSeconds(31)).sweep();
        assertThat(jdbc.queryForObject("SELECT connection_state FROM safety_camera WHERE id = ?", String.class, camera)).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT freshness FROM safety_camera WHERE id = ?", String.class, camera)).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("SELECT freshness FROM safety_observation WHERE id = ?", String.class, id)).isEqualTo("STALE");

        // 다시 프레임이 오면 연결됨, 다시 분석하면 FRESH
        assertThat(send(frame(UUID.randomUUID(), 1, Instant.now()), JPEG).statusCode()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT connection_state FROM safety_camera WHERE id = ?", String.class, camera)).isEqualTo("CONNECTED");
        assertThat(analyzer.analyze(frames.get(camera))).isNotNull();
        assertThat(jdbc.queryForObject("SELECT freshness FROM safety_camera WHERE id = ?", String.class, camera)).isEqualTo("FRESH");
    }

    @Test
    void cleanupRemovesObservationsAfterThirtyDays() throws Exception {
        UUID camera = camera("cam-01", true);
        assertThat(send(frame(UUID.randomUUID(), 1, Instant.now()), JPEG).statusCode()).isEqualTo(202);
        UUID id = analyzer.analyze(frames.get(camera));
        assertThat(analyzer(new StubCrowdEstimator(), Duration.ofDays(29)).cleanup(100)).isZero();
        assertThat(analyzer(new StubCrowdEstimator(), Duration.ofDays(31)).cleanup(1000)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_observation WHERE id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_observation_id FROM safety_camera WHERE id = ?", UUID.class, camera)).isNull();
    }

    // ---- 도우미 -------------------------------------------------------------------------------------------

    CameraAnalyzer analyzer(CrowdEstimator estimator, Duration ahead) {
        return analyzer(estimator, ahead, Duration.ofSeconds(10));
    }

    CameraAnalyzer analyzer(CrowdEstimator estimator, Duration ahead, Duration interval) {
        return new CameraAnalyzer(frames, estimator, jdbc, manager, Clock.offset(Clock.systemUTC(), ahead), interval);
    }

    static void await(java.util.function.BooleanSupplier done) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("5초 안에 끝나지 않았습니다");
            Thread.sleep(20);
        }
    }

    /** 시각만 앞당긴 현재 프레임 조회. 운영 빈처럼 읽기 트랜잭션 안에서 부른다. */
    SafetySnapshotService.Snapshot snapshotAt(Duration ahead, UUID camera) {
        var service = new SafetySnapshotService(jdbc, members, frames, Clock.offset(Clock.systemUTC(), ahead), CameraAnalyzer.FRAME_TTL);
        var tx = new TransactionTemplate(manager);
        tx.setReadOnly(true);
        return tx.execute(status -> service.snapshot(camera, chief));
    }

    /** 무대 앞(PARTIAL, 면적 40㎡)과 로비(UNDETERMINED, 면적 없음)를 비추는 카메라 */
    UUID camera(String binding, boolean enabled) {
        UUID id = UUID.randomUUID();
        String coverage = """
                [{"zoneId":"%s","layoutId":"%s","layoutRevision":"1","profileRevision":"1","scope":"PARTIAL",
                  "roi":[{"x":0.1,"y":0.1},{"x":0.5,"y":0.1},{"x":0.5,"y":0.5}],"effectiveAreaM2":40,"calibrationEvidence":null},
                 {"zoneId":"%s","layoutId":"%s","layoutRevision":"1","profileRevision":"1","scope":"UNDETERMINED",
                  "roi":[{"x":0.5,"y":0.5},{"x":0.9,"y":0.5},{"x":0.9,"y":0.9}],"effectiveAreaM2":null,"calibrationEvidence":null}]
                """.formatted(stage, UUID.randomUUID(), lobby, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO safety_camera (id, event_id, name, source_binding_ref, enabled, revision, coverage, configured_by_member_id,
                                           configured_at, connection_state, freshness)
                VALUES (?, ?, ?, ?, ?, 1, ?::jsonb, (SELECT id FROM member WHERE event_id = ? AND event_role = 'CHIEF'), now(), ?, 'UNKNOWN')""",
                id, EVENT, "카메라 " + id, binding, enabled, coverage, EVENT, enabled ? "CONNECTING" : "DISABLED");
        return id;
    }

    HttpRequest.Builder frame(UUID session, long sequence, Instant capturedAt) {
        return frame("camera-laptop", TOKEN, "cam-01", session, sequence, capturedAt);
    }

    HttpRequest.Builder frame(String collector, String token, String source, UUID session, long sequence, Instant capturedAt) {
        var b = HttpRequest.newBuilder(URI.create(url("/internal/camera-collectors/" + collector + "/frames")))
                .header("Content-Type", "image/jpeg")
                .header("X-SceneCrew-Source", source)
                .header("X-SceneCrew-Stream-Session", session.toString())
                .header("X-SceneCrew-Sequence", Long.toString(sequence))
                .header("X-SceneCrew-Captured-At", capturedAt.toString())
                .header("X-SceneCrew-Capture-Time-Basis", "COLLECTOR_CLOCK");
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b;
    }

    HttpResponse<String> send(HttpRequest.Builder b, byte[] body) throws Exception {
        return http.send(b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> snapshot(UUID camera, String cookie) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url("/api/v1/safety-cameras/" + camera + "/snapshot")))
                .header("Cookie", cookie).header("Accept", "image/jpeg, application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path, String cookie) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    void error(HttpResponse<String> r, int status, String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json.readTree(r.body()).get("code").asString()).as(r.body()).isEqualTo(code);
    }

    void reason(HttpResponse<String> r, int status, String code, String reason) {
        error(r, status, code);
        assertThat(json.readTree(r.body()).get("details").get("reason").asString()).isEqualTo(reason);
    }

    void field(HttpResponse<String> r, String field, String reason) {
        error(r, 400, "INVALID_COLLECTOR_REQUEST");
        var d = json.readTree(r.body()).get("details");
        assertThat(d.get("field").asString()).isEqualTo(field);
        assertThat(d.get("reason").asString()).isEqualTo(reason);
    }

    static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(n.asString());
        return out;
    }

    UUID zone(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, event_id, name, description, aliases) VALUES (?, ?, ?, NULL, '[]'::jsonb)", id, EVENT, name);
        return id;
    }

    void member(UUID account, String role, int crewNo) {
        jdbc.update("""
                INSERT INTO member (id, event_id, account_id, work_group_id, crew_no, event_role, display_name, available, is_reserve,
                                    active, participation_revision)
                VALUES (?, ?, ?, NULL, ?, ?, '참여자', true, false, true, 1)""", UUID.randomUUID(), EVENT, account, crewNo, role);
    }

    UUID account() {
        UUID id = UUID.randomUUID();
        accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())", id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)", UUID.randomUUID(), id, id.toString(),
                encoder.encode("SyntheticPass8!"), id + "@example.invalid");
        return id;
    }

    String login(UUID account) throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login"))).header("Content-Type", "application/json")
                .header("Origin", WEB).header("X-SceneCrew-Client", "WEB").header("X-SceneCrew-CSRF", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + account + "@example.invalid\",\"password\":\"SyntheticPass8!\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("Set-Cookie").stream().map(c -> c.split(";", 2)[0])
                .filter(c -> c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean PasswordEncoder encoder() {
            return new BCryptPasswordEncoder(4);
        }
    }
}

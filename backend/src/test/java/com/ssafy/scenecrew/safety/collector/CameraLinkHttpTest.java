package com.ssafy.scenecrew.safety.collector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

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
import org.testcontainers.containers.GenericContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카메라 웹 연결(#393, V9-D58·D60): 연결 시작·넘겨받기·해제, 로그인 권한 프레임, 내 연결 목록, 서버 설정 경로와의 겹침,
 * 웹 연결 카메라 등록(EXT22·24). 실제 소켓·제품 Security·PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import(CameraLinkHttpTest.Beans.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CameraLinkHttpTest {
    static final String WEB = "https://web.example.invalid";
    static final String TOKEN = "camera-link-token-" + UUID.randomUUID();
    static final UUID EVENT = UUID.randomUUID();
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, (byte) 0xFF, (byte) 0xD9 };

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled", () -> "true");
        r.add("scenecrew.auth.signing-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer", () -> "camera-link-test");
        r.add("scenecrew.auth.audience", () -> "camera-link-test");
        r.add("scenecrew.auth.web-origins", () -> WEB);
        r.add("scenecrew.auth.android-origins", () -> "https://android.example.invalid");
        r.add("scenecrew.safety.camera.enabled", () -> "true");
        r.add("scenecrew.safety.camera.scheduler.enabled", () -> "false");
        r.add("scenecrew.safety.camera.registrations", () -> """
                [{"collectorId":"camera-laptop","eventId":"%s","tokenSha256":"%s","sources":["cam-01"]}]
                """.formatted(EVENT, CameraCollectors.sha256(TOKEN)));
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
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    final List<UUID> accounts = new ArrayList<>();
    UUID chiefMember, workerMember, otherMember, group, stage, task;
    String chiefCookie, workerCookie, otherCookie, outsiderCookie;

    @BeforeEach
    void fixture() throws Exception {
        jdbc.update("INSERT INTO event (id, name, state, revision, last_input_modified_at) VALUES (?, '웹 연결 시험', 'LIVE', 1, now())"
                + " ON CONFLICT (id) DO UPDATE SET state = 'LIVE'", EVENT);
        UUID chief = account(), worker = account(), other = account(), outsider = account();
        chiefMember = member(chief, "CHIEF", 1);
        workerMember = member(worker, "WORKER", 2);
        otherMember = member(other, "WORKER", 3);
        chiefCookie = login(chief);
        workerCookie = login(worker);
        otherCookie = login(other);
        outsiderCookie = login(outsider);
        stage = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, event_id, name, description, aliases) VALUES (?, ?, '무대 앞', NULL, '[]'::jsonb)", stage, EVENT);
        group = UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id, event_id, name, instructions, active) VALUES (?, ?, '안전', '', true)", group, EVENT);
        task = task(workerMember, "WAITING");
    }

    @AfterEach
    void cleanup() {
        jdbc.queryForList("SELECT id FROM safety_camera WHERE event_id = ?", UUID.class, EVENT).forEach(frames::discardCamera);
        jdbc.update("DELETE FROM safety_observation WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM safety_camera WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM operation_log WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM idempotency_record WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM task WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM work_group WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM zone WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM member WHERE event_id = ?", EVENT);
        for (UUID a : accounts) {
            jdbc.update("DELETE FROM idempotency_record WHERE actor_account_id = ?", a);
            jdbc.update("DELETE FROM auth_identity WHERE account_id = ?", a);
            jdbc.update("DELETE FROM account WHERE id = ?", a);
        }
        accounts.clear();
    }

    // ---- 연결·프레임 ----------------------------------------------------------------------------------------

    @Test
    void assigneeLinksSendsFramesAndChiefSeesTheFrame() throws Exception {
        UUID camera = camera(null, task);
        var before = myLinks(workerCookie);
        assertThat(before.get("cameras")).hasSize(1);
        JsonNode item = before.get("cameras").get(0);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "name", "revision", "enabled", "connectionState",
                "inputKind", "linkedByMe", "calibrationRequired", "canLink", "linkTask", "lastReceivedAt");
        assertThat(item.get("connectionState").asString()).isEqualTo("UNLINKED");
        assertThat(item.get("canLink").asBoolean()).isTrue();
        assertThat(item.get("linkedByMe").asBoolean()).isFalse();
        assertThat(item.get("calibrationRequired").asBoolean()).isTrue();
        assertThat(item.get("linkTask").get("id").asString()).isEqualTo(task.toString());
        // 담당이 아닌 팀원 목록은 비고, 참여하지 않은 사람은 404
        assertThat(myLinks(otherCookie).get("cameras")).isEmpty();
        error(get("/api/v1/events/" + EVENT + "/my-links", outsiderCookie), 404, "NOT_FOUND");

        var linked = link(camera, workerCookie, "1", false);
        assertThat(linked.statusCode()).as(linked.body()).isEqualTo(200);
        JsonNode body = json.readTree(linked.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("linkId", "cameraRevision");
        assertThat(body.get("cameraRevision").asString()).isEqualTo("2");
        String linkId = body.get("linkId").asString();

        UUID session = UUID.randomUUID();
        Instant before202 = Instant.now().minusSeconds(1);
        var sent = frame(camera, workerCookie, linkId, session, 1, JPEG);
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(202);
        assertThat(json.readTree(sent.body()).propertyNames()).containsExactlyInAnyOrder("receivedAt", "sequence");
        var row = jdbc.queryForMap("SELECT connection_state, input_kind, link_member_id FROM safety_camera WHERE id = ?", camera);
        assertThat(row.get("connection_state")).isEqualTo("CONNECTED");
        assertThat(row.get("input_kind")).isEqualTo("BROWSER");
        assertThat(row.get("link_member_id")).isEqualTo(workerMember);
        // 프레임 시각은 서버가 받은 시각이다
        var stored = frames.get(camera);
        assertThat(stored.captureTimeBasis()).isEqualTo("SERVER_RECEIVED");
        assertThat(stored.capturedAt()).isAfter(before202);

        var snap = http.send(HttpRequest.newBuilder(URI.create(url("/api/v1/safety-cameras/" + camera + "/snapshot")))
                .header("Cookie", chiefCookie).header("Accept", "image/jpeg, application/json").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(snap.statusCode()).isEqualTo(200);
        assertThat(snap.body()).isEqualTo(JPEG);
        // 보정 전 카메라는 인원을 세지 않는다(관측을 만들지 않음)
        assertThat(analyzer.analyze(stored)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_observation WHERE camera_id = ?", Integer.class, camera)).isZero();

        JsonNode after = myLinks(workerCookie).get("cameras").get(0);
        assertThat(after.get("linkedByMe").asBoolean()).isTrue();
        assertThat(after.get("connectionState").asString()).isEqualTo("CONNECTED");
        assertThat(after.get("inputKind").asString()).isEqualTo("BROWSER");
        // 같은 세션의 지난 번호는 받지 않는다
        reason(frame(camera, workerCookie, linkId, session, 1, JPEG), 409, "CAMERA_FRAME_OUTDATED", "SEQUENCE");
        assertThat(frame(camera, workerCookie, linkId, session, 2, JPEG).statusCode()).isEqualTo(202);
    }

    @Test
    void onlyChiefOrActiveAssigneeStartsALink() throws Exception {
        UUID camera = camera(null, task);
        error(link(camera, otherCookie, "1", false), 403, "CAMERA_LINK_FORBIDDEN");
        error(link(camera, outsiderCookie, "1", false), 404, "NOT_FOUND");
        // 업무가 끝났으면 담당자도 새로 시작하지 못한다. 총괄은 할 수 있다
        jdbc.update("UPDATE task SET status = 'DONE' WHERE id = ?", task);
        error(link(camera, workerCookie, "1", false), 403, "CAMERA_LINK_FORBIDDEN");
        assertThat(myLinks(workerCookie).get("cameras").get(0).get("canLink").asBoolean()).isFalse();
        jdbc.update("UPDATE task SET status = 'IN_PROGRESS' WHERE id = ?", task);
        error(link(camera, workerCookie, "9", false), 409, "VERSION_CONFLICT");
        assertThat(link(camera, chiefCookie, "1", false).statusCode()).isEqualTo(200);
        // 사용 중지·행사 종료
        UUID off = camera(null, task);
        jdbc.update("UPDATE safety_camera SET enabled = false, connection_state = 'DISABLED' WHERE id = ?", off);
        error(link(off, workerCookie, "1", false), 409, "CAMERA_DISABLED");
        jdbc.update("UPDATE event SET state = 'ENDED' WHERE id = ?", EVENT);
        error(link(camera, chiefCookie, "2", false), 409, "INVALID_STATE");
    }

    @Test
    void sendingLinkIsTakenOverOnlyWithConfirmation() throws Exception {
        UUID camera = camera(null, task);
        String first = linkId(link(camera, workerCookie, "1", false));
        UUID session = UUID.randomUUID();
        assertThat(frame(camera, workerCookie, first, session, 1, JPEG).statusCode()).isEqualTo(202);

        error(link(camera, chiefCookie, "2", false), 409, "CAMERA_LINK_TAKEN");
        String second = linkId(link(camera, chiefCookie, "2", true));
        // 넘겨받은 뒤 이전 브라우저의 프레임은 거절한다
        error(frame(camera, workerCookie, first, session, 2, JPEG), 409, "CAMERA_LINK_TAKEN");
        // 다른 사람이 연결 식별자를 알아도 그 사람의 연결이 아니다
        error(frame(camera, workerCookie, second, session, 2, JPEG), 409, "CAMERA_LINK_TAKEN");
        assertThat(frame(camera, chiefCookie, second, UUID.randomUUID(), 1, JPEG).statusCode()).isEqualTo(202);
        assertThat(frames.get(camera).streamSessionId()).isNotEqualTo(session);

        // 막 연결해 첫 프레임을 기다리는 칸(15초 안)도 넘겨받기 확인이 필요하다
        UUID idle = camera(null, task);
        linkId(link(idle, chiefCookie, "1", false));
        error(link(idle, workerCookie, "2", false), 409, "CAMERA_LINK_TAKEN");
        // 15초가 지나도 프레임이 없으면 끊긴 칸으로 보고 확인 없이 다시 연결한다
        jdbc.update("UPDATE safety_camera SET linked_at = now() - interval '20 seconds' WHERE id = ?", idle);
        assertThat(link(idle, workerCookie, "2", false).statusCode()).isEqualTo(200);
    }

    @Test
    void framesStopWhenTheSenderLosesTheRole() throws Exception {
        UUID camera = camera(null, task);
        String link = linkId(link(camera, workerCookie, "1", false));
        UUID session = UUID.randomUUID();
        // 업무를 완료해도 담당자로 남아 있으면 계속 보낸다
        jdbc.update("UPDATE task SET status = 'DONE' WHERE id = ?", task);
        assertThat(frame(camera, workerCookie, link, session, 1, JPEG).statusCode()).isEqualTo(202);
        // 담당 해제
        jdbc.update("UPDATE task SET assignee_id = ? WHERE id = ?", otherMember, task);
        error(frame(camera, workerCookie, link, session, 2, JPEG), 403, "CAMERA_LINK_FORBIDDEN");
        jdbc.update("UPDATE task SET assignee_id = ? WHERE id = ?", workerMember, task);
        assertThat(frame(camera, workerCookie, link, session, 3, JPEG).statusCode()).isEqualTo(202);
        // 참여 종료
        jdbc.update("UPDATE member SET active = false WHERE id = ?", workerMember);
        error(frame(camera, workerCookie, link, session, 4, JPEG), 403, "CAMERA_LINK_FORBIDDEN");
        jdbc.update("UPDATE member SET active = true WHERE id = ?", workerMember);
        // 행사 종료
        jdbc.update("UPDATE event SET state = 'ENDED' WHERE id = ?", EVENT);
        error(frame(camera, workerCookie, link, session, 5, JPEG), 403, "CAMERA_LINK_FORBIDDEN");
    }

    @Test
    void unlinkReturnsToUnlinkedAndRejectsLaterFrames() throws Exception {
        UUID camera = camera(null, task);
        String link = linkId(link(camera, workerCookie, "1", false));
        UUID session = UUID.randomUUID();
        assertThat(frame(camera, workerCookie, link, session, 1, JPEG).statusCode()).isEqualTo(202);
        error(unlink(camera, otherCookie), 403, "CAMERA_LINK_FORBIDDEN");

        var r = unlink(camera, workerCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json.readTree(r.body()).get("cameraRevision").asString()).isEqualTo("3");
        var row = jdbc.queryForMap("SELECT connection_state, input_kind, link_id, stream_session_id FROM safety_camera WHERE id = ?", camera);
        assertThat(row.get("connection_state")).isEqualTo("UNLINKED");
        assertThat(row.get("input_kind")).isNull();
        assertThat(row.get("link_id")).isNull();
        assertThat(row.get("stream_session_id")).isNull();
        assertThat(frames.get(camera)).isNull();
        error(frame(camera, workerCookie, link, session, 2, JPEG), 409, "CAMERA_UNLINKED");
        error(unlink(camera, workerCookie), 409, "CAMERA_UNLINKED");
        // 총괄은 다른 사람의 연결도 해제한다
        String again = linkId(link(camera, workerCookie, "3", false));
        assertThat(again).isNotEqualTo(link);
        assertThat(unlink(camera, chiefCookie).statusCode()).isEqualTo(200);
    }

    @Test
    void collectorPathIsRejectedWhileLinkedOnTheWeb() throws Exception {
        UUID camera = camera("cam-01", task);
        assertThat(jdbc.queryForObject("SELECT input_kind FROM safety_camera WHERE id = ?", String.class, camera)).isEqualTo("COLLECTOR");
        String link = linkId(link(camera, workerCookie, "1", false));
        error(collector(UUID.randomUUID(), 1), 409, "INPUT_LINKED_ELSEWHERE");
        assertThat(frame(camera, workerCookie, link, UUID.randomUUID(), 1, JPEG).statusCode()).isEqualTo(202);

        // 웹 연결을 해제하면 예비 경로를 다시 받는다
        assertThat(unlink(camera, workerCookie).statusCode()).isEqualTo(200);
        var row = jdbc.queryForMap("SELECT connection_state, input_kind FROM safety_camera WHERE id = ?", camera);
        assertThat(row.get("connection_state")).isEqualTo("CONNECTING");
        assertThat(row.get("input_kind")).isEqualTo("COLLECTOR");
        assertThat(collector(UUID.randomUUID(), 1).statusCode()).isEqualTo(202);
    }

    @Test
    void rejectsMalformedFrameRequests() throws Exception {
        UUID camera = camera(null, task);
        String link = linkId(link(camera, workerCookie, "1", false));
        UUID session = UUID.randomUUID();
        var noLink = send(frameRequest(camera, workerCookie, null, session, "1").POST(HttpRequest.BodyPublishers.ofByteArray(JPEG)));
        field(noLink, "X-SceneCrew-Link", "REQUIRED");
        field(send(frameRequest(camera, workerCookie, link, session, "01").POST(HttpRequest.BodyPublishers.ofByteArray(JPEG))),
                "X-SceneCrew-Sequence", "FORMAT");
        field(frame(camera, workerCookie, link, session, 1, new byte[] { 1, 2, 3 }), "body", "NOT_JPEG");
        error(frame(camera, workerCookie, link, session, 1, new byte[CameraFrameService.MAX_FRAME_BYTES + 1]), 413, "PAYLOAD_TOO_LARGE");
        // 허용하지 않은 Origin·다른 본문 형식
        var foreign = HttpRequest.newBuilder(URI.create(url("/api/v1/safety-cameras/" + camera + "/frames")))
                .header("Content-Type", "image/jpeg").header("Origin", "https://evil.example.invalid").header("Cookie", workerCookie)
                .header("X-SceneCrew-Link", link).header("X-SceneCrew-Stream-Session", session.toString())
                .header("X-SceneCrew-Sequence", "1").POST(HttpRequest.BodyPublishers.ofByteArray(JPEG));
        error(send(foreign), 403, "CSRF_REJECTED");
        var png = HttpRequest.newBuilder(URI.create(url("/api/v1/safety-cameras/" + camera + "/frames")))
                .header("Content-Type", "image/png").header("Origin", WEB).header("Cookie", workerCookie)
                .header("X-SceneCrew-Link", link).header("X-SceneCrew-Stream-Session", session.toString())
                .header("X-SceneCrew-Sequence", "1").POST(HttpRequest.BodyPublishers.ofByteArray(JPEG));
        error(send(png), 415, "UNSUPPORTED_MEDIA_TYPE");
        // 로그인 없음
        var anonymous = HttpRequest.newBuilder(URI.create(url("/api/v1/safety-cameras/" + camera + "/frames")))
                .header("Content-Type", "image/jpeg").header("Origin", WEB).POST(HttpRequest.BodyPublishers.ofByteArray(JPEG));
        assertThat(send(anonymous).statusCode()).isEqualTo(401);
    }

    // ---- 카메라 등록·수정(EXT22·24) ---------------------------------------------------------------------------

    @Test
    void chiefRegistersAWebCameraWithoutBindingOrCalibration() throws Exception {
        var created = send(json("POST", "/api/v1/events/" + EVENT + "/safety-cameras", chiefCookie, """
                {"expectedEventRevision":"%s","name":"객석 카메라 1","enabled":true,"linkTaskId":"%s"}""".formatted(revision(), task)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID camera = UUID.fromString(json.readTree(created.body()).get("id").asString());
        JsonNode view = json.readTree(get("/api/v1/safety-cameras/" + camera, chiefCookie).body());
        assertThat(view.get("connectionState").asString()).isEqualTo("UNLINKED");
        assertThat(view.get("sourceBindingRef").isNull()).isTrue();
        assertThat(view.get("inputKind").isNull()).isTrue();
        assertThat(view.get("linkTaskId").asString()).isEqualTo(task.toString());
        assertThat(view.get("coverage")).isEmpty();
        assertThat(myLinks(workerCookie).get("cameras")).hasSize(1);

        // 다른 행사의 업무는 422
        UUID foreign = UUID.randomUUID();
        var bad = send(json("POST", "/api/v1/events/" + EVENT + "/safety-cameras", chiefCookie, """
                {"expectedEventRevision":"%s","name":"객석 카메라 2","enabled":true,"linkTaskId":"%s"}""".formatted(revision(), foreign)));
        error(bad, 422, "INVALID_REFERENCE");

        // 담당 업무 해제(null) → 팀원 목록에서 빠진다. 서버 설정 경로를 붙이면 COLLECTOR, 다시 비우면 UNLINKED
        var cleared = send(json("PATCH", "/api/v1/safety-cameras/" + camera, chiefCookie,
                "{\"expectedCameraRevision\":\"1\",\"linkTaskId\":null}"));
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(myLinks(workerCookie).get("cameras")).isEmpty();
        assertThat(send(json("PATCH", "/api/v1/safety-cameras/" + camera, chiefCookie,
                "{\"expectedCameraRevision\":\"2\",\"sourceBindingRef\":\"cam-01\"}")).statusCode()).isEqualTo(200);
        var row = jdbc.queryForMap("SELECT connection_state, input_kind FROM safety_camera WHERE id = ?", camera);
        assertThat(row.get("connection_state")).isEqualTo("CONNECTING");
        assertThat(row.get("input_kind")).isEqualTo("COLLECTOR");
        assertThat(send(json("PATCH", "/api/v1/safety-cameras/" + camera, chiefCookie,
                "{\"expectedCameraRevision\":\"3\",\"sourceBindingRef\":null}")).statusCode()).isEqualTo(200);
        row = jdbc.queryForMap("SELECT connection_state, input_kind FROM safety_camera WHERE id = ?", camera);
        assertThat(row.get("connection_state")).isEqualTo("UNLINKED");
        assertThat(row.get("input_kind")).isNull();
    }

    /** #395 V9-D60: 담당 업무를 정하지 않고 등록하면 이미 있는 "객석 카메라 연결"(묶음 키 camera-link) 업무를 담당 업무로 둔다. */
    @Test void newCameraJoinsTheExistingCameraLinkTask() throws Exception {
        var none = send(json("POST", "/api/v1/events/" + EVENT + "/safety-cameras", chiefCookie, """
                {"expectedEventRevision":"%s","name":"업무 없는 카메라","enabled":true}""".formatted(revision())));
        assertThat(none.statusCode()).as(none.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT link_task_id FROM safety_camera WHERE id = ?", UUID.class,
                UUID.fromString(json.readTree(none.body()).get("id").asString()))).isNull();

        UUID linkTask = task(workerMember, "WAITING");
        jdbc.update("UPDATE task SET bundle_key = 'camera-link' WHERE id = ?", linkTask);
        var created = send(json("POST", "/api/v1/events/" + EVENT + "/safety-cameras", chiefCookie, """
                {"expectedEventRevision":"%s","name":"추가 카메라","enabled":true}""".formatted(revision())));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID camera = UUID.fromString(json.readTree(created.body()).get("id").asString());
        assertThat(jdbc.queryForObject("SELECT link_task_id FROM safety_camera WHERE id = ?", UUID.class, camera)).isEqualTo(linkTask);
        // null을 보내면 지정하지 않는다(화면의 "정하지 않음")
        var unset = send(json("POST", "/api/v1/events/" + EVENT + "/safety-cameras", chiefCookie, """
                {"expectedEventRevision":"%s","name":"지정 안 한 카메라","enabled":true,"linkTaskId":null}""".formatted(revision())));
        assertThat(unset.statusCode()).as(unset.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT link_task_id FROM safety_camera WHERE id = ?", UUID.class,
                UUID.fromString(json.readTree(unset.body()).get("id").asString()))).isNull();
        // 정해서 보내면 그 업무다
        var chosen = send(json("POST", "/api/v1/events/" + EVENT + "/safety-cameras", chiefCookie, """
                {"expectedEventRevision":"%s","name":"지정 카메라","enabled":true,"linkTaskId":"%s"}""".formatted(revision(), task)));
        assertThat(jdbc.queryForObject("SELECT link_task_id FROM safety_camera WHERE id = ?", UUID.class,
                UUID.fromString(json.readTree(chosen.body()).get("id").asString()))).isEqualTo(task);
    }

    // ---- 도움 함수 -------------------------------------------------------------------------------------------

    UUID camera(String binding, UUID linkTask) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO safety_camera (id, event_id, name, source_binding_ref, enabled, revision, coverage, configured_by_member_id,
                                           configured_at, connection_state, freshness, input_kind, link_task_id)
                VALUES (?, ?, ?, ?, true, 1, '[]'::jsonb, ?, now(), ?, 'UNKNOWN', ?, ?)""",
                id, EVENT, "카메라 " + id, binding, chiefMember, binding == null ? "UNLINKED" : "CONNECTING",
                binding == null ? null : "COLLECTOR", linkTask);
        return id;
    }

    UUID task(UUID assignee, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id, event_id, work_group_id, bundle_key, assignee_id, zone_id, program_id, source_issue_id, title,
                                  start_at, end_at, steps, checklist, status)
                VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, '객석 카메라 연결', now(), now() + interval '30 minutes',
                        '["연결 화면 열기"]'::jsonb, '[]'::jsonb, ?)""", id, EVENT, group, "link-" + id, assignee, stage, status);
        return id;
    }

    String revision() {
        return Long.toString(jdbc.queryForObject("SELECT revision FROM event WHERE id = ?", Long.class, EVENT));
    }

    JsonNode myLinks(String cookie) throws Exception {
        var r = get("/api/v1/events/" + EVENT + "/my-links", cookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    HttpResponse<String> link(UUID camera, String cookie, String revision, boolean takeover) throws Exception {
        return send(json("POST", "/api/v1/safety-cameras/" + camera + "/browser-link", cookie,
                "{\"expectedCameraRevision\":\"" + revision + "\",\"takeover\":" + takeover + "}"));
    }

    String linkId(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body()).get("linkId").asString();
    }

    HttpResponse<String> unlink(UUID camera, String cookie) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/api/v1/safety-cameras/" + camera + "/browser-link")))
                .header("Origin", WEB).header("Cookie", cookie).DELETE());
    }

    HttpRequest.Builder frameRequest(UUID camera, String cookie, String link, UUID session, String sequence) {
        var b = HttpRequest.newBuilder(URI.create(url("/api/v1/safety-cameras/" + camera + "/frames")))
                .header("Content-Type", "image/jpeg").header("Origin", WEB).header("Cookie", cookie)
                .header("X-SceneCrew-Stream-Session", session.toString()).header("X-SceneCrew-Sequence", sequence);
        if (link != null) b.header("X-SceneCrew-Link", link);
        return b;
    }

    HttpResponse<String> frame(UUID camera, String cookie, String link, UUID session, long sequence, byte[] body) throws Exception {
        return send(frameRequest(camera, cookie, link, session, Long.toString(sequence)).POST(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    HttpResponse<String> collector(UUID session, long sequence) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/internal/camera-collectors/camera-laptop/frames")))
                .header("Content-Type", "image/jpeg").header("Authorization", "Bearer " + TOKEN)
                .header("X-SceneCrew-Source", "cam-01").header("X-SceneCrew-Stream-Session", session.toString())
                .header("X-SceneCrew-Sequence", Long.toString(sequence))
                .header("X-SceneCrew-Captured-At", Instant.now().minusMillis(200).toString())
                .header("X-SceneCrew-Capture-Time-Basis", "COLLECTOR_CLOCK").POST(HttpRequest.BodyPublishers.ofByteArray(JPEG)));
    }

    HttpRequest.Builder json(String method, String path, String cookie, String body) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type", "application/json").header("Origin", WEB)
                .header("Cookie", cookie).header("Idempotency-Key", UUID.randomUUID().toString())
                .method(method, HttpRequest.BodyPublishers.ofString(body));
    }

    HttpResponse<String> get(String path, String cookie) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
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
        error(r, 400, "INVALID_REQUEST");
        var f = json.readTree(r.body()).get("details").get("fields").get(0);
        assertThat(f.get("field").asString()).isEqualTo(field);
        assertThat(f.get("reason").asString()).isEqualTo(reason);
    }

    UUID member(UUID account, String role, int crewNo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO member (id, event_id, account_id, work_group_id, crew_no, event_role, display_name, available, is_reserve,
                                    active, participation_revision)
                VALUES (?, ?, ?, NULL, ?, ?, '참여자', true, false, true, 1)""", id, EVENT, account, crewNo, role);
        return id;
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

package com.ssafy.scenecrew.equipment.collector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import com.ssafy.scenecrew.equipment.service.AutoTechnicalStates;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장비 웹 연결(#394, V9-D59·D60): 연결 코드 발급·교환, 찾은 수신기 보고, 수신기 ↔ 장비 칸 연결·넘겨받기·해제,
 * DB 수집기의 관측 수신과 유지 자격, 설정 경로와의 겹침, 자동 관측 상태(EXT03·내 연결 목록). 실제 소켓·제품 Security·PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@Import(EquipmentLinkHttpTest.Beans.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EquipmentLinkHttpTest {
    static final String WEB = "https://web.example.invalid";
    static final UUID EVENT = UUID.randomUUID(), ASSET = UUID.randomUUID(), CONFIG_RUN = UUID.randomUUID();
    static final String CONFIG_TOKEN = "config-token-" + UUID.randomUUID();
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled", () -> "true");
        r.add("scenecrew.auth.signing-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer", () -> "equipment-link-test");
        r.add("scenecrew.auth.audience", () -> "equipment-link-test");
        r.add("scenecrew.auth.web-origins", () -> WEB);
        r.add("scenecrew.auth.android-origins", () -> "https://android.example.invalid");
        r.add("scenecrew.equipment.collector.enabled", () -> "true");
        r.add("scenecrew.equipment.collector.cleanup.enabled", () -> "false");
        // 자동 기술 상태는 시험이 직접 맞춘다(주기 작업과 겹치지 않게)
        r.add("scenecrew.equipment.auto-technical-state.enabled", () -> "false");
        r.add("scenecrew.equipment.collector.registrations", () -> """
                [{"collectorId":"collector-config","eventId":"%s","tokenSha256":"%s","sources":{"cfg-src":"%s"},
                  "runs":[{"runId":"%s","active":true}]}]
                """.formatted(EVENT, CollectorRegistry.sha256(CONFIG_TOKEN), ASSET, CONFIG_RUN));
    }

    @AfterAll
    static void stop() {
        REDIS.stop();
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired EventWriteSupport events;
    @Autowired PlatformTransactionManager txManager;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    final List<UUID> accounts = new ArrayList<>();
    UUID chiefMember, workerMember, otherMember, group, zone, task;
    String chiefCookie, workerCookie, otherCookie, outsiderCookie;

    @BeforeEach
    void fixture() throws Exception {
        jdbc.update("INSERT INTO event (id, name, state, revision, last_input_modified_at) VALUES (?, '장비 연결 시험', 'LIVE', 1, now())"
                + " ON CONFLICT (id) DO UPDATE SET state = 'LIVE'", EVENT);
        UUID chief = account(), worker = account(), other = account(), outsider = account();
        chiefMember = member(chief, "CHIEF", 1);
        workerMember = member(worker, "WORKER", 2);
        otherMember = member(other, "WORKER", 3);
        chiefCookie = login(chief);
        workerCookie = login(worker);
        otherCookie = login(other);
        outsiderCookie = login(outsider);
        zone = UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id, event_id, name, description, aliases) VALUES (?, ?, '무대', NULL, '[]'::jsonb)", zone, EVENT);
        group = UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id, event_id, name, instructions, active) VALUES (?, ?, '장비 운영', '', true)", group, EVENT);
        task = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id, event_id, work_group_id, bundle_key, assignee_id, zone_id, program_id, source_issue_id, title,
                                  start_at, end_at, steps, checklist, status)
                VALUES (?, ?, ?, 'equipment-link', ?, ?, NULL, NULL, '장비 자동 수집 연결', now(), now() + interval '30 minutes',
                        '["연결 화면 열기"]'::jsonb, '[]'::jsonb, 'WAITING')""", task, EVENT, group, workerMember, zone);
        jdbc.update("""
                INSERT INTO equipment_asset (id, event_id, name, model, technical_state, state_evidence, component_asset_ids, ports,
                                             verified_compatibility, revision, auto_observation_kind, link_task_id)
                VALUES (?, ?, 'QLXD4 수신기 1', 'QLXD4', 'UNKNOWN', '{}'::jsonb, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 1, 'QLXD4', ?)""",
                ASSET, EVENT, task);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM equipment_observation WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM equipment_observation_latest WHERE equipment_asset_id IN (SELECT id FROM equipment_asset WHERE event_id = ?)", EVENT);
        jdbc.update("DELETE FROM equipment_asset WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM equipment_collector_receiver WHERE collector_id IN (SELECT id FROM equipment_collector WHERE event_id = ?)", EVENT);
        jdbc.update("DELETE FROM equipment_collector_code WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM equipment_collector WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM operation_log WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM task WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM work_group WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM zone WHERE event_id = ?", EVENT);
        jdbc.update("DELETE FROM member WHERE event_id = ?", EVENT);
        for (UUID a : accounts) {
            jdbc.update("DELETE FROM auth_identity WHERE account_id = ?", a);
            jdbc.update("DELETE FROM account WHERE id = ?", a);
        }
        accounts.clear();
    }

    // ---- 흐름 ---------------------------------------------------------------------------------------------

    @Test
    void assigneePairsReportsLinksAndCollects() throws Exception {
        var issued = code(workerCookie);
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        JsonNode code = json.readTree(issued.body());
        assertThat(code.propertyNames()).containsExactlyInAnyOrder("code", "expiresAt");
        assertThat(code.get("code").asString()).matches("[A-HJ-KM-NP-Z2-9]{4}-[A-HJ-KM-NP-Z2-9]{4}");
        // 쓰지 않은 코드가 있으면 장비는 "코드 대기"다
        assertThat(detail(ASSET).get("autoObservation").get("state").asString()).isEqualTo("CODE_PENDING");

        // 대소문자·하이픈은 무시한다. 응답은 캐시하지 않는다. 버전·노트북 이름은 선택이다
        var paired = send(HttpRequest.newBuilder(URI.create(url("/internal/equipment-collectors/pair")))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                        "{\"code\":\"" + code.get("code").asString().toLowerCase().replace("-", "")
                                + "\",\"programVersion\":\"0.4.0\",\"hostLabel\":\"무대 노트북\"}")));
        assertThat(paired.statusCode()).as(paired.body()).isEqualTo(200);
        assertThat(paired.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode collector = json.readTree(paired.body());
        assertThat(collector.propertyNames()).containsExactlyInAnyOrder("collectorId", "token", "runId");
        String id = collector.get("collectorId").asString(), token = collector.get("token").asString();
        assertThat(id).startsWith("web-");
        assertThat(jdbc.queryForObject("SELECT token_sha256 FROM equipment_collector WHERE id = ?", String.class, id))
                .isEqualTo(CollectorRegistry.sha256(token)).isNotEqualTo(token);
        // 같은 코드는 한 번만, 틀린 코드도 같은 401
        error(pair(code.get("code").asString()), 401, "COLLECTOR_UNAUTHENTICATED");
        error(pair("AAAA-AAAA"), 401, "COLLECTOR_UNAUTHENTICATED");
        error(pair("0000-0000"), 401, "COLLECTOR_UNAUTHENTICATED");

        var reported = receivers(id, token, "[{\"receiverKey\":\"rx-main\",\"name\":\"무대 수신기\",\"model\":\"QLXD4\",\"channels\":1}]");
        assertThat(reported.statusCode()).as(reported.body()).isEqualTo(200);
        assertThat(json.readTree(reported.body()).get("receivers").asInt()).isEqualTo(1);
        assertThat(json.readTree(reported.body()).get("links")).isEmpty();       // 아직 연결된 수신기가 없다
        assertThat(jdbc.queryForObject("SELECT program_version || '/' || host_label FROM equipment_collector WHERE id = ?",
                String.class, id)).isEqualTo("0.4.0/무대 노트북");
        JsonNode mine = myLinks(workerCookie);
        assertThat(mine.get("receivers").get(0).get("receiverKey").asString()).isEqualTo("rx-main");
        assertThat(mine.get("receivers").get(0).get("linkedAssetId").isNull()).isTrue();
        assertThat(mine.get("receivers").get(0).get("hostLabel").asString()).isEqualTo("무대 노트북");
        JsonNode item = mine.get("equipment").get(0);
        assertThat(item.get("canLink").asBoolean()).isTrue();
        assertThat(item.get("link").isNull()).isTrue();
        assertThat(item.get("autoObservation").get("state").asString()).isEqualTo("UNLINKED");
        assertThat(myLinks(otherCookie).get("equipment")).isEmpty();
        assertThat(myLinks(otherCookie).get("receivers")).isEmpty();

        var linked = link(workerCookie, id, "rx-main", false);
        assertThat(linked.statusCode()).as(linked.body()).isEqualTo(200);
        assertThat(json.readTree(linked.body()).get("revision").asString()).isEqualTo("2");
        // 프로그램은 수신기 보고 응답으로 연결된 수신기를 알고 그 기록만 보낸다
        JsonNode links = json.readTree(receivers(id, token,
                "[{\"receiverKey\":\"rx-main\",\"name\":\"무대 수신기\",\"model\":\"QLXD4\",\"channels\":1},"
                        + "{\"receiverKey\":\"rx-spare\",\"name\":null,\"model\":\"QLXD4\",\"channels\":1}]").body()).get("links");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("receiverKey").asString()).isEqualTo("rx-main");
        assertThat(links.get(0).get("channel").asInt()).isEqualTo(1);
        // 수신기 보고만 오고 관측 묶음이 없으면 연결됨이 아니다
        assertThat(detail(ASSET).get("autoObservation").get("state").asString()).isEqualTo("PROGRAM_OFFLINE");

        var collected = ingest(id, token, List.of(reading(id, "rx-main", 1, "TX_TYPE", "QLXD2", "OK"),
                reading(id, "rx-main", 2, "BATT_BARS", 4, "OK"), reading(id, "rx-main", 3, "RF_LEVEL", 87, "OK"),
                reading(id, "rx-main", 4, "AUDIO_LEVEL", 255, "OUT_OF_RANGE"), status(id, "rx-main", 5, "CONNECTED")));
        assertThat(collected.statusCode()).as(collected.body()).isEqualTo(200);
        JsonNode auto = detail(ASSET).get("autoObservation");
        assertThat(auto.get("kind").asString()).isEqualTo("QLXD4");
        assertThat(auto.get("inputKind").asString()).isEqualTo("WEB");
        assertThat(auto.get("state").asString()).isEqualTo("CONNECTED");
        assertThat(auto.get("transmitterOn").asBoolean()).isTrue();
        assertThat(auto.get("batteryBars").asInt()).isEqualTo(4);
        assertThat(auto.get("rfLevel").asInt()).isEqualTo(87);
        assertThat(auto.get("audioLevel").isNull()).isTrue();        // 모르는 값을 0으로 채우지 않는다
        assertThat(auto.get("lastReceivedAt").isNull()).isFalse();
        assertThat(auto.toString()).doesNotContain(token);
        assertThat(myLinks(workerCookie).get("equipment").get(0).get("linkedByMe").asBoolean()).isTrue();

        // 수신기 응답 없음, 프로그램 끊김(15초)
        assertThat(ingest(id, token, List.of(status(id, "rx-main", 6, "OFFLINE"))).statusCode()).isEqualTo(200);
        assertThat(detail(ASSET).get("autoObservation").get("state").asString()).isEqualTo("RECEIVER_NOT_RESPONDING");
        jdbc.update("UPDATE equipment_collector SET last_batch_at = now() - interval '20 seconds' WHERE id = ?", id);
        assertThat(detail(ASSET).get("autoObservation").get("state").asString()).isEqualTo("PROGRAM_OFFLINE");
        // 자동 관측 종류가 없는 장비에는 칸이 없다
        UUID plain = asset("믹서", null);
        assertThat(detail(plain).has("autoObservation")).isFalse();
    }

    @Test
    void onlyChiefOrActiveAssigneeIssuesCodesAndLinksTheirOwnCollector() throws Exception {
        error(code(otherCookie), 403, "COLLECTOR_LINK_FORBIDDEN");
        error(code(outsiderCookie), 404, "NOT_FOUND");
        jdbc.update("UPDATE task SET status = 'DONE' WHERE id = ?", task);
        error(code(workerCookie), 403, "COLLECTOR_LINK_FORBIDDEN");
        jdbc.update("UPDATE task SET status = 'IN_PROGRESS' WHERE id = ?", task);

        String[] chiefCollector = pairWith(chiefCookie, "rx-chief");
        String[] workerCollector = pairWith(workerCookie, "rx-worker");
        // 팀원은 자기 코드로 등록한 수집기만 쓴다
        error(link(workerCookie, chiefCollector[0], "rx-chief", false), 403, "COLLECTOR_LINK_FORBIDDEN");
        error(link(otherCookie, workerCollector[0], "rx-worker", false), 403, "COLLECTOR_LINK_FORBIDDEN");
        reason(link(workerCookie, workerCollector[0], "rx-none", false), 422, "INVALID_REFERENCE", "NOT_FOUND");
        error(link(workerCookie, "web-unknown", "rx-worker", false), 422, "INVALID_REFERENCE");
        var badChannel = send(json("PUT", "/api/v1/equipment/" + ASSET + "/link", workerCookie,
                "{\"collectorId\":\"" + workerCollector[0] + "\",\"receiverKey\":\"rx-worker\",\"channel\":2}"));
        error(badChannel, 422, "INVALID_REFERENCE");
        // 2채널 수신기라도 지금은 채널 1만 연결한다(수신기 하나 = 장비 칸 하나)
        assertThat(receivers(workerCollector[0], workerCollector[1], "[{\"receiverKey\":\"rx-worker\",\"channels\":2}]")
                .statusCode()).isEqualTo(200);
        var secondChannel = send(json("PUT", "/api/v1/equipment/" + ASSET + "/link", workerCookie,
                "{\"collectorId\":\"" + workerCollector[0] + "\",\"receiverKey\":\"rx-worker\",\"channel\":2}"));
        error(secondChannel, 422, "INVALID_REFERENCE");
        UUID plain = asset("믹서", null);
        reason(send(json("PUT", "/api/v1/equipment/" + plain + "/link", chiefCookie,
                "{\"collectorId\":\"" + chiefCollector[0] + "\",\"receiverKey\":\"rx-chief\",\"channel\":1}")), 409, "INVALID_STATE",
                "NOT_AUTO_OBSERVED");
        // 총괄은 누구의 수집기든 쓴다
        assertThat(link(chiefCookie, workerCollector[0], "rx-worker", false).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE event SET state = 'ENDED' WHERE id = ?", EVENT);
        reason(link(chiefCookie, chiefCollector[0], "rx-chief", true), 409, "INVALID_STATE", "EVENT_CLOSED");
    }

    @Test
    void collectingLinkIsTakenOverOnlyWithConfirmationAndUnusedCollectorIsRevoked() throws Exception {
        String[] worker = pairWith(workerCookie, "rx-a");
        String[] chief = pairWith(chiefCookie, "rx-b");
        assertThat(link(workerCookie, worker[0], "rx-a", false).statusCode()).isEqualTo(200);
        reason(link(chiefCookie, chief[0], "rx-b", false), 409, "EQUIPMENT_LINK_TAKEN", "ASSET_IN_USE");
        assertThat(link(chiefCookie, chief[0], "rx-b", true).statusCode()).isEqualTo(200);
        // 연결된 장비가 남지 않은 이전 수집기는 토큰을 폐기한다
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM equipment_collector WHERE id = ?", Boolean.class, worker[0]))
                .isTrue();
        error(ingest(worker[0], worker[1], List.of(status(worker[0], "rx-a", 1, "CONNECTED"))), 401, "COLLECTOR_UNAUTHENTICATED");
        error(receivers(worker[0], worker[1], "[]"), 401, "COLLECTOR_UNAUTHENTICATED");

        // 같은 수신기를 다른 장비 칸에 잇는 것도 넘겨받기로만
        UUID second = asset("QLXD4 수신기 2", "QLXD4");
        var moved = send(json("PUT", "/api/v1/equipment/" + second + "/link", chiefCookie,
                "{\"collectorId\":\"" + chief[0] + "\",\"receiverKey\":\"rx-b\",\"channel\":1}"));
        reason(moved, 409, "EQUIPMENT_LINK_TAKEN", "RECEIVER_IN_USE");
        assertThat(send(json("PUT", "/api/v1/equipment/" + second + "/link", chiefCookie,
                "{\"collectorId\":\"" + chief[0] + "\",\"receiverKey\":\"rx-b\",\"channel\":1,\"takeover\":true}")).statusCode())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT link_collector_id FROM equipment_asset WHERE id = ?", String.class, ASSET)).isNull();

        // 수집이 15초 넘게 없고 연결도 15초가 지났으면 확인 없이 다시 연결한다.
        // 수신기 보고도 프로그램이 살아 있다는 신호라 그 시각(last_seen_at)도 15초 전으로 돌린다.
        String[] again = pairWith(workerCookie, "rx-c");
        jdbc.update("UPDATE equipment_asset SET linked_at = now() - interval '20 seconds' WHERE id = ?", second);
        jdbc.update("UPDATE equipment_collector SET last_seen_at = now() - interval '20 seconds' WHERE id = ?", chief[0]);
        var retaken = send(json("PUT", "/api/v1/equipment/" + second + "/link", workerCookie,
                "{\"collectorId\":\"" + again[0] + "\",\"receiverKey\":\"rx-c\",\"channel\":1}"));
        assertThat(retaken.statusCode()).as(retaken.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT link_member_id FROM equipment_asset WHERE id = ?", UUID.class, second))
                .isEqualTo(workerMember);
    }

    @Test
    void collectionStopsWhenTheLinkerLosesTheRoleAndUnlinkRevokes() throws Exception {
        String[] worker = pairWith(workerCookie, "rx-a");
        assertThat(link(workerCookie, worker[0], "rx-a", false).statusCode()).isEqualTo(200);
        // 업무를 완료해도 담당자면 계속 받는다
        jdbc.update("UPDATE task SET status = 'DONE' WHERE id = ?", task);
        assertThat(ingest(worker[0], worker[1], List.of(status(worker[0], "rx-a", 1, "CONNECTED"))).statusCode()).isEqualTo(200);
        // 담당 해제·참여 종료·행사 종료는 403
        jdbc.update("UPDATE task SET assignee_id = ? WHERE id = ?", otherMember, task);
        error(ingest(worker[0], worker[1], List.of(status(worker[0], "rx-a", 2, "CONNECTED"))), 403, "COLLECTOR_LINK_FORBIDDEN");
        jdbc.update("UPDATE task SET assignee_id = ? WHERE id = ?", workerMember, task);
        jdbc.update("UPDATE member SET active = false WHERE id = ?", workerMember);
        error(ingest(worker[0], worker[1], List.of(status(worker[0], "rx-a", 3, "CONNECTED"))), 403, "COLLECTOR_LINK_FORBIDDEN");
        jdbc.update("UPDATE member SET active = true WHERE id = ?", workerMember);
        // 연결하지 않은 수신기 기록은 받지 않는다
        error(ingest(worker[0], worker[1], List.of(status(worker[0], "rx-other", 4, "CONNECTED"))), 422, "COLLECTOR_SOURCE_UNMAPPED");

        error(unlink(otherCookie), 403, "COLLECTOR_LINK_FORBIDDEN");
        var r = unlink(workerCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        error(unlink(workerCookie), 409, "EQUIPMENT_UNLINKED");
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM equipment_collector WHERE id = ?", Boolean.class, worker[0]))
                .isTrue();
        error(ingest(worker[0], worker[1], List.of(status(worker[0], "rx-a", 5, "CONNECTED"))), 401, "COLLECTOR_UNAUTHENTICATED");
        // 해제한 웹 연결의 남은 값으로 연결됨처럼 보이지 않는다
        assertThat(detail(ASSET).get("autoObservation").get("state").asString()).isEqualTo("UNLINKED");
    }

    @Test
    void configPathIsRejectedWhileLinkedOnTheWeb() throws Exception {
        assertThat(ingest("collector-config", CONFIG_TOKEN, List.of(configStatus(1))).statusCode()).isEqualTo(200);
        JsonNode auto = detail(ASSET).get("autoObservation");
        assertThat(auto.get("inputKind").asString()).isEqualTo("CONFIG");
        assertThat(auto.get("state").asString()).isEqualTo("CONNECTED");

        String[] worker = pairWith(workerCookie, "rx-a");
        assertThat(link(workerCookie, worker[0], "rx-a", false).statusCode()).isEqualTo(200);
        error(ingest("collector-config", CONFIG_TOKEN, List.of(configStatus(2))), 409, "INPUT_LINKED_ELSEWHERE");
        assertThat(unlink(workerCookie).statusCode()).isEqualTo(200);
        assertThat(ingest("collector-config", CONFIG_TOKEN, List.of(configStatus(3))).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsMalformedReceiverReportsAndPairRequests() throws Exception {
        String[] worker = pairWith(workerCookie, "rx-a");
        error(receivers(worker[0], worker[1], "[{\"receiverKey\":\"rx-a\",\"channels\":1,\"ip\":\"10.0.0.5\"}]"), 400,
                "INVALID_COLLECTOR_REQUEST");
        error(receivers(worker[0], worker[1], "[{\"receiverKey\":\"rx a\",\"channels\":1}]"), 400, "INVALID_COLLECTOR_REQUEST");
        error(receivers(worker[0], worker[1], "[{\"receiverKey\":\"rx-a\",\"channels\":5}]"), 400, "INVALID_COLLECTOR_REQUEST");
        error(receivers(worker[0], worker[1], "[{\"receiverKey\":\"rx-a\",\"channels\":1},{\"receiverKey\":\"rx-a\",\"channels\":1}]"),
                400, "INVALID_COLLECTOR_REQUEST");
        error(receivers(worker[0], "wrong", "[]"), 401, "COLLECTOR_UNAUTHENTICATED");
        for (String body : List.of("{\"code\":1}", "{\"code\":\"AAAA-AAAA\",\"ip\":\"10.0.0.5\"}",
                "{\"code\":\"AAAA-AAAA\",\"hostLabel\":\"" + "가".repeat(61) + "\"}"))
            error(send(HttpRequest.newBuilder(URI.create(url("/internal/equipment-collectors/pair")))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))), 400,
                    "INVALID_COLLECTOR_REQUEST");
        // 만료된 코드
        var issued = json.readTree(code(workerCookie).body()).get("code").asString();
        jdbc.update("UPDATE equipment_collector_code SET expires_at = now() - interval '1 second' WHERE used_at IS NULL");
        error(pair(issued), 401, "COLLECTOR_UNAUTHENTICATED");
        // 코드 발급은 WEB 허용 Origin에서만
        var foreign = send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/" + EVENT + "/equipment-links/codes")))
                .header("Content-Type", "application/json").header("Origin", "https://evil.example.invalid").header("Cookie", workerCookie)
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        error(foreign, 403, "CSRF_REJECTED");
    }

    @Test
    void autoObservationSetsTechnicalStateOnlyWhileItDecidesAndNeverOverHumanRecords() throws Exception {
        String[] c = pairWith(workerCookie, "rx-main");
        String id = c[0], token = c[1];
        assertThat(link(workerCookie, id, "rx-main", false).statusCode()).isEqualTo(200);
        long eventRevision = jdbc.queryForObject("SELECT revision FROM event WHERE id = ?", Long.class, EVENT);
        // 송신기 꺼짐(배터리 모름)은 미확인 그대로다
        assertThat(ingest(id, token, List.of(reading(id, "rx-main", 1, "TX_TYPE", "UNKN", "UNKNOWN_VALUE"),
                status(id, "rx-main", 2, "CONNECTED"))).statusCode()).isEqualTo(200);
        assertThat(reconcile()).isZero();
        assertThat(detail(ASSET).get("technicalState").asString()).isEqualTo("UNKNOWN");

        // 연결됨 + 송신기 켜짐 + 배터리 값 → 정상(자동 측정 근거). 공연 revision은 그대로
        assertThat(ingest(id, token, List.of(reading(id, "rx-main", 3, "TX_TYPE", "QLXD2", "OK"),
                reading(id, "rx-main", 4, "BATT_BARS", 5, "OK"))).statusCode()).isEqualTo(200);
        assertThat(reconcile()).isEqualTo(1);
        JsonNode asset = detail(ASSET);
        assertThat(asset.get("technicalState").asString()).isEqualTo("VERIFIED_USABLE");
        JsonNode evidence = asset.get("stateEvidence");
        assertThat(evidence.get("sourceKind").asString()).isEqualTo("AUTOMATIC_MEASUREMENT");
        assertThat(evidence.get("sourceRef").asString()).isEqualTo(AutoTechnicalStates.SOURCE_REF);
        assertThat(evidence.get("confirmed").asBoolean()).isTrue();
        assertThat(evidence.get("confirmedByMemberId").isNull()).isTrue();
        assertThat(evidence.get("observedAt").isNull()).isFalse();
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id = ?", Long.class, EVENT)).isEqualTo(eventRevision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_log WHERE event_id = ? AND type = 'EQUIPMENT_STATE_AUTO'",
                Integer.class, EVENT)).isEqualTo(1);
        assertThat(reconcile()).isZero();       // 같으면 다시 쓰지 않는다

        // 수신기 응답 없음 → 점검 필요, 프로그램 끊김 → 미확인으로 되돌림
        assertThat(ingest(id, token, List.of(status(id, "rx-main", 5, "OFFLINE"))).statusCode()).isEqualTo(200);
        assertThat(reconcile()).isEqualTo(1);
        assertThat(detail(ASSET).get("technicalState").asString()).isEqualTo("CHECK_REQUIRED");
        jdbc.update("UPDATE equipment_collector SET last_batch_at = now() - interval '20 seconds' WHERE id = ?", id);
        assertThat(reconcile()).isEqualTo(1);
        asset = detail(ASSET);
        assertThat(asset.get("technicalState").asString()).isEqualTo("UNKNOWN");
        assertThat(asset.get("stateEvidence").get("confirmed").asBoolean()).isFalse();

        // 사람이 기록한 상태는 자동 값이 정상이어도 덮지 않는다
        jdbc.update("""
                UPDATE equipment_asset SET technical_state = 'UNUSABLE', revision = revision + 1,
                       state_evidence = '{"sourceKind":"MANUAL_CONFIRMATION","sourceRef":"chief","observedAt":null,"recordedAt":"2026-11-13T00:00:00Z","confirmedByMemberId":null,"confirmed":true}'::jsonb
                 WHERE id = ?""", ASSET);
        assertThat(ingest(id, token, List.of(status(id, "rx-main", 6, "CONNECTED"))).statusCode()).isEqualTo(200);
        assertThat(reconcile()).isZero();
        assertThat(detail(ASSET).get("technicalState").asString()).isEqualTo("UNUSABLE");
    }

    @Test
    void relinkingWithANewCollectorIgnoresValuesLeftByTheOldOne() throws Exception {
        String[] old = pairWith(workerCookie, "rx-main");
        assertThat(link(workerCookie, old[0], "rx-main", false).statusCode()).isEqualTo(200);
        assertThat(ingest(old[0], old[1], List.of(reading(old[0], "rx-main", 1, "TX_TYPE", "QLXD2", "OK"),
                reading(old[0], "rx-main", 2, "BATT_BARS", 5, "OK"), status(old[0], "rx-main", 3, "CONNECTED")))
                .statusCode()).isEqualTo(200);
        assertThat(reconcile()).isEqualTo(1);
        assertThat(unlink(workerCookie).statusCode()).isEqualTo(200);
        assertThat(reconcile()).isEqualTo(1);       // 해제되면 미확인으로 돌아간다

        // 새 노트북(수집기)으로 다시 연결하고, 새 수집기는 아직 연결 기록(LINK)만 보냈다
        String[] fresh = pairWith(workerCookie, "rx-main");
        assertThat(link(workerCookie, fresh[0], "rx-main", false).statusCode()).isEqualTo(200);
        Map<String, Object> linkRecord = base(fresh[0], "rx-main", "LINK", 1);
        linkRecord.put("linkState", "CONNECTING");
        linkRecord.put("reason", null);
        assertThat(ingest(fresh[0], fresh[1], List.of(linkRecord)).statusCode()).isEqualTo(200);
        JsonNode auto = detail(ASSET).get("autoObservation");
        assertThat(auto.get("state").asString()).isEqualTo("CONNECTED");
        // 예전 수집기가 남긴 송신기·배터리·수신 시각은 쓰지 않는다
        assertThat(auto.get("transmitterOn").isNull()).isTrue();
        assertThat(auto.get("batteryBars").isNull()).isTrue();
        assertThat(reconcile()).isZero();
        assertThat(detail(ASSET).get("technicalState").asString()).isEqualTo("UNKNOWN");
    }

    int reconcile() {
        return AutoTechnicalStates.reconcile(jdbc, events, new TransactionTemplate(txManager), EventWriteSupport.now());
    }

    // ---- 도움 함수 -------------------------------------------------------------------------------------------

    /** 코드 발급 → 교환 → 수신기 하나 보고. {collectorId, token} */
    String[] pairWith(String cookie, String receiverKey) throws Exception {
        var issued = code(cookie);
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        var paired = pair(json.readTree(issued.body()).get("code").asString());
        assertThat(paired.statusCode()).as(paired.body()).isEqualTo(200);
        JsonNode c = json.readTree(paired.body());
        String id = c.get("collectorId").asString(), token = c.get("token").asString();
        assertThat(receivers(id, token, "[{\"receiverKey\":\"" + receiverKey + "\",\"name\":null,\"model\":\"QLXD4\",\"channels\":1}]")
                .statusCode()).isEqualTo(200);
        return new String[] { id, token };
    }

    UUID asset(String name, String kind) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO equipment_asset (id, event_id, name, model, technical_state, state_evidence, component_asset_ids, ports,
                                             verified_compatibility, revision, auto_observation_kind, link_task_id)
                VALUES (?, ?, ?, NULL, 'UNKNOWN', '{}'::jsonb, '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, 1, ?, ?)""",
                id, EVENT, name, kind, kind == null ? null : task);
        return id;
    }

    HttpResponse<String> code(String cookie) throws Exception {
        return send(json("POST", "/api/v1/events/" + EVENT + "/equipment-links/codes", cookie, "{}"));
    }

    HttpResponse<String> pair(String code) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/internal/equipment-collectors/pair")))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code + "\"}")));
    }

    HttpResponse<String> receivers(String collector, String token, String list) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/internal/equipment-collectors/" + collector + "/receivers")))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"receivers\":" + list + "}")));
    }

    HttpResponse<String> link(String cookie, String collector, String receiverKey, boolean takeover) throws Exception {
        return send(json("PUT", "/api/v1/equipment/" + ASSET + "/link", cookie, "{\"collectorId\":\"" + collector
                + "\",\"receiverKey\":\"" + receiverKey + "\",\"channel\":1,\"takeover\":" + takeover + "}"));
    }

    HttpResponse<String> unlink(String cookie) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/api/v1/equipment/" + ASSET + "/link"))).header("Origin", WEB)
                .header("Cookie", cookie).DELETE());
    }

    JsonNode detail(UUID asset) throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create(url("/api/v1/equipment/" + asset))).header("Cookie", chiefCookie).GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body()).get("asset");
    }

    JsonNode myLinks(String cookie) throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/" + EVENT + "/my-links"))).header("Cookie", cookie)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    // ---- 관측 기록(수집 프로그램 observation.py와 같은 모양) ----

    final Instant start = Instant.now().minusSeconds(30);

    Map<String, Object> base(String collector, String sourceRef, String type, long seq) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schema", ObservationBatch.SCHEMA);
        r.put("recordType", type);
        r.put("collectorId", collector);
        r.put("runId", runOf(collector).toString());
        r.put("sessionId", "9d145a8e-8b8e-4c47-9f0e-6a4b2f0d1c11");
        r.put("sequence", seq);
        r.put("adapter", "shure-qlxd4");
        r.put("sourceRef", sourceRef);
        r.put("mode", "SIMULATOR");
        r.put("channel", 1);
        r.put("receivedAt", start.plusMillis(seq * 500).toString());
        return r;
    }

    UUID runOf(String collector) {
        if (collector.equals("collector-config")) return CONFIG_RUN;
        return jdbc.query("SELECT run_id FROM equipment_collector WHERE id = ?", rs -> rs.next() ? rs.getObject(1, UUID.class)
                : UUID.randomUUID(), collector);
    }

    Map<String, Object> reading(String collector, String sourceRef, long seq, String param, Object value, String status) {
        Map<String, Object> r = base(collector, sourceRef, "READING", seq);
        r.put("origin", "REPORT");
        r.put("param", param);
        r.put("raw", String.valueOf(value));
        r.put("value", "OK".equals(status) ? value : null);
        r.put("valueStatus", status);
        r.put("unit", null);
        r.put("frame", "REP 1 " + param + " " + value);
        r.put("observedAt", null);
        r.put("timeBasis", "RECEIVED");
        return r;
    }

    Map<String, Object> status(String collector, String sourceRef, long seq, String linkState) {
        Map<String, Object> r = base(collector, sourceRef, "STATUS", seq);
        r.put("linkState", linkState);
        r.put("metering", "OFFLINE".equals(linkState) ? "OFFLINE" : "FRESH");
        r.put("staleAfterMs", 2000);
        r.put("lastSampleAt", start.toString());
        r.put("sampleAgeMs", 120);
        r.put("lastValues", Map.of());
        return r;
    }

    Map<String, Object> configStatus(long seq) {
        return status("collector-config", "cfg-src", seq, "CONNECTED");
    }

    HttpResponse<String> ingest(String collector, String token, List<Map<String, Object>> records) throws Exception {
        String body = json.writeValueAsString(Map.of("schema", ObservationBatch.SCHEMA, "collectorId", collector,
                "sentAt", Instant.now().toString(), "droppedSinceLast", 0, "records", records));
        return send(HttpRequest.newBuilder(URI.create(url("/internal/equipment-collectors/" + collector + "/observations")))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    HttpRequest.Builder json(String method, String path, String cookie, String body) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type", "application/json").header("Origin", WEB)
                .header("Cookie", cookie).method(method, HttpRequest.BodyPublishers.ofString(body));
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

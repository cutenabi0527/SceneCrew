package com.ssafy.scenecrew.event;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.ssafy.scenecrew.event.domain.EventRole;
import com.ssafy.scenecrew.event.service.ChiefTransferService;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ChiefTransferPostgresTest.Encoder.class)
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
class ChiefTransferPostgresTest {
    @TestConfiguration
    static class Encoder {
        @Bean BCryptPasswordEncoder testOnlyEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    private static final String WEB_ORIGIN = "https://web.example.invalid";
    private static final String ANDROID_ORIGIN = "https://android.example.invalid";
    private static final String PASSWORD = "ChiefTransfer8!";
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine")
            .withExposedPorts(6379);
    private static final String KEY = randomKey();

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("scenecrew.auth.public-enabled", () -> "true");
        registry.add("scenecrew.auth.signing-key-base64", () -> KEY);
        registry.add("scenecrew.auth.failure-hmac-key-base64", () -> KEY);
        registry.add("scenecrew.auth.issuer", () -> "chief-transfer-test");
        registry.add("scenecrew.auth.audience", () -> "chief-transfer-test");
        registry.add("scenecrew.auth.web-origins", () -> WEB_ORIGIN);
        registry.add("scenecrew.auth.android-origins", () -> ANDROID_ORIGIN);
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Autowired ChiefTransferService transfers;
    @Autowired JdbcTemplate jdbc;
    @Autowired BCryptPasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void webCookieKeepsSessionButRechecksCurrentRole() throws Exception {
        verifiesRealSessionTransfer("WEB", WEB_ORIGIN);
    }

    @Test
    void androidBearerKeepsSessionButRechecksCurrentRole() throws Exception {
        verifiesRealSessionTransfer("ANDROID", ANDROID_ORIGIN);
    }

    private void verifiesRealSessionTransfer(String channel, String origin) throws Exception {
        var s = scenario("LIVE", 1);
        var other = scenario("LIVE", 1);
        jdbc.update("update member set account_id = ? where id = ?", s.chiefAccount, other.chief);
        jdbc.update("update member set account_id = ? where id = ?", s.successorAccount, other.successor);
        var previous = login(s.chiefAccount, channel, origin);
        var next = login(s.successorAccount, channel, origin);
        var request = payload(s.successor, s.group, 1);
        assertThat(send(post(path(s), request)).statusCode()).isEqualTo(401);
        if ("WEB".equals(channel)) {
            assertThat(send(post(path(s), request).header(previous.header, previous.value)
                    .header("Origin", "https://foreign.example.invalid")).statusCode()).isEqualTo(403);
        }
        var result = transfer(s, request, previous);
        assertThat(result.statusCode()).isEqualTo(200);
        var body = json.readTree(result.body());
        assertThat(body.get("previousChief").get("eventRole").asString()).isEqualTo("WORKER");
        assertThat(body.get("previousChief").get("workGroupId").asString()).isEqualTo(s.group.toString());
        assertThat(body.get("currentChief").get("memberId").asString()).isEqualTo(s.successor.toString());
        assertThat(body.get("eventRevision").asString()).isEqualTo("2");
        var denied = transfer(s, payload(s.other, s.group, 2), previous);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(json.readTree(denied.body()).get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(transfer(s, payload(s.other, s.group, 2), next).statusCode()).isEqualTo(200);
        assertChief(s, s.other, 3);

        assertThat(transfer(other, payload(other.other, other.group, 1), next).statusCode()).isEqualTo(403);
        assertThat(transfer(other, payload(other.other, other.group, 1), previous).statusCode()).isEqualTo(200);
    }

    private Credential login(UUID account, String channel, String origin) throws Exception {
        String email = account + "@example.invalid";
        jdbc.update("""
                insert into auth_identity values (?, ?, 'LOCAL', ?, ?, ?, true)
                """, UUID.randomUUID(), account, account.toString(), encoder.encode(PASSWORD), email);
        var request = post("/api/v1/auth/login",
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))
                .header("Origin", origin).header("X-SceneCrew-Client", channel);
        if ("WEB".equals(channel)) request.header("X-SceneCrew-CSRF", "1");
        var response = send(request);
        assertThat(response.statusCode()).isEqualTo(200);
        if ("WEB".equals(channel)) {
            String cookie = response.headers().allValues("Set-Cookie").stream()
                    .filter(value -> value.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
            return new Credential("Cookie", cookie.substring(0, cookie.indexOf(';')), origin);
        }
        return new Credential("Authorization", "Bearer " + json.readTree(response.body()).get("accessToken").asString(), origin);
    }

    private HttpResponse<String> transfer(Scenario scenario, String payload, Credential credential) throws Exception {
        return send(post(path(scenario), payload).header(credential.header, credential.value)
                .header("Origin", credential.origin));
    }

    private static String path(Scenario scenario) {
        return "/api/v1/events/" + scenario.event + "/chief-transfer";
    }

    private HttpRequest.Builder post(String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String payload(UUID successor, UUID group, long revision) {
        return """
                {"successorMemberId":"%s","previousChiefWorkGroupId":"%s","expectedEventRevision":"%d"}
                """.formatted(successor, group, revision);
    }

    private record Credential(String header, String value, String origin) {}

    @Test
    void reviewRejectsGroupMoveWithPreviousChiefsInProgressTask() {
        var s = scenario("LIVE", 1);
        var previousGroup = setChiefGroup(s);
        var task = assignedTask(s, "IN_PROGRESS"); // Assigned work belongs to a different group.
        assertCode(ErrorCode.INVALID_STATE,
                () -> transfers.transfer(s.event, s.chiefAccount, s.successor, s.group, 1));
        assertChief(s, s.chief, 1);
        assertThat(jdbc.queryForObject("select event_role from member where id = ?", String.class, s.successor))
                .isEqualTo("WORKER");
        assertThat(jdbc.queryForObject("select work_group_id from member where id = ?", UUID.class, s.chief))
                .isEqualTo(previousGroup);
        assertThat(assignment(task)).isEqualTo(s.chief);
        assertThat(jdbc.queryForObject("select status from task where id = ?", String.class, task))
                .isEqualTo("IN_PROGRESS");
        assertThat(moveLogs(s)).isZero();
    }

    @Test
    void actualGroupMoveReleasesWaitingTasksAndRecordsHistory() {
        var s = scenario("LIVE", 1);
        var previousGroup = setChiefGroup(s);
        var waiting = assignedTask(s, "WAITING");
        var done = assignedTask(s, "DONE");
        var cancelled = assignedTask(s, "CANCELED");
        var firstAssignedAt = firstAssignedAt(waiting);
        transfers.transfer(s.event, s.chiefAccount, s.successor, s.group, 1);
        assertChief(s, s.successor, 2);
        assertThat(assignment(waiting)).isNull();
        assertThat(assignment(done)).isEqualTo(s.chief);
        assertThat(assignment(cancelled)).isEqualTo(s.chief);
        assertThat(firstAssignedAt(waiting)).isEqualTo(firstAssignedAt);
        assertThat(jdbc.queryForObject("select status from task where id = ?", String.class, waiting))
                .isEqualTo("WAITING");
        assertThat(moveLogs(s)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select changes::text from operation_log where event_id = ?", String.class, s.event))
                .contains(previousGroup.toString(), s.group.toString(), waiting.toString())
                .doesNotContain(done.toString(), cancelled.toString());
    }

    @Test
    void sameGroupTransferLeavesAssignmentsAndHistoryUntouched() {
        var s = scenario("LIVE", 1);
        jdbc.update("update member set work_group_id = ? where id = ?", s.group, s.chief);
        var running = assignedTask(s, "IN_PROGRESS");
        var waiting = assignedTask(s, "WAITING");
        transfers.transfer(s.event, s.chiefAccount, s.successor, s.group, 1);
        assertChief(s, s.successor, 2);
        assertThat(assignment(running)).isEqualTo(s.chief);
        assertThat(assignment(waiting)).isEqualTo(s.chief);
        assertThat(moveLogs(s)).isZero();
    }

    private UUID setChiefGroup(Scenario s) {
        UUID group = UUID.randomUUID();
        jdbc.update("insert into work_group (id,event_id,name,instructions,active) values (?,?,'previous','',true)", group,s.event);
        jdbc.update("update member set work_group_id = ? where id = ?",group,s.chief);
        return group;
    }

    private UUID assignedTask(Scenario s, String status) {
        UUID zone = UUID.randomUUID(), task = UUID.randomUUID();
        jdbc.update("insert into zone (id,event_id,name,aliases) values (?,?,?, '[]'::jsonb)", zone,s.event,zone.toString());
        jdbc.update("""
                insert into task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,title,start_at,end_at,
                    steps,checklist,status,first_assigned_at)
                values (?,?,?,'bundle',?,?,'test',now(),now()+interval '1 hour','[]'::jsonb,'[]'::jsonb,?,now())
                """,task,s.event,s.group,s.chief,zone,status);
        return task;
    }

    private UUID assignment(UUID task) {
        return jdbc.queryForObject("select assignee_id from task where id = ?", UUID.class, task);
    }

    private java.sql.Timestamp firstAssignedAt(UUID task) {
        return jdbc.queryForObject("select first_assigned_at from task where id = ?", java.sql.Timestamp.class, task);
    }

    private long moveLogs(Scenario s) {
        return jdbc.queryForObject("select count(*) from operation_log where event_id = ?", Long.class,s.event);
    }

    @Test
    void commitsBothRolesAndRechecksCurrentAuthorityForSameAccount() {
        var s = scenario("LIVE", 1);
        var result = transfers.transfer(s.event, s.chiefAccount, s.successor, s.group, 1);
        assertThat(result.previousChief().eventRole()).isEqualTo(EventRole.WORKER);
        assertThat(result.previousChief().workGroupId()).isEqualTo(s.group);
        assertThat(result.currentChief().memberId()).isEqualTo(s.successor);
        assertThat(result.currentChief().eventRole()).isEqualTo(EventRole.CHIEF);
        assertThat(result.previousChief().participationRevision()).isEqualTo("1");
        assertThat(result.currentChief().participationRevision()).isEqualTo("1");
        assertThat(result.eventRevision()).isEqualTo("2");
        assertChief(s, s.successor, 2);
        assertCode(ErrorCode.FORBIDDEN,
                () -> transfers.transfer(s.event, s.chiefAccount, s.other, s.group, 2));
        assertThat(transfers.transfer(s.event, s.successorAccount, s.other, s.group, 2).eventRevision())
                .isEqualTo("3");
        assertChief(s, s.other, 3);
    }

    @Test
    void rejectsInvalidStateRevisionTargetsAndGroupWithoutChangingRows() {
        var s = scenario("LIVE", 1);
        var otherEvent = scenario("ENDED", 1);
        assertCode(ErrorCode.INVALID_STATE,
                () -> transfers.transfer(otherEvent.event, otherEvent.chiefAccount, otherEvent.successor, otherEvent.group, 1));
        assertCode(ErrorCode.FORBIDDEN,
                () -> transfers.transfer(s.event, s.successorAccount, s.other, s.group, 1));
        assertCode(ErrorCode.VERSION_CONFLICT,
                () -> transfers.transfer(s.event, s.chiefAccount, s.successor, s.group, 0));
        for (UUID target : new UUID[]{s.chief, otherEvent.successor, UUID.randomUUID()}) {
            assertCode(ErrorCode.INVALID_REFERENCE,
                    () -> transfers.transfer(s.event, s.chiefAccount, target, s.group, 1));
        }
        assertCode(ErrorCode.INVALID_REFERENCE,
                () -> transfers.transfer(s.event, s.chiefAccount, s.successor, otherEvent.group, 1));
        jdbc.update("update member set active = false where id = ?", s.other);
        assertCode(ErrorCode.INVALID_REFERENCE,
                () -> transfers.transfer(s.event, s.chiefAccount, s.other, s.group, 1));
        jdbc.update("update work_group set active = false where id = ?", s.group);
        assertCode(ErrorCode.INVALID_REFERENCE,
                () -> transfers.transfer(s.event, s.chiefAccount, s.successor, s.group, 1));
        assertChief(s, s.chief, 1);
    }

    @Test
    void failureAfterDemotionFlushRollsBackTheWholeTransfer() {
        var s = scenario("LIVE", Long.MAX_VALUE);
        var waiting = assignedTask(s, "WAITING");
        var firstAssignedAt = firstAssignedAt(waiting);
        assertThatThrownBy(() -> transfers.transfer(s.event, s.chiefAccount, s.successor, s.group, Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
        assertChief(s, s.chief, Long.MAX_VALUE);
        assertThat(assignment(waiting)).isEqualTo(s.chief);
        assertThat(firstAssignedAt(waiting)).isEqualTo(firstAssignedAt);
        assertThat(moveLogs(s)).isZero();
        assertThat(jdbc.queryForObject("select event_role from member where id = ?", String.class, s.successor))
                .isEqualTo("WORKER");
    }

    @Test
    void competingTransfersCanCommitOnlyOneSuccess() throws Exception {
        var s = scenario("LIVE", 1);
        var locked = new CountDownLatch(1);
        var competing = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var winner = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var result = transfers.transfer(s.event, s.chiefAccount, s.successor, s.group, 1);
                locked.countDown();
                await(competing);
                return result;
            }));
            var loser = executor.submit(() -> {
                await(locked);
                competing.countDown();
                assertCode(ErrorCode.FORBIDDEN,
                        () -> transfers.transfer(s.event, s.chiefAccount, s.other, s.group, 1));
            });
            assertThat(winner.get(15, TimeUnit.SECONDS).currentChief().memberId()).isEqualTo(s.successor);
            loser.get(15, TimeUnit.SECONDS);
        }
        assertChief(s, s.successor, 2);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void assertCode(ErrorCode code, Runnable request) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    private void assertChief(Scenario s, UUID member, long revision) {
        assertThat(jdbc.queryForList("select id from member where event_id = ? and active and event_role = 'CHIEF'",
                UUID.class, s.event)).containsExactly(member);
        assertThat(jdbc.queryForObject("select revision from event where id = ?", Long.class, s.event)).isEqualTo(revision);
    }

    private Scenario scenario(String state, long revision) {
        UUID event = UUID.randomUUID(), group = UUID.randomUUID();
        UUID chiefAccount = UUID.randomUUID(), successorAccount = UUID.randomUUID();
        jdbc.update("insert into event (id, name, state, revision, last_input_modified_at) values (?, 'test', ?, ?, now())",
                event, state, revision);
        jdbc.update("insert into work_group (id,event_id,name,instructions,active) values (?,?,'group','',true)",
                group, event);
        UUID chief = member(event, chiefAccount, null, 1, "CHIEF");
        UUID successor = member(event, successorAccount, group, 2, "WORKER");
        UUID other = member(event, UUID.randomUUID(), group, 3, "WORKER");
        return new Scenario(event, group, chief, chiefAccount, successor, successorAccount, other);
    }

    private UUID member(UUID event, UUID account, UUID group, int number, String role) {
        var id = UUID.randomUUID();
        jdbc.update("""
                insert into account (id,display_name,status,signup_completed,token_version,consent,created_at)
                values (?,'test','ACTIVE',true,0,'{}'::jsonb,now())
                """, account);
        jdbc.update("""
                insert into member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,
                                    available,is_reserve,active,participation_revision)
                values (?,?,?,?,?,?,'test',true,false,true,1)
                """, id, event, account, group, number, role);
        return id;
    }

    private record Scenario(UUID event, UUID group, UUID chief, UUID chiefAccount,
                            UUID successor, UUID successorAccount, UUID other) {}
}

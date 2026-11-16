package com.ssafy.scenecrew.plan;

import com.ssafy.scenecrew.aijob.client.AiClient;
import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.pipeline.AiJobOutboxTypes;
import com.ssafy.scenecrew.global.outbox.OutboxRelay;
import com.ssafy.scenecrew.plan.ai.assign.InitialAssignmentTrigger;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import com.ssafy.scenecrew.plan.service.TaskCursor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.plan.service.TaskReadService;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** #281 manual MVP. Real HTTP/Security and isolated PostgreSQL/Redis; no unmerged #345 code. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "scenecrew.ai.server.service-token=test-service-token")
@ActiveProfiles("test")
@Import(TaskReadHttpTest.Encoder.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskReadHttpTest {
    static final String WEB = "https://web.example.invalid", ANDROID = "https://android.example.invalid";
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        POSTGRES.start(); REDIS.start();
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled", () -> "true");
        r.add("scenecrew.auth.signing-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer", () -> "task-read-test");
        r.add("scenecrew.auth.audience", () -> "task-read-test");
        r.add("scenecrew.auth.web-origins", () -> WEB);
        r.add("scenecrew.auth.android-origins", () -> ANDROID);
    }
    @AfterAll static void stop() { REDIS.stop(); POSTGRES.stop(); }
    @TestConfiguration(proxyBeanMethods = false) static class Encoder {
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(4); }
        @Bean @Primary FakeAssign fakeAssign() { return new FakeAssign(); }
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired TaskReadService reads;
    @Autowired OutboxRelay relay;
    @Autowired FakeAssign ai;
    @Autowired TransactionTemplate tx;
    @Autowired TaskCursor codec;
    @Autowired com.ssafy.scenecrew.plan.repository.TaskReadQueries queries;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    final List<UUID> accounts = new ArrayList<>();
    UUID event, group, zone, chief, worker, outsider, chiefMember, workerMember, task1, task2;
    String cookie;

    @BeforeEach void fixture() throws Exception {
        event = UUID.randomUUID(); group = UUID.randomUUID(); zone = UUID.randomUUID();
        jdbc.update("INSERT INTO event (id,name,state,revision,last_input_modified_at) VALUES (?,'fixture','LIVE',3,now())", event);
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'fixture','',true)", group, event);
        jdbc.update("INSERT INTO zone (id,event_id,name,aliases) VALUES (?,?,'fixture','[]'::jsonb)", zone, event);
        chief = account(); worker = account(); outsider = account();
        chiefMember = member(chief, "CHIEF", null, 1);
        workerMember = member(worker, "WORKER", group, 2);
        task1 = task("2026-11-14T09:00:00Z", "2026-11-14T10:00:00Z");
        task2 = task("2026-11-14T10:00:00Z", "2026-11-14T11:00:00Z");
        cookie = login(chief, "WEB");
    }

    @AfterEach void cleanup() {
        ai.answer = null;
        jdbc.update("DELETE FROM idempotency_record WHERE event_id=?", event);
        jdbc.update("DELETE FROM notification_delivery WHERE notification_id IN (SELECT id FROM notification WHERE event_id=?)", event);
        jdbc.update("DELETE FROM notification WHERE event_id=?", event);
        jdbc.update("DELETE FROM change_acknowledgment WHERE event_id=?", event);
        jdbc.update("DELETE FROM operation_log WHERE event_id=?", event);
        jdbc.update("DELETE FROM change_set_evidence_revision WHERE change_set_id IN (SELECT id FROM change_set WHERE event_id=?)", event);
        jdbc.update("DELETE FROM change_set WHERE event_id=?", event);
        jdbc.update("DELETE FROM task WHERE event_id=?", event);
        jdbc.update("DELETE FROM ai_job WHERE event_id=?", event);
        jdbc.update("DELETE FROM member WHERE event_id=?", event);
        jdbc.update("DELETE FROM zone WHERE event_id=?", event);
        jdbc.update("DELETE FROM work_group WHERE event_id=?", event);
        jdbc.update("DELETE FROM outbox_event WHERE event_id=?", event);
        jdbc.update("DELETE FROM event WHERE id=?", event);
        for (UUID account : accounts) {
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?", account);
            jdbc.update("DELETE FROM account WHERE id=?", account);
        }
        accounts.clear();
    }

    @Test void unassignedDetailHasExactExistingTaskShapeAndNullableValues() throws Exception {
        var response = get(task1, cookie);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "workGroupId", "assigneeId", "title", "status",
                "startAt", "endAt", "zoneId", "programId", "sourceIssueId", "steps", "checklist", "firstAssignedAt",
                "actualStartedAt", "actualEndedAt", "bundleKey");
        for (String key : List.of("assigneeId", "programId", "sourceIssueId", "firstAssignedAt", "actualStartedAt", "actualEndedAt"))
            assertThat(body.get(key).isNull()).as(key).isTrue();
        assertThat(body.get("startAt").asString()).isEqualTo("2026-11-14T09:00:00Z");
        assertThat(body.get("steps").get(0).asString()).isEqualTo("준비");
        assertThat(body.get("checklist").get(0).get("checked").asBoolean()).isFalse();
        assertThat(body.has("equipment")).isFalse();
        assertThat(body.has("safety")).isFalse();
    }

    @Test void actual344HttpAssignmentIsVisibleInDetailAndListWithoutWrites() throws Exception {
        var assigned = send(HttpRequest.newBuilder(uri("/api/v1/events/" + event + "/initial-assignments"))
                .header("Cookie", cookie).header("Origin", WEB).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "bundleKey", "bundle", "memberId", workerMember, "expectedEventRevision", "3")))));
        assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(200);
        var receipt = json.readTree(assigned.body());
        String before = snapshot();
        var listed = reads.list(event, chief, null, 100, codec).items();
        assertThat(listed).hasSize(2);
        assertThat(listed).allSatisfy(t -> {
            assertThat(t.assigneeId()).isEqualTo(workerMember);
            assertThat(t.firstAssignedAt()).isNotNull();
            assertThat(t.status()).isEqualTo("WAITING");
        });
        for (JsonNode task : receipt.get("tasks")) {
            var response = get(UUID.fromString(task.get("id").asString()), cookie);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(json.readTree(response.body())).isEqualTo(task);
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?", Long.class, event)).isEqualTo(4);
    }

    @Test void currentListIsEventScopedAndAuthorizedEmptyEventIsEmpty() {
        assertThat(reads.list(event, chief, null, 100, codec).items()).extracting(t -> t.id()).containsExactlyInAnyOrder(task1, task2);
        jdbc.update("DELETE FROM task WHERE event_id=?", event);
        var empty = reads.list(event, chief, null, 100, codec);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"WAITING", "IN_PROGRESS", "DONE", "CANCELED"})
    void chiefCanReadAllTaskStatesAndChecklistEvidenceWithoutChanges(String state) throws Exception {
        jdbc.update("""
                UPDATE task SET status=?,assignee_id=?,actual_started_at='2026-11-14T09:00:00Z',
                    checklist=?::jsonb WHERE id=?""", state, workerMember,
                "[{\"key\":\"check\",\"text\":\"점검\",\"required\":true,\"checked\":true,"
                        + "\"checkedAt\":\"2026-11-14T09:01:00Z\",\"checkedByMemberId\":\"" + workerMember + "\"}]", task1);
        String before = snapshot();
        var response = get(task1, cookie);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo(state);
        assertThat(body.get("checklist").get(0).get("checkedByMemberId").asString()).isEqualTo(workerMember.toString());
        assertThat(reads.list(event, chief, null, 100, codec).items()).hasSize(2);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void anonymousUnknownAndNonMemberRequestsUseExistingErrors() throws Exception {
        error(send(HttpRequest.newBuilder(uri("/api/v1/tasks/" + task1)).GET()), 401, "UNAUTHENTICATED");
        error(get(UUID.randomUUID(), cookie), 404, "NOT_FOUND");
        error(get(task1, login(outsider, "WEB")), 404, "NOT_FOUND");
    }

    @Test void chiefOfAnotherEventCannotReadTaskById() throws Exception {
        UUID otherEvent = UUID.randomUUID();
        UUID otherGroup = UUID.randomUUID(), otherZone = UUID.randomUUID(), otherTask = UUID.randomUUID();
        jdbc.update("INSERT INTO event (id,name,state,revision,last_input_modified_at) VALUES (?,'other','LIVE',1,now())", otherEvent);
        try {
            jdbc.update("""
                    INSERT INTO member (id,event_id,account_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                    VALUES (?,?,?,1,'CHIEF','other',true,false,true,1)""", UUID.randomUUID(), otherEvent, outsider);
            jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'other','',true)", otherGroup, otherEvent);
            jdbc.update("INSERT INTO zone (id,event_id,name,aliases) VALUES (?,?,'other','[]'::jsonb)", otherZone, otherEvent);
            jdbc.update("""
                    INSERT INTO task (id,event_id,work_group_id,bundle_key,zone_id,title,start_at,end_at,steps,checklist,status)
                    VALUES (?,?,?,'other',?,'private','2026-11-14T09:00:00Z','2026-11-14T10:00:00Z','["step"]'::jsonb,'[]'::jsonb,'WAITING')
                    """, otherTask, otherEvent, otherGroup, otherZone);
            error(get(task1, login(outsider, "WEB")), 404, "NOT_FOUND");
            error(get(otherTask, cookie), 404, "NOT_FOUND");
            error(command(otherTask, "start", 3, cookie, null), 404, "NOT_FOUND");
            assertThat(reads.list(event, chief, null, 100, codec).items()).extracting(t -> t.id()).containsExactlyInAnyOrder(task1, task2);
            assertThat(reads.list(otherEvent, outsider, null, 100, codec).items()).extracting(t -> t.id()).containsExactly(otherTask);
            TaskReadServiceTest.code(() -> reads.list(event, outsider, null, 100, codec), com.ssafy.scenecrew.global.error.ErrorCode.NOT_FOUND);
        } finally {
            jdbc.update("DELETE FROM task WHERE event_id=?", otherEvent);
            jdbc.update("DELETE FROM member WHERE event_id=?", otherEvent);
            jdbc.update("DELETE FROM zone WHERE event_id=?", otherEvent);
            jdbc.update("DELETE FROM work_group WHERE event_id=?", otherEvent);
            jdbc.update("DELETE FROM outbox_event WHERE event_id=?", otherEvent);
            jdbc.update("DELETE FROM event WHERE id=?", otherEvent);
        }
    }

    @Test void dataQueriesRecheckCurrentRoleMembershipAndAccountAtTheirReadBoundary() {
        var scope = new TaskCursor.Scope(event, chief, chiefMember, 2, "CHIEF");
        assertThat(queries.oneForMember(scope, task1)).hasSize(1);
        jdbc.update("UPDATE member SET active=false WHERE id=?", chiefMember);
        assertThat(queries.page(scope, null, 100)).isEmpty();
        assertThat(queries.oneForMember(scope, task1)).isEmpty();
        jdbc.update("UPDATE member SET active=true,event_role='WORKER',work_group_id=? WHERE id=?", group, chiefMember);
        assertThat(queries.page(scope, null, 100)).isEmpty();
        assertThat(queries.oneForMember(scope, task1)).isEmpty();
        jdbc.update("UPDATE member SET event_role='CHIEF',participation_revision=3 WHERE id=?", chiefMember);
        assertThat(queries.page(scope, null, 100)).isEmpty();
        assertThat(queries.oneForMember(scope, task1)).isEmpty();
        jdbc.update("UPDATE member SET participation_revision=2 WHERE id=?", chiefMember);
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", chief);
        assertThat(queries.page(scope, null, 100)).isEmpty();
        assertThat(queries.oneForMember(scope, task1)).isEmpty();
    }

    @Test void inactiveMembershipAndRevokedRoleAreDenied() throws Exception {
        jdbc.update("UPDATE member SET active=false WHERE id=?", chiefMember);
        error(get(task1, cookie), 404, "NOT_FOUND");
        jdbc.update("UPDATE member SET active=true,event_role='WORKER',work_group_id=? WHERE id=?", group, chiefMember);
        error(get(task1, cookie), 404, "NOT_FOUND");
    }

    @Test void workerOldRoundTaskIsHistoryOnlyUnderTheStageTwoDecision() throws Exception {
        jdbc.update("UPDATE task SET assignee_id=?,status='DONE' WHERE id=?", workerMember, task1);
        jdbc.update("UPDATE member SET participation_revision=3 WHERE id=?", workerMember);
        String before = snapshot();
        String workerCookie = login(worker, "WEB");
        assertThat(get(task1, workerCookie).statusCode()).isEqualTo(200);
        var current = send(HttpRequest.newBuilder(uri("/api/v1/events/" + event + "/tasks")).GET().header("Cookie", workerCookie));
        assertThat(current.statusCode()).as(current.body()).isEqualTo(200);
        assertThat(json.readTree(current.body()).get("items").isEmpty()).isTrue();
        assertThat(reads.list(event, worker, null, 100, codec).items()).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void androidBearerWorksWithoutNewHeadersAndGetDoesNotRequireWriteCsrf() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/api/v1/tasks/" + task1)).GET()
                .header("Authorization", "Bearer " + login(chief, "ANDROID")).header("Origin", ANDROID));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(get(task1, cookie).statusCode()).isEqualTo(200);
    }

    @Test void malformedIdsAndUnexpectedQueriesAreRejected() throws Exception {
        for (String path : List.of("/api/v1/tasks/not-a-uuid", "/api/v1/tasks/1-1-1-1-1",
                "/api/v1/tasks/" + task1 + "?extra=1", "/api/v1/tasks/" + task1 + "?limit=30"))
            error(send(HttpRequest.newBuilder(uri(path)).GET().header("Cookie", cookie)), 400, "INVALID_REQUEST");
    }

    @Test void stageTwo050IsOpenWhileUnsupportedWritesRemainClosed() throws Exception {
        var listed = send(HttpRequest.newBuilder(uri("/api/v1/events/" + event + "/tasks")).GET().header("Cookie", cookie));
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        var response = send(HttpRequest.newBuilder(uri("/api/v1/tasks/" + task1 + "/start"))
                .header("Cookie", cookie).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{}")));
        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test void revokedAccountAndTokenAreNotAuthenticated() throws Exception {
        jdbc.update("UPDATE account SET token_version=2 WHERE id=?", chief);
        error(get(task1, cookie), 401, "UNAUTHENTICATED");
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?", chief);
        error(get(task1, cookie), 401, "UNAUTHENTICATED");
    }

    @Test void joinTriggersAutomaticAssignmentAndWorkerCompletesBundleTask() throws Exception {
        jdbc.update("DELETE FROM member WHERE id=?", workerMember);
        UUID otherWorker = member(outsider, "WORKER", group, 3);
        jdbc.update("UPDATE member SET available=false WHERE id=?", otherWorker);
        String workerCookie = login(worker, "WEB");
        String otherWorkerCookie = login(outsider, "WEB");
        String joinKey = UUID.randomUUID().toString();
        long beforeJoin = revision();
        String token = prepareInvite();

        var joined = join(workerCookie, beforeJoin, token, joinKey);
        assertThat(joined.statusCode()).as(joined.body()).isEqualTo(200);
        var joinBody = json.readTree(joined.body());
        var participation = joinBody.path("participation");
        workerMember = UUID.fromString(participation.path("memberId").asString());
        String round = participation.path("participationRevision").asString();
        assertThat(round).isEqualTo("1");
        assertThat(jdbc.queryForObject("""
                SELECT participation_revision::text FROM member WHERE id=? AND event_id=? AND account_id=?
                    AND work_group_id=? AND active AND available AND NOT is_reserve AND event_role='WORKER'
                """, String.class, workerMember, event, worker, group)).isEqualTo(round);
        assertThat(joinBody.path("initialAssignment").asString()).isEqualTo("UNKNOWN");
        assertThat(count("""
                SELECT count(*) FROM task WHERE event_id=? AND bundle_key='bundle'
                    AND status='WAITING' AND assignee_id IS NULL AND first_assigned_at IS NULL
                """, event)).isEqualTo(2);
        UUID triggerId = jdbc.queryForObject("""
                SELECT id FROM outbox_event WHERE event_id=? AND type=? AND processed_at IS NULL
                    AND payload->>'workGroupId'=?
                """, UUID.class, event, InitialAssignmentTrigger.TYPE, group.toString());
        assertThat(count("SELECT count(*) FROM ai_job WHERE event_id=?", event)).isZero();

        var requests = new LinkedBlockingQueue<JsonNode>();
        UUID assignedMember = workerMember;
        ai.answer = request -> {
            requests.add(request);
            return assignmentResponse(request, assignedMember);
        };
        relay.relayOnce(); // 실제 Join 트리거 → Job 접수
        UUID jobId = jdbc.queryForObject("""
                SELECT id FROM ai_job WHERE event_id=? AND kind='ASSIGN_INITIAL'
                """, UUID.class, event);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE id=? AND processed_at IS NOT NULL", triggerId))
                .isEqualTo(1);
        relay.relayOnce(); // Job dispatch → 실제 Runner
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT state FROM ai_job WHERE id=?", String.class, jobId)).isEqualTo("SUCCEEDED"));
        var request = requests.poll();
        assertThat(request).isNotNull();
        assertThat(request.path("kind").asString()).isEqualTo("ASSIGN_INITIAL");
        assertThat(request.path("jobId").asString()).isEqualTo(jobId.toString());
        assertThat(request.path("payload").path("members").get(0).path("memberId").asString())
                .isEqualTo(workerMember.toString());
        assertThat(request.path("payload").path("allowExternalAi").asBoolean()).isFalse();

        long afterAssignment = revision();
        assertThat(afterAssignment).isEqualTo(beforeJoin + 2);
        var assigned = jdbc.queryForList("""
                SELECT id, first_assigned_at::text AS assigned_at FROM task
                WHERE event_id=? AND bundle_key='bundle' AND assignee_id=? AND status='WAITING'
                    AND first_assigned_at IS NOT NULL
                """, event, workerMember);
        assertThat(assigned).extracting(row -> row.get("id")).containsExactlyInAnyOrder(task1, task2);
        assertThat(assigned).extracting(row -> row.get("assigned_at")).containsOnly(assigned.getFirst().get("assigned_at"));
        assertThat(count("""
                SELECT count(*) FROM operation_log WHERE event_id=? AND type='INITIAL_ASSIGNMENT' AND resource_id=?
                    AND changes->>'source'='AI' AND changes->>'jobId'=? AND changes->>'participationRevision'=?
                """, event, workerMember, jobId.toString(), round)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM notification WHERE event_id=? AND type='INITIAL_ASSIGNMENT'
                    AND member_id=? AND participation_revision=?::bigint
                """, event, workerMember, round)).isEqualTo(1);

        Set<UUID> listed = new HashSet<>();
        for (var task : page(workerCookie, "").path("items")) {
            listed.add(UUID.fromString(task.path("id").asString()));
        }
        assertThat(listed).containsExactlyInAnyOrder(task1, task2);
        var detail = get(task1, workerCookie);
        assertThat(detail.statusCode()).isEqualTo(200);
        String firstAssignedAt = json.readTree(detail.body()).path("firstAssignedAt").asString();
        assertThat(firstAssignedAt).isNotBlank();
        error(command(task1, "start", afterAssignment, otherWorkerCookie, null), 403, "FORBIDDEN");
        success(command(task1, "start", afterAssignment, workerCookie, null), "IN_PROGRESS", afterAssignment + 1);
        success(command(task1, "checklist", afterAssignment + 1, workerCookie, true), "IN_PROGRESS", afterAssignment + 2);
        success(command(task1, "complete", afterAssignment + 2, workerCookie, null), "DONE", afterAssignment + 3);
        assertThat(count("""
                SELECT count(*) FROM task WHERE id=? AND assignee_id=? AND status='DONE'
                    AND first_assigned_at=?::timestamptz AND actual_started_at IS NOT NULL
                    AND actual_ended_at >= actual_started_at AND (checklist->0->>'checked')::boolean
                    AND checklist->0->>'checkedByMemberId'=?
                """, task1, workerMember, firstAssignedAt, workerMember.toString())).isEqualTo(1);

        // Join 재요청을 확인하기 전에 Job 완료가 발행한 후속 이벤트를 처리한다.
        assertThat(count("""
                SELECT count(*) FROM outbox_event WHERE event_id=? AND type=? AND processed_at IS NULL
                """, event, AiJobOutboxTypes.FINISHED)).isEqualTo(1);
        assertThat(relay.relayOnce()).isEqualTo(1);
        String beforeReplay = effects();
        var replay = join(workerCookie, beforeJoin, token, joinKey);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(json.readTree(replay.body())).isEqualTo(joinBody);
        assertThat(effects()).isEqualTo(beforeReplay);
        assertThat(relay.relayOnce()).isZero();

        // 이미 처리한 원본 행을 다시 전달해 업무 효과가 반복되는지 확인한다.
        assertThat(jdbc.update("UPDATE outbox_event SET processed_at=NULL WHERE id=?", triggerId)).isEqualTo(1);
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE id=? AND processed_at IS NOT NULL", triggerId))
                .isEqualTo(1);
        assertThat(effects()).isEqualTo(beforeReplay);
        assertThat(requests).isEmpty();
        assertThat(count("SELECT count(*) FROM ai_job WHERE event_id=?", event)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=? AND type='INITIAL_ASSIGNMENT'", event)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification WHERE event_id=? AND type='INITIAL_ASSIGNMENT'", event)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE event_id=?", event)).isZero();
        assertThat(count("""
                SELECT count(*) FROM task WHERE event_id=? AND bundle_key='bundle'
                    AND assignee_id=? AND first_assigned_at=?::timestamptz
                """, event, workerMember, firstAssignedAt)).isEqualTo(2);
    }

    private AiResponse assignmentResponse(JsonNode request, UUID assignedMember) {
        var response = json.createObjectNode();
        for (String field : List.of("jobId", "attempt", "requestSchemaVersion", "inputHash", "contextRevision")) {
            response.set(field, request.path(field));
        }
        response.put("resultSchemaVersion", "1");
        response.put("modelVersion", "assign-rule-v1");
        var result = response.putObject("result");
        result.putArray("assignments").addObject().put("bundleKey", "bundle").put("memberId", assignedMember.toString());
        result.putArray("unassigned");
        result.putArray("reasons").addObject().put("bundleKey", "bundle").put("text", "시간이 비는 팀원입니다.");
        result.put("generator", "RULE");
        return new AiResponse(200, json.writeValueAsString(response));
    }

    static final class FakeAssign implements AiClient {
        volatile Function<JsonNode, AiResponse> answer;

        @Override
        public AiResponse post(String path, String body, Duration timeout) {
            var configured = answer;
            if (configured == null) throw new IllegalStateException("No assignment response configured");
            return configured.apply(JsonMapper.builder().build().readTree(body));
        }
    }

    @Test void actualJoinAssignmentStartCheckCompleteAndDoneReplay() throws Exception {
        jdbc.update("DELETE FROM member WHERE id=?", workerMember);
        String workerCookie = login(worker, "WEB");
        long beforeJoin = revision();
        var joined = join(workerCookie, beforeJoin);
        assertThat(joined.statusCode()).as("035 join HTTP: %s", joined.body()).isEqualTo(200);
        var joinedBody = json.readTree(joined.body());
        var participation = joinedBody.get("participation");
        workerMember = UUID.fromString(participation.get("memberId").asString());
        long round = Long.parseLong(participation.get("participationRevision").asString());
        assertThat(round).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=? AND active",
                UUID.class, event, worker)).isEqualTo(workerMember);
        assertThat(jdbc.queryForObject("SELECT participation_revision FROM member WHERE id=?", Long.class, workerMember))
                .as("035 current participation round, member=%s", workerMember).isEqualTo(round);
        long currentRevision = Long.parseLong(joinedBody.get("eventRevision").asString());
        assertThat(currentRevision).isEqualTo(beforeJoin + 1);
        assertThat(revision()).isEqualTo(currentRevision);

        Set<UUID> expectedTasks = Set.of(task1, task2);
        assertThat(jdbc.queryForList("""
                SELECT id FROM task WHERE event_id=? AND bundle_key='bundle' AND status='WAITING'
                    AND assignee_id IS NULL AND first_assigned_at IS NULL
                """, UUID.class, event)).containsExactlyInAnyOrderElementsOf(expectedTasks);
        String key = UUID.randomUUID().toString();
        String assignedBody = json.writeValueAsString(Map.of("bundleKey", "bundle", "memberId", workerMember,
                "expectedEventRevision", Long.toString(currentRevision)));
        var assigned = send(write("POST", "/api/v1/events/" + event + "/initial-assignments", assignedBody, cookie)
                .header("Idempotency-Key", key));
        assertThat(assigned.statusCode()).as("045 assignment HTTP: %s", assigned.body()).isEqualTo(200);
        var assignedReceipt = json.readTree(assigned.body());
        long assignedRevision = Long.parseLong(assignedReceipt.get("eventRevision").asString());
        assertThat(assignedRevision).isEqualTo(currentRevision + 1);
        currentRevision = assignedRevision;
        assertThat(revision()).isEqualTo(currentRevision);
        Map<UUID, JsonNode> assignedTasks = new HashMap<>();
        for (var task : assignedReceipt.get("tasks")) {
            UUID id = UUID.fromString(task.get("id").asString());
            assertThat(assignedTasks.put(id, task)).as("045 duplicate task, task=%s", id).isNull();
            assertThat(task.get("assigneeId").asString()).isEqualTo(workerMember.toString());
            assertThat(task.get("bundleKey").asString()).isEqualTo("bundle");
            assertThat(task.get("status").asString()).isEqualTo("WAITING");
            assertThat(task.get("firstAssignedAt").isNull()).isFalse();
            assertThat(task.get("actualStartedAt").isNull()).isTrue();
            assertThat(task.get("actualEndedAt").isNull()).isTrue();
        }
        assertThat(assignedTasks.keySet()).as("045 assigned task IDs").containsExactlyInAnyOrderElementsOf(expectedTasks);
        String firstAssignedAt = assignedTasks.get(task1).get("firstAssignedAt").asString();
        for (UUID id : expectedTasks) {
            assertThat(assignedTasks.get(id).get("firstAssignedAt").asString())
                    .as("045 bundle assignment time, task=%s", id).isEqualTo(firstAssignedAt);
            assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?", UUID.class, id))
                    .as("045 stored assignee, task=%s", id).isEqualTo(workerMember);
            assertThat(jdbc.queryForObject("SELECT first_assigned_at FROM task WHERE id=?", java.sql.Timestamp.class, id)
                    .toInstant().toString()).as("045 stored assignment time, task=%s", id).isEqualTo(firstAssignedAt);
        }
        var evidence = json.readTree(jdbc.queryForObject("""
                SELECT changes::text FROM operation_log
                WHERE event_id=? AND type='INITIAL_ASSIGNMENT' AND resource_id=?
                """, String.class, event, workerMember));
        assertThat(evidence.get("memberId").asString()).isEqualTo(workerMember.toString());
        assertThat(evidence.get("participationRevision").asString())
                .as("045 OperationLog round, member=%s", workerMember).isEqualTo(Long.toString(round));
        assertThat(evidence.get("firstAssignedAt").asString()).isEqualTo(firstAssignedAt);
        Set<UUID> evidenceIds = new HashSet<>();
        for (var id : evidence.get("taskIds")) evidenceIds.add(UUID.fromString(id.asString()));
        assertThat(evidenceIds).containsExactlyInAnyOrderElementsOf(expectedTasks);
        var refs = json.readTree(jdbc.queryForObject("""
                SELECT result_refs::text FROM idempotency_record
                WHERE event_id=? AND operation='SC-API-045' AND idempotency_key=?
                """, String.class, event, key));
        assertThat(refs.get("memberId").asString()).isEqualTo(workerMember.toString());
        assertThat(refs.get("participationRevision").asLong())
                .as("045 receipt round, member=%s", workerMember).isEqualTo(round);

        var listed = page(workerCookie, "");
        Set<UUID> listedIds = new HashSet<>();
        assertThat(listed.get("items").size()).isEqualTo(expectedTasks.size());
        for (var task : listed.get("items")) {
            UUID id = UUID.fromString(task.get("id").asString());
            assertThat(listedIds.add(id)).as("050 duplicate task, task=%s", id).isTrue();
            assertThat(task).as("050 worker task matches 045, task=%s", id).isEqualTo(assignedTasks.get(id));
        }
        assertThat(listedIds).as("050 worker task IDs").containsExactlyInAnyOrderElementsOf(expectedTasks);
        assertThat(listed.get("nextCursor").isNull()).isTrue();
        for (UUID id : expectedTasks) {
            var detail = get(id, workerCookie);
            assertThat(detail.statusCode()).as("051 detail HTTP, task=%s: %s", id, detail.body()).isEqualTo(200);
            assertThat(json.readTree(detail.body())).as("051 worker detail matches 045, task=%s", id)
                    .isEqualTo(assignedTasks.get(id));
        }

        JsonNode completed = null;
        String actualStartedAt = null;
        long beforeCompletion = -1;
        for (String action : List.of("start", "checklist", "complete")) {
            long expectedRevision = currentRevision;
            var response = command(task1, action, expectedRevision, workerCookie, action.equals("checklist") ? true : null);
            String state = action.equals("complete") ? "DONE" : "IN_PROGRESS";
            assertThat(response.statusCode()).as("%s HTTP, task=%s: %s", action, task1, response.body()).isEqualTo(200);
            var result = json.readTree(response.body());
            assertThat(result.get("task").get("status").asString()).as("%s response state, task=%s", action, task1).isEqualTo(state);
            assertThat(result.get("eventRevision").asString()).as("%s response revision, task=%s", action, task1)
                    .isEqualTo(Long.toString(expectedRevision + 1));
            currentRevision = Long.parseLong(result.get("eventRevision").asString());
            assertThat(revision()).as("%s stored event revision, task=%s", action, task1).isEqualTo(currentRevision);
            var task = result.get("task");
            assertThat(task.get("id").asString()).isEqualTo(task1.toString());
            assertThat(task.get("assigneeId").asString()).as("%s unchanged assignee, task=%s", action, task1)
                    .isEqualTo(workerMember.toString());
            assertThat(task.get("firstAssignedAt").asString()).as("%s unchanged assignment time, task=%s", action, task1)
                    .isEqualTo(firstAssignedAt);
            assertThat(task.get("actualStartedAt").isNull()).isFalse();
            if (actualStartedAt == null) actualStartedAt = task.get("actualStartedAt").asString();
            assertThat(task.get("actualStartedAt").asString()).as("%s stable start time, task=%s", action, task1)
                    .isEqualTo(actualStartedAt);
            assertThat(jdbc.queryForObject("SELECT actual_started_at FROM task WHERE id=?", java.sql.Timestamp.class, task1)
                    .toInstant().toString()).isEqualTo(actualStartedAt);
            assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id=?", String.class, task1))
                    .as("%s stored task state, task=%s", action, task1).isEqualTo(state);
            for (UUID id : expectedTasks) {
                assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?", UUID.class, id))
                        .as("%s stored assignee, task=%s", action, id).isEqualTo(workerMember);
                assertThat(jdbc.queryForObject("SELECT first_assigned_at FROM task WHERE id=?", java.sql.Timestamp.class, id)
                        .toInstant().toString()).as("%s stored assignment time, task=%s", action, id).isEqualTo(firstAssignedAt);
            }
            if (!action.equals("start")) {
                var check = task.get("checklist").get(0);
                assertThat(check.get("checked").asBoolean()).isTrue();
                assertThat(check.get("checkedByMemberId").asString()).isEqualTo(workerMember.toString());
                assertThat(check.get("checkedAt").isNull()).isFalse();
                assertThat(jdbc.queryForObject("SELECT (checklist->0->>'checked')::boolean FROM task WHERE id=?",
                        Boolean.class, task1)).isTrue();
            }
            if (action.equals("complete")) {
                beforeCompletion = expectedRevision;
                completed = result;
                assertThat(jdbc.queryForObject("SELECT actual_ended_at FROM task WHERE id=?", java.sql.Timestamp.class, task1)
                        .toInstant().toString()).isEqualTo(task.get("actualEndedAt").asString());
            } else {
                assertThat(task.get("actualEndedAt").isNull()).isTrue();
                assertThat(jdbc.queryForObject("SELECT actual_ended_at FROM task WHERE id=?", java.sql.Timestamp.class, task1)).isNull();
            }
        }
        assertThat(completed).isNotNull();
        var done = completed.get("task");
        assertThat(done.get("actualStartedAt").isNull()).isFalse();
        assertThat(done.get("actualEndedAt").isNull()).isFalse();
        assertThat(json.readTree(get(task1, workerCookie).body())).isEqualTo(done);
        var remaining = page(workerCookie, "").get("items");
        assertThat(remaining.size()).isEqualTo(1);
        assertThat(remaining.get(0)).as("050 after completion retains only untouched task=%s", task2)
                .isEqualTo(assignedTasks.get(task2));
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id=?", String.class, task2)).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT actual_started_at FROM task WHERE id=?", java.sql.Timestamp.class, task2)).isNull();
        assertThat(jdbc.queryForObject("SELECT actual_ended_at FROM task WHERE id=?", java.sql.Timestamp.class, task2)).isNull();
        assertThat(jdbc.queryForObject("SELECT participation_revision FROM member WHERE id=?", Long.class, workerMember))
                .as("after completion unchanged participation round, member=%s", workerMember).isEqualTo(round);
        assertThat(json.readTree(jdbc.queryForObject("""
                SELECT receipt::text FROM idempotency_record
                WHERE event_id=? AND operation='SC-API-045' AND idempotency_key=?
                """, String.class, event, key))).isEqualTo(assignedReceipt);
        String before = effects();
        var repeated = command(task1, "complete", currentRevision, workerCookie, null);
        assertThat(repeated.statusCode()).as(repeated.body()).isEqualTo(200);
        assertThat(json.readTree(repeated.body())).isEqualTo(completed);
        assertThat(effects()).isEqualTo(before);
        error(command(task1, "complete", beforeCompletion, workerCookie, null), 409, "VERSION_CONFLICT");
        assertThat(effects()).isEqualTo(before);
        // 실제 참여(035)가 남긴 AI 최초 배정 트리거뿐이다(#267). 업무 명령은 outbox를 더 남기지 않는다.
        assertThat(jdbc.queryForList("SELECT type FROM outbox_event WHERE event_id=?", String.class, event))
                .containsExactly("ASSIGN_INITIAL_TRIGGER");
        // 045 수동 배정이 만든 처음 맡음 알림 1건뿐이다(V9-D47). 업무 시작·체크·완료는 알림을 더 만들지 않는다.
        assertThat(count("SELECT count(*) FROM notification WHERE event_id=?", event)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification WHERE event_id=? AND type='INITIAL_ASSIGNMENT' AND member_id=?",
                event, workerMember)).isEqualTo(1);
    }

    @Test void checklistSameValueAndUncheckPreserveDefinitionsAndNoopEvidence() throws Exception {
        assignFixture("WAITING"); String credential = login(worker, "WEB");
        String before = effects();
        success(command(task1, "checklist", 3, credential, false), "WAITING", 3);
        assertThat(effects()).isEqualTo(before);
        var checked = command(task1, "checklist", 3, credential, true);
        success(checked, "WAITING", 4);
        var item = json.readTree(checked.body()).get("task").get("checklist").get(0);
        assertThat(item.get("checkedByMemberId").asString()).isEqualTo(workerMember.toString());
        assertThat(item.get("checkedAt").isNull()).isFalse();
        assertThat(item.get("text").asString()).isEqualTo("점검");
        assertThat(item.get("required").asBoolean()).isTrue();
        before = effects();
        success(command(task1, "checklist", 4, credential, true), "WAITING", 4);
        assertThat(effects()).isEqualTo(before);
        error(command(task1, "checklist", 3, credential, true), 409, "VERSION_CONFLICT");
        var unchecked = command(task1, "checklist", 4, credential, false);
        success(unchecked, "WAITING", 5);
        item = json.readTree(unchecked.body()).get("task").get("checklist").get(0);
        assertThat(item.get("checkedAt").isNull()).isTrue();
        assertThat(item.get("checkedByMemberId").isNull()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"start:IN_PROGRESS", "start:DONE", "start:CANCELED", "complete:WAITING",
            "complete:CANCELED", "checklist:DONE", "checklist:CANCELED", "complete:IN_PROGRESS"})
    void rejectsDisallowedTransitionsAndMissingRequiredCheck(String scenario) throws Exception {
        String[] parts = scenario.split(":"); assignFixture(parts[1]);
        String before = effects();
        error(command(task1, parts[0], 3, login(worker, "WEB"), parts[0].equals("checklist") ? true : null), 409, "INVALID_STATE");
        assertThat(effects()).isEqualTo(before);
    }

    @Test void inputUnknownKeysAndStaleRevisionDoNotWrite() throws Exception {
        assignFixture("WAITING"); String credential = login(worker, "WEB"); String before = effects();
        for (String body : List.of("{}", "{\"expectedEventRevision\":3}", "{\"expectedEventRevision\":\"03\"}",
                "{\"expectedEventRevision\":\"9223372036854775808\"}", "{\"expectedEventRevision\":\"-1\"}",
                "{\"expectedEventRevision\":null}", "{\"expectedEventRevision\":\"3\",\"extra\":1}"))
            error(send(write("POST", "/api/v1/tasks/" + task1 + "/start", body, credential)), 400, "INVALID_REQUEST");
        for (String checks : List.of("null", "{}", "[{\"key\":\"check\",\"checked\":true,\"text\":\"changed\"}]",
                "[{\"key\":\"check\",\"checked\":1}]", "[{\"key\":\"check\",\"checked\":true},{\"key\":\"check\",\"checked\":false}]",
                "[" + String.join(",", java.util.stream.IntStream.range(0,7).mapToObj(i -> "{\"key\":\"" + i + "\",\"checked\":true}").toList()) + "]"))
            error(send(write("PATCH", "/api/v1/tasks/" + task1 + "/checklist", "{\"expectedEventRevision\":\"3\",\"checks\":" + checks + "}", credential)), 400, "INVALID_REQUEST");
        error(send(write("PATCH", "/api/v1/tasks/" + task1 + "/checklist", "{\"expectedEventRevision\":\"3\",\"checks\":[{\"key\":\"missing\",\"checked\":true}]}", credential)), 422, "INVALID_REFERENCE");
        error(command(task1, "start", 2, credential, null), 409, "VERSION_CONFLICT");
        assertThat(effects()).isEqualTo(before);
    }

    @Test void actualRejoinBlocksUnfinishedAssignmentsAndRetainsOnlyReadableOwnHistory() throws Exception {
        assignFixture("WAITING"); String credential = login(worker, "WEB");
        jdbc.update("UPDATE task SET assignee_id=? WHERE id=?", workerMember, task2);
        String oldCursor = page(credential, "?limit=1").get("nextCursor").asString();
        jdbc.update("UPDATE member SET active=false WHERE id=?", workerMember); // Future leave API is not invented here.
        error(join(credential, 3), 409, "INVALID_STATE");
        jdbc.update("UPDATE task SET status='IN_PROGRESS' WHERE id=?", task1);
        error(join(credential, 3), 409, "INVALID_STATE");
        jdbc.update("UPDATE task SET status='DONE',actual_ended_at='2026-11-06T01:00:00Z' WHERE id=?", task1);
        jdbc.update("UPDATE task SET status='CANCELED',assignee_id=? WHERE id=?", workerMember, task2);
        var joined = join(credential, 3);
        assertThat(joined.statusCode()).as(joined.body()).isEqualTo(200);
        var participation = json.readTree(joined.body()).get("participation");
        assertThat(participation.get("memberId").asString()).isEqualTo(workerMember.toString());
        assertThat(participation.get("participationRevision").asString()).isEqualTo("3");
        error(send(HttpRequest.newBuilder(uri("/api/v1/events/" + event + "/tasks?cursor=" + oldCursor)).GET()
                .header("Cookie", credential)), 400, "INVALID_REQUEST");
        assertThat(page(credential, "").get("items").isEmpty()).isTrue();
        assertThat(get(task1, credential).statusCode()).isEqualTo(200);
        assertThat(get(task2, credential).statusCode()).isEqualTo(200);
        String before = effects();
        success(command(task1, "complete", 4, credential, null), "DONE", 4);
        error(command(task1, "start", 4, credential, null), 409, "INVALID_STATE");
        error(command(task2, "complete", 4, credential, null), 409, "INVALID_STATE");
        assertThat(effects()).isEqualTo(before);
    }

    @Test void chiefCannotPerformOthersAndWorkersCannotAccessOthers() throws Exception {
        assignFixture("WAITING"); String credential = login(worker, "WEB");
        error(command(task1, "start", 3, cookie, null), 403, "FORBIDDEN");
        error(get(task2, credential), 404, "NOT_FOUND");
        error(command(task2, "start", 3, credential, null), 403, "FORBIDDEN");
        error(command(task1, "start", 3, login(outsider, "WEB"), null), 404, "NOT_FOUND");
        jdbc.update("UPDATE member SET active=false WHERE id=?", workerMember);
        error(command(task1, "start", 3, credential, null), 404, "NOT_FOUND");
    }

    @Test void androidWritesAndWebOriginProtectionUseExistingPolicy() throws Exception {
        assignFixture("WAITING"); String token = login(worker, "ANDROID");
        var request = HttpRequest.newBuilder(uri("/api/v1/tasks/" + task1 + "/start"))
                .header("Authorization", "Bearer " + token).header("Origin", ANDROID).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"expectedEventRevision\":\"3\"}"));
        success(send(request), "IN_PROGRESS", 4);
        var rejected = HttpRequest.newBuilder(uri("/api/v1/tasks/" + task1 + "/complete"))
                .header("Cookie", login(worker, "WEB")).header("Origin", "https://other.example.invalid")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"expectedEventRevision\":\"4\"}"));
        assertThat(send(rejected).statusCode()).isEqualTo(403);
    }

    @ParameterizedTest @ValueSource(strings = {"DRAFT", "ENDED"})
    void nonoperatingEventsRejectNewExecutionButDoneReplayIsReadLike(String state) throws Exception {
        assignFixture("WAITING"); String credential = login(worker, "WEB");
        jdbc.update("UPDATE event SET state=? WHERE id=?", state, event);
        error(command(task1, "start", 3, credential, null), 409, "INVALID_STATE");
        jdbc.update("UPDATE task SET status='DONE' WHERE id=?", task1);
        String before = effects();
        success(command(task1, "complete", 3, credential, null), "DONE", 3);
        assertThat(effects()).isEqualTo(before);
    }

    @Test void plannedTimePaginationLimitsAndBoundariesMatchDatabaseOrder() throws Exception {
        for (int i = 0; i < 105; i++) task("2026-11-14T09:00:00Z", "2026-11-14T10:00:00Z");
        jdbc.update("UPDATE task SET actual_started_at='2027-01-01T00:00:00Z' WHERE id=?", task1);
        assertThat(page(cookie, "").get("items").size()).isEqualTo(30);
        assertThat(page(cookie, "?limit=100").get("items").size()).isEqualTo(100);
        var expected = jdbc.queryForList("SELECT id::text FROM task WHERE event_id=? ORDER BY start_at ASC NULLS LAST,id", String.class, event);
        var seen = new ArrayList<String>(); String next = null;
        do {
            var value = page(cookie, "?limit=17" + (next == null ? "" : "&cursor=" + next));
            value.get("items").forEach(t -> seen.add(t.get("id").asString()));
            next = value.get("nextCursor").isNull() ? null : value.get("nextCursor").asString();
        } while (next != null && seen.size() < 200);
        assertThat(seen).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        for (String query : List.of("?limit=0", "?limit=101", "?limit=01", "?limit=abc", "?limit=1&limit=2", "?cursor=", "?unknown=1"))
            error(send(HttpRequest.newBuilder(uri("/api/v1/events/" + event + "/tasks" + query)).GET().header("Cookie", cookie)), 400, "INVALID_REQUEST");
    }

    @Test void cursorIsBoundToAccountEventRoleAndRoundAndRejectsTampering() throws Exception {
        String cursor = page(cookie, "?limit=1").get("nextCursor").asString();
        String base = "/api/v1/events/" + event + "/tasks?cursor=";
        error(send(HttpRequest.newBuilder(uri(base + "X" + cursor.substring(1))).GET().header("Cookie", cookie)), 400, "INVALID_REQUEST");
        jdbc.update("UPDATE member SET event_role='WORKER',work_group_id=? WHERE id=?", group, chiefMember);
        error(send(HttpRequest.newBuilder(uri(base + cursor)).GET().header("Cookie", cookie)), 400, "INVALID_REQUEST");
        jdbc.update("UPDATE member SET event_role='CHIEF',participation_revision=3 WHERE id=?", chiefMember);
        error(send(HttpRequest.newBuilder(uri(base + cursor)).GET().header("Cookie", cookie)), 400, "INVALID_REQUEST");
        jdbc.update("UPDATE member SET participation_revision=2 WHERE id=?", chiefMember);
        var other = new TaskCursor.Scope(event, worker, workerMember, 2, "WORKER");
        String foreign = codec.encode(other, new TaskCursor.Position(java.time.Instant.EPOCH, task1));
        error(send(HttpRequest.newBuilder(uri(base + foreign)).GET().header("Cookie", cookie)), 400, "INVALID_REQUEST");
        other = new TaskCursor.Scope(UUID.randomUUID(), chief, chiefMember, 2, "CHIEF");
        foreign = codec.encode(other, new TaskCursor.Position(java.time.Instant.EPOCH, task1));
        error(send(HttpRequest.newBuilder(uri(base + foreign)).GET().header("Cookie", cookie)), 400, "INVALID_REQUEST");
    }

    @Test void nullBoundarySqlWorksInAnIsolatedTemporaryProjectionWithoutChangingTaskSchema() {
        // Production start_at is NOT NULL. A transaction-local temp projection exercises the requested legacy/null ordering.
        tx.executeWithoutResult(status -> {
            jdbc.execute("CREATE TEMP TABLE task ON COMMIT DROP AS SELECT * FROM public.task");
            jdbc.update("UPDATE task SET start_at=NULL WHERE id=?", task2);
            var first = reads.list(event, chief, null, 1, codec);
            assertThat(first.items()).extracting(t -> t.id()).containsExactly(task1);
            var last = reads.list(event, chief, first.nextCursor(), 1, codec);
            assertThat(last.items()).extracting(t -> t.id()).containsExactly(task2);
            assertThat(last.nextCursor()).isNull();
            jdbc.update("UPDATE task SET start_at=NULL");
            first = reads.list(event, chief, null, 1, codec);
            last = reads.list(event, chief, first.nextCursor(), 1, codec);
            assertThat(List.of(first.items().getFirst().id(), last.items().getFirst().id())).doesNotHaveDuplicates().containsExactlyInAnyOrder(task1, task2);
        });
        assertThatThrownBy(() -> jdbc.update("UPDATE task SET start_at=NULL WHERE id=?", task1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void livePagesPermitSortKeyMovementAndRestartReadsCurrentOrder() throws Exception {
        var first = page(cookie, "?limit=1"); String cursor = first.get("nextCursor").asString();
        jdbc.update("UPDATE task SET start_at='2026-11-14T08:00:00Z' WHERE id=?", task2);
        var continued = page(cookie, "?limit=1&cursor=" + cursor);
        assertThat(continued.get("items").isEmpty()).isTrue(); // Accepted live traversal omission, not a snapshot.
        var restarted = page(cookie, "?limit=1");
        assertThat(restarted.get("items").get(0).get("id").asString()).isEqualTo(task2.toString());
        task("2026-11-14T09:30:00Z", "2026-11-14T10:00:00Z");
        assertThat(page(cookie, "?limit=100").get("items").size()).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(strings = {"start", "checklist", "complete-noop"})
    void concurrentCommandsSerializeOnRealEventLock(String operation) throws Exception {
        assignFixture(operation.equals("complete-noop") ? "DONE" : "WAITING");
        String credential = login(worker, "WEB"), before = effects();
        String command = operation.equals("complete-noop") ? "complete" : operation;
        Callable<HttpResponse<String>> call = () -> command(task1, command, 3, credential, operation.equals("checklist") ? true : null);
        var responses = compete(call, call);
        if (operation.equals("complete-noop")) {
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(200, 200);
            assertThat(effects()).isEqualTo(before);
        } else {
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(200, 409);
            assertThat(revision()).isEqualTo(4);
        }
    }

    @Test void checklistThenCompletionAtSameRevisionCannotCommitStaleCompletion() throws Exception {
        assignFixture("IN_PROGRESS"); String credential = login(worker, "WEB");
        var responses = compete(() -> command(task1, "checklist", 3, credential, true),
                () -> command(task1, "complete", 3, credential, null));
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(200, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id=?", String.class, task1)).isEqualTo("IN_PROGRESS");
        assertThat(revision()).isEqualTo(4);
    }

    @Test void concurrentNormalCompletionsCommitOnceThenLatestRevisionIsNoop() throws Exception {
        assignFixture("WAITING"); String credential = login(worker, "WEB");
        success(command(task1, "start", 3, credential, null), "IN_PROGRESS", 4);
        success(command(task1, "checklist", 4, credential, true), "IN_PROGRESS", 5);
        long logs = count("SELECT count(*) FROM operation_log WHERE event_id=?", event);
        var responses = compete(() -> command(task1, "complete", 5, credential, null),
                () -> command(task1, "complete", 5, credential, null));
        success(responses.getFirst(), "DONE", 6);
        error(responses.getLast(), 409, "VERSION_CONFLICT");
        var completed = json.readTree(responses.getFirst().body());
        assertThat(completed.get("task").get("actualEndedAt").isNull()).isFalse();
        assertThat(json.readTree(get(task1, credential).body())).isEqualTo(completed.get("task"));
        assertThat(revision()).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=?", event)).isEqualTo(logs);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_id=?", event)).isZero();
        assertThat(count("SELECT count(*) FROM notification WHERE event_id=?", event)).isZero();
        String before = effects();
        var replay = command(task1, "complete", 6, credential, null);
        success(replay, "DONE", 6);
        assertThat(json.readTree(replay.body())).isEqualTo(completed);
        assertThat(effects()).isEqualTo(before);
    }

    @Test void doneReplayPreservesActualChangeAcknowledgmentAndExistingNotification() throws Exception {
        String credential = login(worker, "WEB");
        String operation = "[{\"type\":\"ASSIGN_TASK\",\"payload\":{\"taskId\":\"" + task1
                + "\",\"fromMemberId\":null,\"toMemberId\":\"" + workerMember + "\"}}]";
        var created = send(write("POST", "/api/v1/events/" + event + "/change-sets",
                "{\"mode\":\"MANUAL\",\"operations\":" + operation + ",\"rationale\":\"fixture\",\"expectedEventRevision\":\"3\"}", cookie)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID change = UUID.fromString(json.readTree(created.body()).get("id").asString());
        var preview = send(write("POST", "/api/v1/change-sets/" + change + "/preview",
                "{\"expectedEventRevision\":\"3\",\"expectedChangeSetRevision\":\"1\"}", cookie));
        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        var value = json.readTree(preview.body());
        var applied = send(write("POST", "/api/v1/change-sets/" + change + "/apply",
                json.writeValueAsString(Map.of("expectedEventRevision", "3", "expectedChangeSetRevision",
                        value.get("revision").asString(), "previewHash", value.get("previewHash").asString())), cookie)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(200);
        UUID acknowledgment = jdbc.queryForObject("SELECT id FROM change_acknowledgment WHERE change_set_id=? AND member_id=?",
                UUID.class, change, workerMember);
        // Existing inbox data is a fixture only; no unmerged #345 producer is installed or copied.
        jdbc.update("""
                INSERT INTO notification (id,member_id,participation_revision,event_id,change_set_id,acknowledgment_id,
                    type,payload,dedupe_key,created_at) VALUES (?,?,2,?,?,?,'CHANGE_ACK','{}'::jsonb,?,now())
                """, UUID.randomUUID(), workerMember, event, change, acknowledgment, "task281-fixture:" + acknowledgment);
        String records = acknowledgmentAndNotificationSnapshot();
        success(command(task1, "start", 4, credential, null), "IN_PROGRESS", 5);
        success(command(task1, "checklist", 5, credential, true), "IN_PROGRESS", 6);
        success(command(task1, "complete", 6, credential, null), "DONE", 7);
        assertThat(acknowledgmentAndNotificationSnapshot()).isEqualTo(records);
        String before = effects();
        success(command(task1, "complete", 7, credential, null), "DONE", 7);
        error(command(task1, "complete", 6, credential, null), 409, "VERSION_CONFLICT");
        assertThat(effects()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"task", "event", "commit"})
    void completeStatementAndCommitFailuresRollBackAndPermitNormalRetry(String point) throws Exception {
        assignFixture("WAITING"); String credential = login(worker, "WEB");
        success(command(task1, "start", 3, credential, null), "IN_PROGRESS", 4);
        success(command(task1, "checklist", 4, credential, true), "IN_PROGRESS", 5);
        String before = effects();
        String function = "complete281_" + UUID.randomUUID().toString().replace("-", "");
        String table = point.equals("task") ? "task" : "event";
        UUID id = point.equals("task") ? task1 : event;
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic complete281 failure'; END $$");
        try {
            jdbc.execute("CREATE " + (point.equals("commit") ? "CONSTRAINT " : "") + "TRIGGER " + function
                    + (point.equals("commit") ? " AFTER UPDATE ON " : " BEFORE UPDATE ON ") + table
                    + (point.equals("commit") ? " DEFERRABLE INITIALLY DEFERRED" : "")
                    + " FOR EACH ROW WHEN (OLD.id='" + id + "'::uuid) EXECUTE FUNCTION " + function + "()");
            error(command(task1, "complete", 5, credential, null), 503, "SERVICE_UNAVAILABLE");
            assertThat(effects()).isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + function + " ON " + table);
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
        var completed = command(task1, "complete", 5, credential, null);
        success(completed, "DONE", 6);
        assertThat(json.readTree(completed.body()).get("task").get("actualEndedAt").isNull()).isFalse();
        before = effects();
        success(command(task1, "complete", 6, credential, null), "DONE", 6);
        assertThat(effects()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_id=?", event)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"task", "event", "commit"})
    void databaseStatementAndCommitFailuresRollBackTaskAndRevision(String point) throws Exception {
        assignFixture("WAITING"); String credential = login(worker, "WEB"), before = effects();
        String function = "task281_" + UUID.randomUUID().toString().replace("-", "");
        String table = point.equals("task") ? "task" : "event";
        UUID id = point.equals("task") ? task1 : event;
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic task281 failure'; END $$");
        try {
            jdbc.execute("CREATE " + (point.equals("commit") ? "CONSTRAINT " : "") + "TRIGGER " + function
                    + (point.equals("commit") ? " AFTER UPDATE ON " : " BEFORE UPDATE ON ") + table
                    + (point.equals("commit") ? " DEFERRABLE INITIALLY DEFERRED" : "")
                    + " FOR EACH ROW WHEN (OLD.id='" + id + "'::uuid) EXECUTE FUNCTION " + function + "()");
            error(command(task1, "start", 3, credential, null), 503, "SERVICE_UNAVAILABLE");
            assertThat(effects()).isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + function + " ON " + table);
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"WAITING", "IN_PROGRESS"})
    void actualChiefTransferUsesGroupMoveReleaseOrRefusesInProgress(String state) throws Exception {
        jdbc.update("UPDATE task SET assignee_id=?,status=? WHERE id=?", chiefMember, state, task1);
        String before = effects();
        var request = write("POST", "/api/v1/events/" + event + "/chief-transfer",
                json.writeValueAsString(Map.of("successorMemberId", workerMember, "previousChiefWorkGroupId", group, "expectedEventRevision", "3")), cookie);
        var response = send(request);
        if (state.equals("IN_PROGRESS")) {
            error(response, 409, "INVALID_STATE"); assertThat(effects()).isEqualTo(before);
        } else {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?", UUID.class, task1)).isNull();
            error(command(task1, "start", 4, cookie, null), 403, "FORBIDDEN");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"assign-first", "assign-second", "cancel-first", "cancel-second"})
    void actualChangeSetAssignmentOrCancellationAndStartShareEventGuard(String scenario) throws Exception {
        assignFixture("WAITING"); String credential = login(worker, "WEB");
        UUID nextMember = member(outsider, "WORKER", group, 3);
        boolean assign = scenario.startsWith("assign");
        String payload = assign ? json.writeValueAsString(Map.of("taskId", task1, "fromMemberId", workerMember, "toMemberId", nextMember))
                : json.writeValueAsString(Map.of("taskId", task1, "reason", "fixture cancellation"));
        String operations = "[{\"type\":\"" + (assign ? "ASSIGN_TASK" : "CANCEL_TASK") + "\",\"payload\":" + payload + "}]";
        var created = send(write("POST", "/api/v1/events/" + event + "/change-sets",
                "{\"mode\":\"MANUAL\",\"operations\":" + operations + ",\"rationale\":\"fixture\",\"expectedEventRevision\":\"3\"}", cookie)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String change = json.readTree(created.body()).get("id").asString();
        var preview = send(write("POST", "/api/v1/change-sets/" + change + "/preview",
                "{\"expectedEventRevision\":\"3\",\"expectedChangeSetRevision\":\"1\"}", cookie));
        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        var value = json.readTree(preview.body());
        String applyBody = json.writeValueAsString(Map.of("expectedEventRevision", "3", "expectedChangeSetRevision",
                value.get("revision").asString(), "previewHash", value.get("previewHash").asString()));
        Callable<HttpResponse<String>> apply = () -> send(write("POST", "/api/v1/change-sets/" + change + "/apply", applyBody, cookie)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
        Callable<HttpResponse<String>> start = () -> command(task1, "start", 3, credential, null);
        boolean applyFirst = scenario.endsWith("first");
        var responses = applyFirst ? compete(apply, start) : compete(start, apply);
        assertThat(responses.getFirst().statusCode()).as(responses.getFirst().body()).isEqualTo(200);
        assertThat(responses.getLast().statusCode()).as(responses.getLast().body()).isEqualTo(applyFirst && assign ? 403 : 409);
        assertThat(revision()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id=?", String.class, task1))
                .isEqualTo(applyFirst ? (assign ? "WAITING" : "CANCELED") : "IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?", UUID.class, task1))
                .isEqualTo(applyFirst && assign ? nextMember : workerMember);
        if (applyFirst && assign) {
            assertThat(page(credential, "").get("items").isEmpty()).isTrue();
            error(get(task1, credential), 404, "NOT_FOUND");
            assertThat(page(login(outsider, "WEB"), "").get("items").size()).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"WAITING", "IN_PROGRESS"})
    void actualAccountDeactivationReleasesWaitingOrRefusesInProgress(String state) throws Exception {
        assignFixture(state); String credential = login(worker, "WEB");
        String before = effects();
        var response = deactivate(credential);
        if (state.equals("IN_PROGRESS")) {
            error(response, 409, "TASK_IN_PROGRESS"); assertThat(effects()).isEqualTo(before);
            assertThat(get(task1, credential).statusCode()).isEqualTo(200);
        } else {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?", UUID.class, task1)).isNull();
            error(command(task1, "start", 4, credential, null), 401, "UNAUTHENTICATED");
        }
    }

    @Test void startThenRealDeactivationDoesNotIntroduceAnAccountEventLockCycle() throws Exception {
        assignFixture("WAITING"); String credential = login(worker, "WEB");
        var responses = compete(() -> command(task1, "start", 3, credential, null), () -> deactivate(credential));
        success(responses.getFirst(), "IN_PROGRESS", 4);
        error(responses.getLast(), 409, "TASK_IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT active FROM member WHERE id=?", Boolean.class, workerMember)).isTrue();
    }

    HttpResponse<String> deactivate(String credential) throws Exception {
        return send(write("POST", "/api/v1/me/deactivate", "{\"password\":\"SyntheticPass8!\"}", credential).header("X-SceneCrew-CSRF", "1"));
    }
    @Test void actualAvailabilityChangeKeepsAssignmentAndAllowsItsOwnerToStart() throws Exception {
        assignFixture("WAITING"); String credential = login(worker, "WEB");
        var response = send(write("PATCH", "/api/v1/members/" + workerMember + "/availability",
                "{\"available\":false,\"expectedEventRevision\":\"3\"}", credential));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(page(credential, "").get("items").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?", UUID.class, task1)).isEqualTo(workerMember);
        success(command(task1, "start", 4, credential, null), "IN_PROGRESS", 5);
    }
    void assignFixture(String state) { jdbc.update("UPDATE task SET assignee_id=?,status=? WHERE id=?", workerMember, state, task1); }
    long revision() { return jdbc.queryForObject("SELECT revision FROM event WHERE id=?", Long.class, event); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    JsonNode page(String credential, String query) throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/api/v1/events/" + event + "/tasks" + query)).GET().header("Cookie", credential));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    HttpRequest.Builder write(String method, String path, String body, String credential) {
        return HttpRequest.newBuilder(uri(path)).header("Cookie", credential).header("Origin", WEB)
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> command(UUID task, String action, long expected, String credential, Boolean checked) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("expectedEventRevision", Long.toString(expected));
        if (action.equals("checklist")) body.put("checks", List.of(Map.of("key", "check", "checked", checked)));
        return send(write(action.equals("checklist") ? "PATCH" : "POST", "/api/v1/tasks/" + task + "/" + action, json.writeValueAsString(body), credential));
    }
    HttpResponse<String> join(String credential, long expected) throws Exception {
        return join(credential, expected, prepareInvite(), UUID.randomUUID().toString());
    }
    private String prepareInvite() {
        String token = "fixture-invite-281";
        jdbc.update("UPDATE event SET invite_token_hash=?,total_capacity=31 WHERE id=?",
                com.ssafy.scenecrew.invite.service.InviteJoinService.tokenHash(token), event);
        return token;
    }
    private HttpResponse<String> join(String credential, long expected, String token, String key) throws Exception {
        return send(write("POST", "/api/v1/invites/" + token + "/join", json.writeValueAsString(Map.of("workGroupId", group,
                "available", true, "isReserve", false, "expectedEventRevision", Long.toString(expected))), credential)
                .header("X-SceneCrew-CSRF", "1").header("Idempotency-Key", key));
    }
    void success(HttpResponse<String> response, String state, long revision) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var value = json.readTree(response.body());
        assertThat(value.get("task").get("status").asString()).isEqualTo(state);
        assertThat(value.get("eventRevision").asString()).isEqualTo(Long.toString(revision));
    }
    String effects() {
        return snapshot() + "|" + count("SELECT count(*) FROM notification WHERE event_id=?", event)
                + "|" + count("SELECT count(*) FROM outbox_event WHERE event_id=?", event)
                + "|" + count("SELECT count(*) FROM ai_job WHERE event_id=?", event)
                + "|" + count("SELECT count(*) FROM idempotency_record WHERE event_id=?", event)
                + "|" + acknowledgmentAndNotificationSnapshot();
    }
    String acknowledgmentAndNotificationSnapshot() {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'acks',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM change_acknowledgment a WHERE event_id=?),
                    'notifications',(SELECT jsonb_agg(to_jsonb(n) ORDER BY id) FROM notification n WHERE event_id=?),
                    'deliveries',(SELECT jsonb_agg(to_jsonb(d) ORDER BY id) FROM notification_delivery d
                        WHERE notification_id IN (SELECT id FROM notification WHERE event_id=?)))::text
                """, String.class, event, event, event);
    }
    List<HttpResponse<String>> compete(Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(3)) {
            var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
            var holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM task WHERE id=? FOR UPDATE", UUID.class, task1);
                locked.countDown();
                try { if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("fixture lock timed out"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            Future<HttpResponse<String>> a = null, b = null;
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                a = pool.submit(first);
                int firstPid = awaitWait("%FROM task WHERE id%FOR UPDATE%");
                b = pool.submit(second);
                int secondPid = awaitWait("%FROM event WHERE id%FOR UPDATE%");
                assertThat(jdbc.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))", Boolean.class, firstPid, secondPid)).isTrue();
                release.countDown(); holder.get(15, TimeUnit.SECONDS);
                return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            } finally {
                release.countDown(); holder.get(15, TimeUnit.SECONDS);
                if (a != null) a.get(30, TimeUnit.SECONDS);
                if (b != null) b.get(30, TimeUnit.SECONDS);
            }
        }
    }
    int awaitWait(String query) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            var pids = jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE ?", Integer.class, query);
            if (!pids.isEmpty()) return pids.getFirst();
            Thread.sleep(20);
        }
        throw new AssertionError("expected a real PostgreSQL lock waiter");
    }

    String snapshot() {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object('tasks',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM task t WHERE event_id=?),
                    'event',(SELECT to_jsonb(e) FROM event e WHERE id=?),
                    'logs',(SELECT count(*) FROM operation_log WHERE event_id=?))::text
                """, String.class, event, event, event);
    }
    UUID account() {
        UUID id = UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account (id,display_name,status,signup_completed,token_version,consent,created_at) VALUES (?,'fixture','ACTIVE',true,1,'{}'::jsonb,now())", id);
        jdbc.update("INSERT INTO auth_identity VALUES (?,?,'LOCAL',?,?,?,true)", UUID.randomUUID(), id,
                id.toString(), encoder.encode("SyntheticPass8!"), id + "@example.invalid");
        return id;
    }
    UUID member(UUID account, String role, UUID workGroup, int number) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,?,?,'fixture',true,false,true,2)""", id, event, account, workGroup, number, role);
        return id;
    }
    UUID task(String start, String end) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,zone_id,title,start_at,end_at,steps,checklist,status)
                VALUES (?,?,?,'bundle',?,'fixture',?::timestamptz,?::timestamptz,'["준비"]'::jsonb,
                    '[{"key":"check","text":"점검","required":true,"checked":false,"checkedAt":null,"checkedByMemberId":null}]'::jsonb,'WAITING')
                """, id, event, group, zone, start, end);
        return id;
    }
    String login(UUID account, String channel) throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("X-SceneCrew-Client", channel).header("Origin", "WEB".equals(channel) ? WEB : ANDROID)
                .header("X-SceneCrew-CSRF", "1").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "email", account + "@example.invalid", "password", "SyntheticPass8!")))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        if ("ANDROID".equals(channel)) return json.readTree(response.body()).get("accessToken").asString();
        return response.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith("__Secure-sc-access="))
                .findFirst().orElseThrow().split(";", 2)[0];
    }
    HttpResponse<String> get(UUID id, String credential) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/tasks/" + id)).GET().header("Cookie", credential));
    }
    URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }
    void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("requestId").asString()).isNotBlank();
        assertThat(body.has("task")).isFalse();
    }
}

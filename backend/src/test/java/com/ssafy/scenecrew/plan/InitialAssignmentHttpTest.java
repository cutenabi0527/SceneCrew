package com.ssafy.scenecrew.plan;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** API 045의 실제 인증 HTTP·PostgreSQL. 공유 DB 대신 test profile의 Testcontainers만 사용한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(InitialAssignmentHttpTest.Encoder.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InitialAssignmentHttpTest {
    static final String WEB = "https://web.example.invalid";
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled", () -> "true");
        r.add("scenecrew.auth.signing-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer", () -> "assignment-test");
        r.add("scenecrew.auth.audience", () -> "assignment-test");
        r.add("scenecrew.auth.web-origins", () -> WEB);
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired TransactionTemplate tx;
    @Autowired com.ssafy.scenecrew.plan.service.InitialAssignmentService assignments;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    final List<UUID> accounts = new ArrayList<>();
    UUID event, group, zone, chief, worker, worker2, chiefMember, target, target2, task1, task2;
    String chiefCookie, workerCookie;

    @BeforeEach void fixture() throws Exception {
        event = UUID.randomUUID(); group = UUID.randomUUID(); zone = UUID.randomUUID();
        jdbc.update("INSERT INTO event (id,name,state,revision,last_input_modified_at) VALUES (?,'fixture','LIVE',3,now())", event);
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'fixture','',true)", group,event);
        jdbc.update("INSERT INTO zone (id,event_id,name,aliases) VALUES (?,?,'fixture','[]'::jsonb)",zone,event);
        chief = account(); worker = account(); worker2 = account();
        chiefMember = member(chief,"CHIEF",null,1); target = member(worker,"WORKER",group,2); target2 = member(worker2,"WORKER",group,3);
        task1 = task("bundle", "2026-11-14T09:00:00Z", "2026-11-14T10:00:00Z");
        task2 = task("bundle", "2026-11-14T10:00:00Z", "2026-11-14T11:00:00Z");
        chiefCookie = login(chief); workerCookie = login(worker);
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",event);
        jdbc.update("DELETE FROM notification_delivery WHERE notification_id IN (SELECT id FROM notification WHERE event_id=?)",event);
        jdbc.update("DELETE FROM notification WHERE event_id=?",event);
        jdbc.update("DELETE FROM change_acknowledgment WHERE event_id=?",event);
        jdbc.update("DELETE FROM operation_log WHERE event_id=?",event);
        jdbc.update("DELETE FROM change_set_evidence_revision WHERE change_set_id IN (SELECT id FROM change_set WHERE event_id=?)",event);
        jdbc.update("DELETE FROM change_set WHERE event_id=?",event);
        jdbc.update("DELETE FROM task WHERE event_id=?",event);
        jdbc.update("DELETE FROM member WHERE event_id=?",event);
        jdbc.update("DELETE FROM zone WHERE event_id=?",event);
        jdbc.update("DELETE FROM work_group WHERE event_id=?",event);
        jdbc.update("DELETE FROM outbox_event WHERE event_id=?",event);
        jdbc.update("DELETE FROM event WHERE id=?",event);
        for (var a : accounts) {
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",a);
            jdbc.update("DELETE FROM account WHERE id=?",a);
        }
        accounts.clear();
    }

    @Test void assignsEntireBundleAndReplaysOriginalSnapshotAfterTaskChanges() throws Exception {
        String key = UUID.randomUUID().toString();
        var response = assign(chiefCookie,key,target,3);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.get("eventRevision").asString()).isEqualTo("4");
        assertThat(body.get("tasks").size()).isEqualTo(2);
        assertThat(body.get("tasks").get(0).get("firstAssignedAt").isNull()).isFalse();
        assertThat(body.get("tasks").get(0).get("checklist").size()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND assignee_id=? AND first_assigned_at IS NOT NULL",event,target)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT changes->>'participationRevision' FROM operation_log WHERE event_id=?",String.class,event)).isEqualTo("2");
        assertThat(count("SELECT count(*) FROM ai_job WHERE event_id=?",event)).isZero();
        assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE event_id=?",event)).isZero();
        assertBundleNotification(target,2);
        // V9-D47: 받은 팀원의 알림함에 묶음 1건이 행사 단위 대상으로 보인다.
        var inbox=json.readTree(ok(send(HttpRequest.newBuilder(URI.create(url("/api/v1/me/notifications")))
                .header("Cookie",workerCookie).GET())).body()).get("items");
        assertThat(inbox.size()).isEqualTo(1);
        assertThat(inbox.get(0).get("title").asString()).isEqualTo("첫 배정 알림");
        assertThat(inbox.get(0).get("target").get("kind").asString()).isEqualTo("INITIAL_ASSIGNMENT");
        assertThat(inbox.get(0).get("target").get("id").asString()).isEqualTo(event.toString());
        jdbc.update("UPDATE task SET title='changed',status='DONE',actual_ended_at=now() WHERE id=?",task1);
        var replay = assign(chiefCookie,key,target,3);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(json.readTree(replay.body())).isEqualTo(body);
        assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=?",event)).isEqualTo(1);
        assertBundleNotification(target,2);
        error(assign(chiefCookie,key,target2,3),409,"IDEMPOTENCY_MISMATCH");
        // #281 구현은 현재 기준에 없다. 실제 본인 조회에 필요한 관계만 DB에서 확인한다.
        assertThat(count("SELECT count(*) FROM task t JOIN member m ON m.id=t.assignee_id AND m.event_id=t.event_id WHERE t.event_id=? AND m.account_id=? AND m.active",event,worker)).isEqualTo(2);
    }

    @Test void rejectsNonChiefOtherEventAndUnauthenticatedRequests() throws Exception {
        error(assign(workerCookie,UUID.randomUUID().toString(),target,3),403,"FORBIDDEN");
        var otherAccount = account();
        error(assign(login(otherAccount),UUID.randomUUID().toString(),target,3),404,"NOT_FOUND");
        error(send(request("{}",null,null)),401,"UNAUTHENTICATED");
        assertUnassigned();
    }

    @ParameterizedTest
    @ValueSource(strings={"inactive","unavailable","reserve","group","groupless","inactiveGroup","foreignMember","state","assigned","historical","started","draft","ended","stale"})
    void rejectsInvalidCurrentContextWithoutPartialAssignment(String scenario) throws Exception {
        switch (scenario) {
            case "inactive" -> jdbc.update("UPDATE member SET active=false WHERE id=?",target);
            case "unavailable" -> jdbc.update("UPDATE member SET available=false WHERE id=?",target);
            case "reserve" -> jdbc.update("UPDATE member SET is_reserve=true WHERE id=?",target);
            case "groupless" -> jdbc.update("UPDATE member SET work_group_id=null WHERE id=?",target);
            case "group" -> {
                UUID other=UUID.randomUUID();
                jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'other','',true)",other,event);
                jdbc.update("UPDATE member SET work_group_id=? WHERE id=?",other,target);
            }
            case "inactiveGroup" -> jdbc.update("UPDATE work_group SET active=false WHERE id=?",group);
            case "state" -> jdbc.update("UPDATE task SET status='DONE' WHERE id=?",task2);
            case "assigned" -> jdbc.update("UPDATE task SET assignee_id=? WHERE id=?",target2,task2);
            case "historical" -> jdbc.update("UPDATE task SET first_assigned_at=now() WHERE id=?",task2);
            case "started" -> jdbc.update("UPDATE task SET actual_started_at=now() WHERE id=?",task2);
            case "draft" -> jdbc.update("UPDATE event SET state='DRAFT' WHERE id=?",event);
            case "ended" -> jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
            case "stale" -> jdbc.update("UPDATE member SET participation_revision=3 WHERE id=?",target);
            default -> { }
        }
        if (scenario.equals("stale")) jdbc.update("UPDATE event SET revision=4 WHERE id=?",event);
        int status = Set.of("state","assigned","historical","started","draft","ended","stale").contains(scenario) ? 409 : 422;
        String code = scenario.equals("stale") ? "VERSION_CONFLICT" : status==409 ? "INVALID_STATE" : "INVALID_REFERENCE";
        error(assign(chiefCookie,UUID.randomUUID().toString(),scenario.equals("foreignMember") ? UUID.randomUUID() : target,3),status,code);
        assertThat(count("SELECT count(*) FROM task WHERE id=? AND assignee_id IS NULL AND first_assigned_at IS NULL",task1)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=?",event)).isZero();
    }

    @Test void rejectsInternalAndExistingScheduleOverlap() throws Exception {
        jdbc.update("UPDATE task SET start_at='2026-11-14T09:30:00Z' WHERE id=?",task2);
        error(assign(chiefCookie,UUID.randomUUID().toString(),target,3),422,"TIME_OVERLAP");
        assertUnassigned();
        jdbc.update("UPDATE task SET start_at='2026-11-14T10:00:00Z' WHERE id=?",task2);
        UUID existing=task("other","2026-11-14T09:30:00Z","2026-11-14T09:45:00Z");
        jdbc.update("UPDATE task SET assignee_id=?,first_assigned_at=now() WHERE id=?",target,existing);
        error(assign(chiefCookie,UUID.randomUUID().toString(),target,3),422,"TIME_OVERLAP");
        assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND bundle_key='bundle' AND assignee_id IS NULL",event)).isEqualTo(2);
    }

    @Test void rejectsMalformedWireAndUntrustedOrigin() throws Exception {
        String body=body(target,3);
        error(send(request(body,chiefCookie,null)),400,"INVALID_REQUEST");
        error(send(request(body.replace("\"3\"","3"),chiefCookie,UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(request(body.replace("\"3\"","\"9223372036854775808\""),chiefCookie,UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(request(body.substring(0,body.length()-1)+",\"extra\":1}",chiefCookie,UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(request(body,chiefCookie,UUID.randomUUID().toString()).setHeader("Origin","https://untrusted.example.invalid")),403,"CSRF_REJECTED");
        assertUnassigned();
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void rejectsDuplicateIdempotencyHeadersWithoutWrites(boolean sameValue) throws Exception {
        String key=UUID.randomUUID().toString();
        var request=request(body(target,3),chiefCookie,key)
                .header("Idempotency-Key",sameValue ? key : UUID.randomUUID().toString());
        assertThat(request.build().headers().allValues("Idempotency-Key")).hasSize(2);
        error(send(request),400,"INVALID_REQUEST");
        assertUnassigned();
        assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=?",event)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={""," ","\u00a0","\u202f","\u0085","\u001c","\t \u00a0\u202f"})
    void rejectsBlankBundleKeysAtHttpAndServiceBoundaries(String blank) throws Exception {
        String payload=json.writeValueAsString(Map.of("bundleKey",blank,"memberId",target,"expectedEventRevision","3"));
        error(send(request(payload,chiefCookie,UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        assertThatThrownBy(()->assignments.assign(event,chief,UUID.randomUUID().toString(),
                new com.ssafy.scenecrew.plan.service.InitialAssignmentService.Input(blank,target,3)))
                .isInstanceOfSatisfying(com.ssafy.scenecrew.global.error.BusinessException.class,
                        ex->assertThat(ex.getErrorCode()).isEqualTo(com.ssafy.scenecrew.global.error.ErrorCode.INVALID_REQUEST));
        assertUnassigned();
        assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=?",event)).isZero();
    }

    @Test void keepsNonBlankBundleIdentifierAndRequestHashExactlyAsSupplied() throws Exception {
        String bundle=" \u00a0bundle\u202f ";
        jdbc.update("UPDATE task SET bundle_key=? WHERE event_id=?",bundle,event);
        String payload=json.writeValueAsString(Map.of("bundleKey",bundle,"memberId",target,"expectedEventRevision","3"));
        String key=UUID.randomUUID().toString();
        var assigned=ok(send(request(payload,chiefCookie,key)));
        assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND bundle_key=? AND assignee_id=?",event,bundle,target)).isEqualTo(2);
        for (var task:json.readTree(assigned.body()).get("tasks"))
            assertThat(task.get("bundleKey").asString()).isEqualTo(bundle);
        String changed=json.writeValueAsString(Map.of("bundleKey","bundle","memberId",target,"expectedEventRevision","3"));
        error(send(request(changed,chiefCookie,key)),409,"IDEMPOTENCY_MISMATCH");
        var replay=ok(send(request(payload,chiefCookie,key)));
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(assigned.body()));
        assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=?",event)).isEqualTo(1);
    }

    @Test void sameKeyConcurrentRequestsReturnSameResult() throws Exception {
        String key=UUID.randomUUID().toString();
        var race=compete("member",target,"%SELECT work_group_id, participation_revision%",
                ()->assign(chiefCookie,key,target,3),()->assign(chiefCookie,key,target,3));
        ok(race.first()); ok(race.second());
        assertThat(json.readTree(race.first().body())).isEqualTo(json.readTree(race.second().body()));
        assertWholeBundle(target,4);
        assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=?",event)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=?",event)).isEqualTo(1);
        assertBundleNotification(target,2);
    }

    @Test void differentKeysCompeteForWholeBundle() throws Exception {
        var race=compete("member",target,"%SELECT work_group_id, participation_revision%",
                ()->assign(chiefCookie,UUID.randomUUID().toString(),target,3),
                ()->assign(chiefCookie,UUID.randomUUID().toString(),target2,3));
        ok(race.first()); error(race.second(),409,"VERSION_CONFLICT");
        assertWholeBundle(target,4);
        assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=?",event)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=?",event)).isEqualTo(1);
        assertBundleNotification(target,2);
    }

    @Test void waitsForParticipationWriterThenRejectsOldEventRevision() throws Exception {
        try (var executor=Executors.newFixedThreadPool(2)) {
            var locked=new CountDownLatch(1);
            var release=new CountDownLatch(1);
            var writer=executor.submit(()->tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT revision FROM event WHERE id=? FOR UPDATE",Long.class,event);
                locked.countDown();
                try { if (!release.await(15,TimeUnit.SECONDS)) throw new AssertionError("writer release timed out"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                jdbc.update("UPDATE member SET participation_revision=3 WHERE id=?",target);
                jdbc.update("UPDATE event SET revision=4 WHERE id=?",event);
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var response=executor.submit(()->assign(chiefCookie,UUID.randomUUID().toString(),target,3));
            try {
                // Observe outside the holder transaction so pg_stat_activity isn't cached in its statistics snapshot.
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while (count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT state, revision FROM event%'")==0) {
                    if (System.nanoTime()>deadline) throw new AssertionError("HTTP transaction did not wait for Event lock");
                    Thread.sleep(20);
                }
            } finally { release.countDown(); }
            writer.get(20,TimeUnit.SECONDS);
            error(response.get(20,TimeUnit.SECONDS),409,"VERSION_CONFLICT");
        }
        assertUnassigned();
    }

    @Test void lateStorageFailureRollsBackTasksRevisionLogAndReceipt() throws Exception {
        // Test-only trigger in the isolated PostgreSQL forces failure after Task writes, at OperationLog insertion.
        jdbc.execute("CREATE FUNCTION fail_initial_assignment_344() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_initial_assignment_344 BEFORE INSERT ON operation_log FOR EACH ROW EXECUTE FUNCTION fail_initial_assignment_344()");
        String key=UUID.randomUUID().toString();
        try {
            assertThat(assign(chiefCookie,key,target,3).statusCode()).isEqualTo(500);
            assertUnassigned();
            assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(3);
            assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=?",event)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_initial_assignment_344 ON operation_log");
            jdbc.execute("DROP FUNCTION fail_initial_assignment_344()");
        }
        assertThat(assign(chiefCookie,key,target,3).statusCode()).isEqualTo(200);
    }

    @Test void notificationStorageFailureRollsBackWholeAssignment() throws Exception {
        // 알림 저장이 마지막에 실패해도 업무·revision·로그·receipt가 함께 롤백돼야 한다(V9-D47 같은 커밋).
        jdbc.execute("CREATE FUNCTION fail_initial_notification_354() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_initial_notification_354 BEFORE INSERT ON notification FOR EACH ROW EXECUTE FUNCTION fail_initial_notification_354()");
        String key=UUID.randomUUID().toString();
        try {
            assertThat(assign(chiefCookie,key,target,3).statusCode()).isEqualTo(500);
            assertUnassigned();
            assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(3);
            assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=?",event)).isZero();
            assertThat(count("SELECT count(*) FROM notification WHERE event_id=?",event)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_initial_notification_354 ON notification");
            jdbc.execute("DROP FUNCTION fail_initial_notification_354()");
        }
        ok(assign(chiefCookie,key,target,3));
        assertBundleNotification(target,2);
    }

    @Test void replayRequiresCurrentChiefAndOriginalActorRound() throws Exception {
        String key=UUID.randomUUID().toString();
        assertThat(assign(chiefCookie,key,target,3).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE member SET participation_revision=3 WHERE id=?",chiefMember);
        error(assign(chiefCookie,key,target,3),403,"FORBIDDEN");
        jdbc.update("UPDATE member SET event_role='WORKER',work_group_id=? WHERE id=?",group,chiefMember);
        error(assign(chiefCookie,key,target,3),403,"FORBIDDEN");
    }

    @Test void lostHttpResponseRecoversFirstResultWithoutRepeatingAssignment() throws Exception {
        String key=UUID.randomUUID().toString();
        String body=body(target,3);
        // 실제 HTTP 요청을 보내고 응답을 읽지 않은 채 연결을 닫는다(클라이언트 응답 유실).
        try (var socket=new java.net.Socket("localhost",port)) {
            String wire="POST /api/v1/events/"+event+"/initial-assignments HTTP/1.1\r\nHost: localhost:"+port
                    +"\r\nContent-Type: application/json\r\nOrigin: "+WEB+"\r\nCookie: "+chiefCookie
                    +"\r\nIdempotency-Key: "+key+"\r\nContent-Length: "+body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    +"\r\nConnection: close\r\n\r\n"+body;
            socket.getOutputStream().write(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while (count("SELECT count(*) FROM idempotency_record WHERE event_id=?",event)==0) {
                if (System.nanoTime()>deadline) throw new AssertionError("first HTTP request did not commit");
                Thread.sleep(20);
            }
        }
        var recovered=assign(chiefCookie,key,target,3);
        assertThat(recovered.statusCode()).as(recovered.body()).isEqualTo(200);
        var receipt=jdbc.queryForObject("SELECT receipt::text FROM idempotency_record WHERE event_id=?",String.class,event);
        assertThat(json.readTree(recovered.body())).isEqualTo(json.readTree(receipt));
        assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=?",event)).isEqualTo(1);
        error(assign(chiefCookie,UUID.randomUUID().toString(),target,4),409,"INVALID_STATE");
    }

    @Test void realHttpJoinThenInitialAssignmentRecordsCurrentParticipation() throws Exception {
        String token=invite();
        UUID newcomer=account();
        var joined=ok(join(login(newcomer),token,3));
        var participation=json.readTree(joined.body()).get("participation");
        UUID member=UUID.fromString(participation.get("memberId").asString());
        assertThat(participation.get("participationRevision").asString()).isEqualTo("1");
        assertThat(json.readTree(joined.body()).get("eventRevision").asString()).isEqualTo("4");
        assertThat(json.readTree(joined.body()).get("initialAssignment").asString()).isEqualTo("UNKNOWN");
        assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND assignee_id IS NOT NULL",event)).isZero();
        assertThat(count("SELECT count(*) FROM ai_job WHERE event_id=?",event)).isZero();
        var assigned=ok(assign(chiefCookie,UUID.randomUUID().toString(),member,4));
        assertThat(json.readTree(assigned.body()).get("eventRevision").asString()).isEqualTo("5");
        assertWholeBundle(member,5);
        assertRoundEvidence(member,1);
        assertBundleNotification(member,1);
        var tasks=json.readTree(assigned.body()).get("tasks");
        assertThat(tasks.size()).isEqualTo(2);
        for (var task:tasks) {
            UUID taskId=UUID.fromString(task.get("id").asString());
            assertThat(task.get("assigneeId").asString()).isEqualTo(member.toString());
            assertThat(task.get("firstAssignedAt").asString()).isEqualTo(
                    jdbc.queryForObject("SELECT first_assigned_at FROM task WHERE id=?",java.sql.Timestamp.class,taskId).toInstant().toString());
            assertThat(task.propertyNames()).containsExactlyInAnyOrder("id","workGroupId","assigneeId","title","status",
                    "startAt","endAt","zoneId","programId","sourceIssueId","steps","checklist","firstAssignedAt",
                    "actualStartedAt","actualEndedAt","bundleKey");
        }
    }

    @ParameterizedTest @ValueSource(strings={"WAITING","IN_PROGRESS","DONE","CANCELED"})
    void realHttpRejoinPreservesHistoryAndDoesNotReviveUnfinishedWork(String state) throws Exception {
        String token=invite();
        UUID old=task("previous-round","2026-11-14T09:00:00Z","2026-11-14T11:00:00Z");
        jdbc.update("UPDATE task SET assignee_id=?,first_assigned_at='2026-11-01T00:00:00Z',status=? WHERE id=?",target,state,old);
        jdbc.update("UPDATE member SET active=false WHERE id=?",target);
        // Departure is a completed inactive fixture; no departure API is invented by this test.
        var rejoined=join(workerCookie,token,3);
        if (Set.of("WAITING","IN_PROGRESS").contains(state)) {
            error(rejoined,409,"INVALID_STATE");
            assertThat(count("SELECT participation_revision FROM member WHERE id=?",target)).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM member WHERE id=? AND active",target)).isZero();
            assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(3);
            assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=?",event)).isZero();
        } else {
            var participation=json.readTree(ok(rejoined).body()).get("participation");
            assertThat(participation.get("memberId").asString()).isEqualTo(target.toString());
            assertThat(participation.get("participationRevision").asString()).isEqualTo("3");
            assertThat(count("SELECT crew_no FROM member WHERE id=?",target)).isEqualTo(2);
            ok(assign(chiefCookie,UUID.randomUUID().toString(),target,4));
            assertWholeBundle(target,5);
            assertRoundEvidence(target,3);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id=?",String.class,old)).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,old)).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT first_assigned_at FROM task WHERE id=?",java.sql.Timestamp.class,old)
                .toInstant().toString()).isEqualTo("2026-11-01T00:00:00Z");
    }

    @Test void realHttpJoinOwnsEventLockBeforeCompetingInitialAssignment() throws Exception {
        String token=invite();
        jdbc.update("UPDATE member SET active=false WHERE id=?",target);
        var race=compete("member",target,"%FROM member%account_id%FOR UPDATE%",
                ()->join(workerCookie,token,3),()->assign(chiefCookie,UUID.randomUUID().toString(),target,3));
        ok(race.first()); error(race.second(),409,"VERSION_CONFLICT");
        assertThat(count("SELECT participation_revision FROM member WHERE id=?",target)).isEqualTo(3);
        assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(4);
        assertUnassigned();
        ok(assign(chiefCookie,UUID.randomUUID().toString(),target,4));
        assertWholeBundle(target,5);
        assertRoundEvidence(target,3);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void initialAssignmentAndRealChangeSetApplySerializeOnEvent(boolean initialFirst) throws Exception {
        var ready=readyChange(assignOperation(task1,null,target2),3);
        Callable<HttpResponse<String>> initial=()->assign(chiefCookie,UUID.randomUUID().toString(),target,3);
        Callable<HttpResponse<String>> change=()->apply(ready,3);
        Race race=initialFirst
                ? compete("member",target,"%SELECT work_group_id, participation_revision%",initial,change)
                : compete("task",task1,"%FROM task%FOR UPDATE%",change,initial);
        ok(race.first()); error(race.second(),409,"VERSION_CONFLICT");
        assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(4);
        if (initialFirst) {
            assertWholeBundle(target,4);
            assertThat(jdbc.queryForObject("SELECT status FROM change_set WHERE id=?",String.class,ready.id())).isEqualTo("READY");
            assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE event_id=?",event)).isZero();
            assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=? AND type='INITIAL_ASSIGNMENT'",event)).isEqualTo(1);
        } else {
            assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,task1)).isEqualTo(target2);
            assertThat(count("SELECT count(*) FROM task WHERE id=? AND assignee_id IS NULL AND first_assigned_at IS NULL",task2)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE event_id=? AND member_id=? AND participation_revision=2",event,target2)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=? AND type='INITIAL_ASSIGNMENT'",event)).isZero();
            error(assign(chiefCookie,UUID.randomUUID().toString(),target,4),409,"INVALID_STATE");
        }
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=? AND operation IN ('SC-API-045','SC-API-070')",event)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"WAITING","IN_PROGRESS","DONE","CANCELED"})
    void changeSetUsesSharedScheduleRulesForEachExistingTaskState(String state) throws Exception {
        UUID existing=task("existing","2026-11-14T09:00:00Z","2026-11-14T10:00:00Z");
        jdbc.update("UPDATE task SET assignee_id=?,first_assigned_at=now(),status=? WHERE id=?",target2,state,existing);
        String operations=assignOperation(task1,null,target2);
        if (Set.of("WAITING","IN_PROGRESS").contains(state)) {
            var rejected=createChange(operations,3);
            error(rejected,422,"TIME_OVERLAP");
            assertThat(json.readTree(rejected.body()).get("details").get("memberId").asString()).isEqualTo(target2.toString());
            assertThat(count("SELECT count(*) FROM change_set WHERE event_id=?",event)).isZero();
        } else {
            var ready=readyChange(operations,3);
            ok(apply(ready,3));
            assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,task1)).isEqualTo(target2);
            assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE event_id=? AND member_id=? AND participation_revision=2",event,target2)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM task WHERE id=?",String.class,existing)).isEqualTo(state);
    }

    @Test void changeSetAllowsTouchingIntervalsAndExcludesTheTaskItself() throws Exception {
        UUID existing=task("earlier","2026-11-14T08:00:00Z","2026-11-14T09:00:00Z");
        jdbc.update("UPDATE task SET assignee_id=?,first_assigned_at=now() WHERE id=?",target2,existing);
        ok(apply(readyChange(assignOperation(task1,null,target2),3),3));
        var firstAssigned=jdbc.queryForObject("SELECT first_assigned_at FROM task WHERE id=?",java.sql.Timestamp.class,task1);
        ok(apply(readyChange(assignOperation(task1,target2,target2),4),4));
        assertThat(jdbc.queryForObject("SELECT first_assigned_at FROM task WHERE id=?",java.sql.Timestamp.class,task1)).isEqualTo(firstAssigned);
        assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(5);
    }

    @ParameterizedTest @ValueSource(strings={"DONE","CANCELED"})
    void changeSetCannotReassignOrCancelTerminalTasks(String state) throws Exception {
        jdbc.update("UPDATE task SET status=? WHERE id=?",state,task1);
        error(createChange(assignOperation(task1,null,target2),3),409,"INVALID_STATE");
        error(createChange("[{\"type\":\"CANCEL_TASK\",\"payload\":{\"taskId\":\""+task1+"\",\"reason\":\"fixture\"}}]",3),409,"INVALID_STATE");
        assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=?",event)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"inactive","different-group"})
    void changeSetStillRequiresAnActiveAssigneeInTheSameGroup(String invalid) throws Exception {
        if (invalid.equals("inactive")) jdbc.update("UPDATE member SET active=false WHERE id=?",target2);
        else {
            UUID otherGroup=UUID.randomUUID();
            jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'other','',true)",otherGroup,event);
            jdbc.update("UPDATE member SET work_group_id=? WHERE id=?",otherGroup,target2);
        }
        error(createChange(assignOperation(task1,null,target2),3),422,"INVALID_REFERENCE");
        assertUnassigned();
        assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(3);
    }

    @Test void realRejoinCannotReadOrAcknowledgePreviousRoundChangeTargets() throws Exception {
        String token=invite();
        ok(apply(readyChange(assignOperation(task1,null,target),3),3));
        var oldAck=jdbc.queryForMap("SELECT id,targets_hash FROM change_acknowledgment WHERE event_id=? AND member_id=?",event,target);
        // #281 is excluded: completion and departure are historical fixtures, not fabricated HTTP endpoints.
        jdbc.update("UPDATE task SET status='DONE',actual_ended_at=now() WHERE id=?",task1);
        jdbc.update("UPDATE member SET active=false WHERE id=?",target);
        ok(join(workerCookie,token,4));
        assertThat(count("SELECT participation_revision FROM member WHERE id=?",target)).isEqualTo(3);
        var oldTargets=ok(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/my-changes")))
                .header("Cookie",workerCookie).GET()));
        assertThat(json.readTree(oldTargets.body()).get("items").size()).isZero();
        error(send(post("/api/v1/change-acknowledgments/"+oldAck.get("id")+"/ack",
                "{\"targetsHash\":\""+oldAck.get("targets_hash")+"\"}",workerCookie)),404,"NOT_FOUND");
        ok(apply(readyChange(assignOperation(task2,null,target),5),5));
        var currentTargets=ok(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/my-changes")))
                .header("Cookie",workerCookie).GET()));
        var current=json.readTree(currentTargets.body()).get("items");
        assertThat(current.size()).isEqualTo(1);
        assertThat(current.get(0).get("participationRevision").asString()).isEqualTo("3");
        assertThat(current.get(0).get("targets").get(0).get("taskId").asString()).isEqualTo(task2.toString());
        assertThat(count("SELECT count(*) FROM change_acknowledgment WHERE event_id=? AND member_id=? AND participation_revision=2",event,target)).isEqualTo(1);
    }

    private record Race(HttpResponse<String> first,HttpResponse<String> second) { }

    /** A owns Event and waits for a member/task fixture lock; B must then wait for A's Event lock. */
    Race compete(String table,UUID row,String firstWaitQuery,Callable<HttpResponse<String>> first,
            Callable<HttpResponse<String>> second) throws Exception {
        if (!Set.of("member","task").contains(table)) throw new IllegalArgumentException("fixture table");
        try (var executor=Executors.newFixedThreadPool(3)) {
            var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
            var holder=executor.submit(()->tx.executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",UUID.class,row);
                locked.countDown();
                try { if (!release.await(30,TimeUnit.SECONDS)) throw new AssertionError("fixture lock timed out"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            Future<HttpResponse<String>> a=null,b=null;
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                a=executor.submit(first);
                int firstPid=awaitDatabaseWait(firstWaitQuery);
                b=executor.submit(second);
                int secondPid=awaitDatabaseWait("%SELECT state, revision FROM event%FOR UPDATE%");
                assertThat(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",Boolean.class,firstPid,secondPid)).isTrue();
            } finally { release.countDown(); }
            holder.get(30,TimeUnit.SECONDS);
            return new Race(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        }
    }

    int awaitDatabaseWait(String query) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime()<deadline) {
            var pids=jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE ?",Integer.class,query);
            if (!pids.isEmpty()) return pids.getFirst();
            Thread.sleep(20);
        }
        throw new AssertionError("HTTP transaction did not reach expected database lock: "+query);
    }

    void assertWholeBundle(UUID assignee,long revision) {
        assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND bundle_key='bundle'",event)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND bundle_key='bundle' AND assignee_id=? AND first_assigned_at IS NOT NULL",event,assignee)).isEqualTo(2);
        assertThat(count("SELECT count(DISTINCT first_assigned_at) FROM task WHERE event_id=? AND bundle_key='bundle'",event)).isEqualTo(1);
        assertThat(count("SELECT revision FROM event WHERE id=?",event)).isEqualTo(revision);
    }

    void assertRoundEvidence(UUID member,long round) {
        assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=? AND type='INITIAL_ASSIGNMENT' AND resource_id=? AND changes->>'participationRevision'=?",event,member,Long.toString(round))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE event_id=? AND operation='SC-API-045' AND result_refs->>'participationRevision'=?",event,Long.toString(round))).isEqualTo(1);
    }

    String invite() {
        String token="synthetic-invite-344";
        jdbc.update("UPDATE event SET invite_token_hash=?,total_capacity=31 WHERE id=?",
                com.ssafy.scenecrew.invite.service.InviteJoinService.tokenHash(token),event);
        return token;
    }

    HttpResponse<String> join(String cookie,String token,long revision) throws Exception {
        String body="{\"workGroupId\":\""+group+"\",\"available\":true,\"isReserve\":false,\"expectedEventRevision\":\""+revision+"\"}";
        return send(post("/api/v1/invites/"+token+"/join",body,cookie).header("X-SceneCrew-CSRF","1")
                .header("Idempotency-Key",UUID.randomUUID().toString()));
    }

    private record ReadyChange(UUID id,String revision,String hash) { }

    String assignOperation(UUID task,UUID from,UUID to) {
        return "[{\"type\":\"ASSIGN_TASK\",\"payload\":{\"taskId\":\""+task+"\",\"fromMemberId\":"
                +(from==null?"null":"\""+from+"\"")+",\"toMemberId\":\""+to+"\"}}]";
    }

    HttpResponse<String> createChange(String operations,long revision) throws Exception {
        return send(post("/api/v1/events/"+event+"/change-sets","{\"mode\":\"MANUAL\",\"operations\":"+operations
                +",\"rationale\":\"fixture\",\"expectedEventRevision\":\""+revision+"\"}",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
    }

    ReadyChange readyChange(String operations,long revision) throws Exception {
        var created=createChange(operations,revision);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID id=UUID.fromString(json.readTree(created.body()).get("id").asString());
        var preview=json.readTree(ok(send(post("/api/v1/change-sets/"+id+"/preview",
                "{\"expectedEventRevision\":\""+revision+"\",\"expectedChangeSetRevision\":\"1\"}",chiefCookie))).body());
        return new ReadyChange(id,preview.get("revision").asString(),preview.get("previewHash").asString());
    }

    HttpResponse<String> apply(ReadyChange ready,long revision) throws Exception {
        return send(post("/api/v1/change-sets/"+ready.id()+"/apply","{\"expectedEventRevision\":\""+revision
                +"\",\"expectedChangeSetRevision\":\""+ready.revision()+"\",\"previewHash\":\""+ready.hash()+"\"}",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
    }

    HttpRequest.Builder post(String path,String body,String cookie) {
        return HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(30))
                .header("Origin",WEB).header("Content-Type","application/json").header("Cookie",cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    HttpResponse<String> ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response;
    }

    void assertUnassigned() {
        assertThat(count("SELECT count(*) FROM task WHERE event_id=? AND assignee_id IS NOT NULL",event)).isZero();
        assertThat(count("SELECT count(*) FROM operation_log WHERE event_id=?",event)).isZero();
        assertThat(count("SELECT count(*) FROM notification WHERE event_id=?",event)).isZero();
    }

    void assertBundleNotification(UUID member,long round) {
        assertThat(count("SELECT count(*) FROM notification WHERE event_id=?",event)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM notification WHERE event_id=? AND member_id=? AND participation_revision=?
                  AND type='INITIAL_ASSIGNMENT' AND task_id IS NULL AND acknowledgment_id IS NULL
                  AND payload->>'bundleKey'='bundle' AND dedupe_key=?""",
                event,member,round,"INITIAL_ASSIGNMENT:"+event+":bundle")).isEqualTo(1);
    }
    long count(String sql,Object... args) { return jdbc.queryForObject(sql,Long.class,args); }
    UUID account() {
        UUID id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?,'fixture','ACTIVE',true,1,'{}'::jsonb,now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?,?,'LOCAL',?,?,?,true)",UUID.randomUUID(),id,id.toString(),encoder.encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    UUID member(UUID account,String role,UUID workGroup,int crew) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision) VALUES (?,?,?,?,?,?,'fixture',true,false,true,2)",id,event,account,workGroup,crew,role);
        return id;
    }
    UUID task(String bundle,String start,String end) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO task (id,event_id,work_group_id,bundle_key,zone_id,title,start_at,end_at,steps,checklist,status) VALUES (?,?,?,?,?,'fixture',?::timestamptz,?::timestamptz,'[\"step\"]'::jsonb,'[{\"key\":\"check\",\"text\":\"check\",\"required\":true,\"checked\":false,\"checkedAt\":null,\"checkedByMemberId\":null}]'::jsonb,'WAITING')",id,event,group,bundle,zone,start,end);
        return id;
    }
    String login(UUID account) throws Exception {
        var response=send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login"))).header("Origin",WEB).header("Content-Type","application/json")
                .header("X-SceneCrew-Client","WEB").header("X-SceneCrew-CSRF","1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0]).filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }
    String body(UUID member,long revision) { return "{\"bundleKey\":\"bundle\",\"memberId\":\""+member+"\",\"expectedEventRevision\":\""+revision+"\"}"; }
    HttpResponse<String> assign(String cookie,String key,UUID member,long revision) throws Exception { return send(request(body(member,revision),cookie,key)); }
    HttpRequest.Builder request(String body,String cookie,String key) {
        var request=HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/initial-assignments"))).timeout(Duration.ofSeconds(15))
                .header("Origin",WEB).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie!=null) request.header("Cookie",cookie);
        if (key!=null) request.header("Idempotency-Key",key);
        return request;
    }
    String url(String path) { return "http://localhost:"+port+path; }
    HttpResponse<String> send(HttpRequest.Builder request) throws Exception { return client.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> response,int status,String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Encoder {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}

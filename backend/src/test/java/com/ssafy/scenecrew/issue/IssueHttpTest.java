package com.ssafy.scenecrew.issue;

import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import com.ssafy.scenecrew.aijob.repository.AiJobRepository;
import com.ssafy.scenecrew.issue.ai.analysis.AnalyzeReportKind;
import com.ssafy.scenecrew.issue.service.IssueService;
import com.ssafy.scenecrew.global.outbox.OutboxPublisher;
import com.ssafy.scenecrew.global.outbox.OutboxRelay;
import com.ssafy.scenecrew.notification.service.PushSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** #282 보고·이슈 055~059·062의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis. 행사·그룹·구역·업무는 SQL로 만든다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IssueHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class IssueHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"issue-test"); r.add("scenecrew.auth.audience",()->"issue-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->ANDROID);
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired AiJobRepository aiJobs;
    @Autowired AnalyzeReportKind analyzeKind;
    @Autowired IssueService issues;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired OutboxPublisher outbox;
    @Autowired OutboxRelay relay;
    @MockitoBean PushSender push;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>(), events=new ArrayList<>();
    UUID chief, worker, other, outsider, event, sound, safety, stage, booth, otherEvent, otherZone;
    String chiefCookie, workerCookie, otherCookie;

    @BeforeEach void fixture() throws Exception {
        chief=account(); worker=account(); other=account(); outsider=account();
        event=event("LIVE");
        sound=group(event,"음향·장비"); safety=group(event,"관객 안전");
        stage=zone(event,"스테이지"); booth=zone(event,"음향 부스");
        member(event,chief,"CHIEF",null); member(event,worker,"WORKER",sound); member(event,other,"WORKER",safety);
        otherEvent=event("LIVE"); otherZone=zone(otherEvent,"입구"); member(otherEvent,outsider,"CHIEF",null);
        chiefCookie=login(chief); workerCookie=login(worker); otherCookie=login(other);
    }
    @AfterEach void cleanup() {
        for (var e:events) {
            jdbc.update("DELETE FROM notification_delivery WHERE notification_id IN (SELECT id FROM notification WHERE event_id=?)",e);
            jdbc.update("DELETE FROM notification WHERE event_id=?",e);
            jdbc.update("DELETE FROM operation_log WHERE event_id=?",e);
            jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
            jdbc.update("DELETE FROM outbox_event WHERE event_id=?",e);
            jdbc.update("DELETE FROM ai_job WHERE event_id=?",e);
            jdbc.update("UPDATE task SET source_issue_id=NULL WHERE event_id=?",e);
            jdbc.update("DELETE FROM report WHERE issue_id IN (SELECT id FROM issue WHERE event_id=?)",e);
            jdbc.update("DELETE FROM operational_situation WHERE event_id=?",e);
            jdbc.update("UPDATE issue SET related_issue_id=NULL WHERE event_id=?",e);
            jdbc.update("DELETE FROM issue WHERE event_id=?",e);
            jdbc.update("DELETE FROM task WHERE event_id=?",e);
            jdbc.update("DELETE FROM device_token WHERE member_id IN (SELECT id FROM member WHERE event_id=?)",e);
            jdbc.update("DELETE FROM member WHERE event_id=?",e);
            jdbc.update("DELETE FROM zone WHERE event_id=?",e);
            jdbc.update("DELETE FROM work_group WHERE event_id=?",e);
            jdbc.update("DELETE FROM event WHERE id=?",e);
        }
        for (var a:accounts) {
            jdbc.update("DELETE FROM device_installation WHERE account_id=?",a);
            jdbc.update("DELETE FROM idempotency_record WHERE actor_account_id=?",a);
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",a);
            jdbc.update("DELETE FROM account WHERE id=?",a);
        }
        events.clear(); accounts.clear();
    }

    @Test void rejectsReportWhenMemberLeavesWhileWaitingForEventLock() throws Exception {
        var request=post("/api/v1/events/"+event+"/issues",
                "{\"zoneId\":\""+stage+"\",\"report\":{\"kind\":\"INITIAL\",\"text\":\"이탈 전 요청\",\"quickTemplateKey\":null,\"audioId\":null}}")
                .header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString()).build();
        try (var locked=jdbc.getDataSource().getConnection()) {
            locked.setAutoCommit(false);
            try (var stmt=locked.prepareStatement("SELECT 1 FROM event WHERE id=? FOR UPDATE")) {
                stmt.setObject(1,event);stmt.executeQuery().close();
            }
            int pid;
            try (var stmt=locked.createStatement();var rs=stmt.executeQuery("SELECT pg_backend_pid()")) {rs.next();pid=rs.getInt(1);}
            var pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
            try {
                long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",Long.class,pid)==0
                        && System.nanoTime()<until && !pending.isDone()) Thread.sleep(20);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",Long.class,pid)).isPositive();
                try (var stmt=locked.prepareStatement("UPDATE member SET active=false WHERE event_id=? AND account_id=?")) {
                    stmt.setObject(1,event);stmt.setObject(2,worker);stmt.executeUpdate();
                }
                locked.commit();
                error(pending.get(5,java.util.concurrent.TimeUnit.SECONDS),404,"NOT_FOUND");
            } finally {locked.rollback();pending.get(5,java.util.concurrent.TimeUnit.SECONDS);}
        }
    }

    @Test void workerReportCreatesIssueReportAndAnalysisJobAndReplaysSameKey() throws Exception {
        String key=UUID.randomUUID().toString();
        String body="{\"zoneId\":\""+stage+"\",\"report\":{\"kind\":\"INITIAL\",\"text\":\"  메인   마이크 소리가\\n나오지 않아요  \",\"quickTemplateKey\":null,\"audioId\":null}}";
        var r=send(post("/api/v1/events/"+event+"/issues",body).header("Cookie",workerCookie).header("Idempotency-Key",key));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var created=json.readTree(r.body());
        assertThat(created.propertyNames()).containsExactly("issue","report","analysisJobId");
        var issue=created.get("issue");
        assertThat(issue.propertyNames()).containsExactly("id","title","status","zoneId","workGroupId","taskId",
                "relatedIssueId","reports","analysis","situation","eventRevision","eventId","createdAt");
        // #371: 접수 시각(UTC)을 함께 보낸다
        assertThat(issue.get("createdAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z");
        assertThat(issue.get("title").asString()).isEqualTo("메인 마이크 소리가 나오지 않아요");
        assertThat(issue.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(issue.get("workGroupId").asString()).isEqualTo(sound.toString());
        assertThat(issue.get("taskId").isNull()).isTrue();
        assertThat(issue.get("analysis").isNull()).isTrue();
        assertThat(issue.get("eventRevision").asString()).isEqualTo("4"); // 보고가 revision을 올린다
        var report=created.get("report");
        assertThat(report.propertyNames()).containsExactly("id","issueId","authorMemberId","text","audioId","kind","participationRevision","createdAt");
        assertThat(report.get("text").asString()).isEqualTo("  메인   마이크 소리가\n나오지 않아요  ");
        assertThat(report.get("kind").asString()).isEqualTo("INITIAL");
        assertThat(report.get("participationRevision").asString()).isEqualTo("2");
        assertThat(issue.get("reports").get(0)).isEqualTo(report);
        String jobId=created.get("analysisJobId").asString();
        var job=jdbc.queryForMap("SELECT kind, state, context_revision, input::text AS input FROM ai_job WHERE id=?::uuid",jobId);
        assertThat(job.get("kind")).isEqualTo("ANALYZE_REPORT");
        assertThat(job.get("state")).isEqualTo("QUEUED");
        var input=json.readTree((String)job.get("input"));
        assertThat(input.get("normalizedText").asString()).isEqualTo("메인 마이크 소리가 나오지 않아요");
        assertThat(input.get("allowedZones").get(0).get("id").asString()).isEqualTo(stage.toString());
        assertThat(input.get("allowedWorkGroups").size()).isEqualTo(2);
        // 재요청 기록에는 보고 원문이 없다(제목은 이슈 필드라 남는다).
        var receipt=json.readTree(jdbc.queryForObject("SELECT receipt::text FROM idempotency_record WHERE idempotency_key=?",String.class,key));
        assertThat(receipt.get("report").get("text").isNull()).isTrue();
        assertThat(receipt.get("issue").get("reports").get(0).get("text").isNull()).isTrue();
        assertThat(receipt.toString()).doesNotContain("나오지 않아요  ");

        jdbc.update("UPDATE issue SET status='RESPONDING' WHERE id=?::uuid",issue.get("id").asString());
        var again=send(post("/api/v1/events/"+event+"/issues",body).header("Cookie",workerCookie).header("Idempotency-Key",key));
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(json.readTree(again.body())).isEqualTo(created); // 당시 상태(RECEIVED)·원문 그대로
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issue WHERE event_id=?",Long.class,event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT n.payload->>'reportId' FROM notification n JOIN member m ON m.id=n.member_id
                WHERE n.event_id=? AND m.account_id=? AND n.type='ISSUE'
                  AND n.participation_revision=m.participation_revision
                """,String.class,event,chief)).containsExactly(report.get("id").asString());
        var notification=inbox(chiefCookie).get(0);
        assertThat(notification.get("target").get("kind").asString()).isEqualTo("ISSUE");
        assertThat(notification.get("target").get("id").asString()).isEqualTo(issue.get("id").asString());
        assertThat(inbox(workerCookie).size()).isZero();
        String readPath="/api/v1/notifications/"+notification.get("id").asString()+"/read";
        error(send(post(readPath,"{}").header("Cookie",workerCookie).header("X-SceneCrew-CSRF","1")),404,"NOT_FOUND");
        var read=json.readTree(ok(send(post(readPath,"{}").header("Cookie",chiefCookie)
                .header("X-SceneCrew-CSRF","1"))).body());
        assertThat(read.get("readAt").isString()).isTrue();
        var mismatch=send(post("/api/v1/events/"+event+"/issues",body.replace("나오지","안 나와요"))
                .header("Cookie",workerCookie).header("Idempotency-Key",key));
        error(mismatch,409,"IDEMPOTENCY_MISMATCH");
        assertThat(inbox(chiefCookie).size()).isEqualTo(1);
    }

    @Test void initialReportReturns409AndRollsBackWithoutEligibleChief() throws Exception {
        create(workerCookie,initial(stage,"기존 최초 보고"));
        assertReportRejectedWithoutChief("/api/v1/events/"+event+"/issues",initial(stage,"새 최초 보고"));
    }

    @Test void followupReportReturns409AndRollsBackWithoutEligibleChief() throws Exception {
        String issueId=create(workerCookie,initial(stage,"기존 최초 보고")).get("issue").get("id").asString();
        assertReportRejectedWithoutChief("/api/v1/issues/"+issueId+"/reports",followup("FOLLOWUP","새 후속 보고"));
    }

    private void assertReportRejectedWithoutChief(String path,String body) throws Exception {
        // Test-only setup: normal operations require a chief handover before leaving an active event.
        assertThat(jdbc.update("""
                UPDATE member SET active=false, available=false
                WHERE event_id=? AND account_id=? AND event_role='CHIEF' AND active
                """,event,chief)).isEqualTo(1);
        var before=reportIntakeSnapshot();

        var response=send(post(path,body).header("Cookie",workerCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));

        error(response,409,"INVALID_STATE");
        assertThat(json.readTree(response.body()).get("message").asString())
                .isEqualTo("보고 알림을 받을 수 있는 총괄이 없습니다.");
        assertThat(reportIntakeSnapshot()).isEqualTo(before);
    }

    private Map<String,List<Map<String,Object>>> reportIntakeSnapshot() {
        var snapshot=new LinkedHashMap<String,List<Map<String,Object>>>();
        snapshot.put("event",jdbc.queryForList("SELECT * FROM event WHERE id=?",event));
        snapshot.put("report",jdbc.queryForList("""
                SELECT r.* FROM report r JOIN issue i ON i.id=r.issue_id WHERE i.event_id=? ORDER BY r.id
                """,event));
        for (String table:List.of("issue","notification","ai_job","outbox_event","idempotency_record")) {
            snapshot.put(table,jdbc.queryForList("SELECT * FROM "+table+" WHERE event_id=? ORDER BY id",event));
        }
        return snapshot;
    }

    @Test void reportAndNotificationRollBackTogether() throws Exception {
        registerPush(chief);
        tx.executeWithoutResult(transaction -> {
            issues.create(event,worker,UUID.randomUUID().toString(),
                    new IssueService.NewIssue(stage,null,null,null,null,"마이크 이상",null,null));
            assertThat(notificationCount()).isEqualTo(1);
            assertThat(deliveryCount()).isEqualTo(1);
            entityManager.flush();
            assertThat(pushWorkCount()).isEqualTo(1);
            transaction.setRollbackOnly();
        });
        assertThat(notificationCount()).isZero();
        assertThat(deliveryCount()).isZero();
        assertThat(pushWorkCount()).isZero();
        verifyNoInteractions(push);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issue WHERE event_id=?",Long.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE event_id=?",Long.class,event)).isZero();
        assertThat(rev()).isEqualTo("3");
    }

    @Test void concurrentReportRetriesShareOneNotification() throws Exception {
        registerPush(chief);
        var request=post("/api/v1/events/"+event+"/issues",initial(stage,"동시 접수"))
                .header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString()).build();
        var first=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        var second=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        var firstResponse=created(first.get(15,java.util.concurrent.TimeUnit.SECONDS));
        var secondResponse=created(second.get(15,java.util.concurrent.TimeUnit.SECONDS));
        assertThat(json.readTree(secondResponse.body())).isEqualTo(json.readTree(firstResponse.body()));
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(deliveryCount()).isEqualTo(1);
        assertThat(pushWorkCount()).isEqualTo(1);
        assertThat(rev()).isEqualTo("4");
    }

    @Test void issuePushIsQueuedForIntakeAndStateChangeAndSentByRelay() throws Exception {
        registerPush(chief);
        registerPush(worker);
        String issueId=create(workerCookie,initial(stage,"푸시 검증")).get("issue").get("id").asString();
        String path="/api/v1/issues/"+issueId;
        var followup=post(path+"/reports",followup("FOLLOWUP","후속 보고"))
                .header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString()).build();
        created(client.send(followup,HttpResponse.BodyHandlers.ofString()));
        created(client.send(followup,HttpResponse.BodyHandlers.ofString()));
        ok(send(patch(path,statusChange("RESPONDING",rev())).header("Cookie",chiefCookie)));
        ok(send(patch(path,statusChange("RESPONDING",rev())).header("Cookie",chiefCookie)));

        created(send(post(path+"/reports",followup("RESOLUTION","해결 보고"))
                .header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString())));

        assertThat(deliveryCount()).isEqualTo(4);
        assertThat(pushWorkCount()).isEqualTo(4);
        var intake=issuePushIds(chief);
        var state=issuePushIds(worker);
        assertThat(intake).hasSize(3);
        assertThat(state).hasSize(1);
        verifyNoInteractions(push);
        when(push.send(anyString(),any(UUID.class),eq(event)))
                .thenReturn(new PushSender.Outcome(PushSender.Status.SENT,"fixture-message"));
        relayIssuePush();
        for (UUID notification:intake) verify(push).send(pushToken(chief),notification,event);
        verify(push).send(pushToken(worker),state.getFirst(),event);
        verifyNoMoreInteractions(push);
        assertThat(jdbc.queryForList("""
                SELECT d.state FROM notification_delivery d JOIN notification n ON n.id=d.notification_id
                WHERE n.event_id=?
                """,String.class,event)).containsOnly("SENT").hasSize(4);
        assertThat(relay.relayOnce()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"deactivation","round","consent","device","issueAccess"})
    void queuedIssuePushRechecksEligibility(String revoked) throws Exception {
        registerPush(chief);
        create(workerCookie,initial(stage,"대상 자격 검증"));
        UUID notification=issuePushIds(chief).getFirst();
        switch (revoked) {
            case "deactivation" -> jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",chief);
            case "round" -> jdbc.update("UPDATE member SET participation_revision=3 WHERE event_id=? AND account_id=?",event,chief);
            case "consent" -> jdbc.update("UPDATE account SET consent='{\"push\":false}'::jsonb WHERE id=?",chief);
            case "device" -> jdbc.update("UPDATE device_token SET active=false WHERE fcm_token=?",pushToken(chief));
            case "issueAccess" -> jdbc.update("UPDATE member SET event_role='WORKER',work_group_id=? WHERE event_id=? AND account_id=?",
                    sound,event,chief);
            default -> throw new AssertionError(revoked);
        }
        relayIssuePush();
        verifyNoInteractions(push);
        assertThat(deliveryState(notification)).isEqualTo("CANCELED");
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test void failedIssuePushRetriesWithoutUndoingCommittedReport() throws Exception {
        registerPush(chief);
        var issued=create(workerCookie,initial(stage,"발송 실패 검증"));
        String issueId=issued.get("issue").get("id").asString();
        UUID notification=issuePushIds(chief).getFirst();
        when(push.send(pushToken(chief),notification,event)).thenThrow(new IllegalStateException("fixture failure"));
        for (int attempt=1; attempt<=3; attempt++) {
            jdbc.update("UPDATE outbox_event SET available_at=? WHERE event_id=? AND type='CHANGE_ACK_PUSH'",
                    java.sql.Timestamp.from(java.time.Instant.EPOCH),event);
            relayIssuePush();
            assertThat(deliveryState(notification)).isEqualTo(attempt<3 ? "PENDING" : "FAILED");
        }
        verify(push,times(3)).send(pushToken(chief),notification,event);
        assertThat(relay.relayOnce()).isZero();
        assertThat(status(issueId)).isEqualTo("RECEIVED");
        assertThat(inbox(chiefCookie).get(0).get("id").asString()).isEqualTo(notification.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report WHERE issue_id=?::uuid",Long.class,issueId)).isEqualTo(1);
    }


    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void compatibleRelayProcessesPreviouslyQueuedIssueWork(boolean retainsIssueAccess) throws Exception {
        create(workerCookie,initial(stage,"소비자 호환성"));
        UUID notification=issuePushIds(chief).getFirst();
        registerPush(chief);
        assertThat(deliveryCount()).isZero();
        assertThat(pushWorkCount()).isZero();

        // Emulate durable work left by a producer before switching to the compatible relay.
        UUID deliveryId=UUID.randomUUID();
        tx.executeWithoutResult(transaction -> {
            assertThat(jdbc.update("""
                    INSERT INTO notification_delivery (id,notification_id,device_token_id,attempt,state)
                    SELECT ?,?,id,0,'PENDING' FROM device_token WHERE fcm_token=?
                    """,deliveryId,notification,pushToken(chief))).isEqualTo(1);
            outbox.publish(event,"CHANGE_ACK_PUSH",deliveryId,null);
        });
        if (retainsIssueAccess) {
            when(push.send(pushToken(chief),notification,event))
                    .thenReturn(new PushSender.Outcome(PushSender.Status.SENT,"fixture-message"));
        } else {
            jdbc.update("UPDATE member SET event_role='WORKER',work_group_id=? WHERE event_id=? AND account_id=?",
                    sound,event,chief);
        }

        relayIssuePush();
        assertThat(deliveryState(notification)).isEqualTo(retainsIssueAccess ? "SENT" : "CANCELED");
        if (retainsIssueAccess) {
            verify(push).send(pushToken(chief),notification,event);
        } else {
            verifyNoInteractions(push);
        }
        assertThat(jdbc.queryForObject("""
                SELECT processed_at IS NOT NULL FROM outbox_event WHERE event_id=? AND aggregate_id=?
                """,Boolean.class,event,deliveryId)).isTrue();
        assertThat(relay.relayOnce()).isZero();
    }

    @Test void webWritesNeedAllowedOriginButNoCsrfHeaderAndAndroidWorks() throws Exception {
        String body=initial(stage,"믹서 전원이 꺼졌어요");
        var noOrigin=HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/issues"))).header("Content-Type","application/json")
                .header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(body));
        error(send(noOrigin),403,"CSRF_REJECTED");
        error(send(post("/api/v1/events/"+event+"/issues",body).setHeader("Origin","https://evil.example.invalid")
                .header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString())),403,"CSRF_REJECTED");
        var android=send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login"))).header("Content-Type","application/json")
                .header("X-SceneCrew-Client","ANDROID").header("Origin",ANDROID)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+worker+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")));
        String token=json.readTree(android.body()).get("accessToken").asString();
        var r=send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/issues"))).header("Content-Type","application/json")
                .header("Authorization","Bearer "+token).header("Origin",ANDROID).header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        error(send(post("/api/v1/events/"+event+"/issues",body).header("Idempotency-Key",UUID.randomUUID().toString())),401,"UNAUTHENTICATED");
    }

    @Test void chiefSeesEverythingWorkersSeeOnlyTheirOwnReports() throws Exception {
        var mine=create(workerCookie,initial(stage,"마이크 이상"));
        var theirs=create(otherCookie,initial(booth,"통로가 막혔어요"));
        String mineId=mine.get("issue").get("id").asString(), theirsId=theirs.get("issue").get("id").asString();
        // 다른 팀원이 내 이슈의 원인 업무를 맡으면 그 이슈를 보고, 자기 보고만 받는다.
        UUID task=task(event,safety,other,booth,UUID.fromString(mineId));
        assertThat(created(send(post("/api/v1/issues/"+mineId+"/reports",followup("FOLLOWUP","통로 확인 중"))
                .header("Cookie",otherCookie).header("Idempotency-Key",UUID.randomUUID().toString()))).statusCode()).isEqualTo(201);

        var chiefPage=json.readTree(ok(send(get("/api/v1/events/"+event+"/issues").header("Cookie",chiefCookie))).body());
        assertThat(ids(chiefPage.get("items"))).containsExactly(theirsId,mineId); // 최신순
        assertThat(chiefPage.get("items").get(1).get("reports").size()).isEqualTo(2);

        var workerPage=json.readTree(ok(send(get("/api/v1/events/"+event+"/issues").header("Cookie",workerCookie))).body());
        assertThat(ids(workerPage.get("items"))).containsExactly(mineId);
        assertThat(workerPage.get("items").get(0).get("reports").size()).isEqualTo(1);
        assertThat(workerPage.get("items").get(0).get("analysis").isNull()).isTrue();

        var otherView=json.readTree(ok(send(get("/api/v1/issues/"+mineId).header("Cookie",otherCookie))).body());
        assertThat(otherView.get("reports").size()).isEqualTo(1);
        assertThat(otherView.get("reports").get(0).get("text").asString()).isEqualTo("통로 확인 중");
        error(send(get("/api/v1/issues/"+theirsId).header("Cookie",workerCookie)),404,"NOT_FOUND");
        error(send(get("/api/v1/issues/"+mineId).header("Cookie",login(outsider))),404,"NOT_FOUND");
        assertThat(task).isNotNull();
    }

    @Test void replaysOwnFollowupAfterTaskReassignmentButRejectsNewReports() throws Exception {
        var initial=create(workerCookie,initial(stage,"마이크 이상"));
        String issueId=initial.get("issue").get("id").asString();
        UUID task=task(event,safety,other,booth,UUID.fromString(issueId));
        String key=UUID.randomUUID().toString(), body=followup("FOLLOWUP","현장 확인 완료");
        var first=created(send(post("/api/v1/issues/"+issueId+"/reports",body)
                .header("Cookie",otherCookie).header("Idempotency-Key",key)));
        jdbc.update("UPDATE task SET assignee_id=NULL WHERE id=?",task);
        // 현재 참여 중인 작성자는 담당 해제 뒤에도 자기 공식 보고를 볼 수 있다(P02).
        var visible=json.readTree(ok(send(get("/api/v1/issues/"+issueId).header("Cookie",otherCookie))).body());
        assertThat(visible.get("reports").size()).isEqualTo(1);
        var replay=created(send(post("/api/v1/issues/"+issueId+"/reports",body)
                .header("Cookie",otherCookie).header("Idempotency-Key",key)));
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(first.body()));
        error(send(post("/api/v1/issues/"+issueId+"/reports",body)
                .header("Cookie",otherCookie).header("Idempotency-Key",UUID.randomUUID().toString())),404,"NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report WHERE issue_id=?::uuid",Long.class,issueId)).isEqualTo(2);
        assertThat(notificationCount()).isEqualTo(2);
        jdbc.update("UPDATE member SET active=false WHERE event_id=? AND account_id=?",event,other);
        error(send(post("/api/v1/issues/"+issueId+"/reports",body)
                .header("Cookie",otherCookie).header("Idempotency-Key",key)),404,"NOT_FOUND");
    }

    @Test void analysisAppliesToReportAndBecomesStaleWhenPlanChanges() throws Exception {
        var created=create(workerCookie,initial(stage,"스테이지 마이크 이상"));
        String issueId=created.get("issue").get("id").asString(), reportId=created.get("report").get("id").asString();
        var job=aiJobs.findById(UUID.fromString(created.get("analysisJobId").asString())).orElseThrow();
        String result="{\"reportId\":\""+reportId+"\",\"category\":\"EQUIPMENT\",\"reportedState\":\"OCCURRED\",\"zoneCandidates\":"
                +"[{\"zoneId\":\""+stage+"\",\"source\":\"ZONE\",\"sourceRef\":\""+stage+"\",\"contextRevision\":3}]}";
        var applied=tx.execute(s->analyzeKind.apply(job,AiJobOutcome.succeeded("3","m1",result)));
        assertThat(applied.resultJson()).isEqualTo("{\"reportId\":\""+reportId+"\"}");
        var view=json.readTree(ok(send(get("/api/v1/issues/"+issueId).header("Cookie",chiefCookie))).body());
        var analysis=view.get("analysis");
        assertThat(analysis.propertyNames()).containsExactly("analysisValidity","category","reportedState","zoneCandidates");
        assertThat(analysis.get("analysisValidity").asString()).isEqualTo("CURRENT");
        assertThat(analysis.get("category").asString()).isEqualTo("EQUIPMENT");
        assertThat(analysis.get("zoneCandidates").get(0).get("contextRevision").asString()).isEqualTo("3");
        assertThat(json.readTree(ok(send(get("/api/v1/issues/"+issueId).header("Cookie",workerCookie))).body())
                .get("analysis").isNull()).isTrue();
        jdbc.update("UPDATE event SET revision=revision+1 WHERE id=?",event);
        view=json.readTree(ok(send(get("/api/v1/issues/"+issueId).header("Cookie",chiefCookie))).body());
        assertThat(view.get("analysis").get("analysisValidity").asString()).isEqualTo("STALE");
        // V9-D27: 최신 보고에 분석이 없으면 이전 보고의 분석으로 대신하지 않는다.
        created(send(post("/api/v1/issues/"+issueId+"/reports",followup("FOLLOWUP","예비로 교체")).header("Cookie",workerCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())));
        view=json.readTree(ok(send(get("/api/v1/issues/"+issueId).header("Cookie",chiefCookie))).body());
        assertThat(view.get("analysis").isNull()).isTrue();
    }

    @Test void followupAndResolutionRulesAndChiefStatusChange() throws Exception {
        var created=create(workerCookie,initial(stage,"마이크 이상"));
        String issueId=created.get("issue").get("id").asString();
        var follow=created(send(post("/api/v1/issues/"+issueId+"/reports",followup("FOLLOWUP","예비 마이크로 교체 중"))
                .header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString())));
        var followBody=json.readTree(follow.body());
        assertThat(followBody.propertyNames()).containsExactly("report","analysisJobId","eventRevision");
        assertThat(followBody.get("analysisJobId").isNull()).isTrue();
        assertThat(followBody.get("eventRevision").asString()).isEqualTo("5").isEqualTo(rev());
        assertThat(followBody.get("report").get("text").asString()).isEqualTo("예비 마이크로 교체 중");
        // 다른 팀원은 이슈를 볼 수 없어 404, 총괄은 보고 자격이 없어 403
        error(send(post("/api/v1/issues/"+issueId+"/reports",followup("FOLLOWUP","x")).header("Cookie",otherCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),404,"NOT_FOUND");
        error(send(post("/api/v1/issues/"+issueId+"/reports",followup("FOLLOWUP","x")).header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),403,"FORBIDDEN");

        var responding=json.readTree(ok(send(patch("/api/v1/issues/"+issueId,"{\"status\":\"RESPONDING\",\"title\":\" 메인 마이크 \",\"expectedEventRevision\":\""+rev()+"\"}")
                .header("Cookie",chiefCookie))).body());
        assertThat(responding.get("status").asString()).isEqualTo("RESPONDING");
        assertThat(responding.has("urgency")).isFalse();
        assertThat(responding.get("title").asString()).isEqualTo("메인 마이크");
        assertThat(responding.get("eventRevision").asString()).isEqualTo("6");
        // 같은 값만 보내면 바뀐 것이 없어 revision이 그대로다.
        var same=json.readTree(ok(send(patch("/api/v1/issues/"+issueId,"{\"title\":\"메인 마이크\",\"expectedEventRevision\":\"6\"}")
                .header("Cookie",chiefCookie))).body());
        assertThat(same.get("eventRevision").asString()).isEqualTo("6");
        // V9-D50(#381): 긴급도는 폐지돼 058이 받지 않는다
        error(send(patch("/api/v1/issues/"+issueId,"{\"urgency\":\"URGENT\",\"expectedEventRevision\":\"6\"}")
                .header("Cookie",chiefCookie)),400,"INVALID_REQUEST");
        var conflict=send(patch("/api/v1/issues/"+issueId,"{\"status\":\"CLOSED\",\"expectedEventRevision\":\"2\"}").header("Cookie",chiefCookie));
        error(conflict,409,"VERSION_CONFLICT");
        assertThat(json.readTree(conflict.body()).get("details").get("target").asString()).isEqualTo("event");
        error(send(patch("/api/v1/issues/"+issueId,"{\"status\":\"RESOLUTION_REPORTED\",\"expectedEventRevision\":\""+rev()+"\"}").header("Cookie",chiefCookie)),409,"INVALID_STATE");
        error(send(patch("/api/v1/issues/"+issueId,"{\"status\":\"CLOSED\",\"expectedEventRevision\":\""+rev()+"\"}").header("Cookie",workerCookie)),403,"FORBIDDEN");

        created(send(post("/api/v1/issues/"+issueId+"/reports",followup("RESOLUTION","교체 완료, 소리 정상")).header("Cookie",workerCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())));
        assertThat(status(issueId)).isEqualTo("RESOLUTION_REPORTED");
        var closed=json.readTree(ok(send(patch("/api/v1/issues/"+issueId,"{\"status\":\"CLOSED\",\"expectedEventRevision\":\""+rev()+"\"}").header("Cookie",chiefCookie))).body());
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM issue WHERE id=?::uuid",Boolean.class,issueId)).isTrue();
        error(send(post("/api/v1/issues/"+issueId+"/reports",followup("FOLLOWUP","또 안 나와요")).header("Cookie",workerCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),409,"INVALID_STATE");
        error(send(patch("/api/v1/issues/"+issueId,"{\"status\":\"RESPONDING\",\"expectedEventRevision\":\""+rev()+"\"}").header("Cookie",chiefCookie)),409,"INVALID_STATE");
    }

    @Test void statusNotificationsGoOnceToEachAuthorInTheCurrentRound() throws Exception {
        registerPush(worker);
        registerPush(other);
        String issueId=create(workerCookie,initial(stage,"마이크 이상")).get("issue").get("id").asString();
        String path="/api/v1/issues/"+issueId;
        UUID assignment=task(event,safety,other,booth,UUID.fromString(issueId));
        for (String kind:List.of("FOLLOWUP","RESOLUTION")) {
            created(send(post(path+"/reports",followup(kind,"현장 확인"))
                    .header("Cookie",otherCookie).header("Idempotency-Key",UUID.randomUUID().toString())));
        }
        jdbc.update("UPDATE task SET assignee_id=NULL WHERE id=?",assignment);
        String responding=statusChange("RESPONDING",rev());
        error(send(patch(path,responding).header("Cookie",workerCookie)),403,"FORBIDDEN");
        error(send(patch(path,responding).header("Cookie",login(outsider))),404,"NOT_FOUND");
        assertThat(notificationCount()).isEqualTo(3);
        ok(send(patch(path,responding).header("Cookie",chiefCookie)));
        assertThat(notificationCount()).isEqualTo(5);
        assertThat(deliveryCount()).isEqualTo(2);
        var workerInbox=inbox(workerCookie);
        var otherInbox=inbox(otherCookie);
        assertThat(workerInbox.size()).isEqualTo(1);
        assertThat(otherInbox.size()).isEqualTo(1);
        assertThat(otherInbox.get(0).get("target").get("id").asString()).isEqualTo(issueId);
        String otherRead="/api/v1/notifications/"+otherInbox.get(0).get("id").asString()+"/read";
        ok(send(post(otherRead,"{}").header("Cookie",otherCookie).header("X-SceneCrew-CSRF","1")));

        error(send(patch(path,responding).header("Cookie",chiefCookie)),409,"VERSION_CONFLICT");
        ok(send(patch(path,statusChange("RESPONDING",rev())).header("Cookie",chiefCookie)));
        assertThat(notificationCount()).isEqualTo(5);

        long beforeClose=Long.parseLong(rev());
        tx.executeWithoutResult(transaction -> {
            issues.change(UUID.fromString(issueId),chief,new IssueService.IssueChange(null,"CLOSED",beforeClose));
            assertThat(notificationCount()).isEqualTo(7);
            assertThat(deliveryCount()).isEqualTo(4);
            entityManager.flush();
            assertThat(pushWorkCount()).isEqualTo(4);
            transaction.setRollbackOnly();
        });
        assertThat(notificationCount()).isEqualTo(5);
        assertThat(deliveryCount()).isEqualTo(2);
        assertThat(pushWorkCount()).isEqualTo(2);
        assertThat(status(issueId)).isEqualTo("RESPONDING");
        assertThat(rev()).isEqualTo(Long.toString(beforeClose));

        jdbc.update("UPDATE member SET participation_revision=3 WHERE event_id=? AND account_id=?",event,worker);
        String workerRead="/api/v1/notifications/"+workerInbox.get(0).get("id").asString()+"/read";
        error(send(post(workerRead,"{}").header("Cookie",workerCookie).header("X-SceneCrew-CSRF","1")),404,"NOT_FOUND");
        ok(send(patch(path,statusChange("CLOSED",rev())).header("Cookie",chiefCookie)));
        assertThat(notificationCount()).isEqualTo(6);
        assertThat(inbox(workerCookie).size()).isZero();
        assertThat(inbox(otherCookie).size()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void statusChangeAndAuthorDeactivationSerializeOnEvent(boolean statusFirst) throws Exception {
        String issueId=create(workerCookie,initial(stage,"마이크 이상")).get("issue").get("id").asString();
        UUID author=jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,event,worker);
        long beforeRevision=Long.parseLong(rev());
        long deadlocks=deadlocks();
        var change=patch("/api/v1/issues/"+issueId,statusChange("RESPONDING",rev()))
                .header("Cookie",chiefCookie).timeout(Duration.ofSeconds(25)).build();
        var leave=post("/api/v1/me/deactivate","{\"password\":\"SyntheticPass8!\"}")
                .header("Cookie",workerCookie).header("X-SceneCrew-CSRF","1").timeout(Duration.ofSeconds(25)).build();
        var body=HttpResponse.BodyHandlers.ofString();
        HttpResponse<String> changed;
        HttpResponse<String> left;
        // Hold the winner at its next row lock, after it acquires Event. Observe both DB waits before releasing it.
        try (var gate=jdbc.getDataSource().getConnection()) {
            gate.setAutoCommit(false);
            int gatePid;
            try (var stmt=gate.createStatement(); var rows=stmt.executeQuery("SELECT pg_backend_pid()")) {
                rows.next();
                gatePid=rows.getInt(1);
            }
            String lock=statusFirst ? "SELECT id FROM issue WHERE id=? FOR UPDATE"
                    : "SELECT id FROM member WHERE id=? FOR UPDATE";
            try (var stmt=gate.prepareStatement(lock)) {
                stmt.setObject(1,statusFirst ? UUID.fromString(issueId) : author);
                assertThat(stmt.executeQuery().next()).isTrue();
            }
            var first=client.sendAsync(statusFirst ? change : leave,body);
            java.util.concurrent.CompletableFuture<HttpResponse<String>> second=null;
            try {
                int firstPid=waitForLock(gatePid,statusFirst ? "FROM issue i WHERE" : "SELECT id, event_role FROM member");
                second=client.sendAsync(statusFirst ? leave : change,body);
                waitForLock(firstPid,statusFirst ? "SELECT state FROM event" : "SELECT id FROM event");
            } finally {
                gate.rollback();
                first.get(30,TimeUnit.SECONDS);
                if (second!=null) second.get(30,TimeUnit.SECONDS);
            }
            changed=(statusFirst ? first : second).join();
            left=(statusFirst ? second : first).join();
        }
        ok(left);
        assertThat(jdbc.queryForMap("SELECT active, participation_revision FROM member WHERE id=?",author))
                .containsEntry("active",false).containsEntry("participation_revision",3L);
        assertThat(deadlocks()).isEqualTo(deadlocks);
        assertThat(Long.parseLong(rev())).isEqualTo(beforeRevision+(statusFirst ? 2 : 1));
        var authorNotifications=jdbc.queryForList("""
                SELECT id FROM notification WHERE event_id=? AND member_id=? AND participation_revision=2
                  AND type='ISSUE' AND payload->>'status'='RESPONDING' AND payload#>>'{target,id}'=?
                """,UUID.class,event,author,issueId);
        if (statusFirst) {
            ok(changed);
            assertThat(status(issueId)).isEqualTo("RESPONDING");
            assertThat(authorNotifications).hasSize(1);
            String read="/api/v1/notifications/"+authorNotifications.getFirst()+"/read";
            error(send(post(read,"{}").header("Cookie",workerCookie).header("X-SceneCrew-CSRF","1")),401,"UNAUTHENTICATED");
        } else {
            error(changed,409,"VERSION_CONFLICT");
            assertThat(status(issueId)).isEqualTo("RECEIVED");
            assertThat(authorNotifications).isEmpty();
            ok(send(patch("/api/v1/issues/"+issueId,statusChange("RESPONDING",rev())).header("Cookie",chiefCookie)));
            assertThat(status(issueId)).isEqualTo("RESPONDING");
        }
        assertThat(notificationCount()).isEqualTo(statusFirst ? 2 : 1);
        assertThat(inbox(chiefCookie).size()).isEqualTo(1);
        error(send(get("/api/v1/me/notifications").header("Cookie",workerCookie)),401,"UNAUTHENTICATED");
    }

    private int waitForLock(int blocker,String queryFragment) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime()<deadline) {
            var waiters=jdbc.queryForList("""
                    SELECT pid FROM pg_stat_activity
                    WHERE datname=current_database() AND wait_event_type='Lock'
                      AND ?=ANY(pg_blocking_pids(pid)) AND query LIKE ?
                    """,Integer.class,blocker,"%"+queryFragment+"%");
            if (waiters.size()==1) return waiters.getFirst();
            Thread.sleep(20);
        }
        throw new AssertionError("PostgreSQL lock wait not observed: "+queryFragment);
    }

    private long deadlocks() {
        return jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()",Long.class);
    }

    private static String statusChange(String status,String revision) {
        return "{\"status\":\""+status+"\",\"expectedEventRevision\":\""+revision+"\"}";
    }

    /** V9-D48: 최신 보고의 AI 추천이 이슈 상황으로 보이고, 총괄 정정값이 우선하며 팀원에게는 없다. */
    @Test void situationShowsLatestSuggestionThenChiefCorrectionWins() throws Exception {
        var created=create(workerCookie,initial(stage,"스테이지 마이크 소리가 안 나요"));
        String issueId=created.get("issue").get("id").asString(), reportId=created.get("report").get("id").asString();
        assertThat(created.get("issue").get("situation").isNull()).isTrue();
        applySituation(created, reportId, "{\"primary\":\"EQUIPMENT_ANOMALY\",\"related\":[\"SCHEDULE_DELAY\"]}");

        var view=json.readTree(ok(send(get("/api/v1/issues/"+issueId).header("Cookie",chiefCookie))).body());
        var situation=view.get("situation");
        assertThat(situation.propertyNames()).containsExactly("primaryType","relatedTypes","confirmed","confirmedByMemberId",
                "confirmedAt","reason","suggestion");
        assertThat(situation.get("primaryType").asString()).isEqualTo("EQUIPMENT_ANOMALY");
        assertThat(situation.get("relatedTypes").get(0).asString()).isEqualTo("SCHEDULE_DELAY");
        assertThat(situation.get("confirmed").asBoolean()).isFalse();
        assertThat(situation.get("suggestion").get("primaryType").asString()).isEqualTo("EQUIPMENT_ANOMALY");
        assertThat(json.readTree(ok(send(get("/api/v1/issues/"+issueId).header("Cookie",workerCookie))).body())
                .get("situation").isNull()).isTrue();

        // 총괄 정정: 사유 필수, 영수증만 돌려주고 Event.revision을 올린다. 같은 키 재요청은 같은 영수증이다.
        long before=Long.parseLong(rev());
        String key=UUID.randomUUID().toString();
        String body="{\"expectedEventRevision\":\""+before+"\",\"primaryType\":null,\"relatedTypes\":[\"STAFF_GAP\"],\"reason\":\"원인 확인 전\"}";
        var r=send(patch("/api/v1/issues/"+issueId+"/situation",body).header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var receipt=json.readTree(r.body());
        assertThat(receipt.propertyNames()).containsExactly("id","revision","eventRevision");
        assertThat(receipt.get("eventRevision").asString()).isEqualTo(Long.toString(before+1));
        assertThat(rev()).isEqualTo(Long.toString(before+1));
        var again=send(patch("/api/v1/issues/"+issueId+"/situation",body).header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(json.readTree(again.body())).isEqualTo(receipt);
        assertThat(rev()).isEqualTo(Long.toString(before+1));

        situation=json.readTree(ok(send(get("/api/v1/issues/"+issueId).header("Cookie",chiefCookie))).body()).get("situation");
        assertThat(situation.get("primaryType").isNull()).isTrue();
        assertThat(situation.get("relatedTypes").get(0).asString()).isEqualTo("STAFF_GAP");
        assertThat(situation.get("confirmed").asBoolean()).isTrue();
        assertThat(situation.get("reason").asString()).isEqualTo("원인 확인 전");
        assertThat(situation.get("confirmedAt").isNull()).isFalse();
        // AI 원본 추천은 지우지 않고 suggestion으로 따로 보인다.
        assertThat(situation.get("suggestion").get("primaryType").asString()).isEqualTo("EQUIPMENT_ANOMALY");
        assertThat(jdbc.queryForObject("SELECT analysis->'situation'->>'primary' FROM report WHERE id=?::uuid",String.class,reportId))
                .isEqualTo("EQUIPMENT_ANOMALY");

        // 정정 뒤 새 보고의 추천은 이슈 상황을 덮어쓰지 않는다.
        var follow=json.readTree(created(send(post("/api/v1/issues/"+issueId+"/reports",followup("FOLLOWUP","관객이 몰립니다"))
                .header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString()))).body());
        jdbc.update("UPDATE report SET analysis=?::jsonb WHERE id=?::uuid",
                "{\"jobId\":\""+UUID.randomUUID()+"\",\"contextRevision\":1,\"category\":\"CONGESTION\",\"reportedState\":null,"
                        +"\"zoneCandidates\":[],\"situation\":{\"primary\":\"AUDIENCE_SAFETY\",\"related\":[]}}",
                follow.get("report").get("id").asString());
        situation=json.readTree(ok(send(get("/api/v1/issues/"+issueId).header("Cookie",chiefCookie))).body()).get("situation");
        assertThat(situation.get("primaryType").isNull()).isTrue();
        assertThat(situation.get("confirmed").asBoolean()).isTrue();
        assertThat(situation.get("suggestion").get("primaryType").asString()).isEqualTo("AUDIENCE_SAFETY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operational_situation WHERE issue_id=?::uuid",Long.class,issueId))
                .isEqualTo(1);
    }

    @Test void situationCorrectionValidatesInputRoleAndRevision() throws Exception {
        var created=create(workerCookie,initial(stage,"스테이지 마이크 이상"));
        String issueId=created.get("issue").get("id").asString();
        String path="/api/v1/issues/"+issueId+"/situation";
        java.util.function.Function<String,String> body=tail->"{\"expectedEventRevision\":\""+rev()+"\","+tail+"}";
        for (String bad:new String[] {
                "\"primaryType\":\"UNCLASSIFIED\",\"relatedTypes\":[],\"reason\":\"x\"",
                "\"primaryType\":\"STAFF_GAP\",\"relatedTypes\":[\"STAFF_GAP\"],\"reason\":\"x\"",
                "\"primaryType\":null,\"relatedTypes\":[\"STAFF_GAP\",\"STAFF_GAP\"],\"reason\":\"x\"",
                "\"primaryType\":null,\"relatedTypes\":[],\"reason\":\"   \"",
                "\"primaryType\":null,\"relatedTypes\":[]",
                "\"relatedTypes\":[],\"reason\":\"x\"",
                "\"primaryType\":null,\"relatedTypes\":[],\"reason\":\"x\",\"source\":\"AI\""})
            error(send(patch(path,body.apply(bad)).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),
                    400,"INVALID_REQUEST");
        String ok="\"primaryType\":\"EQUIPMENT_ANOMALY\",\"relatedTypes\":[],\"reason\":\"현장 확인\"";
        error(send(patch(path,body.apply(ok)).header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString())),
                403,"FORBIDDEN");
        error(send(patch(path,"{\"expectedEventRevision\":\"2\","+ok+"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),409,"VERSION_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operational_situation WHERE issue_id=?::uuid",Long.class,issueId))
                .isZero();
        // DB도 확인된 행에 사유가 없으면 거절한다(V10).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO operational_situation (id,event_id,issue_id,primary_type,related_types,source_kind,source_refs,
                                                   confirmed_by_member_id,confirmed_at,reason)
                VALUES (?,?,?::uuid,NULL,'[]'::jsonb,'FORMAL_REPORT','[]'::jsonb,NULL,now(),' ')""",
                UUID.randomUUID(),event,issueId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /** EXT19: 키 재사용 불일치·Origin·비참여·종료 이슈·종료 행사. 거절은 운영 상황을 남기지 않고 revision도 그대로다. */
    @Test void situationCorrectionRejectsMismatchOriginOutsiderClosedIssueAndEndedEvent() throws Exception {
        var created=create(workerCookie,initial(stage,"스테이지 마이크 이상"));
        String issueId=created.get("issue").get("id").asString();
        String path="/api/v1/issues/"+issueId+"/situation";
        java.util.function.Function<String,String> body=reason->"{\"expectedEventRevision\":\""+rev()
                +"\",\"primaryType\":\"EQUIPMENT_ANOMALY\",\"relatedTypes\":[],\"reason\":\""+reason+"\"}";

        // 같은 키에 다른 본문은 409 IDEMPOTENCY_MISMATCH이고 첫 결과를 바꾸지 않는다.
        String key=UUID.randomUUID().toString(), first=body.apply("현장 확인");
        ok(send(patch(path,first).header("Cookie",chiefCookie).header("Idempotency-Key",key)));
        String afterFirst=rev();
        error(send(patch(path,first.replace("현장 확인","다른 사유")).header("Cookie",chiefCookie).header("Idempotency-Key",key)),
                409,"IDEMPOTENCY_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT reason FROM operational_situation WHERE issue_id=?::uuid",String.class,issueId))
                .isEqualTo("현장 확인");
        assertThat(rev()).isEqualTo(afterFirst);

        // WEB 쓰기는 허용 Origin이 있어야 한다.
        error(send(patch(path,body.apply("x")).setHeader("Origin","https://evil.example.invalid").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),403,"CSRF_REJECTED");
        error(send(HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type","application/json").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()).method("PATCH",HttpRequest.BodyPublishers.ofString(body.apply("x")))),
                403,"CSRF_REJECTED");
        // 다른 행사의 총괄(비참여)은 이슈 존재를 알 수 없다.
        error(send(patch(path,body.apply("x")).header("Cookie",login(outsider)).header("Idempotency-Key",UUID.randomUUID().toString())),
                404,"NOT_FOUND");

        // 종료(CLOSED) 이슈는 정정할 수 없다.
        jdbc.update("UPDATE issue SET status='CLOSED', closed_at=now() WHERE id=?::uuid",issueId);
        error(send(patch(path,body.apply("종료 뒤")).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),
                409,"INVALID_STATE");
        // 종료(ENDED) 행사도 정정할 수 없다.
        jdbc.update("UPDATE issue SET status='RECEIVED', closed_at=NULL WHERE id=?::uuid",issueId);
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        error(send(patch(path,body.apply("행사 종료 뒤")).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),
                409,"INVALID_STATE");

        assertThat(jdbc.queryForObject("SELECT reason FROM operational_situation WHERE issue_id=?::uuid",String.class,issueId))
                .isEqualTo("현장 확인");
        assertThat(rev()).isEqualTo(afterFirst);
    }

    void applySituation(tools.jackson.databind.JsonNode created, String reportId, String situation) {
        var job=aiJobs.findById(UUID.fromString(created.get("analysisJobId").asString())).orElseThrow();
        String result="{\"reportId\":\""+reportId+"\",\"category\":\"EQUIPMENT\",\"reportedState\":\"OCCURRED\","
                +"\"zoneCandidates\":[],\"situation\":"+situation+"}";
        tx.execute(s->analyzeKind.apply(job,AiJobOutcome.succeeded("4","m1",result)));
    }

    @Test void chiefRequestsAnalysisWithExpectedRevision() throws Exception {
        var created=create(workerCookie,initial(stage,"마이크 이상"));
        String reportId=created.get("report").get("id").asString(), key=UUID.randomUUID().toString();
        var r=send(post("/api/v1/reports/"+reportId+"/analyze","{\"expectedEventRevision\":\""+rev()+"\"}").header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(202);
        var accepted=json.readTree(r.body());
        assertThat(accepted.get("statusUrl").asString()).isEqualTo("/api/v1/ai-jobs/"+accepted.get("jobId").asString());
        var again=send(post("/api/v1/reports/"+reportId+"/analyze","{\"expectedEventRevision\":\""+rev()+"\"}").header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(json.readTree(again.body())).isEqualTo(accepted);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isEqualTo(2);
        error(send(post("/api/v1/reports/"+reportId+"/analyze","{\"expectedEventRevision\":\"2\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),409,"VERSION_CONFLICT");
        error(send(post("/api/v1/reports/"+reportId+"/analyze","{\"expectedEventRevision\":\""+rev()+"\"}").header("Cookie",workerCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),403,"FORBIDDEN");
    }

    @Test void rejectsBadInputReferencesAndClosedEvents() throws Exception {
        String path="/api/v1/events/"+event+"/issues";
        for (String bad:List.of("{}", "{\"zoneId\":\""+stage+"\"}", "{\"zoneId\":\""+stage+"\",\"report\":{\"kind\":\"INITIAL\",\"text\":\"   \"}}",
                "{\"zoneId\":\""+stage+"\",\"report\":{\"kind\":\"FOLLOWUP\",\"text\":\"a\"}}",
                "{\"zoneId\":\""+stage+"\",\"report\":{\"kind\":\"INITIAL\",\"text\":\"a\"},\"extra\":1}",
                "{\"zoneId\":\""+stage+"\",\"report\":{\"kind\":\"INITIAL\",\"text\":\""+"가".repeat(1001)+"\"}}",
                "{\"zoneId\":\"not-a-uuid\",\"report\":{\"kind\":\"INITIAL\",\"text\":\"a\"}}"))
            error(send(post(path,bad).header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(post(path,initial(stage,"a")).header("Cookie",workerCookie)),400,"INVALID_REQUEST"); // 키 없음
        var ref=send(post(path,initial(otherZone,"a")).header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        error(ref,422,"INVALID_REFERENCE");
        assertThat(json.readTree(ref.body()).get("details").get("field").asString()).isEqualTo("zoneId");
        // 그룹 없는 총괄은 workGroupId를 정해야 한다.
        error(send(post(path,initial(stage,"a")).header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        var chiefReport=send(post(path,"{\"zoneId\":\""+stage+"\",\"workGroupId\":\""+safety+"\",\"report\":{\"kind\":\"INITIAL\",\"text\":\"총괄 보고\"}}")
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString()));
        assertThat(chiefReport.statusCode()).as(chiefReport.body()).isEqualTo(201);
        error(send(post(path,initial(stage,"a")).header("Cookie",login(outsider)).header("Idempotency-Key",UUID.randomUUID().toString())),404,"NOT_FOUND");
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        error(send(post(path,initial(stage,"a")).header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString())),409,"INVALID_STATE");
        for (String q:List.of("limit=0","limit=101","cursor=xx","other=1"))
            error(send(get(path+"?"+q).header("Cookie",chiefCookie)),400,"INVALID_REQUEST");
    }

    @Test void listCursorPagesWithoutRepeatsOrGaps() throws Exception {
        for (int i=0;i<7;i++) create(workerCookie,initial(stage,"보고 "+i));
        var expected=jdbc.queryForList("SELECT id::text FROM issue WHERE event_id=? ORDER BY created_at DESC, id DESC",String.class,event);
        var seen=new ArrayList<String>(); String cursor=null; int pages=0;
        do {
            var page=json.readTree(ok(send(get("/api/v1/events/"+event+"/issues?limit=3"+(cursor==null?"":"&cursor="+cursor)).header("Cookie",workerCookie))).body());
            seen.addAll(ids(page.get("items")));
            cursor=page.get("nextCursor").isNull()?null:page.get("nextCursor").asString();
            pages++;
        } while (cursor!=null && pages<10);
        assertThat(seen).containsExactlyElementsOf(expected).hasSize(7);
        assertThat(pages).isEqualTo(3);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={0x00a0,0x202f,0x0085})
    void rejectsUnicodeWhitespaceOnlyReports(int codePoint) throws Exception {
        String text=Character.toString(codePoint);
        error(send(post("/api/v1/events/"+event+"/issues",initial(stage,text)).header("Cookie",workerCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        var valid=create(workerCookie,initial(stage,"마이크 확인"));
        String issueId=valid.get("issue").get("id").asString();
        error(send(post("/api/v1/issues/"+issueId+"/reports",followup("FOLLOWUP",text)).header("Cookie",workerCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString())),400,"INVALID_REQUEST");
        error(send(patch("/api/v1/issues/"+issueId,"{\"title\":\""+text+"\",\"expectedEventRevision\":\""+rev()+"\"}")
                .header("Cookie",chiefCookie)),400,"INVALID_REQUEST");
    }

    // ---- 도움 ----
    JsonNode create(String cookie,String body) throws Exception {
        return json.readTree(created(send(post("/api/v1/events/"+event+"/issues",body).header("Cookie",cookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()))).body());
    }
    static String initial(UUID zone,String text) { return "{\"zoneId\":\""+zone+"\",\"report\":{\"kind\":\"INITIAL\",\"text\":\""+text+"\"}}"; }
    static String followup(String kind,String text) { return "{\"kind\":\""+kind+"\",\"text\":\""+text+"\",\"quickTemplateKey\":null,\"audioId\":null}"; }
    String rev() { return String.valueOf(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)); }
    String status(String issueId) { return jdbc.queryForObject("SELECT status FROM issue WHERE id=?::uuid",String.class,issueId); }
    static List<String> ids(JsonNode items) { var out=new ArrayList<String>(); for (JsonNode n:items) out.add(n.get("id").asString()); return out; }
    HttpResponse<String> ok(HttpResponse<String> r) { assertThat(r.statusCode()).as(r.body()).isEqualTo(200); return r; }
    HttpResponse<String> created(HttpResponse<String> r) { assertThat(r.statusCode()).as(r.body()).isEqualTo(201); return r; }
    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                encoder.encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    UUID event(String state) {
        var id=UUID.randomUUID(); events.add(id);
        jdbc.update("INSERT INTO event (id,name,state,revision,date,last_input_modified_at) VALUES (?,?,?,?,?,now())",
                id,"공연",state,3L,LocalDate.of(2026,11,14));
        return id;
    }
    UUID group(UUID event,String name) {
        var id=UUID.randomUUID(); jdbc.update("INSERT INTO work_group VALUES (?, ?, ?, '', true)",id,event,name); return id;
    }
    UUID zone(UUID event,String name) {
        var id=UUID.randomUUID(); jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,?,NULL,'[]'::jsonb)",id,event,name); return id;
    }
    UUID task(UUID event,UUID group,UUID assigneeAccount,UUID zone,UUID sourceIssue) {
        var id=UUID.randomUUID();
        UUID assignee=jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,event,assigneeAccount);
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,program_id,source_issue_id,title,start_at,end_at,steps,checklist,status)
                VALUES (?,?,?,'b1',?,?,NULL,?,'지원','2026-11-14T09:00:00Z','2026-11-14T09:30:00Z','[]'::jsonb,'[]'::jsonb,'WAITING')""",
                id,event,group,assignee,zone,sourceIssue);
        return id;
    }
    void member(UUID event,UUID account,String role,UUID group) {
        Long crew=jdbc.queryForObject("SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?",Long.class,event);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,?,?,'fixture',true,false,true,2)""",UUID.randomUUID(),event,account,group,crew,role);
    }
    String login(UUID account) throws Exception {
        var r=send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login"))).header("Content-Type","application/json")
                .header("X-SceneCrew-Client","WEB").header("Origin",WEB).header("X-SceneCrew-CSRF","1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }
    String url(String path) { return "http://localhost:"+port+path; }
    private void registerPush(UUID account) throws Exception {
        jdbc.update("UPDATE account SET consent='{\"push\":true}'::jsonb WHERE id=?",account);
        var login=ok(send(android("/api/v1/auth/login").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("email",account+"@example.invalid","password","SyntheticPass8!"))))));
        String access=json.readTree(login.body()).get("accessToken").asString();
        String body=json.writeValueAsString(Map.of("token",pushToken(account),
                "deviceProof",Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])));
        var registered=send(android("/api/v1/me/devices/"+UUID.randomUUID())
                .header("Authorization","Bearer "+access).header("Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(200);
    }
    private HttpRequest.Builder android(String path) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Origin",ANDROID).header("X-SceneCrew-Client","ANDROID");
    }
    private static String pushToken(UUID account) { return "fixture-issue-push-"+account; }
    private List<UUID> issuePushIds(UUID account) {
        return jdbc.queryForList("""
                SELECT n.id FROM notification n JOIN member m ON m.id=n.member_id
                WHERE n.event_id=? AND m.account_id=? AND n.type='ISSUE'
                """,UUID.class,event,account);
    }
    private long deliveryCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notification_delivery d JOIN notification n ON n.id=d.notification_id WHERE n.event_id=?
                """,Long.class,event);
    }
    private long pushWorkCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_id=? AND type='CHANGE_ACK_PUSH'",Long.class,event);
    }
    private String deliveryState(UUID notification) {
        return jdbc.queryForObject("SELECT state FROM notification_delivery WHERE notification_id=?",String.class,notification);
    }
    private void relayIssuePush() {
        // Leave analysis jobs queued and exercise only push dispatch with a mocked transport.
        jdbc.update("UPDATE outbox_event SET available_at=now()+interval '1 hour' WHERE event_id=? AND type<>'CHANGE_ACK_PUSH'",event);
        assertThat(relay.relayOnce()).isPositive();
    }
    long notificationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE event_id=?",Long.class,event);
    }
    JsonNode inbox(String cookie) throws Exception {
        return json.readTree(ok(send(get("/api/v1/me/notifications").header("Cookie",cookie))).body()).get("items");
    }
    HttpRequest.Builder get(String path) { return HttpRequest.newBuilder(URI.create(url(path))).GET(); }
    HttpRequest.Builder post(String path,String body) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type","application/json").header("Origin",WEB)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }
    HttpRequest.Builder patch(String path,String body) {
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type","application/json").header("Origin",WEB)
                .method("PATCH",HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception { return client.send(r.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> r,int status,String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo(code);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Encoder {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}

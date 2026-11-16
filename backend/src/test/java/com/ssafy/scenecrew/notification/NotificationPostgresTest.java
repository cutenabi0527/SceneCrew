package com.ssafy.scenecrew.notification;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.notification.dto.NotificationView;
import com.ssafy.scenecrew.notification.service.NotificationCursor;
import com.ssafy.scenecrew.notification.service.NotificationService;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL/Flyway + Redis + production Security over HTTP. Fixtures are private to each test. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationPostgresTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final byte[] KEY = new byte[32];
    static { new java.security.SecureRandom().nextBytes(KEY); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled", () -> "true");
        r.add("scenecrew.auth.signing-key-base64", () -> Base64.getEncoder().encodeToString(KEY));
        r.add("scenecrew.auth.failure-hmac-key-base64", () -> Base64.getEncoder().encodeToString(KEY));
        r.add("scenecrew.auth.issuer", () -> "notification-test");
        r.add("scenecrew.auth.audience", () -> "notification-test");
        r.add("scenecrew.auth.web-origins", () -> "https://web.example.invalid");
        r.add("scenecrew.auth.android-origins", () -> "https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService service;
    @Autowired NotificationCursor codec;
    @Autowired PlatformTransactionManager manager;
    @Autowired LoginTokenIssuer issuer;
    @Autowired RedisLoginSessions sessions;
    @Autowired StringRedisTemplate redis;
    @LocalServerPort int port;
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    UUID account, other, event, otherEvent, member, otherMember, identity, group, zone;
    SessionAuthenticator.Principal principal;
    final List<UUID> sessionIds = new ArrayList<>();
    final Instant time = Instant.parse("2026-11-05T01:02:03.123456Z");

    @BeforeEach void fixture() {
        account=UUID.randomUUID(); other=UUID.randomUUID(); event=UUID.randomUUID(); otherEvent=UUID.randomUUID();
        member=UUID.randomUUID(); otherMember=UUID.randomUUID(); identity=UUID.randomUUID(); group=UUID.randomUUID(); zone=UUID.randomUUID();
        for (UUID id : List.of(account, other))
            jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 3, '{}'::jsonb, now())", id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, 'unused', ?, true)", identity, account,
                identity.toString(), identity + "@example.invalid");
        for (UUID id : List.of(event, otherEvent))
            jdbc.update("INSERT INTO event (id,name,state,revision,last_input_modified_at) VALUES (?, 'fixture', 'LIVE', 7, now())", id);
        jdbc.update("INSERT INTO member VALUES (?, ?, ?, NULL, 1, 'WORKER', 'fixture', true, false, true, 1)", member, event, account);
        jdbc.update("INSERT INTO member VALUES (?, ?, ?, NULL, 2, 'WORKER', 'other', true, false, true, 1)", otherMember, event, other);
        jdbc.update("INSERT INTO work_group VALUES (?, ?, 'fixture', '', true)", group, event);
        jdbc.update("INSERT INTO zone VALUES (?, ?, 'fixture', '', '[]'::jsonb)", zone, event);
        principal=new SessionAuthenticator.Principal(account, UUID.randomUUID(), identity, 3, "WEB");
    }
    @AfterEach void cleanup() {
        sessionIds.forEach(id -> redis.delete(RedisLoginSessions.key(id)));
        jdbc.update("DELETE FROM notification WHERE event_id IN (?,?)", event, otherEvent);
        jdbc.update("DELETE FROM change_acknowledgment WHERE event_id=?", event);
        jdbc.update("DELETE FROM change_set WHERE event_id=?", event);
        jdbc.update("DELETE FROM task WHERE event_id=?", event);
        jdbc.update("DELETE FROM report WHERE issue_id IN (SELECT id FROM issue WHERE event_id=?)", event);
        jdbc.update("DELETE FROM issue WHERE event_id=?", event);
        jdbc.update("DELETE FROM member WHERE event_id=?", event);
        jdbc.update("DELETE FROM work_group WHERE event_id=?", event);
        jdbc.update("DELETE FROM zone WHERE event_id=?", event);
        jdbc.update("DELETE FROM event WHERE id IN (?,?)", event, otherEvent);
        jdbc.update("DELETE FROM auth_identity WHERE account_id=?", account);
        jdbc.update("DELETE FROM account WHERE id IN (?,?)", account, other);
    }
    UUID notification(UUID owner, UUID e, long revision, Instant created) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification (id,member_id,participation_revision,event_id,type,payload,dedupe_key,created_at)
                VALUES (?, ?, ?, ?, 'INITIAL_ASSIGNMENT', '{"title":"private-name","body":"private-report"}'::jsonb, ?, ?)
                """, id, owner, revision, e, id.toString(), Timestamp.from(created));
        return id;
    }
    UUID own() { return notification(member, event, 1, time); }
    NotificationView.Page list(String cursor, int limit) { return service.list(principal, cursor, limit, codec); }
    void denied(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
    Instant readAt(UUID id) {
        var ts=jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?", Timestamp.class, id);
        return ts==null ? null : ts.toInstant();
    }

    @Test void emptyInbox() { assertThat(list(null,30)).isEqualTo(new NotificationView.Page(List.of(),null)); }
    @Test void filtersOtherAccountEventAndOldParticipationBeforeLimit() {
        UUID mine=own();
        notification(otherMember,event,1,time.plusSeconds(1));
        notification(member,otherEvent,1,time.plusSeconds(2));
        notification(member,event,0,time.plusSeconds(3));
        var page=list(null,1);
        assertThat(page.items()).extracting(NotificationView::id).containsExactly(mine);
        assertThat(page.nextCursor()).isNull();
        assertThat(json.writeValueAsString(page)).doesNotContain("private-name","private-report","payload","memberId");
    }
    @Test void keysetTiesAreCompleteOrderedAndDisjoint() {
        List<UUID> inserted=new ArrayList<>();
        for(int i=0;i<8;i++) inserted.add(own());
        inserted.sort(Comparator.comparing(UUID::toString).reversed()); // PostgreSQL UUID unsigned byte order
        List<UUID> seen=new ArrayList<>(); String cursor=null;
        do { var page=list(cursor,3); page.items().forEach(n->seen.add(n.id())); cursor=page.nextCursor(); } while(cursor!=null);
        assertThat(seen).containsExactlyElementsOf(inserted);
    }
    @Test void newNotificationsDoNotShiftExistingPageBoundary() {
        UUID oldest=notification(member,event,1,time.minusSeconds(1)); UUID first=own();
        var page=list(null,1); assertThat(page.items().getFirst().id()).isEqualTo(first);
        UUID newer=notification(member,event,1,time.plusSeconds(1));
        assertThat(list(page.nextCursor(),1).items()).extracting(NotificationView::id).containsExactly(oldest);
        assertThat(list(null,1).items()).extracting(NotificationView::id).containsExactly(newer);
    }
    @Test void everyPageRechecksRevocationAndRejoin() {
        own(); own(); var page=list(null,1);
        jdbc.update("UPDATE member SET active=false WHERE id=?",member);
        assertThat(list(page.nextCursor(),30).items()).isEmpty();
        jdbc.update("UPDATE member SET active=true,participation_revision=2 WHERE id=?",member);
        assertThat(list(null,30).items()).isEmpty();
        UUID current=notification(member,event,2,time);
        assertThat(list(null,30).items()).extracting(NotificationView::id).containsExactly(current);
    }
    @Test void invalidLimitsAndForeignCursorNeverReturnData() {
        own(); own(); String cursor=list(null,1).nextCursor();
        var foreign=new SessionAuthenticator.Principal(other,UUID.randomUUID(),UUID.randomUUID(),3,"WEB");
        denied(()->service.list(foreign,cursor,30,codec),ErrorCode.INVALID_REQUEST);
        for(int limit:new int[]{0,-1,101}) denied(()->list(null,limit),ErrorCode.INVALID_REQUEST);
        denied(()->list("broken",30),ErrorCode.INVALID_REQUEST);
    }
    @Test void firstReadPersistsAndRepeatedReadIsExactlyStable() {
        UUID id=own(); var first=service.read(principal,id);
        assertThat(first.readAt()).isNotNull().isEqualTo(readAt(id));
        assertThat(service.read(principal,id)).isEqualTo(first);
        assertThat(list(null,30).items()).containsExactly(first);
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(7);
    }
    @Test void foreignMissingMismatchedAndOldNotificationsAreHiddenAndUnchanged() {
        for(UUID id:List.of(notification(otherMember,event,1,time),notification(member,otherEvent,1,time),notification(member,event,0,time))) {
            denied(()->service.read(principal,id),ErrorCode.NOT_FOUND); assertThat(readAt(id)).isNull();
        }
        denied(()->service.read(principal,UUID.randomUUID()),ErrorCode.NOT_FOUND);
    }
    @Test void revokedMembershipAndAccountCannotRead() {
        UUID id=own(); jdbc.update("UPDATE member SET active=false WHERE id=?",member);
        denied(()->service.read(principal,id),ErrorCode.NOT_FOUND);
        jdbc.update("UPDATE member SET active=true, participation_revision=2 WHERE id=?",member);
        denied(()->service.read(principal,id),ErrorCode.NOT_FOUND);
        jdbc.update("UPDATE member SET participation_revision=1 WHERE id=?",member);
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",account);
        denied(()->service.read(principal,id),ErrorCode.NOT_FOUND);
        assertThat(list(null,30).items()).isEmpty(); assertThat(readAt(id)).isNull();
    }
    @Test void endedEventAllowsOwnInboxButDraftWorkerCannotAccess() {
        UUID id=own(); jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        assertThat(service.read(principal,id).readAt()).isNotNull();
        jdbc.update("UPDATE event SET state='DRAFT' WHERE id=?",event);
        assertThat(list(null,30).items()).isEmpty(); denied(()->service.read(principal,id),ErrorCode.NOT_FOUND);
    }
    @Test void taskReassignmentAndChiefRoleRevocationRemovePrivateTarget() {
        UUID task=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,title,start_at,end_at,steps,checklist,status)
                VALUES (?, ?, ?, 'fixture', ?, ?, 'private-task', now(), now(), '[]', '[]', 'WAITING')
                """,task,event,group,member,zone);
        UUID id=own(); jdbc.update("UPDATE notification SET type='TASK',task_id=? WHERE id=?",task,id);
        assertThat(list(null,30).items().getFirst().target()).isEqualTo(new NotificationView.Target("TASK",task));
        jdbc.update("UPDATE task SET assignee_id=? WHERE id=?",otherMember,task);
        assertThat(list(null,30).items()).isEmpty(); denied(()->service.read(principal,id),ErrorCode.NOT_FOUND);
        jdbc.update("UPDATE member SET event_role='CHIEF' WHERE id=?",member);
        assertThat(list(null,30).items()).hasSize(1);
        jdbc.update("UPDATE member SET event_role='WORKER' WHERE id=?",member);
        assertThat(list(null,30).items()).isEmpty();
    }
    @Test void readingChangeNotificationNeverAcknowledgesChanges() {
        UUID change=UUID.randomUUID(), ack=UUID.randomUUID(), id=own();
        jdbc.update("""
                INSERT INTO change_set (id,event_id,mode,status,revision,base_revision,operations,rationale)
                VALUES (?, ?, 'MANUAL', 'APPLIED', 1, 7, '[]', 'fixture')
                """,change,event);
        jdbc.update("""
                INSERT INTO change_acknowledgment (id,event_id,change_set_id,member_id,participation_revision,targets,targets_hash)
                VALUES (?, ?, ?, ?, 1, '[]', 'fixture-hash')
                """,ack,event,change,member);
        jdbc.update("UPDATE notification SET type='CHANGE_ACK',acknowledgment_id=?,change_set_id=? WHERE id=?",ack,change,id);
        var before=jdbc.queryForMap("SELECT * FROM change_acknowledgment WHERE id=?",ack);
        assertThat(service.read(principal,id).target()).isEqualTo(new NotificationView.Target("CHANGE_ACK",ack));
        service.read(principal,id);
        assertThat(jdbc.queryForMap("SELECT * FROM change_acknowledgment WHERE id=?",ack)).isEqualTo(before);
        jdbc.update("UPDATE change_acknowledgment SET participation_revision=2 WHERE id=?",ack);
        assertThat(list(null,30).items()).isEmpty();
    }
    @Test void issueTargetIsOnlyProjectedWithinCurrentOwnScope() {
        UUID issue=UUID.randomUUID(), id=own();
        jdbc.update("""
                INSERT INTO issue (id,event_id,work_group_id,zone_id,title,reporter_member_id,status,created_at)
                VALUES (?, ?, ?, ?, 'private-issue', ?, 'RECEIVED', now())
                """,issue,event,group,zone,member);
        jdbc.update("UPDATE notification SET type='ISSUE',payload=?::jsonb WHERE id=?",
                "{\"target\":{\"id\":\""+issue+"\"},\"body\":\"private-report\"}",id);
        var view=service.read(principal,id);
        assertThat(view.target()).isEqualTo(new NotificationView.Target("ISSUE",issue));
        assertThat(view.body()).isEmpty(); assertThat(view.title()).doesNotContain("private");
        jdbc.update("UPDATE issue SET reporter_member_id=? WHERE id=?",otherMember,issue);
        assertThat(list(null,30).items()).isEmpty(); denied(()->service.read(principal,id),ErrorCode.NOT_FOUND);
        jdbc.update("UPDATE notification SET payload='{\"target\":{\"id\":\"not-a-uuid\"}}'::jsonb WHERE id=?",id);
        assertThat(list(null,30).items()).isEmpty();
    }
    @ParameterizedTest
    @ValueSource(strings={"FOLLOWUP", "RESOLUTION"})
    void ownReportKeepsIssueNotificationVisibleAndReadableAfterTaskReassignment(String kind) throws Exception {
        UUID issue=issueWithOwnReportAfterTaskReassignment(kind);
        UUID id=own();
        jdbc.update("UPDATE notification SET type='ISSUE',payload=?::jsonb WHERE id=?",
                "{\"target\":{\"id\":\""+issue+"\"},\"body\":\"private-report\"}",id);
        var reportsBefore=jdbc.queryForList("SELECT * FROM report WHERE issue_id=? ORDER BY id",issue);
        String token=access("WEB");
        var issueResponse=request("GET","/api/v1/issues/"+issue,token,true,true);
        assertThat(issueResponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(issueResponse.body()).get("reports").size()).isEqualTo(1);
        var page=request("GET","/api/v1/me/notifications",token,true,true);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(json.readTree(page.body()).get("items").size()).isEqualTo(1);
        var item=json.readTree(page.body()).get("items").get(0);
        assertThat(item.get("id").asString()).isEqualTo(id.toString());
        assertThat(item.get("target").get("id").asString()).isEqualTo(issue.toString());
        assertThat(item.get("body").asString()).isEmpty();
        assertThat(page.body()).doesNotContain("private-report","private-issue","private-initial","private-task");
        var first=request("POST","/api/v1/notifications/"+id+"/read",token,true,true);
        assertThat(first.statusCode()).isEqualTo(200);
        Instant firstReadAt=readAt(id);
        assertThat(firstReadAt).isNotNull();
        assertThat(Instant.parse(json.readTree(first.body()).get("readAt").asString())).isEqualTo(firstReadAt);
        var repeated=request("POST","/api/v1/notifications/"+id+"/read",token,true,true);
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(repeated.body()).isEqualTo(first.body());
        assertThat(readAt(id)).isEqualTo(firstReadAt);
        assertThat(jdbc.queryForList("SELECT * FROM report WHERE issue_id=? ORDER BY id",issue)).isEqualTo(reportsBefore);
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(7L);

        jdbc.update("UPDATE member SET active=false WHERE id=?",member);
        assertThat(json.readTree(request("GET","/api/v1/me/notifications",token,true,true).body()).get("items").size()).isZero();
        assertError(request("POST","/api/v1/notifications/"+id+"/read",token,true,true),404,"NOT_FOUND");
        jdbc.update("UPDATE member SET active=true,participation_revision=2 WHERE id=?",member);
        assertThat(list(null,30).items()).isEmpty();
        assertError(request("POST","/api/v1/notifications/"+id+"/read",token,true,true),404,"NOT_FOUND");
        assertThat(readAt(id)).isEqualTo(firstReadAt);
    }

    @Test void ownReportDoesNotGrantAnotherIssueEventOrNotificationOwnerAccess() {
        UUID allowed=issueWithOwnReportAfterTaskReassignment("FOLLOWUP"), unrelated=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO issue (id,event_id,work_group_id,zone_id,title,reporter_member_id,status,created_at)
                VALUES (?, ?, ?, ?, 'private-issue', ?, 'RECEIVED', now())
                """,unrelated,event,group,zone,otherMember);
        UUID foreignOwner=notification(otherMember,event,1,time), wrongEvent=notification(member,otherEvent,1,time), wrongIssue=own();
        for(UUID id:List.of(foreignOwner,wrongEvent,wrongIssue)) {
            UUID target=id.equals(wrongIssue)?unrelated:allowed;
            jdbc.update("UPDATE notification SET type='ISSUE',payload=?::jsonb WHERE id=?",
                    "{\"target\":{\"id\":\""+target+"\"}}",id);
            denied(()->service.read(principal,id),ErrorCode.NOT_FOUND);
            assertThat(readAt(id)).isNull();
        }
        assertThat(list(null,30).items()).isEmpty();
    }

    private UUID issueWithOwnReportAfterTaskReassignment(String kind) {
        UUID issue=UUID.randomUUID(), task=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO issue (id,event_id,work_group_id,zone_id,title,reporter_member_id,status,created_at)
                VALUES (?, ?, ?, ?, 'private-issue', ?, 'RECEIVED', now())
                """,issue,event,group,zone,otherMember);
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,title,start_at,end_at,steps,checklist,status,source_issue_id)
                VALUES (?, ?, ?, 'fixture', ?, ?, 'private-task', now(), now()+interval '1 hour', '[]', '[]', 'WAITING', ?)
                """,task,event,group,member,zone,issue);
        for(UUID author:List.of(otherMember,member)) {
            String text=author.equals(member)?"private-report":"private-initial";
            jdbc.update("""
                    INSERT INTO report (id,issue_id,author_member_id,participation_revision,kind,text,normalized_text,created_at)
                    VALUES (?, ?, ?, 1, ?, ?, ?, now())
                    """,UUID.randomUUID(),issue,author,author.equals(member)?kind:"INITIAL",text,text);
        }
        jdbc.update("UPDATE task SET assignee_id=NULL WHERE id=?",task);
        return issue;
    }

    /** Latest develop's real 076/077 routes must stay independent from 074/075. */
    @Test void officialAcknowledgmentAndNotificationReadRemainIndependentOverHttp() throws Exception {
        UUID change=UUID.randomUUID(), ack=UUID.randomUUID(), id=own();
        jdbc.update("""
                INSERT INTO change_set (id,event_id,mode,status,revision,base_revision,operations,rationale,applied_at)
                VALUES (?, ?, 'MANUAL', 'APPLIED', 1, 7, '[]', 'fixture', now())
                """,change,event);
        String targets="[{\"taskId\":\""+UUID.randomUUID()+"\",\"title\":\"private-task\","
                +"\"changeKinds\":[\"TIME\"],\"removed\":true,\"changedAt\":\""+time+"\"}]";
        jdbc.update("""
                INSERT INTO change_acknowledgment (id,event_id,change_set_id,member_id,participation_revision,targets,targets_hash)
                VALUES (?, ?, ?, ?, 1, ?::jsonb, 'fixture-hash')
                """,ack,event,change,member,targets);
        jdbc.update("UPDATE notification SET type='CHANGE_ACK',acknowledgment_id=?,change_set_id=? WHERE id=?",ack,change,id);
        String token=access("WEB");
        var pending=request("GET","/api/v1/events/"+event+"/my-changes",token,true,true);
        assertThat(pending.statusCode()).isEqualTo(200);
        assertThat(json.readTree(pending.body()).get("items").get(0).get("state").asString()).isEqualTo("PENDING");
        var before=jdbc.queryForMap("SELECT * FROM change_acknowledgment WHERE id=?",ack);
        assertThat(request("POST","/api/v1/notifications/"+id+"/read",token,true,true).statusCode()).isEqualTo(200);
        Instant firstRead=readAt(id);
        assertThat(jdbc.queryForMap("SELECT * FROM change_acknowledgment WHERE id=?",ack)).isEqualTo(before);
        UUID unread=own();
        jdbc.update("UPDATE notification SET type='CHANGE_ACK',acknowledgment_id=?,change_set_id=? WHERE id=?",ack,change,unread);
        var confirm=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/change-acknowledgments/"+ack+"/ack"))
                .header("Cookie","__Secure-sc-access="+token).header("Origin","https://web.example.invalid")
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"targetsHash\":\"fixture-hash\"}")).build();
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(confirm,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).get("state").asString()).isEqualTo("ACKNOWLEDGED");
        }
        var acknowledged=jdbc.queryForMap("SELECT * FROM change_acknowledgment WHERE id=?",ack);
        assertThat(acknowledged.get("acknowledged_at")).isNotNull();
        assertThat(readAt(id)).isEqualTo(firstRead);
        assertThat(readAt(unread)).isNull(); // Official confirmation does not mark the inbox as read either.
        assertThat(service.read(principal,unread).readAt()).isNotNull();
        assertThat(request("POST","/api/v1/notifications/"+id+"/read",token,true,true).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT * FROM change_acknowledgment WHERE id=?",ack)).isEqualTo(acknowledged);
        assertThat(readAt(id)).isEqualTo(firstRead);
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(7);
    }
    @Test void concurrentDuplicateReadsHaveOneStoredTimestamp() throws Exception {
        UUID id=own(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<NotificationView> call=()->{start.await(); return service.read(principal,id);};
            var a=pool.submit(call); var b=pool.submit(call); start.countDown();
            var first=a.get(10,TimeUnit.SECONDS); assertThat(b.get(10,TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(readAt(id)).isEqualTo(first.readAt());
        }
    }
    @Test void revocationCommittedWhileReadWaitsIsRechecked() throws Exception {
        UUID id=own();
        try(var pool=Executors.newSingleThreadExecutor(); var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("UPDATE member SET active=false WHERE id=?")) {
                statement.setObject(1,member); statement.executeUpdate();
            }
            var result=pool.submit(()->service.read(principal,id));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<until && jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%FOR SHARE OF m%'",Long.class)==0)
                Thread.sleep(20);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%FOR SHARE OF m%'",Long.class)).isGreaterThan(0);
            connection.commit();
            assertThatThrownBy(()->result.get(10,TimeUnit.SECONDS)).cause().isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
            assertThat(readAt(id)).isNull();
        }
    }
    @Test void deferredCommitFailureRollsBackReadBeforeReturningSuccess() {
        UUID id=own(); String name="notification_failure_"+id.toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic'; END $$");
        try {
            jdbc.execute("CREATE CONSTRAINT TRIGGER "+name+" AFTER UPDATE ON notification DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.id='"+id+"'::uuid) EXECUTE FUNCTION "+name+"()");
            try { denied(()->service.read(principal,id),ErrorCode.SERVICE_UNAVAILABLE); assertThat(readAt(id)).isNull(); }
            finally { jdbc.execute("DROP TRIGGER "+name+" ON notification"); }
        } finally { jdbc.execute("DROP FUNCTION "+name+"()"); }
    }

    String access(String channel) {
        var verified=new LocalCredentialVerifier.VerifiedAccount(account,"fixture",identity+"@example.invalid",3,identity);
        var tokens=issuer.issue(verified); sessions.register(verified,tokens,channel); sessionIds.add(tokens.sessionId());
        return tokens.accessToken();
    }
    HttpResponse<String> request(String method,String path,String token,boolean web,boolean includeWebOrigin) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(10));
        if(token!=null) {
            if(web) builder.header("Cookie","__Secure-sc-access="+token);
            else builder.header("Authorization","Bearer "+token).header("Origin","https://android.example.invalid");
        }
        if(method.equals("POST")) {
            if(web && includeWebOrigin) builder.header("Origin","https://web.example.invalid");
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else builder.GET();
        try(var client=HttpClient.newHttpClient()) { return client.send(builder.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    @Test void httpWebAndAndroidUseExistingSessionAuthenticationAndWire() throws Exception {
        UUID id=own(); String web=access("WEB"), android=access("ANDROID");
        for(boolean isWeb:List.of(true,false)) {
            String token=isWeb?web:android;
            var listed=request("GET","/api/v1/me/notifications",token,isWeb,true);
            assertThat(listed.statusCode()).isEqualTo(200);
            var item=json.readTree(listed.body()).get("items").get(0);
            assertThat(item.size()).isEqualTo(8);
            assertThat(item.get("target").size()).isEqualTo(2);
            assertThat(item.get("id").asString()).isEqualTo(id.toString());
            assertThat(item.get("participationRevision").isString()).isTrue();
            assertThat(item.get("readAt").isNull()).isEqualTo(isWeb);
            assertThat(listed.headers().firstValue("X-Request-Id")).isPresent();
            var read=request("POST","/api/v1/notifications/"+id+"/read",token,isWeb,true);
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(Instant.parse(json.readTree(read.body()).get("readAt").asString())).isEqualTo(readAt(id));
        }
    }
    @Test void httpRejectsAnonymousRevokedSessionMissingOriginAndInvalidInput() throws Exception {
        UUID id=own(); String web=access("WEB");
        assertThat(request("GET","/api/v1/me/notifications",null,true,true).statusCode()).isEqualTo(401);
        assertThat(request("POST","/api/v1/notifications/"+id+"/read",web,true,false).statusCode()).isEqualTo(403);
        assertThat(readAt(id)).isNull();
        for(String query:List.of("?limit=0","?limit=101","?limit=abc","?cursor=broken"))
            assertThat(request("GET","/api/v1/me/notifications"+query,web,true,true).statusCode()).isEqualTo(400);
        assertThat(request("POST","/api/v1/notifications/not-uuid/read",web,true,true).statusCode()).isEqualTo(400);
        redis.delete(RedisLoginSessions.key(sessionIds.getFirst()));
        var expired=request("GET","/api/v1/me/notifications",web,true,true);
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(json.readTree(expired.body()).get("code").asString()).isEqualTo("UNAUTHENTICATED");
    }
    @Test void httpCannotReadAnotherUsersNotification() throws Exception {
        UUID id=notification(otherMember,event,1,time);
        var response=request("POST","/api/v1/notifications/"+id+"/read",access("WEB"),true,true);
        assertThat(response.statusCode()).isEqualTo(404); assertThat(readAt(id)).isNull();
    }
    @Test void httpBodylessReadRequiresExactSingleWebOriginButNoCsrfMarker() throws Exception {
        UUID id=own(); String token=access("WEB");
        for(String origin:List.of("null", "https://web.example.invalid.evil.invalid", "http://web.example.invalid",
                "https://web.example.invalid:444", "https://evil.example.invalid", "https://web.example.invalid/")) {
            var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/notifications/"+id+"/read"))
                    .header("Cookie","__Secure-sc-access="+token).header("Origin",origin).POST(HttpRequest.BodyPublishers.noBody());
            try(var client=HttpClient.newHttpClient()) {
                assertThat(client.send(builder.build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            }
            assertThat(readAt(id)).isNull();
        }
        var duplicate=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/notifications/"+id+"/read"))
                .header("Cookie","__Secure-sc-access="+token).header("Origin","https://web.example.invalid")
                .header("Origin","https://web.example.invalid").POST(HttpRequest.BodyPublishers.noBody()).build();
        try(var client=HttpClient.newHttpClient()) {
            assertThat(client.send(duplicate,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
        assertThat(request("POST","/api/v1/notifications/"+id+"/read",token,true,false).statusCode()).isEqualTo(403);
        var first=request("POST","/api/v1/notifications/"+id+"/read",token,true,true);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(request("POST","/api/v1/notifications/"+id+"/read",token,true,true).body()).isEqualTo(first.body());
    }
    @Test void httpExpiredAccessAndAccountRevocationAreUnauthorized() throws Exception {
        UUID id=own();
        var verified=new LocalCredentialVerifier.VerifiedAccount(account,"fixture",identity+"@example.invalid",3,identity);
        var expiredIssuer=new LoginTokenIssuer(new javax.crypto.spec.SecretKeySpec(KEY,"HmacSHA256"),
                Clock.offset(Clock.systemUTC(),Duration.ofHours(-2)),"notification-test","notification-test");
        var expired=expiredIssuer.issue(verified); sessions.register(verified,expired,"WEB"); sessionIds.add(expired.sessionId());
        assertThat(request("POST","/api/v1/notifications/"+id+"/read",expired.accessToken(),true,true).statusCode()).isEqualTo(401);
        String current=access("WEB"); jdbc.update("UPDATE account SET token_version=4 WHERE id=?",account);
        assertThat(request("POST","/api/v1/notifications/"+id+"/read",current,true,true).statusCode()).isEqualTo(401);
        assertThat(readAt(id)).isNull();
    }
    @Test void httpTransportStillRejectsMixedCredentialsAndWrongStoredChannel() throws Exception {
        UUID id=own(); String web=access("WEB"), android=access("ANDROID");
        assertThat(request("GET","/api/v1/me/notifications",web,false,true).statusCode()).isEqualTo(403);
        assertThat(request("GET","/api/v1/me/notifications",android,true,true).statusCode()).isEqualTo(403);
        var mixed=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/me/notifications"))
                .header("Cookie","__Secure-sc-access="+web).header("Authorization","Bearer "+android).GET().build();
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(mixed,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("MIXED_AUTH_CREDENTIALS");
        }
        assertThat(readAt(id)).isNull();
    }
    @Test void httpErrorsKeepSharedEnvelopeAndAndroidChallenge() throws Exception {
        UUID mine=own(), foreign=notification(otherMember,event,1,time); String web=access("WEB");
        assertError(request("GET","/api/v1/me/notifications?cursor=broken",web,true,true),400,"INVALID_REQUEST");
        assertError(request("GET","/api/v1/me/notifications",null,true,true),401,"UNAUTHENTICATED");
        assertError(request("POST","/api/v1/notifications/"+mine+"/read",web,true,false),403,"FORBIDDEN");
        assertError(request("POST","/api/v1/notifications/"+foreign+"/read",web,true,true),404,"NOT_FOUND");
        String android=access("ANDROID"); redis.delete(RedisLoginSessions.key(sessionIds.getLast()));
        var revoked=request("GET","/api/v1/me/notifications",android,false,true);
        assertError(revoked,401,"UNAUTHENTICATED");
        assertThat(revoked.headers().firstValue("WWW-Authenticate")).contains("Bearer error=\"invalid_token\"");
        assertThat(readAt(mine)).isNull(); assertThat(readAt(foreign)).isNull();
    }
    @Test void androidPreflightAndUnauthorizedResponseUseExistingCorsPolicy() throws Exception {
        String origin="https://android.example.invalid";
        var preflight=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/notifications/"+own()+"/read"))
                .header("Origin",origin).header("Access-Control-Request-Method","POST")
                .header("Access-Control-Request-Headers","Authorization,X-SceneCrew-Client")
                .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(preflight,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
            assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
            assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow())
                    .containsIgnoringCase("Authorization").doesNotContain("X-SceneCrew-CSRF");
        }
        String android=access("ANDROID"); redis.delete(RedisLoginSessions.key(sessionIds.getLast()));
        var revoked=request("GET","/api/v1/me/notifications",android,false,true);
        assertError(revoked,401,"UNAUTHENTICATED");
        assertThat(revoked.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
        assertThat(revoked.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        String token=access("ANDROID");
        var invalid=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/me/notifications"))
                .header("Origin","https://android.example.invalid:444").header("Authorization","Bearer "+token).GET().build();
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(invalid,HttpResponse.BodyHandlers.ofString());
            assertError(response,403,"AUTH_CHANNEL_MISMATCH");
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        }
    }
    private void assertError(HttpResponse<String> response,int status,String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body=json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("message").asString()).isNotBlank();
        assertThat(body.get("requestId").asString()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(body.size()).isEqualTo(3);
        assertThat(response.body()).doesNotContain("private-report","private-task","private-name","payload","tokenVersion");
    }
}

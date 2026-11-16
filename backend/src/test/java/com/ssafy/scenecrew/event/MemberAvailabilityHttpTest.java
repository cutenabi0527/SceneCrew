package com.ssafy.scenecrew.event;

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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.event.service.MemberAvailabilityService;
import com.ssafy.scenecrew.invite.service.InviteJoinService;
import static org.assertj.core.api.Assertions.*;

/** 전용 PostgreSQL/Redis와 실제 HTTP. Docker가 없으면 성공/skip으로 대신하지 않고 실패한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MemberAvailabilityHttpTest.Encoder.class)
class MemberAvailabilityHttpTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final byte[] SECRET = new byte[32];
    static { new java.security.SecureRandom().nextBytes(SECRET); }
    static final String WEB = "https://web.example.invalid", ANDROID = "https://android.example.invalid";
    static final String PASSWORD = "Availability8!";
    @TestConfiguration(proxyBeanMethods = false) static class Encoder {
        @Bean BCryptPasswordEncoder testOnlyEncoder() { return new BCryptPasswordEncoder(4); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        POSTGRES.start(); REDIS.start();
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.data.redis.timeout", () -> "500ms");
        r.add("spring.data.redis.connect-timeout", () -> "500ms");
        r.add("scenecrew.auth.public-enabled", () -> "true");
        r.add("scenecrew.auth.signing-key-base64", () -> Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.failure-hmac-key-base64", () -> Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.issuer", () -> "availability-test");
        r.add("scenecrew.auth.audience", () -> "availability-test");
        r.add("scenecrew.auth.web-origins", () -> WEB);
        r.add("scenecrew.auth.android-origins", () -> ANDROID);
    }
    @AfterAll static void stop() { REDIS.stop(); POSTGRES.stop(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionAuthenticator authenticator;
    @Autowired PlatformTransactionManager manager;
    @Autowired MemberAvailabilityService service;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired org.springframework.core.env.Environment environment;
    static boolean fixtureReported;
    @LocalServerPort int port;
    UUID event, group, account, identity, member, chiefAccount, chief, zone;
    String token;
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @BeforeEach void fixture() {
        assertThat(environment.getProperty("spring.data.redis.port")).isEqualTo(REDIS.getMappedPort(6379).toString());
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(POSTGRES.getJdbcUrl());
        if (!fixtureReported) {
            System.out.printf("AVAILABILITY_FIXTURE normal context=%s postgres=%s redis=%s redisPort=%s%n",
                    System.identityHashCode(context), POSTGRES.getContainerId(), REDIS.getContainerId(), REDIS.getMappedPort(6379));
            fixtureReported = true;
        }
        event = UUID.randomUUID(); group = UUID.randomUUID(); zone = UUID.randomUUID();
        account = UUID.randomUUID(); identity = UUID.randomUUID(); member = UUID.randomUUID();
        chiefAccount = UUID.randomUUID(); chief = UUID.randomUUID(); token = "fixture-" + UUID.randomUUID();
        account(account, identity); account(chiefAccount, UUID.randomUUID());
        jdbc.update("INSERT INTO event(id,name,state,revision,total_capacity,invite_token_hash,last_input_modified_at) VALUES (?,'availability','LIVE',10,31,?,now())",
                event, InviteJoinService.tokenHash(token));
        jdbc.update("INSERT INTO work_group(id,event_id,name,instructions,active) VALUES (?,?,'group','',true)", group,event);
        jdbc.update("INSERT INTO zone(id,event_id,name,aliases) VALUES (?,?,'zone','[]')",zone,event);
        member(member,account,"WORKER",2); member(chief,chiefAccount,"CHIEF",1);
    }
    void account(UUID id, UUID authId) {
        jdbc.update("INSERT INTO account VALUES (?,'fixture','ACTIVE',true,3,'{}',now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?,?,'LOCAL',?,?,?,true)",authId,id,id.toString(),encoder.encode(PASSWORD),id+"@example.invalid");
    }
    void member(UUID id, UUID owner, String role, long number) {
        jdbc.update("INSERT INTO member VALUES (?,?,?,?,?,?,'fixture',true,false,true,1)",id,event,owner,group,number,role);
    }
    long revision() { return jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event); }
    boolean available() { return jdbc.queryForObject("SELECT available FROM member WHERE id=?",Boolean.class,member); }
    String body(boolean value,long revision) { return "{\"available\":"+value+",\"expectedEventRevision\":\""+revision+"\"}"; }
    String path(UUID id) { return "/api/v1/members/"+id+"/availability"; }

    @ParameterizedTest @ValueSource(strings = {"WEB","ANDROID"})
    void realAuthenticationChangeNoopStaleAndChangeBack(String channel) throws Exception {
        var c = login(account,channel);
        var result = change(c,member,false,10); success(result,11,member);
        assertThat(available()).isFalse(); assertThat(revision()).isEqualTo(11);
        success(change(c,member,false,11),11,member);
        error(change(c,member,false,10),409,"VERSION_CONFLICT");
        assertThat(available()).isFalse(); assertThat(revision()).isEqualTo(11);
        success(change(c,member,true,11),12,member);
        // 다시 가용해지면 같은 트랜잭션에 AI 최초 배정 트리거만 남긴다(#267). 작업 접수는 outbox 처리기가 따로 한다.
        assertThat(available()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isZero();
        assertThat(jdbc.queryForList("SELECT type FROM outbox_event WHERE event_id=?",String.class,event))
                .containsExactly("ASSIGN_INITIAL_TRIGGER");
        var rows=json.readTree(send(request("/api/v1/events/"+event+"/members").GET()
                .header("Origin",WEB).header("Cookie",login(chiefAccount,"WEB").value())).body());
        assertThat(rows.toString()).contains(member.toString(),"\"available\":true");
    }

    @Test void chiefCanChangeOnlyOwnAvailability() throws Exception {
        var c=login(chiefAccount,"WEB");
        success(change(c,chief,false,10),11,chief);
        error(change(c,member,false,11),403,"FORBIDDEN");
        assertThat(available()).isTrue(); assertThat(revision()).isEqualTo(11);
    }

    @Test void noopIssuesNoMemberOrEventUpdate() throws Exception {
        var c=login(account,"WEB");
        String name="noop_guard_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'unexpected update'; END $$");
        try {
            jdbc.execute("CREATE TRIGGER "+name+" BEFORE UPDATE ON member FOR EACH ROW WHEN (OLD.id='"+member+"'::uuid) EXECUTE FUNCTION "+name+"()");
            try {
                jdbc.execute("CREATE TRIGGER "+name+" BEFORE UPDATE ON event FOR EACH ROW WHEN (OLD.id='"+event+"'::uuid) EXECUTE FUNCTION "+name+"()");
                try {
                    success(change(c,member,true,10),10,member);
                    error(change(c,member,true,9),409,"VERSION_CONFLICT");
                    assertThat(revision()).isEqualTo(10); assertNoFollowup();
                } finally { jdbc.execute("DROP TRIGGER "+name+" ON event"); }
            } finally { jdbc.execute("DROP TRIGGER "+name+" ON member"); }
        } finally { jdbc.execute("DROP FUNCTION "+name+"()"); }
    }

    @Test void sameEventOtherAccountOtherEventUnknownAndInactiveAreDenied() throws Exception {
        var c=login(account,"ANDROID");
        error(change(c,chief,false,10),403,"FORBIDDEN");
        UUID otherEvent=UUID.randomUUID(),otherMember=UUID.randomUUID();
        jdbc.update("INSERT INTO event(id,name,state,revision,last_input_modified_at) VALUES (?,'other','LIVE',10,now())",otherEvent);
        jdbc.update("INSERT INTO member VALUES (?,?,?,null,1,'CHIEF','other',true,false,true,1)",otherMember,otherEvent,chiefAccount);
        error(change(c,otherMember,false,10),404,"NOT_FOUND");
        error(change(c,UUID.randomUUID(),false,10),404,"NOT_FOUND");
        jdbc.update("UPDATE member SET active=false WHERE id=?",member);
        error(change(c,member,false,10),404,"NOT_FOUND");
        assertThat(available()).isTrue(); assertThat(revision()).isEqualTo(10); assertNoFollowup();
    }

    @ParameterizedTest @ValueSource(strings = {"RECRUITING","LIVE"})
    void supportedEventStatesAllowChange(String state) throws Exception {
        jdbc.update("UPDATE event SET state=? WHERE id=?",state,event);
        success(change(login(account,"WEB"),member,false,10),11,member);
    }
    @Test void endedRejectsEvenNoop() throws Exception {
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        error(change(login(account,"WEB"),member,true,10),409,"INVALID_STATE");
        assertThat(revision()).isEqualTo(10); assertThat(available()).isTrue();
    }
    @Test void provisionalDraftRejectionDoesNotRepresentTeamApproval() throws Exception {
        jdbc.update("UPDATE event SET state='DRAFT' WHERE id=?",event);
        error(change(login(chiefAccount,"WEB"),chief,false,10),409,"INVALID_STATE");
        assertThat(revision()).isEqualTo(10);
    }

    @Test void malformedHttpInputsAndIgnoredIdempotencyKey() throws Exception {
        var c=login(account,"WEB");
        for(String bad:List.of("{}","null","{\"available\":null,\"expectedEventRevision\":\"10\"}",
                "{\"available\":\"false\",\"expectedEventRevision\":\"10\"}",
                "{\"available\":false,\"expectedEventRevision\":10}",
                "{\"available\":false,\"expectedEventRevision\":\"9223372036854775808\"}",
                "{\"available\":false,\"expectedEventRevision\":\"10\",\"isReserve\":true}"))
            error(send(patch(path(member),bad,c)),400,"INVALID_REQUEST");
        error(send(patch("/api/v1/members/1-1-1-1-1/availability",body(false,10),c)),400,"INVALID_REQUEST");
        error(send(patch(path(member),body(false,10),c).setHeader("Content-Type","text/plain")),415,"UNSUPPORTED_MEDIA_TYPE");
        success(send(patch(path(member),body(false,10),c).header("Idempotency-Key","deliberately-not-a-uuid")),11,member);
        assertThat(available()).isFalse(); assertNoFollowup();
    }

    @Test void transportProtectionUsesBusinessWriteWithoutExpandingH01() throws Exception {
        var web=login(account,"WEB"); var android=login(account,"ANDROID");
        error(send(request(path(member)).header("Content-Type","application/json").header(web.header(),web.value())
                .method("PATCH",HttpRequest.BodyPublishers.ofString(body(false,10)))),403,"CSRF_REJECTED");
        error(send(patch(path(member),body(false,10),web).setHeader("Origin","https://wrong.example.invalid")),403,"CSRF_REJECTED");
        error(send(patch(path(member),body(false,10),android).setHeader("Origin",WEB)),403,"AUTH_CHANNEL_MISMATCH");
        error(send(patch(path(member),body(false,10),web).header(android.header(),android.value())),400,"MIXED_AUTH_CREDENTIALS");
        String token=web.value().substring(web.value().indexOf('=')+1);
        error(send(patch(path(member),body(false,10),new Credentials("Authorization","Bearer "+token,ANDROID))),403,"AUTH_CHANNEL_MISMATCH");
        // V9-D34: 039 업무 쓰기는 H01 헤더를 요구하지 않는다. Origin 검사는 위에서 유지한다.
        success(change(web,member,false,10),11,member);
    }

    @Test void absentInvalidRevokedAndInactiveAccountSessionsCannotWrite() throws Exception {
        error(send(request(path(member)).header("Origin",ANDROID).header("Content-Type","application/json")
                .method("PATCH",HttpRequest.BodyPublishers.ofString(body(false,10)))),401,"UNAUTHENTICATED");
        error(change(new Credentials("Authorization","Bearer invalid",ANDROID),member,false,10),401,"UNAUTHENTICATED");
        var c=login(account,"ANDROID");
        var principal=authenticator.authenticate(c.value().substring(7));
        redis.delete(RedisLoginSessions.key(principal.sessionId()));
        error(change(c,member,false,10),401,"UNAUTHENTICATED");
        var next=login(account,"WEB");
        jdbc.update("UPDATE account SET status='INACTIVE' WHERE id=?",account);
        error(change(next,member,false,10),401,"UNAUTHENTICATED");
        assertThat(available()).isTrue(); assertThat(revision()).isEqualTo(10);
    }

    @Test void realRejoinReusesIdButOldRevisionCannotWriteEvenSameValue() throws Exception {
        var c=login(account,"WEB");
        // 이탈 API는 기준 develop에 없다. 이탈 부분만 Event 우선 fixture writer로 표현한다.
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT revision FROM event WHERE id=? FOR UPDATE",Long.class,event);
            jdbc.update("UPDATE member SET active=false WHERE id=?",member);
            jdbc.update("UPDATE event SET revision=revision+1 WHERE id=?",event);
        });
        error(change(c,member,true,10),404,"NOT_FOUND");
        var joinBody="{\"workGroupId\":\""+group+"\",\"available\":true,\"isReserve\":false,\"expectedEventRevision\":\"11\"}";
        var joined=send(request("/api/v1/invites/"+token+"/join").header(c.header(),c.value()).header("Origin",c.origin())
                .header("X-SceneCrew-CSRF","1").header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(joinBody)));
        assertThat(joined.statusCode()).isEqualTo(200);
        var p=json.readTree(joined.body()).get("participation");
        assertThat(p.get("memberId").asString()).isEqualTo(member.toString());
        assertThat(p.get("participationRevision").asString()).isEqualTo("2");
        assertThat(revision()).isEqualTo(12);
        error(change(c,member,true,10),409,"VERSION_CONFLICT");
        success(change(c,member,false,12),13,member);
    }

    @ParameterizedTest @ValueSource(strings = {"WAITING","IN_PROGRESS","DONE","CANCELED"})
    void allExistingTaskAndMemberEvidenceSurvives(String state) throws Exception {
        UUID task=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task(id,event_id,work_group_id,bundle_key,assignee_id,zone_id,title,start_at,end_at,steps,checklist,status,
                                 first_assigned_at,actual_started_at,actual_ended_at)
                VALUES (?,?,?,'bundle',?,?,'task',now(),now()+interval '1 hour','[]','[]',?,now()-interval '1 hour',now(),now())""",
                task,event,group,member,zone,state);
        UUID log=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO operation_log(id,event_id,type,actor_member_id,resource_type,resource_id,changes,occurred_at)
                VALUES (?,?,'INITIAL_ASSIGNMENT',?,'TASK',?,'{"participationRevision":"1"}',now())""",log,event,chief,task);
        var evidenceBefore=jdbc.queryForMap("SELECT * FROM operation_log WHERE id=?",log);
        jdbc.update("UPDATE member SET is_reserve=true WHERE id=?",member);
        var before=jdbc.queryForMap("SELECT * FROM task WHERE id=?",task);
        var memberBefore=jdbc.queryForMap("SELECT * FROM member WHERE id=?",member); memberBefore.remove("available");
        success(change(login(account,"ANDROID"),member,false,10),11,member);
        assertThat(jdbc.queryForMap("SELECT * FROM task WHERE id=?",task)).isEqualTo(before);
        var after=jdbc.queryForMap("SELECT * FROM member WHERE id=?",member); after.remove("available");
        assertThat(after).isEqualTo(memberBefore); assertNoFollowup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_log WHERE event_id=?",Long.class,event)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM operation_log WHERE id=?",log)).isEqualTo(evidenceBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_acknowledgment WHERE event_id=?",Long.class,event)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"member_immediate","event_immediate","event_deferred"})
    void storageAndCommitFailuresRollBackBothRows(String scenario) throws Exception {
        var c=login(account,"WEB");
        String table=scenario.startsWith("member")?"member":"event";
        String name="availability_fail_"+UUID.randomUUID().toString().replace("-","");
        UUID id=table.equals("member")?member:event;
        jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        try {
            String trigger=scenario.endsWith("deferred")?"CREATE CONSTRAINT TRIGGER "+name+" AFTER UPDATE ON "+table+" DEFERRABLE INITIALLY DEFERRED"
                    :"CREATE TRIGGER "+name+" AFTER UPDATE ON "+table;
            jdbc.execute(trigger+" FOR EACH ROW WHEN (NEW.id='"+id+"'::uuid) EXECUTE FUNCTION "+name+"()");
            try {
                error(change(c,member,false,10),503,"SERVICE_UNAVAILABLE");
                assertThat(available()).isTrue(); assertThat(revision()).isEqualTo(10); assertNoFollowup();
            } finally { jdbc.execute("DROP TRIGGER "+name+" ON "+table); }
        } finally { jdbc.execute("DROP FUNCTION "+name+"()"); }
        success(change(c,member,false,10),11,member);
    }

    @Test void twoRealChangesAtSameRevisionCommitOnlyOnce() throws Exception { compete(false,false,200,409); }
    @Test void noopThenRealChangeCanBothSucceed() throws Exception { compete(true,false,200,200); }
    @Test void realChangeThenOldNoopConflicts() throws Exception { compete(false,true,200,409); }
    void compete(boolean firstValue,boolean secondValue,int firstCode,int secondCode) throws Exception {
        var c=login(account,"ANDROID");
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var gate=pool.submit(() -> new TransactionTemplate(manager).execute(s -> {
                jdbc.queryForObject("SELECT revision FROM event WHERE id=? FOR UPDATE",Long.class,event);
                locked.countDown(); await(release); return null;
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                var first=pool.submit(() -> change(c,member,firstValue,10)); awaitWaiters(1);
                var second=pool.submit(() -> change(c,member,secondValue,10)); awaitWaiters(2);
                release.countDown(); gate.get(15,TimeUnit.SECONDS);
                var a=first.get(15,TimeUnit.SECONDS); var b=second.get(15,TimeUnit.SECONDS);
                assertThat(a.statusCode()).isEqualTo(firstCode);
                if(secondCode==409) error(b,409,"VERSION_CONFLICT"); else success(b,11,member);
                assertThat(revision()).isEqualTo(11); assertThat(available()).isFalse(); assertNoFollowup();
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"departure","rejoin","account","ended"})
    void waitingRequestRechecksCommittedWriterState(String scenario) throws Exception {
        var c=login(account,"WEB"); var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(() -> new TransactionTemplate(manager).execute(s -> {
                jdbc.queryForObject("SELECT revision FROM event WHERE id=? FOR UPDATE",Long.class,event);
                locked.countDown(); await(release);
                switch(scenario) {
                    case "departure" -> jdbc.update("UPDATE member SET active=false WHERE id=?",member);
                    // 실제 Join은 위 시험에서 검증. 여기서는 대기 중 회차 변경 커밋을 강제하는 fixture writer다.
                    case "rejoin" -> jdbc.update("UPDATE member SET participation_revision=participation_revision+1 WHERE id=?",member);
                    case "account" -> jdbc.update("UPDATE account SET status='INACTIVE' WHERE id=?",account);
                    case "ended" -> jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
                }
                jdbc.update("UPDATE event SET revision=revision+1 WHERE id=?",event); return null;
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                var changing=pool.submit(() -> change(c,member,true,10)); awaitWaiters(1);
                release.countDown(); writer.get(15,TimeUnit.SECONDS);
                var result=changing.get(15,TimeUnit.SECONDS);
                switch(scenario) {
                    case "departure" -> error(result,404,"NOT_FOUND");
                    case "rejoin" -> error(result,409,"VERSION_CONFLICT");
                    case "account" -> error(result,401,"UNAUTHENTICATED");
                    case "ended" -> error(result,409,"INVALID_STATE");
                }
                assertThat(available()).isTrue(); assertThat(revision()).isEqualTo(11); assertNoFollowup();
            } finally { release.countDown(); }
        }
    }


    @ParameterizedTest @ValueSource(booleans = {true, false})
    void manualAssignmentAndAvailabilitySerializeOnEvent(boolean availabilityFirst) throws Exception {
        UUID task=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task(id,event_id,work_group_id,bundle_key,zone_id,title,start_at,end_at,steps,checklist,status)
                VALUES (?,?,?,'availability-bundle',?,'task',now(),now()+interval '1 hour','[]','[]','WAITING')""",
                task,event,group,zone);
        var workerCredentials=login(account,"ANDROID"); var chiefCredentials=login(chiefAccount,"WEB");
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var gate=pool.submit(() -> new TransactionTemplate(manager).execute(s -> {
                jdbc.queryForObject("SELECT revision FROM event WHERE id=? FOR UPDATE",Long.class,event);
                locked.countDown(); await(release); return null;
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                var first=pool.submit(() -> availabilityFirst ? change(workerCredentials,member,false,10) : assign(chiefCredentials,10));
                awaitWaiters(1);
                var second=pool.submit(() -> availabilityFirst ? assign(chiefCredentials,10) : change(workerCredentials,member,false,10));
                awaitWaiters(2); release.countDown(); gate.get(15,TimeUnit.SECONDS);
                assertThat(first.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                error(second.get(15,TimeUnit.SECONDS),409,"VERSION_CONFLICT");
                assertThat(revision()).isEqualTo(11);
                if(availabilityFirst) {
                    error(assign(chiefCredentials,11),422,"INVALID_REFERENCE");
                    var row=jdbc.queryForMap("SELECT * FROM task WHERE id=?",task);
                    assertThat(row.get("assignee_id")).isNull(); assertThat(row.get("first_assigned_at")).isNull();
                    assertThat(row.get("status")).isEqualTo("WAITING");
                    assertThat(available()).isFalse(); assertThat(revision()).isEqualTo(11);
                } else {
                    var assigned=jdbc.queryForMap("SELECT * FROM task WHERE id=?",task);
                    assertThat(assigned.get("assignee_id")).isEqualTo(member);
                    assertThat(assigned.get("first_assigned_at")).isNotNull();
                    var logs=jdbc.queryForList("SELECT * FROM operation_log WHERE event_id=? ORDER BY id",event);
                    assertThat(logs).hasSize(1);
                    success(change(workerCredentials,member,false,11),12,member);
                    assertThat(jdbc.queryForMap("SELECT * FROM task WHERE id=?",task)).isEqualTo(assigned);
                    assertThat(jdbc.queryForList("SELECT * FROM operation_log WHERE event_id=? ORDER BY id",event)).isEqualTo(logs);
                    assertThat(available()).isFalse();
                }
            } finally { release.countDown(); }
        }
    }
    HttpResponse<String> assign(Credentials chiefCredentials,long revision) throws Exception {
        return send(request("/api/v1/events/"+event+"/initial-assignments")
                .header(chiefCredentials.header(),chiefCredentials.value()).header("Origin",chiefCredentials.origin())
                .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"bundleKey\":\"availability-bundle\",\"memberId\":\""+member
                        +"\",\"expectedEventRevision\":\""+revision+"\"}")));
    }

    void awaitWaiters(int count) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) {
            int waiting=jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                     AND cardinality(pg_blocking_pids(pid))>0 AND lower(query) LIKE '%from event%for%update%'
                    """,Integer.class);
            if(waiting>=count) return;
            Thread.sleep(20);
        }
        fail("Expected "+count+" actual PostgreSQL Event lock waiters");
    }
    static void await(CountDownLatch latch) {
        try { if(!latch.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    void assertNoFollowup() {
        for(String table:List.of("ai_job","outbox_event"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE event_id=?",Long.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE operation='SC-API-039'",Long.class)).isZero();
    }
    record Credentials(String header,String value,String origin) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    Credentials login(UUID owner,String channel) throws Exception {
        boolean web=channel.equals("WEB"); String origin=web?WEB:ANDROID;
        var req=request("/api/v1/auth/login").header("Content-Type","application/json").header("Origin",origin)
                .header("X-SceneCrew-Client",channel);
        if(web) req.header("X-SceneCrew-CSRF","1");
        var result=send(req.POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+owner+"@example.invalid\",\"password\":\""+PASSWORD+"\"}")));
        assertThat(result.statusCode()).isEqualTo(200);
        if(web) return new Credentials("Cookie",result.headers().allValues("Set-Cookie").stream()
                .filter(s->s.startsWith("__Secure-sc-access=")).findFirst().orElseThrow().split(";",2)[0],origin);
        return new Credentials("Authorization","Bearer "+json.readTree(result.body()).get("accessToken").asString(),origin);
    }
    HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)); }
    HttpRequest.Builder patch(String path,String body,Credentials c) {
        return request(path).header("Content-Type","application/json").header("Origin",c.origin()).header(c.header(),c.value())
                .method("PATCH",HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> change(Credentials c,UUID id,boolean value,long revision) throws Exception {
        return send(patch(path(id),body(value,revision),c));
    }
    HttpResponse<String> send(HttpRequest.Builder req) throws Exception {
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(req.timeout(Duration.ofSeconds(25)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    void success(HttpResponse<String> result,long revision,UUID id) {
        assertThat(result.statusCode()).isEqualTo(200);
        JsonNode body=json.readTree(result.body());
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.get("eventRevision").asString()).isEqualTo(Long.toString(revision));
        var p=body.get("participation"); assertThat(p.size()).isEqualTo(5);
        assertThat(p.get("memberId").asString()).isEqualTo(id.toString());
        assertThat(p.get("active").asBoolean()).isTrue();
        assertThat(p.has("available")).isFalse(); assertThat(p.has("isReserve")).isFalse();
        assertThat(result.headers().firstValue("Cache-Control")).hasValue("no-store");
    }
    void error(HttpResponse<String> result,int status,String code) {
        assertThat(result.statusCode()).isEqualTo(status);
        assertThat(json.readTree(result.body()).get("code").asString()).isEqualTo(code);
        assertThat(result.headers().firstValue("X-Request-Id")).isPresent();
    }
}

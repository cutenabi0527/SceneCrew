package com.ssafy.scenecrew.invite;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.invite.controller.InviteJoinController;
import com.ssafy.scenecrew.invite.dto.JoinRequest;
import com.ssafy.scenecrew.invite.dto.JoinResult;
import com.ssafy.scenecrew.invite.service.InviteJoinService;
import static org.assertj.core.api.Assertions.*;

/** Exclusive containers and random HTTP ports; no shared DB cleanup or Redis flush. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class InviteJoinPostgresTest {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final byte[] SECRET = new byte[32];
    static { new java.security.SecureRandom().nextBytes(SECRET); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        POSTGRES.start(); REDIS.start();
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled", () -> "true");
        r.add("scenecrew.auth.signing-key-base64", () -> Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.failure-hmac-key-base64", () -> Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.issuer", () -> "invite-test");
        r.add("scenecrew.auth.audience", () -> "invite-test");
        r.add("scenecrew.auth.web-origins", () -> "https://web.example.invalid");
        r.add("scenecrew.auth.android-origins", () -> "https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); POSTGRES.stop(); }
    @Autowired InviteJoinService service;
    private JoinResult join(SessionAuthenticator.Principal principal, String token, JoinRequest request) {
        return service.join(principal, token, UUID.randomUUID().toString(), request).body();
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired PasswordEncoder encoder;
    @Autowired EmailLoginService login;
    @Autowired SessionAuthenticator authenticator;
    @Autowired InviteJoinController controller;
    @LocalServerPort int port;
    UUID event, group, account, identity;
    String token, email;
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @BeforeEach void fixture() {
        event=UUID.randomUUID(); group=UUID.randomUUID(); account=UUID.randomUUID(); identity=UUID.randomUUID();
        token="fixture-"+UUID.randomUUID(); email=account+"@example.invalid";
        account(account);
        jdbc.update("INSERT INTO auth_identity VALUES (?,?,'LOCAL',?,?,?,true)", identity, account,
                account.toString(), encoder.encode("SyntheticPass8!"), email);
        jdbc.update("INSERT INTO event (id,name,state,revision,total_capacity,invite_token_hash,last_input_modified_at) VALUES (?,'join','RECRUITING',1,31,?,now())",
                event, InviteJoinService.tokenHash(token));
        group(group, event, true);
    }
    private void account(UUID id) { jdbc.update("INSERT INTO account VALUES (?,'fixture','ACTIVE',true,3,'{}',now())", id); }
    private void group(UUID id, UUID eventId, boolean active) {
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,?,'',?)",id,eventId,id.toString(),active);
    }
    private SessionAuthenticator.Principal principal(UUID id) {
        return new SessionAuthenticator.Principal(id,UUID.randomUUID(),identity,3,"WEB");
    }
    private JoinRequest request(long revision) { return new JoinRequest(group,true,false,revision); }
    private JoinResult join() { return join(principal(account),token,request(1)); }
    private long revision() { return jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event); }
    private long count() { return jdbc.queryForObject("SELECT count(*) FROM member WHERE event_id=? AND active",Long.class,event); }
    private void business(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
    private void failure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test void httpSameKeyReturnsOriginalBodyAndChangedBodyIs409() throws Exception {
        String key=UUID.randomUUID().toString(); var web=httpCredentials("WEB");
        var first=send(joinRequest(token,group,1).setHeader("Idempotency-Key",key)
                .header(web.header(),web.value()).header("Origin",web.origin()).header("X-SceneCrew-CSRF","1"));
        assertThat(first.statusCode()).isEqualTo(200);
        jdbc.update("UPDATE event SET revision=10 WHERE id=?",event);
        var replay=send(joinRequest(token,group,1).setHeader("Idempotency-Key",key)
                .header(web.header(),web.value()).header("Origin",web.origin()).header("X-SceneCrew-CSRF","1"));
        assertThat(replay.statusCode()).isEqualTo(200); assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(first.body()));
        assertThat(replay.headers().firstValue("Cache-Control")).hasValue("no-store");
        httpError(send(joinRequest(token,group,2).setHeader("Idempotency-Key",key)
                .header(web.header(),web.value()).header("Origin",web.origin()).header("X-SceneCrew-CSRF","1")),409,"IDEMPOTENCY_MISMATCH");
        assertThat(count()).isEqualTo(1); assertThat(revision()).isEqualTo(10);
    }
    @Test void sameKeyReplaysOriginalResponseAfterEventRevisionChanges() {
        String key=UUID.randomUUID().toString();
        var first=service.join(principal(account),token,key,request(1));
        jdbc.update("UPDATE event SET revision=10 WHERE id=?",event);
        var replay=service.join(principal(account),token,key.toUpperCase(Locale.ROOT),request(1));
        assertThat(replay.replayed()).isTrue(); assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.httpStatus()).isEqualTo(200); assertThat(revision()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE actor_account_id=? AND operation='SC-API-035'",Long.class,account)).isEqualTo(1);
    }
    @Test void sameKeyDifferentPayloadOrRotatedTokenIsMismatch() {
        String key=UUID.randomUUID().toString();
        service.join(principal(account),token,key,request(1));
        for (var changed : List.of(new JoinRequest(UUID.randomUUID(),true,false,1),
                new JoinRequest(group,false,false,1), new JoinRequest(group,true,true,1), request(2))) {
            business(() -> service.join(principal(account),token,key,changed),ErrorCode.IDEMPOTENCY_MISMATCH);
        }
        String rotated="rotated-"+UUID.randomUUID();
        jdbc.update("UPDATE event SET invite_token_hash=? WHERE id=?",InviteJoinService.tokenHash(rotated),event);
        business(() -> service.join(principal(account),rotated,key,request(1)),ErrorCode.IDEMPOTENCY_MISMATCH);
        assertThat(count()).isEqualTo(1); assertThat(revision()).isEqualTo(2);
    }
    @Test void priorKeyCannotReplayAfterDepartureOrRejoin() {
        String key=UUID.randomUUID().toString();
        var first=service.join(principal(account),token,key,request(1));
        jdbc.update("UPDATE member SET active=false WHERE id=?",first.body().participation().memberId());
        business(() -> service.join(principal(account),token,key,request(1)),ErrorCode.FORBIDDEN);
        assertThat(count()).isZero(); assertThat(revision()).isEqualTo(2);
        var rejoined=service.join(principal(account),token,UUID.randomUUID().toString(),request(2));
        assertThat(rejoined.body().participation().participationRevision()).isEqualTo("2");
        business(() -> service.join(principal(account),token,key,request(1)),ErrorCode.FORBIDDEN);
        assertThat(revision()).isEqualTo(3);
    }
    @Test void sameKeyConcurrentRequestsCommitOneParticipationAndOneReceipt() throws Exception {
        String key=UUID.randomUUID().toString(); var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<com.ssafy.scenecrew.global.idempotency.service.IdempotentResult<JoinResult>> work=() -> {
                start.await(); return service.join(principal(account),token,key,request(1));
            };
            var a=pool.submit(work); var b=pool.submit(work); start.countDown();
            var x=a.get(15,TimeUnit.SECONDS); var y=b.get(15,TimeUnit.SECONDS);
            assertThat(x.body()).isEqualTo(y.body()); assertThat(x.replayed()).isNotEqualTo(y.replayed());
            assertThat(count()).isEqualTo(1); assertThat(revision()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE actor_account_id=? AND operation='SC-API-035'",Long.class,account)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void receiptCommitFailureRollsBackParticipationAndAllowsSameKeyRetry() {
        String key=UUID.randomUUID().toString(); String trigger="receipt_fail_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        try {
            jdbc.execute("CREATE CONSTRAINT TRIGGER "+trigger+" AFTER INSERT ON idempotency_record DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.actor_account_id='"+account+"'::uuid) EXECUTE FUNCTION "+trigger+"()");
            try {
                business(() -> service.join(principal(account),token,key,request(1)),ErrorCode.SERVICE_UNAVAILABLE);
                assertThat(count()).isZero(); assertThat(revision()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE actor_account_id=?",Long.class,account)).isZero();
            } finally { jdbc.execute("DROP TRIGGER "+trigger+" ON idempotency_record"); }
        } finally { jdbc.execute("DROP FUNCTION "+trigger+"()"); }
        assertThat(service.join(principal(account),token,key,request(1)).replayed()).isFalse();
        assertThat(count()).isEqualTo(1);
    }
    @Test void normalJoinPersistsParticipationButDoesNotClaimAssignmentOrCreateJobs() {
        var result=join();
        assertThat(result.eventRevision()).isEqualTo("2");
        assertThat(result.participation().participationRevision()).isEqualTo("1");
        assertThat(result.participation().workGroupId()).isEqualTo(group);
        assertThat(result.participation().eventRole()).isEqualTo("WORKER");
        assertThat(result.initialAssignment()).isEqualTo("UNKNOWN");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available AND NOT is_reserve AND crew_no=1 FROM member WHERE id=?",
                Boolean.class,result.participation().memberId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE event_id=?",Long.class,event)).isZero();
    }
    @Test void bothAvailableAndReserveBooleanBoundariesAreStoredWithoutInventedRestrictions() {
        long revision=1;
        for (boolean available : List.of(false,true)) for (boolean reserve : List.of(false,true)) {
            UUID id=UUID.randomUUID(); account(id);
            var result=join(principal(id),token,new JoinRequest(group,available,reserve,revision++));
            var values=jdbc.queryForMap("SELECT available,is_reserve FROM member WHERE id=?",result.participation().memberId());
            assertThat(values.get("available")).isEqualTo(available);
            assertThat(values.get("is_reserve")).isEqualTo(reserve);
        }
        assertThat(count()).isEqualTo(4);
    }
    @Test void invalidAndRotatedTokensDoNotCreateMembers() {
        business(() -> join(principal(account),"invalid",request(1)),ErrorCode.NOT_FOUND);
        jdbc.update("UPDATE event SET invite_token_hash=? WHERE id=?",InviteJoinService.tokenHash("new-token"),event);
        business(this::join,ErrorCode.NOT_FOUND); assertThat(count()).isZero(); assertThat(revision()).isEqualTo(1);
    }
    @Test void onlyRecruitingAndLivePermitJoin() {
        for (String state : List.of("DRAFT","ENDED")) {
            jdbc.update("UPDATE event SET state=? WHERE id=?",state,event);
            business(this::join,ErrorCode.INVALID_STATE); assertThat(count()).isZero();
        }
        jdbc.update("UPDATE event SET state='LIVE' WHERE id=?",event);
        assertThat(join().participation().active()).isTrue();
    }
    @Test void inactiveMissingAndOtherEventGroupsAreRejected() {
        jdbc.update("UPDATE work_group SET active=false WHERE id=?",group);
        failure(this::join,ErrorCode.INVALID_REFERENCE);
        failure(() -> join(principal(account),token,new JoinRequest(UUID.randomUUID(),true,false,1)),ErrorCode.INVALID_REFERENCE);
        UUID otherEvent=UUID.randomUUID(); UUID otherGroup=UUID.randomUUID();
        jdbc.update("INSERT INTO event(id,name,state,revision,last_input_modified_at) VALUES (?,'other','DRAFT',1,now())",otherEvent);
        group(otherGroup,otherEvent,true);
        failure(() -> join(principal(account),token,new JoinRequest(otherGroup,true,false,1)),ErrorCode.INVALID_REFERENCE);
        assertThat(count()).isZero(); assertThat(revision()).isEqualTo(1);
    }
    @Test void activeDuplicateIgnoresRequestedGroupFlagsAndStaleSelectionRevision() {
        var original=join(); UUID other=UUID.randomUUID(); group(other,event,true);
        var duplicate=join(principal(account),token,new JoinRequest(other,false,true,0));
        assertThat(duplicate).isEqualTo(original); assertThat(revision()).isEqualTo(2); assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available AND NOT is_reserve FROM member WHERE id=?",Boolean.class,original.participation().memberId())).isTrue();
    }
    @Test void staleRevisionAndFullCapacityLeaveNoPartialEffects() {
        failure(() -> join(principal(account),token,request(0)),ErrorCode.VERSION_CONFLICT);
        assertThat(count()).isZero(); join();
        jdbc.update("UPDATE event SET total_capacity=1 WHERE id=?",event);
        UUID other=UUID.randomUUID(); account(other);
        failure(() -> join(principal(other),token,request(2)),ErrorCode.EVENT_FULL);
        assertThat(count()).isEqualTo(1); assertThat(revision()).isEqualTo(2);
    }
    @Test void accountEligibilityIsRechecked() {
        jdbc.update("UPDATE account SET token_version=4 WHERE id=?",account);
        business(this::join,ErrorCode.UNAUTHENTICATED); assertThat(count()).isZero();
        jdbc.update("UPDATE account SET token_version=3,status='DISABLED' WHERE id=?",account);
        business(this::join,ErrorCode.UNAUTHENTICATED);
        business(() -> join(null,token,request(1)),ErrorCode.UNAUTHENTICATED);
    }
    @Test void sameAccountCanJoinAnotherEventWithoutChangingItsExistingParticipation() {
        var first=join(); UUID otherEvent=UUID.randomUUID(), otherGroup=UUID.randomUUID();
        String otherToken="other-"+UUID.randomUUID();
        jdbc.update("INSERT INTO event(id,name,state,revision,total_capacity,invite_token_hash,last_input_modified_at) VALUES (?,'other','LIVE',7,31,?,now())",
                otherEvent,InviteJoinService.tokenHash(otherToken));
        group(otherGroup,otherEvent,true);
        var other=join(principal(account),otherToken,new JoinRequest(otherGroup,false,true,7));
        assertThat(other.participation().memberId()).isNotEqualTo(first.participation().memberId());
        assertThat(other.participation().workGroupId()).isEqualTo(otherGroup);
        assertThat(other.eventRevision()).isEqualTo("8");
        assertThat(join(principal(account),token,request(1))).isEqualTo(first);
        assertThat(count()).isEqualTo(1); assertThat(revision()).isEqualTo(2);
    }
    @Test void physicalUniqueConstraintsProtectAccountEventAndCrewNumberEvenForInactiveMembers() {
        var first=join(); UUID member=first.participation().memberId();
        jdbc.update("UPDATE member SET active=false WHERE id=?",member);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO member SELECT ?,event_id,account_id,work_group_id,crew_no+1,event_role,
                display_name,available,is_reserve,true,1 FROM member WHERE id=?
                """,UUID.randomUUID(),member)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        UUID other=UUID.randomUUID(); account(other);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO member SELECT ?,event_id,?,work_group_id,crew_no,event_role,
                display_name,available,is_reserve,true,1 FROM member WHERE id=?
                """,UUID.randomUUID(),other,member)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(count()).isZero(); assertThat(revision()).isEqualTo(2);
    }
    @Test void rejoinReusesMemberAndCrewNumberIncrementsRoundAndRefreshesName() {
        var first=join(); UUID member=first.participation().memberId();
        jdbc.update("UPDATE member SET active=false WHERE id=?",member);
        jdbc.update("UPDATE account SET display_name='current' WHERE id=?",account);
        var second=join(principal(account),token,new JoinRequest(group,false,true,2));
        assertThat(second.participation().memberId()).isEqualTo(member);
        assertThat(second.participation().participationRevision()).isEqualTo("2");
        assertThat(second.initialAssignment()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForMap("SELECT crew_no,display_name,available,is_reserve FROM member WHERE id=?",member))
                .containsEntry("crew_no",1L).containsEntry("display_name","current").containsEntry("available",false).containsEntry("is_reserve",true);
        assertThat(revision()).isEqualTo(3); assertThat(count()).isEqualTo(1);
    }
    @Test void incompleteDepartureCannotReviveOldUnfinishedWorkButCompletedHistoryIsPreserved() {
        UUID member=join().participation().memberId(); UUID zone=UUID.randomUUID(), task=UUID.randomUUID();
        jdbc.update("INSERT INTO zone(id,event_id,name,aliases) VALUES (?,?,'fixture','[]')",zone,event);
        jdbc.update("""
                INSERT INTO task(id,event_id,work_group_id,bundle_key,assignee_id,zone_id,title,start_at,end_at,
                                 steps,checklist,status,first_assigned_at)
                VALUES (?,?,?,'old',?,?,'old',now(),now()+interval '1 hour','[]','[]','WAITING',now())
                """,task,event,group,member,zone);
        jdbc.update("UPDATE member SET active=false WHERE id=?",member);
        business(() -> join(principal(account),token,request(2)),ErrorCode.INVALID_STATE);
        assertThat(count()).isZero(); assertThat(revision()).isEqualTo(2);
        jdbc.update("UPDATE task SET status='DONE' WHERE id=?",task);
        assertThat(join(principal(account),token,request(2)).initialAssignment()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT assignee_id=? AND first_assigned_at IS NOT NULL AND status='DONE' FROM task WHERE id=?",
                Boolean.class,member,task)).isTrue();
    }
    @Test void concurrentSameAccountAndDifferentGroupsProduceOneParticipation() throws Exception {
        UUID other=UUID.randomUUID(); group(other,event,true);
        var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> { start.await(); return join(); });
            var b=pool.submit(() -> { start.await(); return join(principal(account),token,new JoinRequest(other,false,true,1)); });
            start.countDown();
            var first=a.get(15,TimeUnit.SECONDS); var second=b.get(15,TimeUnit.SECONDS);
            assertThat(first).isEqualTo(second); assertThat(count()).isEqualTo(1); assertThat(revision()).isEqualTo(2);
        }
    }
    @Test void concurrentDifferentAccountsForLastSeatCannotOverbook() throws Exception {
        jdbc.update("UPDATE event SET total_capacity=1 WHERE id=?",event);
        UUID other=UUID.randomUUID(); account(other); var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> raceJoin(start,account)); var b=pool.submit(() -> raceJoin(start,other)); start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("JOINED","VERSION_CONFLICT");
            assertThat(count()).isEqualTo(1); assertThat(revision()).isEqualTo(2);
        }
    }
    private String raceJoin(CountDownLatch start, UUID id) throws Exception {
        start.await();
        try { join(principal(id),token,request(1)); return "JOINED"; }
        catch (BusinessException failure) { return failure.getErrorCode().name(); }
    }
    @Test void tokenRotationCommittedWhileJoinWaitsIsRecheckedAfterEventLock() throws Exception {
        competingWriter("invite_token_hash='"+InviteJoinService.tokenHash("rotated")+"'",ErrorCode.NOT_FOUND);
    }
    @Test void endCommittedWhileJoinWaitsIsRecheckedAfterEventLock() throws Exception {
        competingWriter("state='ENDED'",ErrorCode.INVALID_STATE);
    }
    @Test void groupDeactivationCommittedWhileJoinWaitsIsRecheckedAtGroupLock() throws Exception {
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.queryForObject("SELECT active FROM work_group WHERE id=? FOR UPDATE",Boolean.class,group);
                locked.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                jdbc.update("UPDATE work_group SET active=false WHERE id=?",group); return null;
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                var joining=pool.submit(() -> join());
                awaitLock("SELECT id FROM work_group%"); release.countDown(); writer.get(10,TimeUnit.SECONDS);
                assertThatThrownBy(() -> joining.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class)
                        .satisfies(e -> assertThat(((BusinessException)e.getCause()).getErrorCode()).isEqualTo(ErrorCode.INVALID_REFERENCE));
                assertThat(count()).isZero(); assertThat(revision()).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }
    private void competingWriter(String set, ErrorCode error) throws Exception {
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                // Acquire first, but leave old committed values visible until Join reaches FOR UPDATE.
                jdbc.queryForObject("SELECT revision FROM event WHERE id=? FOR UPDATE",Long.class,event);
                locked.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                jdbc.update("UPDATE event SET "+set+" WHERE id=?",event); return null;
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                var joining=pool.submit(() -> join()); awaitLock("SELECT id, invite_token_hash%FOR UPDATE%"); release.countDown(); writer.get(10,TimeUnit.SECONDS);
                assertThatThrownBy(() -> joining.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class)
                        .satisfies(e -> assertThat(((BusinessException)e.getCause()).getErrorCode()).isEqualTo(error));
                assertThat(count()).isZero(); assertThat(revision()).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }
    private void awaitLock(String queryPattern) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos();
        while (System.nanoTime()<deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
                    AND wait_event_type='Lock' AND query LIKE ?)
                    """,Boolean.class,queryPattern))) return;
            Thread.sleep(20);
        }
        fail("Join did not reach a real PostgreSQL Event row-lock wait");
    }
    @Test void deferredCommitFailureRollsBackMemberAndRevision() throws Exception {
        var credentials=httpCredentials("WEB");
        String trigger="join_fail_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        try {
            jdbc.execute("CREATE CONSTRAINT TRIGGER "+trigger+" AFTER UPDATE ON event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.id='"+event+"'::uuid) EXECUTE FUNCTION "+trigger+"()");
            try {
                business(this::join,ErrorCode.SERVICE_UNAVAILABLE);
                httpError(httpJoin(credentials,token,group,1),503,"SERVICE_UNAVAILABLE");
                assertThat(count()).isZero(); assertThat(revision()).isEqualTo(1);
            } finally { jdbc.execute("DROP TRIGGER "+trigger+" ON event"); }
        } finally { jdbc.execute("DROP FUNCTION "+trigger+"()"); }
    }
    @Test void realWebAndAndroidSessionPrincipalsCanUseJoinServiceWithoutProductRoutingChanges() {
        JoinResult first=null;
        for (String channel : List.of("WEB","ANDROID")) {
            var tokens=login.login(email,"SyntheticPass8!",channel).tokens();
            var principal=authenticator.authenticate(tokens.accessToken());
            assertThat(principal.channel()).isEqualTo(channel);
            var joined=join(principal,token,request(1));
            if (first == null) first=joined;
            else assertThat(joined).isEqualTo(first);
        }
        assertThat(count()).isEqualTo(1); assertThat(revision()).isEqualTo(2);
    }
    @Test void webCookieJoinUsesProductLoginAndSecurity() throws Exception { assertHttpJoin("WEB"); }
    @Test void androidBearerJoinUsesProductLoginAndSecurity() throws Exception { assertHttpJoin("ANDROID"); }
    private void assertHttpJoin(String channel) throws Exception {
        var response=httpJoin(httpCredentials(channel),token,group,1);
        assertThat(response.statusCode()).isEqualTo(200);
        var result=json.readTree(response.body());
        assertThat(result.get("participation").get("workGroupId").asString()).isEqualTo(group.toString());
        assertThat(result.get("participation").get("participationRevision").asString()).isEqualTo("1");
        assertThat(result.get("eventRevision").asString()).isEqualTo("2");
        assertThat(result.get("initialAssignment").asString()).isEqualTo("UNKNOWN");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(count()).isEqualTo(1); assertThat(revision()).isEqualTo(2);
    }
    @Test void missingInvalidAndRevokedSessionsCannotJoin() throws Exception {
        httpError(send(joinRequest(token,group,1)),401,"UNAUTHENTICATED");
        httpError(httpJoin(new HttpCredentials("Authorization","Bearer invalid","https://android.example.invalid",false),token,group,1),401,"UNAUTHENTICATED");
        var credentials=httpCredentials("ANDROID");
        var principal=authenticator.authenticate(credentials.value().substring(7));
        // Revoke only this test's session in its exclusively owned Redis.
        redis.delete(RedisLoginSessions.key(principal.sessionId()));
        var revoked=httpJoin(credentials,token,group,1);
        httpError(revoked,401,"UNAUTHENTICATED");
        assertThat(revoked.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"invalid_token\"");
        assertThat(count()).isZero();
    }
    @Test void channelOriginCsrfAndMixedCredentialsFailBeforeParticipation() throws Exception {
        var web=httpCredentials("WEB"); var android=httpCredentials("ANDROID");
        httpError(httpJoin(new HttpCredentials(web.header(),web.value(),web.origin(),false),token,group,1),403,"CSRF_REJECTED");
        httpError(httpJoin(new HttpCredentials(web.header(),web.value(),"https://wrong.example.invalid",true),token,group,1),403,"CSRF_REJECTED");
        httpError(httpJoin(new HttpCredentials(android.header(),android.value(),"https://wrong.example.invalid",false),token,group,1),403,"AUTH_CHANNEL_MISMATCH");
        String webToken=web.value().substring(web.value().indexOf('=')+1);
        httpError(httpJoin(new HttpCredentials("Authorization","Bearer "+webToken,android.origin(),false),token,group,1),403,"AUTH_CHANNEL_MISMATCH");
        var mixed=joinRequest(token,group,1).header(web.header(),web.value()).header(android.header(),android.value()).header("Origin",android.origin());
        httpError(send(mixed),400,"MIXED_AUTH_CREDENTIALS");
        assertThat(count()).isZero(); assertThat(revision()).isEqualTo(1);
    }
    @Test void webJoinRejectsMissingOriginWrongCsrfAndNonJsonWithoutWrites() throws Exception {
        var web=httpCredentials("WEB");
        httpError(send(joinRequest(token,group,1).header(web.header(),web.value()).header("X-SceneCrew-CSRF","1")),403,"CSRF_REJECTED");
        httpError(send(joinRequest(token,group,1).header(web.header(),web.value()).header("Origin",web.origin())
                .header("X-SceneCrew-CSRF","wrong")),403,"CSRF_REJECTED");
        httpError(send(joinRequest(token,group,1).setHeader("Content-Type","text/plain")
                .header(web.header(),web.value()).header("Origin",web.origin()).header("X-SceneCrew-CSRF","1")),415,"UNSUPPORTED_MEDIA_TYPE");
        assertThat(count()).isZero(); assertThat(revision()).isEqualTo(1);
    }
    @Test void expiredSignedAccessCannotJoinEvenWithExistingSession() throws Exception {
        var android=httpCredentials("ANDROID");
        var original=com.nimbusds.jwt.SignedJWT.parse(android.value().substring(7));
        var now=java.time.Instant.now();
        var claims=new com.nimbusds.jwt.JWTClaimsSet.Builder(original.getJWTClaimsSet())
                .issueTime(Date.from(now.minusSeconds(7200))).expirationTime(Date.from(now.minusSeconds(3600))).build();
        var expired=new com.nimbusds.jwt.SignedJWT(original.getHeader(),claims);
        expired.sign(new com.nimbusds.jose.crypto.MACSigner(SECRET));
        var response=httpJoin(new HttpCredentials("Authorization","Bearer "+expired.serialize(),android.origin(),false),token,group,1);
        httpError(response,401,"UNAUTHENTICATED");
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"invalid_token\"");
        assertThat(count()).isZero(); assertThat(revision()).isEqualTo(1);
    }
    @Test void httpRejectsTokenStateGroupAndRevisionFailuresWithoutPartialParticipation() throws Exception {
        var web=httpCredentials("WEB");
        httpError(httpJoin(web,"invalid",group,1),404,"NOT_FOUND");
        jdbc.update("UPDATE event SET invite_token_hash=? WHERE id=?",InviteJoinService.tokenHash("rotated"),event);
        httpError(httpJoin(web,token,group,1),404,"NOT_FOUND");
        jdbc.update("UPDATE event SET invite_token_hash=? WHERE id=?",InviteJoinService.tokenHash(token),event);
        for(String state:List.of("DRAFT","ENDED")) {
            jdbc.update("UPDATE event SET state=? WHERE id=?",state,event);
            httpError(httpJoin(web,token,group,1),409,"INVALID_STATE");
        }
        jdbc.update("UPDATE event SET state='RECRUITING' WHERE id=?",event);
        httpError(httpJoin(web,token,group,0),409,"VERSION_CONFLICT");
        jdbc.update("UPDATE work_group SET active=false WHERE id=?",group);
        httpError(httpJoin(web,token,group,1),422,"INVALID_REFERENCE");
        UUID otherEvent=UUID.randomUUID(),otherGroup=UUID.randomUUID();
        jdbc.update("INSERT INTO event(id,name,state,revision,last_input_modified_at) VALUES (?,'other','DRAFT',1,now())",otherEvent);
        group(otherGroup,otherEvent,true);
        httpError(httpJoin(web,token,otherGroup,1),422,"INVALID_REFERENCE");
        assertThat(count()).isZero(); assertThat(revision()).isEqualTo(1);
    }
    @Test void httpDuplicateDifferentGroupAndRejoinPreserveParticipationRules() throws Exception {
        var web=httpCredentials("WEB"); UUID other=UUID.randomUUID(); group(other,event,true);
        var first=httpJoin(web,token,group,1); assertThat(first.statusCode()).isEqualTo(200);
        var duplicate=httpJoin(web,token,other,0); assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(json.readTree(duplicate.body())).isEqualTo(json.readTree(first.body()));
        UUID member=UUID.fromString(json.readTree(first.body()).get("participation").get("memberId").asString());
        // Departure API is not implemented here: simulate its completed inactive state via DB fixture.
        jdbc.update("UPDATE member SET active=false WHERE id=?",member);
        var rejoin=httpJoin(web,token,other,2); assertThat(rejoin.statusCode()).isEqualTo(200);
        var current=json.readTree(rejoin.body()).get("participation");
        assertThat(current.get("memberId").asString()).isEqualTo(member.toString());
        assertThat(current.get("participationRevision").asString()).isEqualTo("2");
        assertThat(current.get("workGroupId").asString()).isEqualTo(other.toString());
        assertThat(count()).isEqualTo(1); assertThat(revision()).isEqualTo(3);
    }
    @Test void httpCapacityRejectsAnotherAccount() throws Exception {
        UUID other=UUID.randomUUID();account(other);
        join(principal(other),token,request(1));
        jdbc.update("UPDATE event SET total_capacity=1 WHERE id=?",event);
        httpError(httpJoin(httpCredentials("ANDROID"),token,group,2),409,"EVENT_FULL");
        assertThat(count()).isEqualTo(1);assertThat(revision()).isEqualTo(2);
    }
    @Test void httpConcurrentDifferentGroupRequestsCreateOneMember() throws Exception {
        var web=httpCredentials("WEB");UUID other=UUID.randomUUID();group(other,event,true);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return httpJoin(web,token,group,1);});
            var second=pool.submit(()->{start.await();return httpJoin(web,token,other,1);});start.countDown();
            var a=first.get(15,TimeUnit.SECONDS);var b=second.get(15,TimeUnit.SECONDS);
            assertThat(a.statusCode()).isEqualTo(200);assertThat(b.statusCode()).isEqualTo(200);
            assertThat(json.readTree(a.body())).isEqualTo(json.readTree(b.body()));
            assertThat(count()).isEqualTo(1);assertThat(revision()).isEqualTo(2);
        }
    }
    @Test void httpJoinRechecksTokenRotationAfterActualEventLockWait() throws Exception {
        httpCompetingWriter("invite_token_hash='"+InviteJoinService.tokenHash("rotated")+"'",404,"NOT_FOUND");
    }
    @Test void httpJoinRechecksEndAfterActualEventLockWait() throws Exception {
        httpCompetingWriter("state='ENDED'",409,"INVALID_STATE");
    }
    private void httpCompetingWriter(String set,int statusCode,String code) throws Exception {
        var credentials=httpCredentials("WEB");var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                jdbc.queryForObject("SELECT revision FROM event WHERE id=? FOR UPDATE",Long.class,event);locked.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                jdbc.update("UPDATE event SET "+set+" WHERE id=?",event);return null;
            }));
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                var joining=pool.submit(()->httpJoin(credentials,token,group,1));
                awaitLock("SELECT id, invite_token_hash%FOR UPDATE%");release.countDown();writer.get(10,TimeUnit.SECONDS);
                httpError(joining.get(10,TimeUnit.SECONDS),statusCode,code);assertThat(count()).isZero();assertThat(revision()).isEqualTo(1);
            }finally{release.countDown();}
        }
    }
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    private record HttpCredentials(String header,String value,String origin,boolean csrf) {
        @Override public String toString(){return "HttpCredentials[redacted]";}
    }
    private HttpCredentials httpCredentials(String channel) throws Exception {
        boolean web=channel.equals("WEB");String origin=web?"https://web.example.invalid":"https://android.example.invalid";
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/login"))
                .header("Content-Type","application/json").header("Origin",origin).header("X-SceneCrew-Client",channel);
        if(web)request.header("X-SceneCrew-CSRF","1");
        var response=send(request.POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+email+"\",\"password\":\"SyntheticPass8!\"}")));
        assertThat(response.statusCode()).isEqualTo(200);
        if(web) {
            String cookie=response.headers().allValues("Set-Cookie").stream().filter(c->c.startsWith("__Secure-sc-access="))
                    .findFirst().orElseThrow().split(";",2)[0];
            return new HttpCredentials("Cookie",cookie,origin,true);
        }
        return new HttpCredentials("Authorization","Bearer "+json.readTree(response.body()).get("accessToken").asString(),origin,false);
    }
    private HttpRequest.Builder joinRequest(String invite,UUID selected,long revision) {
        return HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/invites/"+invite+"/join"))
                .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"workGroupId\":\""+selected+"\",\"available\":true,\"isReserve\":false,\"expectedEventRevision\":\""+revision+"\"}"));
    }
    private HttpResponse<String> httpJoin(HttpCredentials c,String invite,UUID selected,long revision) throws Exception {
        var request=joinRequest(invite,selected,revision).header(c.header(),c.value()).header("Origin",c.origin());
        if(c.csrf())request.header("X-SceneCrew-CSRF","1");return send(request);
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request.timeout(Duration.ofSeconds(15)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    private void httpError(HttpResponse<String> response,int status,String code) {
        assertThat(response.statusCode()).isEqualTo(status);assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }
}

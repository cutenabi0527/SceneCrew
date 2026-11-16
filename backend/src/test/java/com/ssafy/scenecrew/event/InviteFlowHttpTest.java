package com.ssafy.scenecrew.event;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** SC-API-022·024·029·030·031의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis. 실제 초대 암호화·발급·회전·참여를 확인한다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class InviteFlowHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES = new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        POSTGRES.start(); REDIS.start();
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("spring.datasource.hikari.maximum-pool-size",()->"8");
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"event-test"); r.add("scenecrew.auth.audience",()->"event-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->ANDROID);
    }
    @AfterAll static void stop() { REDIS.stop(); POSTGRES.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired com.ssafy.scenecrew.invite.service.InviteCipher cipher;
    @Autowired com.ssafy.scenecrew.invite.service.InviteService invites;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired com.ssafy.scenecrew.event.service.ChiefTransferService transfers;
    @Autowired com.ssafy.scenecrew.event.service.EventLifecycleService lifecycle;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>();
    UUID chief, worker, outsider;
    String chiefCookie;

    @BeforeEach void fixture() throws Exception {
        chief=account(); worker=account(); outsider=account();
        chiefCookie=login(chief);
    }
    @AfterEach void cleanup() {
        for (var a:accounts) {
            for (var e:jdbc.queryForList("SELECT event_id FROM member WHERE account_id=?",UUID.class,a)) {
                jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
                jdbc.update("DELETE FROM ai_job WHERE event_id=?",e);
                jdbc.update("DELETE FROM operation_log WHERE event_id=?",e);
                jdbc.update("DELETE FROM task WHERE event_id=?",e);
                jdbc.update("DELETE FROM program WHERE event_id=?",e);
                jdbc.update("DELETE FROM zone WHERE event_id=?",e);
                jdbc.update("DELETE FROM member WHERE event_id=?",e);
                jdbc.update("DELETE FROM work_group WHERE event_id=?",e);
                jdbc.update("DELETE FROM outbox_event WHERE event_id=?",e);
                jdbc.update("DELETE FROM event WHERE id=?",e);
            }
        }
        for (var a:accounts) {
            jdbc.update("DELETE FROM idempotency_record WHERE actor_account_id=?",a);
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",a);
            jdbc.update("DELETE FROM account WHERE id=?",a);
        }
        accounts.clear();
    }


    @Test void fullHttpFlowAndLostRotationResponse() throws Exception {
        UUID e=create("초대"); String original=storedToken(e);
        assertThat(original).matches("[A-Za-z0-9_-]{43}");
        error(get("/api/v1/events/"+e+"/invite",chiefCookie),404,"NOT_FOUND");
        error(get("/api/v1/invites/"+original+"/options",chiefCookie),404,"NOT_FOUND");
        open(e);
        var link=get("/api/v1/events/"+e+"/invite",chiefCookie);
        assertThat(link.statusCode()).isEqualTo(200);
        assertThat(link.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(link.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(json.readTree(link.body()).get("inviteUrl").asString()).endsWith("/invites/"+original);
        assertThat(storedToken(e)).isEqualTo(original);
        String cookie=login(worker); UUID g=groupId(e);
        var options=get("/api/v1/invites/"+original+"/options",cookie);
        assertThat(options.statusCode()).isEqualTo(200);
        assertThat(json.readTree(options.body()).get("myParticipation").isNull()).isTrue();
        assertThat(options.body()).doesNotContain("assignee","report","chat",chief.toString());
        assertThat(join(original,cookie,g,"3").statusCode()).isEqualTo(200);
        var rotated=rotate(e,"4",chiefCookie); assertThat(rotated.statusCode()).isEqualTo(200);
        String fresh=storedToken(e); assertThat(fresh).isNotEqualTo(original);
        var recovered=get("/api/v1/events/"+e+"/invite",chiefCookie);
        assertThat(recovered.body()).isEqualTo(rotated.body());
        error(rotate(e,"4",chiefCookie),409,"VERSION_CONFLICT");
        assertThat(storedToken(e)).isEqualTo(fresh);
        error(join(original,login(outsider),g,"5"),404,"NOT_FOUND");
        assertThat(join(fresh,login(outsider),g,"5").statusCode()).isEqualTo(200);
        String receipts=jdbc.queryForObject("SELECT coalesce(string_agg(receipt::text,''),'') FROM idempotency_record WHERE event_id=?",String.class,e);
        assertThat(receipts).doesNotContain(original,fresh);
    }
    @Test void currentChiefOnlyAndEndedState() throws Exception {
        UUID e=create("권한"); open(e); String t=storedToken(e); String w=login(worker);
        assertThat(join(t,w,groupId(e),"3").statusCode()).isEqualTo(200);
        error(get("/api/v1/events/"+e+"/invite",w),403,"FORBIDDEN");
        error(get("/api/v1/events/"+e+"/invite",login(outsider)),404,"NOT_FOUND");
        UUID successor=jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,e,worker);
        transfers.transfer(e,chief,successor,groupId(e),4);
        error(rotate(e,"5",chiefCookie),403,"FORBIDDEN");
        assertThat(get("/api/v1/events/"+e+"/invite",w).statusCode()).isEqualTo(200);
        assertThat(storedToken(e)).isEqualTo(t);
        lifecycle.end(e,worker,null,5);
        error(get("/api/v1/invites/"+t+"/options",w),404,"NOT_FOUND");
        error(get("/api/v1/events/"+e+"/invite",w),404,"NOT_FOUND");
        error(rotate(e,"6",w),409,"INVALID_STATE");
    }
    @Test void legacyAbsenceAndHashOnlyAreDifferent() throws Exception {
        UUID e=create("legacy"); jdbc.update("UPDATE event SET invite_token_ciphertext=NULL,invite_token_hash=NULL WHERE id=?",e);
        open(e); assertThat(storedToken(e)).isNotBlank();
        jdbc.update("UPDATE event SET invite_token_ciphertext=NULL WHERE id=?",e);
        String hash=jdbc.queryForObject("SELECT invite_token_hash FROM event WHERE id=?",String.class,e);
        error(get("/api/v1/events/"+e+"/invite",chiefCookie),503,"SERVICE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT invite_token_hash FROM event WHERE id=?",String.class,e)).isEqualTo(hash);
        assertThat(rotate(e,"3",chiefCookie).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE event SET invite_token_ciphertext=NULL,invite_token_hash=NULL WHERE id=?",e);
        error(get("/api/v1/events/"+e+"/invite",chiefCookie),404,"NOT_FOUND");
        assertThat(rotate(e,"4",chiefCookie).statusCode()).isEqualTo(200);
    }
    @Test void hashOnlyDraftPublishFailsWithoutReplacingLink() throws Exception {
        UUID e=create("hashonly"); String t=storedToken(e);
        jdbc.update("UPDATE event SET invite_token_ciphertext=NULL WHERE id=?",e);
        plan(e); send(patch("/api/v1/events/"+e,complete("1")).header("Cookie",chiefCookie));
        error(publish(e,"2"),503,"SERVICE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT state FROM event WHERE id=?",String.class,e)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT invite_token_hash FROM event WHERE id=?",String.class,e)).isEqualTo(com.ssafy.scenecrew.invite.service.InviteJoinService.tokenHash(t));
    }
    @Test void tamperedCiphertextFailsWithoutAutomaticRotation() throws Exception {
        UUID e=create("tamper"); open(e);
        String h=jdbc.queryForObject("SELECT invite_token_hash FROM event WHERE id=?",String.class,e);
        jdbc.update("UPDATE event SET invite_token_ciphertext='v1:unknown:invalid:invalid:invalid' WHERE id=?",e);
        error(get("/api/v1/events/"+e+"/invite",chiefCookie),503,"SERVICE_UNAVAILABLE");
        error(rotate(e,"3",chiefCookie),503,"SERVICE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,e)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT invite_token_hash FROM event WHERE id=?",String.class,e)).isEqualTo(h);
    }
    @Test void concurrentSameRevisionRotationsHaveOneWinner() throws Exception {
        UUID e=create("race"); open(e);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{start.await();return rotate(e,"3",chiefCookie).statusCode();});
            var b=pool.submit(()->{start.await();return rotate(e,"3",chiefCookie).statusCode();});
            start.countDown(); assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,e)).isEqualTo(4);
        assertThat(storedToken(e)).isNotBlank();
    }
    @Test void rotationRollbackKeepsHashCiphertextAndRevision() throws Exception {
        UUID e=create("rollback");open(e); String t=storedToken(e);
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        tx.executeWithoutResult(status->{invites.rotate(e,chief,3);status.setRollbackOnly();});
        assertThat(storedToken(e)).isEqualTo(t);
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,e)).isEqualTo(3);
    }
    @Test void creationReplayDoesNotReissue() throws Exception {
        String key=UUID.randomUUID().toString();
        var first=send(post("/api/v1/events","{\"name\":\"replay\"}").header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(first.statusCode()).isEqualTo(201); UUID e=UUID.fromString(json.readTree(first.body()).get("id").asString());
        String t=storedToken(e);
        var second=send(post("/api/v1/events","{\"name\":\"replay\"}").header("Cookie",chiefCookie).header("Idempotency-Key",key));
        assertThat(second.body()).isEqualTo(first.body());assertThat(storedToken(e)).isEqualTo(t);
    }
    @Test void malformedAcceptPreservesFrameworkErrorAndNeverMutatesReadOnlyHeaders(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        UUID event=create("accept");open(event);String token=storedToken(event);
        String syntheticKey=Base64.getEncoder().encodeToString(new byte[32]);
        for(String path:List.of("/api/v1/events/"+event+"/invite", "/api/v1/invites/"+token+"/options")) {
            var response=send(HttpRequest.newBuilder(URI.create(url(path))).header("Cookie",chiefCookie).header("Accept","invalid").GET());
            assertThat(response.statusCode()).isEqualTo(406);
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
            var body=json.readTree(response.body());
            assertThat(body.propertyNames()).containsExactlyInAnyOrder("code","message","requestId");
            assertThat(body.get("code").asString()).isEqualTo("INVALID_REQUEST");
            assertThat(body.get("requestId").asString()).isNotBlank();
            assertThat(response.body()).doesNotContain(token,syntheticKey);
        }
        assertThat(output.getAll()).doesNotContain("UnsupportedOperationException",token,syntheticKey);
    }

    @Test void rotationOriginErrorMatchesPublishedContract(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var expected=List.of("FORBIDDEN","AUTH_CHANNEL_MISMATCH","CSRF_REJECTED");
        for(String file:List.of("openapi-implementation.yaml","reference/openapi-80.yaml")) {
            Map<String,Object> document=new org.yaml.snakeyaml.Yaml().load(java.nio.file.Files.readString(
                    java.nio.file.Path.of("../docs/api-contract",file)));
            JsonNode response=json.valueToTree(document).get("paths").get("/events/{e}/invite/rotate")
                    .get("post").get("responses").get("403");
            assertThat(response.get("x-codes").valueStream().map(JsonNode::asString).toList()).containsExactlyElementsOf(expected);
            assertThat(response.get("content").get("application/json").get("schema").get("allOf").get(1)
                    .get("properties").get("code").get("enum").valueStream().map(JsonNode::asString).toList()).containsExactlyElementsOf(expected);
        }
        UUID e=create("origin-contract");open(e);String token=storedToken(e);
        var before=jdbc.queryForMap("SELECT invite_token_hash,invite_token_ciphertext,revision FROM event WHERE id=?",e);
        String path="/api/v1/events/"+e+"/invite/rotate", body="{\"expectedEventRevision\":\"3\"}";
        var rejected=send(post(path,body).setHeader("Origin","https://evil.example.invalid").header("Cookie",chiefCookie));
        error(rejected,403,"CSRF_REJECTED");
        assertThat(expected).contains(json.readTree(rejected.body()).get("code").asString());
        assertThat(rejected.body()).doesNotContain(token,Base64.getEncoder().encodeToString(new byte[32]));
        error(send(post(path,body)),401,"UNAUTHENTICATED");
        // Bearer transport with a WEB Origin is rejected before credential authentication.
        error(send(post(path,body).header("Authorization","Bearer syntactically.valid.token")),403,"AUTH_CHANNEL_MISMATCH");
        assertThat(jdbc.queryForMap("SELECT invite_token_hash,invite_token_ciphertext,revision FROM event WHERE id=?",e)).isEqualTo(before);
        assertThat(rotate(e,"3",chiefCookie).statusCode()).isEqualTo(200);
        assertThat(storedToken(e)).isNotEqualTo(token);
        assertThat(output.getAll()).doesNotContain(token,Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test void securityAndInputBoundaries() throws Exception {
        UUID e=create("security");open(e);
        error(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+e+"/invite"))).GET()),401,"UNAUTHENTICATED");
        error(send(post("/api/v1/events/"+e+"/invite/rotate","{\"expectedEventRevision\":\"3\"}")
                .setHeader("Origin","https://evil.example.invalid").header("Cookie",chiefCookie)),403,"CSRF_REJECTED");
        error(rotate(e,"9223372036854775808",chiefCookie),400,"INVALID_REQUEST");
        error(rotate(e,"03",chiefCookie),400,"INVALID_REQUEST");
        error(rotate(e,"2",chiefCookie),409,"VERSION_CONFLICT");
        error(get("/api/v1/invites/unknown/options",chiefCookie),404,"NOT_FOUND");
    }

    @Test void committedRotationRejectsJoinAlreadyWaitingOnEvent() throws Exception {
        UUID e=create("joinrace");open(e);String old=storedToken(e),cookie=login(worker);UUID g=groupId(e);
        race(e,()->invites.rotate(e,chief,3),()->join(old,cookie,g,"3"),404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE event_id=?",Long.class,e)).isEqualTo(1);
    }
    @Test void committedTransferRejectsWaitingPreviousChiefRotation() throws Exception {
        UUID e=create("transferrace");open(e);String t=storedToken(e),w=login(worker);UUID g=groupId(e);
        assertThat(join(t,w,g,"3").statusCode()).isEqualTo(200);
        UUID successor=jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,e,worker);
        race(e,()->transfers.transfer(e,chief,successor,g,4),()->rotate(e,"4",chiefCookie),403);
        assertThat(storedToken(e)).isEqualTo(t);
    }
    @Test void committedEndRejectsWaitingRotation() throws Exception {
        UUID e=create("endrace");open(e);String t=storedToken(e);
        race(e,()->lifecycle.end(e,chief,null,3),()->rotate(e,"3",chiefCookie),409);
        assertThat(storedToken(e)).isEqualTo(t);
    }
    @Test void rotationAndWaitingWorkerDeactivationBothCommitInEventFirstOrder() throws Exception {
        UUID e=create("deactivation-race");open(e);String old=storedToken(e),cookie=login(worker);
        assertThat(join(old,cookie,groupId(e),"3").statusCode()).isEqualTo(200);
        race(e,()->invites.rotate(e,chief,4),()->send(post("/api/v1/me/deactivate",
                "{\"password\":\"SyntheticPass8!\"}").header("Cookie",cookie).header("X-SceneCrew-CSRF","1")),200);
        assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id=?",String.class,worker)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT active FROM member WHERE event_id=? AND account_id=?",Boolean.class,e,worker)).isFalse();
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,e)).isEqualTo(6);
        assertThat(storedToken(e)).isNotEqualTo(old);
        assertThat(get("/api/v1/events/"+e+"/invite",chiefCookie).statusCode()).isEqualTo(200);
        error(join(old,login(outsider),groupId(e),"6"),404,"NOT_FOUND");
    }
    @Test void optionsRechecksHashAfterRealLockWait() throws Exception {
        UUID e=create("optionrace");open(e);String old=storedToken(e),w=login(worker);
        race(e,()->invites.rotate(e,chief,3),()->get("/api/v1/invites/"+old+"/options",w),404);
    }
    void race(UUID e,Runnable change,java.util.concurrent.Callable<HttpResponse<String>> waiting,int status) throws Exception {
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx->{
                jdbc.queryForObject("SELECT id FROM event WHERE id=? FOR UPDATE",UUID.class,e); locked.countDown();
                try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("timeout");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
                change.run();
            }));
            try {
                assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();var waiter=pool.submit(waiting);
                awaitEventWait();release.countDown();writer.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(waiter.get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(status);
            } finally {release.countDown();}
        }
    }
    void awaitEventWait() throws Exception { awaitQueryWait("%FROM event WHERE id=%FOR UPDATE%"); }
    void awaitQueryWait(String pattern) throws Exception {
        long until=System.nanoTime()+java.time.Duration.ofSeconds(8).toNanos();
        while(System.nanoTime()<until) {
            // pg_stat_activity can retain a snapshot inside the lock-holder transaction.
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)",Boolean.class,pattern)))return;
            Thread.sleep(20);
        }
        fail("Expected a real PostgreSQL row lock wait: " + pattern);
    }
    @Test void deferredRotationCommitFailureLeavesAllThreeColumnsUnchanged() throws Exception {
        UUID e=create("commitfail");open(e);
        var before=jdbc.queryForMap("SELECT invite_token_hash,invite_token_ciphertext,revision FROM event WHERE id=?",e);
        String name="fail_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        try {
            jdbc.execute("CREATE CONSTRAINT TRIGGER "+name+" AFTER UPDATE ON event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.id='"+e+"'::uuid) EXECUTE FUNCTION "+name+"()");
            try {error(rotate(e,"3",chiefCookie),503,"SERVICE_UNAVAILABLE");}
            finally {jdbc.execute("DROP TRIGGER "+name+" ON event");}
        } finally {jdbc.execute("DROP FUNCTION "+name+"()");}
        assertThat(jdbc.queryForMap("SELECT invite_token_hash,invite_token_ciphertext,revision FROM event WHERE id=?",e)).isEqualTo(before);
    }
    @Test void issuanceDatabaseFailureRollsBackEventChiefAndReceipt() throws Exception {
        String name="fail_"+UUID.randomUUID().toString().replace("-","");
        long count=jdbc.queryForObject("SELECT count(*) FROM event",Long.class);
        jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        try {
            jdbc.execute("CREATE TRIGGER "+name+" BEFORE UPDATE OF invite_token_ciphertext ON event FOR EACH ROW EXECUTE FUNCTION "+name+"()");
            try {
                assertThatThrownBy(()->lifecycle.create(chief,UUID.randomUUID().toString(),"fail","")).isInstanceOf(RuntimeException.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM event",Long.class)).isEqualTo(count);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE account_id=?",Long.class,chief)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE actor_account_id=?",Long.class,chief)).isZero();
            } finally {jdbc.execute("DROP TRIGGER "+name+" ON event");}
        } finally {jdbc.execute("DROP FUNCTION "+name+"()");}
    }
    @Test void optionsOnlyExposeCurrentEventAndOwnParticipation() throws Exception {
        UUID e=create("mine");open(e);String t=storedToken(e),w=login(worker);
        UUID other=create("other");open(other);
        jdbc.update("UPDATE work_group SET name='private-other-group' WHERE event_id=?",other);
        jdbc.update("UPDATE task SET title='private-other-task' WHERE event_id=?",other);
        assertThat(join(t,w,groupId(e),"3").statusCode()).isEqualTo(200);
        var result=get("/api/v1/invites/"+t+"/options",w);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).doesNotContain("private-other",other.toString(),chief.toString());
        assertThat(json.readTree(result.body()).get("myParticipation").get("eventRole").asString()).isEqualTo("WORKER");
    }

    @Test void androidBearerCanReadOptionsAndRotateWithoutCookies() throws Exception {
        UUID e=create("android");open(e);
        var login=send(post("/api/v1/auth/login","{\"email\":\""+chief+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                .setHeader("Origin",ANDROID).header("X-SceneCrew-Client","ANDROID"));
        assertThat(login.statusCode()).isEqualTo(200);
        String access=json.readTree(login.body()).get("accessToken").asString();
        String authorization="Bearer "+access;
        var read=send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+e+"/invite"))).header("Authorization",authorization).header("Origin",ANDROID).GET());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/api/v1/invites/"+storedToken(e)+"/options"))).header("Authorization",authorization).header("Origin",ANDROID).GET()).statusCode()).isEqualTo(200);
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+e+"/invite/rotate"))).header("Authorization",authorization).header("Origin",ANDROID)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"expectedEventRevision\":\"3\"}"))).statusCode()).isEqualTo(200);
        error(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+e+"/invite"))).header("Authorization",authorization).header("Origin",ANDROID).header("Cookie",chiefCookie).GET()),400,"MIXED_AUTH_CREDENTIALS");
    }
    @Test void revokedWebSessionCannotReadAndErrorsDoNotLogToken(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        UUID e=create("logs");open(e);String token=storedToken(e);
        var signedIn=send(post("/api/v1/auth/login","{\"email\":\""+worker+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                .header("X-SceneCrew-Client","WEB").header("X-SceneCrew-CSRF","1"));
        assertThat(signedIn.statusCode()).isEqualTo(200);
        String cookies=signedIn.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0]).collect(java.util.stream.Collectors.joining("; "));
        String cookie=signedIn.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0]).filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
        error(get("/api/v1/invites/"+token+"/options?unknown=1",cookie),400,"INVALID_REQUEST");
        var logout=send(post("/api/v1/auth/logout","{}").header("Cookie",cookies).header("X-SceneCrew-Logout-Attempt","AAAAAAAAAAAAAAAAAAAAAA").header("X-SceneCrew-CSRF","1").header("X-SceneCrew-Client","WEB"));
        assertThat(logout.statusCode()).isIn(200,204);
        var rejected=get("/api/v1/invites/"+token+"/options",cookie);
        error(rejected,401,"UNAUTHENTICATED");
        assertThat(rejected.body()).doesNotContain(token);
        assertThat(rejected.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(output.getAll()).doesNotContain(token);
    }
    @Test void liveKeepsPublishedLinkAndAllowsOptionsAndJoin() throws Exception {
        UUID e=create("live");open(e);String token=storedToken(e);
        lifecycle.start(e,chief,3);
        assertThat(storedToken(e)).isEqualTo(token);
        String cookie=login(worker);
        assertThat(get("/api/v1/invites/"+token+"/options",cookie).statusCode()).isEqualTo(200);
        assertThat(join(token,cookie,groupId(e),"4").statusCode()).isEqualTo(200);
    }
    @Test void joinHoldingEventWinsBeforeStaleRotationWithoutChangingLink() throws Exception {
        UUID e=create("join-first");open(e);String token=storedToken(e),cookie=login(worker);UUID group=groupId(e);
        var joining=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
        var rotating=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<HttpResponse<String>>>();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx->{
                jdbc.queryForObject("SELECT id FROM work_group WHERE id=? FOR UPDATE",UUID.class,group);
                joining.set(pool.submit(()->join(token,cookie,group,"3")));
                try {
                    awaitQueryWait("%FROM work_group%FOR SHARE%");
                    rotating.set(pool.submit(()->rotate(e,"3",chiefCookie)));
                    awaitEventWait();
                } catch(Exception failure) {throw new IllegalStateException(failure);}
            });
            assertThat(joining.get().get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(rotating.get().get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        }
        assertThat(storedToken(e)).isEqualTo(token);
    }
    void open(UUID e) throws Exception {plan(e);assertThat(send(patch("/api/v1/events/"+e,complete("1")).header("Cookie",chiefCookie)).statusCode()).isEqualTo(200);assertThat(publish(e,"2").statusCode()).isEqualTo(200);}
    UUID groupId(UUID e) {return jdbc.queryForObject("SELECT id FROM work_group WHERE event_id=? LIMIT 1",UUID.class,e);}
    String storedToken(UUID e) {return jdbc.queryForObject("SELECT invite_token_hash,invite_token_ciphertext FROM event WHERE id=?",(r,n)->cipher.decrypt(e,r.getString(1),r.getString(2)),e);}
    HttpResponse<String> get(String path,String cookie) throws Exception {return send(HttpRequest.newBuilder(URI.create(url(path))).header("Cookie",cookie).GET());}
    HttpResponse<String> rotate(UUID e,String revision,String cookie) throws Exception {return send(post("/api/v1/events/"+e+"/invite/rotate","{\"expectedEventRevision\":\""+revision+"\"}").header("Cookie",cookie));}
    HttpResponse<String> join(String token,String cookie,UUID group,String revision) throws Exception {
        return send(post("/api/v1/invites/"+token+"/join","{\"workGroupId\":\""+group+"\",\"available\":true,\"isReserve\":false,\"expectedEventRevision\":\""+revision+"\"}")
                .header("Cookie",cookie).header("X-SceneCrew-CSRF","1").header("Idempotency-Key",UUID.randomUUID().toString()));
    }
    /** 최소 계획: 구역 1·활성 그룹 1·업무 1 */
    void plan(UUID event) {
        task(event,group(event,"기본조",true),zone(event,"기본구역"),"base","2026-11-18T01:00:00Z","2026-11-18T02:00:00Z");
    }
    UUID zone(UUID event,String name) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,?,NULL,'[]'::jsonb)",id,event,name);
        return id;
    }
    UUID group(UUID event,String name,boolean active) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,?,'',?)",id,event,name,active);
        return id;
    }
    UUID program(UUID event,UUID zone,String name,String start,String end) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO program (id,event_id,zone_id,name,start_at,end_at) VALUES (?,?,?,?,?::timestamptz,?::timestamptz)",
                id,event,zone,name,start,end);
        return id;
    }
    UUID task(UUID event,UUID group,UUID zone,String bundle,String start,String end) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,program_id,source_issue_id,title,start_at,end_at,steps,checklist,status)
                VALUES (?,?,?,?,NULL,?,NULL,NULL,'업무',?::timestamptz,?::timestamptz,'["준비"]'::jsonb,'[]'::jsonb,'WAITING')""",
                id,event,group,bundle,zone,start,end);
        return id;
    }
    HttpResponse<String> publish(UUID event,String revision) throws Exception {
        return send(post("/api/v1/events/"+event+"/publish","{\"expectedEventRevision\":\""+revision+"\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
    }
    /** 공개 거절의 details.fields를 "field:reason[:id]"로 */
    List<String> publishFields(UUID event,String revision) throws Exception {
        var r=publish(event,revision);
        error(r,422,"EVENT_INCOMPLETE");
        var out=new ArrayList<String>();
        for (JsonNode f:json.readTree(r.body()).get("details").get("fields"))
            out.add(f.get("field").asString()+":"+f.get("reason").asString()+(f.has("id")?":"+f.get("id").asString():""));
        return out;
    }

    static String complete(String revision) {
        return """
                {"date":"2026-11-18","prepStartAt":"2026-11-18T09:00:00+09:00","openAt":"2026-11-18T10:00:00+09:00",
                 "closeAt":"2026-11-18T20:00:00+09:00","teardownEndAt":"2026-11-18T21:00:00+09:00","locationName":"합성 시연장",
                 "address":"합성 장소 주소","totalCapacity":"10","expectedVisitors":"50","peakVisitors":null,
                 "desiredReserveCount":"1","expectedEventRevision":"%s"}""".formatted(revision);
    }
    UUID create(String name) throws Exception {
        var r=send(post("/api/v1/events","{\"name\":\""+name+"\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return UUID.fromString(json.readTree(r.body()).get("id").asString());
    }
    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                encoder.encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    String login(UUID account) throws Exception {
        var r=send(post("/api/v1/auth/login","{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                .header("X-SceneCrew-Client","WEB").header("X-SceneCrew-CSRF","1"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }
    String url(String path) { return "http://localhost:"+port+path; }
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
}

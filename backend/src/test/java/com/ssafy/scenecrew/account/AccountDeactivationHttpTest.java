package com.ssafy.scenecrew.account;

import java.net.URI;
import java.net.http.*;
import java.time.LocalDate;
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
import com.ssafy.scenecrew.account.service.AccountAnonymizer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** SC-API-018 탈퇴·삭제 요청(#334, V9-D39)의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis와 익명화 작업. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AccountDeactivationHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AccountDeactivationHttpTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final String PASSWORD="SyntheticPass8!";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"crew-test"); r.add("scenecrew.auth.audience",()->"crew-test");
        r.add("scenecrew.auth.web-origins",()->"https://web.example.invalid");
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountAnonymizer anonymizer;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>(), events=new ArrayList<>();
    UUID chief, worker, live, ended, group, zone, workerMember;

    @BeforeEach void fixture() {
        chief=account(); worker=account();
        live=event("LIVE"); ended=event("ENDED");
        group=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,'음향','',true)",group,live);
        zone=UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,'무대',NULL,'[]'::jsonb)",zone,live);
        member(live,chief,"CHIEF",null);
        workerMember=member(live,worker,"WORKER",group);
        // 종료된 공연의 총괄이었던 것은 탈퇴를 막지 않는다.
        member(ended,worker,"CHIEF",null);
    }
    @AfterEach void cleanup() {
        for (var e:events) {
            jdbc.update("DELETE FROM operation_log WHERE event_id=?",e);
            jdbc.update("DELETE FROM task WHERE event_id=?",e);
            jdbc.update("DELETE FROM zone WHERE event_id=?",e);
            jdbc.update("DELETE FROM device_token WHERE member_id IN (SELECT id FROM member WHERE event_id=?)",e);
            jdbc.update("DELETE FROM member WHERE event_id=?",e);
            jdbc.update("DELETE FROM work_group WHERE event_id=?",e);
            jdbc.update("DELETE FROM event WHERE id=?",e);
        }
        for (var a:accounts) {
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",a);
            jdbc.update("DELETE FROM account WHERE id=?",a);
        }
        events.clear(); accounts.clear();
    }

    @Test void workerDeactivatesReleasesWaitingTasksAndLosesAccessImmediately() throws Exception {
        UUID waiting=task("WAITING",workerMember), done=task("DONE",workerMember);
        long liveRevision=revision(live), endedRevision=revision(ended);
        String cookie=login(worker);
        var r=deactivate(cookie,PASSWORD);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(r.headers().allValues("Set-Cookie")).anyMatch(c->c.startsWith("__Secure-sc-access=;") && c.contains("Max-Age=0"))
                .anyMatch(c->c.startsWith("__Secure-sc-refresh=;") && c.contains("Max-Age=0"));
        var body=json.readTree(r.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("status","deactivatedAt","receiptNo","deletionDueAt");
        assertThat(body.get("status").asString()).isEqualTo("DISABLED");
        assertThat(body.get("receiptNo").asString()).matches("DEL-\\d{8}-[0-9A-F]{8}")
                .endsWith(worker.toString().substring(0,8).toUpperCase());
        var at=java.time.Instant.parse(body.get("deactivatedAt").asString());
        assertThat(java.time.Instant.parse(body.get("deletionDueAt").asString())).isEqualTo(at.plusSeconds(24*3600));

        assertThat(jdbc.queryForMap("SELECT status, token_version FROM account WHERE id=?",worker))
                .containsEntry("status","DISABLED").containsEntry("token_version",2L);
        assertThat(jdbc.queryForObject("SELECT deactivated_at FROM account WHERE id=?",java.sql.Timestamp.class,worker).toInstant())
                .isEqualTo(at);
        // 대기 업무만 담당을 풀고 끝난 업무 기록은 그대로 둔다.
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,waiting)).isNull();
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,done)).isEqualTo(workerMember);
        assertThat(jdbc.queryForList("SELECT active FROM member WHERE account_id=?",Boolean.class,worker)).containsOnly(false);
        assertThat(jdbc.queryForObject("SELECT participation_revision FROM member WHERE id=?",Long.class,workerMember)).isEqualTo(2L);
        assertThat(revision(live)).isEqualTo(liveRevision+1);
        assertThat(revision(ended)).isEqualTo(endedRevision);
        assertThat(jdbc.queryForObject("""
                SELECT changes->>'releasedTaskIds' FROM operation_log WHERE event_id=? AND type='MEMBER_LEFT_BY_ACCOUNT_DEACTIVATION'
                """,String.class,live)).contains(waiting.toString());

        // 같은 세션은 다음 요청부터 막히고, 같은 비밀번호로 다시 로그인할 수 없다.
        error(send(get("/api/v1/me").header("Cookie",cookie)),401,"UNAUTHENTICATED");
        error(deactivate(cookie,PASSWORD),401,"UNAUTHENTICATED");
        var again=send(loginRequest(worker));
        assertThat(again.statusCode()).as(again.body()).isEqualTo(401);
    }

    @Test void chiefOfUnfinishedEventMustTransferFirst() throws Exception {
        var r=deactivate(login(chief),PASSWORD);
        error(r,409,"CHIEF_TRANSFER_REQUIRED");
        assertThat(json.readTree(r.body()).get("details").get("eventIds").get(0).asString()).isEqualTo(live.toString());
        assertUntouched(chief);
    }

    @Test void inProgressTaskBlocksDeactivation() throws Exception {
        UUID waiting=task("WAITING",workerMember);
        task("IN_PROGRESS",workerMember);
        var r=deactivate(login(worker),PASSWORD);
        error(r,409,"TASK_IN_PROGRESS");
        assertThat(json.readTree(r.body()).get("details").get("eventIds").get(0).asString()).isEqualTo(live.toString());
        assertUntouched(worker);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,waiting)).isEqualTo(workerMember);
    }

    @Test void wrongPasswordAndBadInputAreRejectedWithoutChanges() throws Exception {
        String cookie=login(worker);
        error(deactivate(cookie,"WrongPass8!"),403,"REAUTHENTICATION_REQUIRED");
        for (String body:List.of("{}","{\"password\":1}","{\"password\":\"x\",\"other\":1}","[]"))
            error(send(post("/api/v1/me/deactivate",body).header("Cookie",cookie).header("Origin","https://web.example.invalid")
                    .header("X-SceneCrew-CSRF","1")),400,"INVALID_REQUEST");
        // 쓰기 검사: 허용 Origin·CSRF가 없으면 거절한다.
        var noCsrf=send(post("/api/v1/me/deactivate","{\"password\":\""+PASSWORD+"\"}").header("Cookie",cookie)
                .header("Origin","https://web.example.invalid"));
        assertThat(noCsrf.statusCode()).as(noCsrf.body()).isEqualTo(403);
        error(send(post("/api/v1/me/deactivate","{\"password\":\""+PASSWORD+"\"}")),401,"UNAUTHENTICATED");
        assertUntouched(worker);
    }

    @Test void anonymizerRemovesIdentityDevicesAndNamesButKeepsRecords() throws Exception {
        UUID done=task("DONE",workerMember);
        jdbc.update("INSERT INTO device_token (id,member_id,device_id,fcm_token,active,updated_at) VALUES (?,?,?,?,true,now())",
                UUID.randomUUID(),workerMember,"device-1","fcm-synthetic");
        var deactivated=deactivate(login(worker),PASSWORD);
        assertThat(deactivated.statusCode()).isEqualTo(200);
        String receipt=json.readTree(deactivated.body()).get("receiptNo").asString();
        String due=json.readTree(deactivated.body()).get("deletionDueAt").asString();
        assertThat(jdbc.queryForObject("SELECT active FROM device_token WHERE member_id=?",Boolean.class,workerMember)).isFalse();
        // 처리 상태는 로그인 없이 접수 번호로 본다. 이름·이메일은 돌려주지 않는다.
        var status=send(get("/api/v1/deletion-requests/"+receipt));
        assertThat(status.statusCode()).as(status.body()).isEqualTo(200);
        assertThat(status.headers().firstValue("Cache-Control")).hasValue("no-store");
        var statusBody=json.readTree(status.body());
        assertThat(statusBody.propertyNames()).containsExactlyInAnyOrder("receiptNo","status","deletionDueAt");
        assertThat(statusBody.get("status").asString()).isEqualTo("PROCESSING");
        assertThat(java.time.Instant.parse(statusBody.get("deletionDueAt").asString())).isEqualTo(java.time.Instant.parse(due));

        assertThat(anonymizer.processPending()).isGreaterThanOrEqualTo(1);
        assertThat(json.readTree(send(get("/api/v1/deletion-requests/"+receipt)).body()).get("status").asString())
                .isEqualTo("COMPLETED");
        // 없는 번호·형식이 틀린 번호·활성 계정은 모두 404다.
        for (String other:List.of("DEL-20000101-ABCDEF12","DEL-2026-1106-AAAA","del-"+receipt.substring(4),
                "DEL-"+receipt.substring(4,12)+"-"+chief.toString().substring(0,8).toUpperCase()))
            error(send(get("/api/v1/deletion-requests/"+other)),404,"NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT display_name FROM account WHERE id=?",String.class,worker))
                .isEqualTo(AccountAnonymizer.ANONYMOUS_NAME);
        assertThat(jdbc.queryForList("SELECT display_name FROM member WHERE account_id=?",String.class,worker))
                .hasSize(2).containsOnly(AccountAnonymizer.ANONYMOUS_NAME);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id=?",Long.class,worker)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_token WHERE member_id=?",Long.class,workerMember)).isZero();
        // 업무 기록과 다른 사람의 이름은 그대로다.
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,done)).isEqualTo(workerMember);
        assertThat(jdbc.queryForObject("SELECT display_name FROM account WHERE id=?",String.class,chief)).isEqualTo("fixture");
        // 같은 이메일로 다시 가입할 수 있도록 로그인 수단이 남지 않는다. 두 번째 실행은 할 일이 없다.
        assertThat(anonymizer.processPending()).isZero();
    }

    void assertUntouched(UUID account) {
        assertThat(jdbc.queryForMap("SELECT status, token_version FROM account WHERE id=?",account))
                .containsEntry("status","ACTIVE").containsEntry("token_version",1L);
        assertThat(jdbc.queryForObject("SELECT deactivated_at FROM account WHERE id=?",java.sql.Timestamp.class,account)).isNull();
        assertThat(jdbc.queryForObject("SELECT bool_and(active) FROM member WHERE account_id=? AND event_id=?",Boolean.class,account,live))
                .isTrue();
    }
    long revision(UUID event) { return jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event); }
    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                new BCryptPasswordEncoder(4).encode(PASSWORD),id+"@example.invalid");
        return id;
    }
    UUID event(String state) {
        var id=UUID.randomUUID(); events.add(id);
        jdbc.update("INSERT INTO event (id,name,state,revision,date,last_input_modified_at) VALUES (?,?,?,?,?,now())",
                id,"공연",state,1L,LocalDate.of(2026,11,14));
        return id;
    }
    UUID member(UUID event,UUID account,String role,UUID group) {
        var id=UUID.randomUUID();
        Long crew=jdbc.queryForObject("SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?",Long.class,event);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,?,?,'fixture',true,false,true,1)""",id,event,account,group,crew,role);
        return id;
    }
    UUID task(String status,UUID assignee) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,program_id,source_issue_id,title,start_at,end_at,steps,checklist,status)
                VALUES (?,?,?,?,?,?,NULL,NULL,'업무','2026-11-14T10:00:00Z'::timestamptz,'2026-11-14T11:00:00Z'::timestamptz,'["준비"]'::jsonb,'[]'::jsonb,?)""",
                id,live,group,"b-"+id,assignee,zone,status);
        return id;
    }
    HttpResponse<String> deactivate(String cookie,String password) throws Exception {
        return send(post("/api/v1/me/deactivate","{\"password\":\""+password+"\"}").header("Cookie",cookie)
                .header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1"));
    }
    HttpRequest.Builder loginRequest(UUID account) {
        return post("/api/v1/auth/login","{\"email\":\""+account+"@example.invalid\",\"password\":\""+PASSWORD+"\"}")
                .header("X-SceneCrew-Client","WEB").header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1");
    }
    String login(UUID account) throws Exception {
        var r=send(loginRequest(account));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }
    HttpRequest.Builder get(String path) { return HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET(); }
    HttpRequest.Builder post(String path,String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
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

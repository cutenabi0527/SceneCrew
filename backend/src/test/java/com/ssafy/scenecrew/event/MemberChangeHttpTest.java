package com.ssafy.scenecrew.event;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
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

/**
 * SC-API-038 팀원 그룹 이동·예비 지정과 SC-API-040 행사 이탈의 실제 HTTP·제품 Security·PostgreSQL(V9-D44).
 * 응답 키는 계약(MemberChanged·DepartureReceipt, FE v9 schemas.json)과 정확히 같은지 본다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MemberChangeHttpTest.Beans.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MemberChangeHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final Set<String> MEMBER_KEYS=Set.of("memberId","displayName","workGroupId","eventRole","active","available","isReserve",
            "participationRevision");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"member-change-test"); r.add("scenecrew.auth.audience",()->"member-change-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->ANDROID);
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired com.ssafy.scenecrew.invite.service.InviteCipher cipher;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>();
    UUID chief, worker, outsider;
    String chiefCookie, workerCookie, outsiderCookie;

    @BeforeEach void fixture() throws Exception {
        chief=account(); worker=account(); outsider=account();
        chiefCookie=login(chief); workerCookie=login(worker); outsiderCookie=login(outsider);
    }
    @AfterEach void cleanup() {
        for (var a:accounts) {
            for (var e:jdbc.queryForList("SELECT DISTINCT event_id FROM member WHERE account_id=?",UUID.class,a)) {
                jdbc.update("DELETE FROM idempotency_record WHERE event_id=?",e);
                jdbc.update("DELETE FROM operation_log WHERE event_id=?",e);
                jdbc.update("DELETE FROM change_acknowledgment WHERE event_id=?",e);
                jdbc.update("DELETE FROM device_token WHERE member_id IN (SELECT id FROM member WHERE event_id=?)",e);
                jdbc.update("DELETE FROM task WHERE event_id=?",e);
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

    // ---- 038 그룹 이동·예비 지정 ------------------------------------------------------------------------------

    @Test void moveReleasesWaitingTasksAndReserveKeepsThem() throws Exception {
        UUID event=recruiting();
        UUID a=group(event,"입장 안내"), b=group(event,"무대 지원"), zone=zone(event);
        UUID me=member(event,worker,a);
        UUID waiting=task(event,a,zone,me,"WAITING");
        String rev=revision(event);

        var moved=patch(me,"{\"workGroupId\":\""+b+"\",\"expectedEventRevision\":\""+rev+"\"}",chiefCookie);
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        var body=json.readTree(moved.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("member","eventRevision");
        assertThat(body.get("member").propertyNames()).containsExactlyInAnyOrderElementsOf(MEMBER_KEYS);
        assertThat(body.get("member").get("workGroupId").asString()).isEqualTo(b.toString());
        assertThat(body.get("member").get("participationRevision").asString()).isEqualTo("1");
        assertThat(body.get("eventRevision").asString()).isEqualTo(Long.toString(Long.parseLong(rev)+1));
        // 그룹 이동은 대기 업무의 담당을 비운다(USER_SELECTED_D04).
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,waiting)).isNull();
        // 같은 값만 보내면 아무것도 바꾸지 않는다.
        rev=revision(event);
        var same=patch(me,"{\"workGroupId\":\""+b+"\",\"expectedEventRevision\":\""+rev+"\"}",chiefCookie);
        assertThat(json.readTree(same.body()).get("eventRevision").asString()).isEqualTo(rev);

        // 예비 지정은 맡은 업무를 해제하지 않는다.
        UUID kept=task(event,b,zone,me,"WAITING");
        var reserve=patch(me,"{\"isReserve\":true,\"expectedEventRevision\":\""+rev+"\"}",chiefCookie);
        assertThat(reserve.statusCode()).as(reserve.body()).isEqualTo(200);
        assertThat(json.readTree(reserve.body()).get("member").get("isReserve").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,kept)).isEqualTo(me);

        // 진행 중 업무가 있으면 이동을 거절하고 아무것도 바꾸지 않는다.
        jdbc.update("UPDATE task SET status='IN_PROGRESS' WHERE id=?",kept);
        rev=revision(event);
        error(patch(me,"{\"workGroupId\":\""+a+"\",\"expectedEventRevision\":\""+rev+"\"}",chiefCookie),409,"INVALID_STATE");
        assertThat(jdbc.queryForObject("SELECT work_group_id FROM member WHERE id=?",UUID.class,me)).isEqualTo(b);
        assertThat(revision(event)).isEqualTo(rev);
    }

    @Test void updateRules() throws Exception {
        UUID event=recruiting();
        UUID a=group(event,"입장 안내");
        UUID me=member(event,worker,a);
        String rev=revision(event);
        String body="{\"isReserve\":true,\"expectedEventRevision\":\""+rev+"\"}";
        error(patch(me,body,workerCookie),403,"FORBIDDEN");
        error(patch(me,body,outsiderCookie),404,"NOT_FOUND");
        error(patch(me,"{\"isReserve\":true,\"expectedEventRevision\":\"99\"}",chiefCookie),409,"VERSION_CONFLICT");
        field(patch(me,"{\"expectedEventRevision\":\""+rev+"\"}",chiefCookie),"body","NO_CHANGE");
        field(patch(me,"{\"isReserve\":true,\"expectedEventRevision\":\""+rev+"\",\"available\":true}",chiefCookie),"available","UNKNOWN");
        field(patch(me,"{\"workGroupId\":null,\"expectedEventRevision\":\""+rev+"\"}",chiefCookie),"workGroupId","FORMAT");
        // 다른 행사 그룹은 422, 총괄 대상은 409
        UUID foreignGroup=group(recruiting(),"남의 그룹");
        error(patch(me,"{\"workGroupId\":\""+foreignGroup+"\",\"expectedEventRevision\":\""+rev+"\"}",chiefCookie),422,"INVALID_REFERENCE");
        var chiefTarget=patch(memberId(event,chief),body,chiefCookie);
        error(chiefTarget,409,"INVALID_STATE");
        assertThat(json.readTree(chiefTarget.body()).get("details").get("reason").asString()).isEqualTo("CHIEF_TARGET");
        // 끝난 행사는 바꾸지 않는다.
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        error(patch(me,body,chiefCookie),409,"INVALID_STATE");
    }

    // ---- 040 행사 이탈 ----------------------------------------------------------------------------------------

    @Test void leaveReleasesWaitingAndCanRejoin() throws Exception {
        UUID event=recruiting();
        UUID a=group(event,"입장 안내"), zone=zone(event);
        UUID me=member(event,worker,a);
        UUID waiting=task(event,a,zone,me,"WAITING");
        jdbc.update("INSERT INTO device_token (id,member_id,device_id,fcm_token,active,updated_at) VALUES (?,?,'phone','fcm',true,now())",
                UUID.randomUUID(),me);
        String rev=revision(event);
        String key=UUID.randomUUID().toString();
        var left=leave(event,workerCookie,key);
        assertThat(left.statusCode()).as(left.body()).isEqualTo(200);
        var receipt=json.readTree(left.body());
        assertThat(receipt.propertyNames()).containsExactlyInAnyOrder("participation","eventRevision");
        var p=receipt.get("participation");
        assertThat(p.propertyNames()).containsExactlyInAnyOrder("memberId","participationRevision","active","eventRole","workGroupId");
        assertThat(p.get("active").asBoolean()).isFalse();
        assertThat(p.get("participationRevision").asString()).isEqualTo("1");
        assertThat(receipt.get("eventRevision").asString()).isEqualTo(Long.toString(Long.parseLong(rev)+1));
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM task WHERE id=?",UUID.class,waiting)).isNull();
        assertThat(jdbc.queryForObject("SELECT active FROM device_token WHERE member_id=?",Boolean.class,me)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_log WHERE resource_id=? AND type='MEMBER_LEFT'",Integer.class,me)).isOne();
        // 같은 키 재요청은 첫 영수증(이미 비활성이어도), 떠난 뒤에는 행사를 보지 못한다.
        assertThat(json.readTree(leave(event,workerCookie,key).body())).isEqualTo(receipt);
        assertThat(get("/api/v1/events/"+event,workerCookie).statusCode()).isEqualTo(404);
        error(leave(event,workerCookie,UUID.randomUUID().toString()),404,"NOT_FOUND");

        // 초대 링크로 다시 참여하면 참여 회차가 오른다(이탈은 회차를 올리지 않는다).
        String token=storedToken(event);
        var joined=send(post("/api/v1/invites/"+token+"/join","{\"workGroupId\":\""+a+"\",\"available\":true,\"isReserve\":false,\"expectedEventRevision\":\""
                +revision(event)+"\"}").header("Cookie",workerCookie).header("X-SceneCrew-CSRF","1").header("Idempotency-Key",UUID.randomUUID().toString()));
        assertThat(joined.statusCode()).as(joined.body()).isEqualTo(200);
        assertThat(json.readTree(joined.body()).get("participation").get("participationRevision").asString()).isEqualTo("2");
    }

    @Test void leaveRules() throws Exception {
        UUID event=recruiting();
        UUID a=group(event,"입장 안내"), zone=zone(event);
        UUID me=member(event,worker,a);
        UUID busy=task(event,a,zone,me,"IN_PROGRESS");
        // 진행 중 업무가 있으면 거절하고 아무것도 바꾸지 않는다.
        var inProgress=leave(event,workerCookie,UUID.randomUUID().toString());
        error(inProgress,409,"INVALID_STATE");
        assertThat(json.readTree(inProgress.body()).get("details").get("reason").asString()).isEqualTo("TASK_IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT active FROM member WHERE id=?",Boolean.class,me)).isTrue();
        // 종료 전 총괄은 떠날 수 없다.
        var chiefLeave=leave(event,chiefCookie,UUID.randomUUID().toString());
        error(chiefLeave,409,"INVALID_STATE");
        assertThat(json.readTree(chiefLeave.body()).get("details").get("reason").asString()).isEqualTo("CHIEF_TRANSFER_REQUIRED");
        // 비참여 404, 요청 키 없음 400, 허용하지 않은 Origin 403
        error(leave(event,outsiderCookie,UUID.randomUUID().toString()),404,"NOT_FOUND");
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/leave"))).header("Origin",WEB)
                .header("Cookie",workerCookie).POST(HttpRequest.BodyPublishers.noBody())).statusCode()).isEqualTo(400);
        error(send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/leave"))).header("Origin","https://evil.example.invalid")
                .header("Cookie",workerCookie).header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.noBody())),403,"CSRF_REJECTED");
        // 행사가 끝나면 총괄도 떠날 수 있고, revision은 올리지 않는다.
        jdbc.update("UPDATE task SET status='DONE' WHERE id=?",busy);
        jdbc.update("UPDATE event SET state='ENDED' WHERE id=?",event);
        String rev=revision(event);
        var ended=leave(event,chiefCookie,UUID.randomUUID().toString());
        assertThat(ended.statusCode()).as(ended.body()).isEqualTo(200);
        assertThat(revision(event)).isEqualTo(rev);
    }

    @Test void concurrentMoveAndLeaveFinish() throws Exception {
        UUID event=recruiting();
        UUID a=group(event,"입장 안내"), b=group(event,"무대 지원"), zone=zone(event);
        UUID me=member(event,worker,a);
        task(event,a,zone,me,"WAITING");
        String rev=revision(event);
        var pool=Executors.newFixedThreadPool(4);
        try {
            var start=new CountDownLatch(1);
            var futures=new ArrayList<Future<Integer>>();
            for (int i=0;i<2;i++) {
                futures.add(pool.submit(()->{ start.await(); return patch(me,"{\"workGroupId\":\""+b+"\",\"expectedEventRevision\":\""+rev+"\"}",chiefCookie).statusCode(); }));
                futures.add(pool.submit(()->{ start.await(); return leave(event,workerCookie,UUID.randomUUID().toString()).statusCode(); }));
            }
            start.countDown();
            var codes=new ArrayList<Integer>();
            for (var f:futures) codes.add(f.get(30,TimeUnit.SECONDS));
            // 교착 없이 모두 끝나고, 성공은 revision을 잡은 하나뿐이다(나머지는 409 또는 이미 떠나 404).
            assertThat(codes).allMatch(c->c==200||c==409||c==404);
            assertThat(codes.stream().filter(c->c==200).count()).isGreaterThanOrEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE event_id=? AND assignee_id IS NOT NULL",Integer.class,event)).isZero();
    }

    // ---- 도우미 ----------------------------------------------------------------------------------------------

    HttpResponse<String> patch(UUID member,String body,String cookie) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/api/v1/members/"+member))).header("Content-Type","application/json")
                .header("Origin",WEB).header("Cookie",cookie).method("PATCH",HttpRequest.BodyPublishers.ofString(body)));
    }
    HttpResponse<String> leave(UUID event,String cookie,String key) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/leave"))).header("Origin",WEB)
                .header("Cookie",cookie).header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.noBody()));
    }
    String storedToken(UUID e) {
        return jdbc.queryForObject("SELECT invite_token_hash,invite_token_ciphertext FROM event WHERE id=?",
                (r,n)->cipher.decrypt(e,r.getString(1),r.getString(2)),e);
    }
    UUID recruiting() throws Exception {
        var r=send(post("/api/v1/events","{\"name\":\"이동 시험 공연\"}").header("Cookie",chiefCookie)
                .header("Idempotency-Key",UUID.randomUUID().toString()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        UUID id=UUID.fromString(json.readTree(r.body()).get("id").asString());
        jdbc.update("UPDATE event SET state='RECRUITING', total_capacity=10 WHERE id=?",id);
        return id;
    }
    String revision(UUID event) { return Long.toString(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)); }
    UUID memberId(UUID event,UUID account) {
        return jdbc.queryForObject("SELECT id FROM member WHERE event_id=? AND account_id=?",UUID.class,event,account);
    }
    UUID zone(UUID event) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO zone (id,event_id,name,description,aliases) VALUES (?,?,'무대',NULL,'[]'::jsonb)",id,event);
        return id;
    }
    UUID group(UUID event,String name) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group (id,event_id,name,instructions,active) VALUES (?,?,?,'',true)",id,event,name);
        return id;
    }
    UUID task(UUID event,UUID group,UUID zone,UUID assignee,String status) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task (id,event_id,work_group_id,bundle_key,assignee_id,zone_id,program_id,source_issue_id,title,start_at,end_at,steps,checklist,status,first_assigned_at)
                VALUES (?,?,?,'b1',?,?,NULL,NULL,'업무','2026-11-18T09:00:00Z','2026-11-18T10:00:00Z','["준비"]'::jsonb,'[]'::jsonb,?,now())""",
                id,event,group,assignee,zone,status);
        return id;
    }
    UUID member(UUID event,UUID account,UUID group) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,(SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?),'WORKER','팀원',true,false,true,1)""",
                id,event,account,group,event);
        return id;
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
    HttpResponse<String> get(String path,String cookie) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url(path))).header("Cookie",cookie).GET());
    }
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception { return client.send(r.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> r,int status,String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo(code);
    }
    void field(HttpResponse<String> r,String field,String reason) {
        error(r,400,"INVALID_REQUEST");
        var f=json.readTree(r.body()).get("details").get("fields").get(0);
        assertThat(f.get("field").asString()).isEqualTo(field);
        assertThat(f.get("reason").asString()).isEqualTo(reason);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Beans {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}

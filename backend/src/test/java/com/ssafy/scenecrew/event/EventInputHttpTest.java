package com.ssafy.scenecrew.event;

import java.net.URI;
import java.net.http.*;
import java.time.OffsetDateTime;
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
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** SC-API-026 확인한 행사 입력 반영의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis. 한도는 V9-D42. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(EventInputHttpTest.Beans.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EventInputHttpTest {
    static final String WEB="https://web.example.invalid", ANDROID="https://android.example.invalid";
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"input-test"); r.add("scenecrew.auth.audience",()->"input-test");
        r.add("scenecrew.auth.web-origins",()->WEB);
        r.add("scenecrew.auth.android-origins",()->ANDROID);
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
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
                jdbc.update("DELETE FROM intake_document WHERE event_id=?",e);
                jdbc.update("DELETE FROM task WHERE event_id=?",e);
                jdbc.update("DELETE FROM program WHERE event_id=?",e);
                jdbc.update("DELETE FROM zone WHERE event_id=?",e);
                jdbc.update("DELETE FROM member WHERE event_id=?",e);
                jdbc.update("DELETE FROM work_group WHERE event_id=?",e);
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

    @Test void replaceWholeInputAndBumpRevisionOnlyWhenChanged() throws Exception {
        UUID event=create();
        OffsetDateTime before=lastInput(event);
        var r=input(event,"{\"expectedEventRevision\":\"1\",\"confirmedInput\":{\"입장 규칙\":\"QR 확인\",\"가방 검사\":\"\"}}",chiefCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(r.body());
        // 응답은 행사 조회와 같은 Event 모양이고 확인한 조건은 공개하지 않는다.
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id","name","state","revision","date","prepStartAt",
                "openAt","closeAt","teardownEndAt","locationName","address","totalCapacity","expectedVisitors","peakVisitors",
                "desiredReserveCount","myParticipation","description","instructions");
        assertThat(body.get("revision").asString()).isEqualTo("2");
        assertThat(json.readTree(stored(event))).isEqualTo(json.readTree("{\"입장 규칙\":\"QR 확인\",\"가방 검사\":\"\"}"));
        OffsetDateTime changed=lastInput(event);
        assertThat(changed).isAfter(before);

        // 같은 내용(키 순서만 다름)은 바뀐 것이 없으므로 revision·입력 시각을 그대로 둔다.
        var same=input(event,"{\"expectedEventRevision\":\"2\",\"confirmedInput\":{\"가방 검사\":\"\",\"입장 규칙\":\"QR 확인\"}}",chiefCookie);
        assertThat(json.readTree(same.body()).get("revision").asString()).isEqualTo("2");
        assertThat(lastInput(event)).isEqualTo(changed);

        // 전체 교체: 보내지 않은 조건은 사라진다.
        var replaced=input(event,"{\"expectedEventRevision\":\"2\",\"confirmedInput\":{\"입장 규칙\":\"QR과 신분증 확인\"}}",chiefCookie);
        assertThat(json.readTree(replaced.body()).get("revision").asString()).isEqualTo("3");
        assertThat(json.readTree(stored(event))).isEqualTo(json.readTree("{\"입장 규칙\":\"QR과 신분증 확인\"}"));

        // 빈 객체는 모두 비우기다.
        var cleared=input(event,"{\"expectedEventRevision\":\"3\",\"confirmedInput\":{}}",chiefCookie);
        assertThat(json.readTree(cleared.body()).get("revision").asString()).isEqualTo("4");
        assertThat(json.readTree(stored(event))).isEmpty();
    }

    @Test void limitsAndFormat() throws Exception {
        UUID event=create();
        var twenty=new StringBuilder();
        for (int i=0;i<20;i++) twenty.append(i==0?"":",").append("\"조건").append(i).append("\":\"값\"");
        String name40="가".repeat(40), value1000="나".repeat(999)+"😀";
        var ok=input(event,"{\"expectedEventRevision\":\"1\",\"confirmedInput\":{"+twenty+"}}",chiefCookie);
        assertThat(ok.statusCode()).as("20개는 받는다: "+ok.body()).isEqualTo(200);
        var edge=input(event,"{\"expectedEventRevision\":\"2\",\"confirmedInput\":{\""+name40+"\":\""+value1000+"\"}}",chiefCookie);
        assertThat(edge.statusCode()).as("이름 40자·내용 1,000자는 받는다: "+edge.body()).isEqualTo(200);

        Map<String,String> cases=new LinkedHashMap<>();
        cases.put("21개","{\"expectedEventRevision\":\"3\",\"confirmedInput\":{"+twenty+",\"하나 더\":\"값\"}}");
        cases.put("이름 41자","{\"expectedEventRevision\":\"3\",\"confirmedInput\":{\""+name40+"가\":\"값\"}}");
        cases.put("내용 1,001자","{\"expectedEventRevision\":\"3\",\"confirmedInput\":{\"조건\":\""+value1000+"a\"}}");
        cases.put("공백 이름","{\"expectedEventRevision\":\"3\",\"confirmedInput\":{\"  \":\"값\"}}");
        cases.put("문자열 아닌 값","{\"expectedEventRevision\":\"3\",\"confirmedInput\":{\"조건\":1}}");
        cases.put("null 값","{\"expectedEventRevision\":\"3\",\"confirmedInput\":{\"조건\":null}}");
        cases.put("null 사전","{\"expectedEventRevision\":\"3\",\"confirmedInput\":null}");
        cases.put("사전 생략","{\"expectedEventRevision\":\"3\"}");
        cases.put("배열","{\"expectedEventRevision\":\"3\",\"confirmedInput\":[]}");
        cases.put("모르는 키","{\"expectedEventRevision\":\"3\",\"confirmedInput\":{},\"extra\":1}");
        cases.put("숫자 revision","{\"expectedEventRevision\":3,\"confirmedInput\":{}}");
        for (var c:cases.entrySet()) {
            var r=input(event,c.getValue(),chiefCookie);
            assertThat(r.statusCode()).as(c.getKey()+": "+r.body()).isEqualTo(400);
            var f=json.readTree(r.body()).get("details").get("fields").get(0);
            assertThat(f.propertyNames()).as(c.getKey()).containsExactlyInAnyOrder("field","reason");
        }
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(3L);
        var reason=json.readTree(input(event,cases.get("21개"),chiefCookie).body()).get("details").get("fields").get(0);
        assertThat(reason.get("field").asString()).isEqualTo("confirmedInput");
        assertThat(reason.get("reason").asString()).isEqualTo("LIMIT");
    }

    @Test void accessStateAndRevision() throws Exception {
        UUID event=create();
        member(event,worker);
        String body="{\"expectedEventRevision\":\"1\",\"confirmedInput\":{\"입장 규칙\":\"QR 확인\"}}";
        error(input(event,body,workerCookie),403,"FORBIDDEN");
        error(input(event,body,outsiderCookie),404,"NOT_FOUND");
        error(input(UUID.randomUUID(),body,chiefCookie),404,"NOT_FOUND");
        var stale=input(event,body.replace("\"1\"","\"0\""),chiefCookie);
        error(stale,409,"VERSION_CONFLICT");
        assertThat(json.readTree(stale.body()).get("details").get("target").asString()).isEqualTo("event");
        // 로그인 없음 401, 허용하지 않은 Origin 403
        var anonymous=send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/input"))).header("Content-Type","application/json")
                .header("Origin",WEB).method("PATCH",HttpRequest.BodyPublishers.ofString(body)));
        assertThat(anonymous.statusCode()).isEqualTo(401);
        var foreign=send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/input"))).header("Content-Type","application/json")
                .header("Origin","https://evil.example.invalid").header("Cookie",chiefCookie).method("PATCH",HttpRequest.BodyPublishers.ofString(body)));
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(json.readTree(stored(event))).isEmpty();
        // 공개 뒤에는 확인한 입력을 바꾸지 않는다(DRAFT 전용).
        jdbc.update("UPDATE event SET state='RECRUITING' WHERE id=?",event);
        error(input(event,body,chiefCookie),409,"INVALID_STATE");
    }

    @Test void changedInputRestartsRetentionOfLiveDocuments() throws Exception {
        UUID event=create();
        UUID live=document(event,"documents/a","now() + interval '1 day'");
        UUID expired=document(event,"documents/b","now() - interval '1 minute'");
        UUID removed=document(event,null,"now() + interval '1 day'");
        var r=input(event,"{\"expectedEventRevision\":\"1\",\"confirmedInput\":{\"입장 규칙\":\"QR 확인\"}}",chiefCookie);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        // 입력 묶음이 바뀌었으므로 남은 문서만 지금부터 7일로 다시 정한다. 만료됐거나 원본을 지운 문서는 늘리지 않는다.
        assertThat(jdbc.queryForObject("SELECT expires_at > now() + interval '6 days 23 hours' FROM intake_document WHERE id=?",Boolean.class,live)).isTrue();
        assertThat(jdbc.queryForObject("SELECT expires_at < now() FROM intake_document WHERE id=?",Boolean.class,expired)).isTrue();
        assertThat(jdbc.queryForObject("SELECT expires_at < now() + interval '2 days' FROM intake_document WHERE id=?",Boolean.class,removed)).isTrue();
        // 같은 내용을 다시 보내면 바뀐 것이 없으므로 만료도 그대로다.
        jdbc.update("UPDATE intake_document SET expires_at = now() + interval '1 day' WHERE id=?",live);
        input(event,"{\"expectedEventRevision\":\"2\",\"confirmedInput\":{\"입장 규칙\":\"QR 확인\"}}",chiefCookie);
        assertThat(jdbc.queryForObject("SELECT expires_at < now() + interval '2 days' FROM intake_document WHERE id=?",Boolean.class,live)).isTrue();
    }

    @Test void inputAndPlanSaveWithSameRevisionApplyOnce() throws Exception {
        UUID event=create();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->input(event,"{\"expectedEventRevision\":\"1\",\"confirmedInput\":{\"입장 규칙\":\"QR 확인\"}}",chiefCookie));
            var b=pool.submit(()->send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/draft-plan"))).header("Content-Type","application/json")
                    .header("Origin",WEB).header("Cookie",chiefCookie).PUT(HttpRequest.BodyPublishers.ofString("""
                    {"expectedEventRevision":"1","workGroups":[{"tempId":"tmp-g","name":"음향","instructions":""}],
                     "zones":[],"programs":[],"tasks":[],"instructions":[]}"""))));
            int x=a.get(60,TimeUnit.SECONDS).statusCode(), y=b.get(60,TimeUnit.SECONDS).statusCode();
            // 같은 행사 잠금과 revision을 쓰므로 하나만 반영되고 다른 하나는 409다. 교착(500·시간 초과)은 없다.
            assertThat(List.of(x,y)).containsExactlyInAnyOrder(200,409);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT revision FROM event WHERE id=?",Long.class,event)).isEqualTo(2L);
    }

    // ---- 도우미 ------------------------------------------------------------------------------------------------

    UUID document(UUID event,String storageKey,String expiresAtSql) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO intake_document (id,event_id,owner_account_id,original_name,mime,bytes,page_count,sha256,storage_key,created_at,expires_at)"
                + " VALUES (?,?,?,'안내.md','text/markdown',10,NULL,repeat('a',64),?,now(),"+expiresAtSql+")",id,event,chief,storageKey);
        return id;
    }
    String stored(UUID event) { return jdbc.queryForObject("SELECT confirmed_input::text FROM event WHERE id=?",String.class,event); }
    OffsetDateTime lastInput(UUID event) {
        return jdbc.queryForObject("SELECT last_input_modified_at FROM event WHERE id=?",OffsetDateTime.class,event);
    }
    void member(UUID event,UUID account) {
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,NULL,(SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?),'WORKER','팀원',true,false,true,1)""",
                UUID.randomUUID(),event,account,event);
    }
    UUID create() throws Exception {
        var r=send(HttpRequest.newBuilder(URI.create(url("/api/v1/events"))).header("Content-Type","application/json").header("Origin",WEB)
                .header("Cookie",chiefCookie).header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"입력 시험 공연\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return UUID.fromString(json.readTree(r.body()).get("id").asString());
    }
    HttpResponse<String> input(UUID event,String body,String cookie) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url("/api/v1/events/"+event+"/input"))).header("Content-Type","application/json")
                .header("Origin",WEB).header("Cookie",cookie).method("PATCH",HttpRequest.BodyPublishers.ofString(body)));
    }
    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                encoder.encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    String login(UUID account) throws Exception {
        var r=send(HttpRequest.newBuilder(URI.create(url("/api/v1/auth/login"))).header("Content-Type","application/json").header("Origin",WEB)
                .header("X-SceneCrew-Client","WEB").header("X-SceneCrew-CSRF","1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }
    String url(String path) { return "http://localhost:"+port+path; }
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception { return client.send(r.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> r,int status,String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo(code);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Beans {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}

package com.ssafy.scenecrew.event;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** SC-API-036 직무 그룹·037 참여자 조회의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis. 행사·그룹·Member는 SQL로 만든다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(EventCrewHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EventCrewHttpTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
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
    @Autowired PasswordEncoder encoder;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>(), events=new ArrayList<>();
    UUID chief, worker, outsider, event, sound, safety, retired;

    @BeforeEach void fixture() {
        chief=account(); worker=account(); outsider=account();
        event=event();
        sound=group(event,"음향·장비","예비 마이크는 음향 부스에 있습니다.",true);
        safety=group(event,"관객 안전","객석 통로를 비워 두세요.",true);
        retired=group(event,"철거","철거 안내",false);
        member(event,chief,"CHIEF",null,true,1);
        member(event,worker,"WORKER",sound,true,2);
        member(event(),outsider,"CHIEF",null,true,1);
    }
    @AfterEach void cleanup() {
        for (var e:events) {
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

    @Test void chiefSeesEveryGroupWithInstructionsAndActive() throws Exception {
        var r=send(get("/api/v1/events/"+event+"/work-groups").header("Cookie",login(chief)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(r.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("items","nextCursor");
        assertThat(body.get("nextCursor").isNull()).isTrue();
        var items=body.get("items");
        // 이름 순: 관객 안전, 음향·장비, 철거
        assertThat(ids(items)).containsExactly(safety.toString(),sound.toString(),retired.toString());
        for (JsonNode g:items) assertThat(g.propertyNames()).containsExactlyInAnyOrder("id","name","instructions","active");
        assertThat(items.get(1).get("instructions").asString()).isEqualTo("예비 마이크는 음향 부스에 있습니다.");
        assertThat(items.get(2).get("active").asBoolean()).isFalse();
    }

    @Test void workerSeesActiveGroupNamesAndOnlyOwnInstructions() throws Exception {
        var r=send(get("/api/v1/events/"+event+"/work-groups").header("Cookie",login(worker)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var items=json.readTree(r.body()).get("items");
        assertThat(ids(items)).containsExactly(safety.toString(),sound.toString());
        var other=items.get(0); var mine=items.get(1);
        assertThat(other.propertyNames()).containsExactlyInAnyOrder("id","name");
        assertThat(other.get("name").asString()).isEqualTo("관객 안전");
        assertThat(mine.propertyNames()).containsExactlyInAnyOrder("id","name","instructions");
        assertThat(mine.get("instructions").asString()).isEqualTo("예비 마이크는 음향 부스에 있습니다.");
        assertThat(r.body()).doesNotContain("객석 통로를 비워 두세요").doesNotContain("철거");
    }

    @Test void chiefListsMembersIncludingDepartedWithContractShape() throws Exception {
        var gone=account();
        member(event,gone,"WORKER",safety,false,5);
        var r=send(get("/api/v1/events/"+event+"/members").header("Cookie",login(chief)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(r.body());
        assertThat(body.get("nextCursor").isNull()).isTrue();
        var items=body.get("items");
        assertThat(items.size()).isEqualTo(3);
        for (JsonNode m:items) assertThat(m.propertyNames()).containsExactlyInAnyOrder("memberId","displayName","workGroupId",
                "eventRole","active","available","isReserve","participationRevision");
        // 참여 순번 순: 총괄, 팀원, 떠난 팀원
        assertThat(items.get(0).get("eventRole").asString()).isEqualTo("CHIEF");
        assertThat(items.get(0).get("workGroupId").isNull()).isTrue();
        var w=items.get(1);
        assertThat(w.get("workGroupId").asString()).isEqualTo(sound.toString());
        assertThat(w.get("participationRevision").isString()).isTrue();
        assertThat(w.get("participationRevision").asString()).isEqualTo("2");
        assertThat(w.get("isReserve").asBoolean()).isFalse();
        assertThat(w.get("available").asBoolean()).isTrue();
        var left=items.get(2);
        assertThat(left.get("active").asBoolean()).isFalse();
        assertThat(left.get("participationRevision").asString()).isEqualTo("5");
    }

    @Test void membersCursorPagesWithoutRepeatsOrGaps() throws Exception {
        for (int i=0;i<8;i++) member(event,account(),"WORKER",safety,true,1);
        var expected=jdbc.queryForList("SELECT id::text FROM member WHERE event_id=? ORDER BY crew_no",String.class,event);
        String cookie=login(chief); var seen=new ArrayList<String>(); String cursor=null; int pages=0;
        do {
            var r=send(get("/api/v1/events/"+event+"/members?limit=3"+(cursor==null?"":"&cursor="+cursor)).header("Cookie",cookie));
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            var body=json.readTree(r.body());
            for (JsonNode m:body.get("items")) seen.add(m.get("memberId").asString());
            cursor=body.get("nextCursor").isNull()?null:body.get("nextCursor").asString();
            if (cursor!=null) assertThat(cursor).matches("[A-Za-z0-9_-]+");
            pages++;
        } while (cursor!=null && pages<10);
        assertThat(seen).containsExactlyElementsOf(expected).hasSize(10);
        assertThat(pages).isEqualTo(4);
    }

    @Test void permissionsHideOtherEventsAndLimitMembersToChief() throws Exception {
        String w=login(worker), o=login(outsider);
        error(send(get("/api/v1/events/"+event+"/members").header("Cookie",w)),403,"FORBIDDEN");
        for (String path:List.of("/work-groups","/members")) {
            error(send(get("/api/v1/events/"+event+path).header("Cookie",o)),404,"NOT_FOUND");
            error(send(get("/api/v1/events/"+UUID.randomUUID()+path).header("Cookie",w)),404,"NOT_FOUND");
            error(send(get("/api/v1/events/"+event+path)),401,"UNAUTHENTICATED");
        }
        // 떠난 팀원은 Member가 아니다
        jdbc.update("UPDATE member SET active=false WHERE event_id=? AND account_id=?",event,worker);
        error(send(get("/api/v1/events/"+event+"/work-groups").header("Cookie",w)),404,"NOT_FOUND");
    }

    @Test void androidBearerWorks() throws Exception {
        var android=send(post("/api/v1/auth/login","{\"email\":\""+worker+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                .header("X-SceneCrew-Client","ANDROID").header("Origin","https://android.example.invalid"));
        String token=json.readTree(android.body()).get("accessToken").asString();
        var r=send(get("/api/v1/events/"+event+"/work-groups").header("Authorization","Bearer "+token)
                .header("Origin","https://android.example.invalid"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://android.example.invalid");
    }

    @Test void rejectsBadInput() throws Exception {
        String c=login(chief);
        for (String q:List.of("limit=0","limit=101","limit=abc","limit=01","cursor=","cursor=%2F%2F",
                "cursor="+Base64.getUrlEncoder().withoutPadding().encodeToString("v2|3".getBytes()),
                "cursor="+Base64.getUrlEncoder().withoutPadding().encodeToString("v1|-1".getBytes()),
                "other=1","limit=3&limit=4"))
            error(send(get("/api/v1/events/"+event+"/members?"+q).header("Cookie",c)),400,"INVALID_REQUEST");
        error(send(get("/api/v1/events/"+event+"/work-groups?limit=1").header("Cookie",c)),400,"INVALID_REQUEST");
        for (String path:List.of("/work-groups","/members"))
            error(send(get("/api/v1/events/not-a-uuid"+path).header("Cookie",c)),400,"INVALID_REQUEST");
    }

    static List<String> ids(JsonNode items) {
        var out=new ArrayList<String>(); for (JsonNode n:items) out.add(n.get("id").asString()); return out;
    }
    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                encoder.encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    UUID event() {
        var id=UUID.randomUUID(); events.add(id);
        jdbc.update("INSERT INTO event (id,name,state,revision,date,last_input_modified_at) VALUES (?,?,?,?,?,now())",
                id,"공연","LIVE",1L,LocalDate.of(2026,11,14));
        return id;
    }
    UUID group(UUID event,String name,String instructions,boolean active) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group VALUES (?, ?, ?, ?, ?)",id,event,name,instructions,active);
        return id;
    }
    UUID member(UUID event,UUID account,String role,UUID group,boolean active,long revision) {
        var id=UUID.randomUUID();
        Long crew=jdbc.queryForObject("SELECT coalesce(max(crew_no),0)+1 FROM member WHERE event_id=?",Long.class,event);
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,?,?,?,'fixture',true,false,?,?)""",id,event,account,group,crew,role,active,revision);
        return id;
    }
    String login(UUID account) throws Exception {
        var r=send(post("/api/v1/auth/login","{\"email\":\""+account+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                .header("X-SceneCrew-Client","WEB").header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1"));
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

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

/** SC-API-021 목록·SC-API-023 조회의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis. 행사·Member는 SQL로 만든다(생성 API는 #278). */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(EventListHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EventListHttpTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"event-test"); r.add("scenecrew.auth.audience",()->"event-test");
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
    UUID me, other;

    @BeforeEach void fixture() { me=account(); other=account(); }
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

    @Test void listsOnlyMyActiveEventsWithFullContractShape() throws Exception {
        var live=event("본공연","LIVE",LocalDate.of(2026,11,20),7);
        jdbc.update("""
                UPDATE event SET prep_start_at='2026-11-20T01:00:00Z', open_at='2026-11-20T09:00:00Z',
                  close_at='2026-11-20T12:00:00Z', teardown_end_at='2026-11-20T15:00:00Z', location_name='체조경기장',
                  address='서울', total_capacity=15000, expected_visitors=12000, peak_visitors=NULL,
                  desired_reserve_count=3, description='설명', instructions='지시' WHERE id=?""",live);
        UUID group=group(live);
        UUID memberId=member(live,me,"WORKER",group,true,4);
        member(live,other,"CHIEF",null,true,1);
        var draft=event("준비중","DRAFT",null,1);
        member(draft,me,"CHIEF",null,true,1);
        var left=event("떠난공연","RECRUITING",LocalDate.of(2026,12,1),2);
        member(left,me,"WORKER",group(left),false,9);
        event("남의공연","LIVE",LocalDate.of(2026,11,21),1);

        var r=send(get("/api/v1/events").header("Cookie",login(me)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(r.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("items","nextCursor");
        assertThat(body.get("nextCursor").isNull()).isTrue();
        var items=body.get("items");
        // 행사일 최신순, 날짜 없는 행사는 맨 뒤. 탈퇴한 행사와 남의 행사는 없다.
        assertThat(items.size()).isEqualTo(2);
        var first=items.get(0);
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("id","name","state","revision","date","prepStartAt",
                "openAt","closeAt","teardownEndAt","locationName","address","totalCapacity","expectedVisitors","peakVisitors",
                "desiredReserveCount","myParticipation","description","instructions");
        assertThat(first.get("id").asString()).isEqualTo(live.toString());
        assertThat(first.get("state").asString()).isEqualTo("LIVE");
        assertThat(first.get("revision").isString()).isTrue(); assertThat(first.get("revision").asString()).isEqualTo("7");
        assertThat(first.get("date").asString()).isEqualTo("2026-11-20");
        assertThat(first.get("openAt").asString()).isEqualTo("2026-11-20T09:00:00Z");
        assertThat(first.get("totalCapacity").asString()).isEqualTo("15000");
        assertThat(first.get("peakVisitors").isNull()).isTrue();
        assertThat(first.get("desiredReserveCount").asString()).isEqualTo("3");
        var p=first.get("myParticipation");
        assertThat(p.propertyNames()).containsExactlyInAnyOrder("memberId","participationRevision","active","eventRole","workGroupId");
        assertThat(p.get("memberId").asString()).isEqualTo(memberId.toString());
        assertThat(p.get("participationRevision").asString()).isEqualTo("4");
        assertThat(p.get("active").asBoolean()).isTrue();
        assertThat(p.get("eventRole").asString()).isEqualTo("WORKER");
        assertThat(p.get("workGroupId").asString()).isEqualTo(group.toString());
        var second=items.get(1);
        assertThat(second.get("id").asString()).isEqualTo(draft.toString());
        assertThat(second.get("date").isNull()).isTrue();
        assertThat(second.get("myParticipation").get("workGroupId").isNull()).isTrue();
        assertThat(second.get("locationName").isNull()).isTrue();
    }

    @Test void noMemberIsEmptyListAndAndroidBearerWorks() throws Exception {
        var android=send(post("/api/v1/auth/login","{\"email\":\""+me+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                .header("X-SceneCrew-Client","ANDROID").header("Origin","https://android.example.invalid"));
        String token=json.readTree(android.body()).get("accessToken").asString();
        var r=send(get("/api/v1/events").header("Authorization","Bearer "+token).header("Origin","https://android.example.invalid"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"items\":[],\"nextCursor\":null}");
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://android.example.invalid");
    }

    @Test void cursorPagesWithoutRepeatsOrGapsAcrossNullDates() throws Exception {
        var expected=new ArrayList<String>();
        for (int day=5; day>=1; day--) {
            var e=event("d"+day,"RECRUITING",LocalDate.of(2026,11,day),1); member(e,me,"CHIEF",null,true,1);
        }
        var sameDay=new ArrayList<UUID>();
        for (int i=0;i<2;i++) { var e=event("same"+i,"RECRUITING",LocalDate.of(2026,11,3),1); member(e,me,"CHIEF",null,true,1); sameDay.add(e); }
        for (int i=0;i<3;i++) { var e=event("none"+i,"DRAFT",null,1); member(e,me,"CHIEF",null,true,1); }
        expected.addAll(jdbc.queryForList("""
                SELECT e.id::text FROM member m JOIN event e ON e.id=m.event_id WHERE m.account_id=? AND m.active
                 ORDER BY e.date DESC NULLS LAST, e.id""",String.class,me));
        String cookie=login(me); var seen=new ArrayList<String>(); String cursor=null; int pages=0;
        do {
            var r=send(get("/api/v1/events?limit=3"+(cursor==null?"":"&cursor="+cursor)).header("Cookie",cookie));
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
            var body=json.readTree(r.body());
            for (JsonNode item:body.get("items")) seen.add(item.get("id").asString());
            assertThat(body.get("items").size()).isLessThanOrEqualTo(3);
            cursor=body.get("nextCursor").isNull()?null:body.get("nextCursor").asString();
            if (cursor!=null) assertThat(cursor).matches("[A-Za-z0-9_-]+").isNotEqualTo("null");
            pages++;
        } while (cursor!=null && pages<10);
        assertThat(seen).containsExactlyElementsOf(expected).hasSize(10);
        assertThat(pages).isEqualTo(4);
    }

    @Test void rejectsBadQueryAndUnauthenticated() throws Exception {
        String cookie=login(me);
        for (String q:List.of("limit=0","limit=101","limit=abc","limit=01","cursor=","cursor=%2F%2F","cursor=bm90LWEtY3Vyc29y",
                "cursor="+Base64.getUrlEncoder().withoutPadding().encodeToString("v2|-|00000000-0000-4000-8000-000000000001".getBytes()),
                "other=1","limit=3&limit=4"))
            error(send(get("/api/v1/events?"+q).header("Cookie",cookie)),400,"INVALID_REQUEST");
        error(send(get("/api/v1/events")),401,"UNAUTHENTICATED");
        error(send(get("/api/v1/events").header("Cookie","__Secure-sc-access=forged")),401,"UNAUTHENTICATED");
        assertThat(send(get("/api/v1/events?limit=100").header("Cookie",cookie)).statusCode()).isEqualTo(200);
    }

    @Test void readsMyEventWithSameShapeAsListItem() throws Exception {
        var live=event("본공연","LIVE",LocalDate.of(2026,11,20),7);
        jdbc.update("UPDATE event SET open_at='2026-11-20T09:00:00Z', total_capacity=15000, desired_reserve_count=3 WHERE id=?",live);
        UUID group=group(live);
        UUID memberId=member(live,me,"WORKER",group,true,4);
        member(live,other,"CHIEF",null,true,1);
        String cookie=login(me);
        var r=send(get("/api/v1/events/"+live).header("Cookie",cookie));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(r.body());
        // 목록의 항목과 같은 Event 모양이고, myParticipation은 요청한 계정 자신의 것이다.
        var listed=json.readTree(send(get("/api/v1/events").header("Cookie",cookie)).body()).get("items").get(0);
        assertThat(body).isEqualTo(listed);
        assertThat(body.get("id").asString()).isEqualTo(live.toString());
        assertThat(body.get("revision").asString()).isEqualTo("7");
        assertThat(body.get("openAt").asString()).isEqualTo("2026-11-20T09:00:00Z");
        assertThat(body.get("totalCapacity").asString()).isEqualTo("15000");
        assertThat(body.get("myParticipation").get("memberId").asString()).isEqualTo(memberId.toString());
        assertThat(body.get("myParticipation").get("eventRole").asString()).isEqualTo("WORKER");
        // 대문자 UUID도 같은 행사다.
        assertThat(send(get("/api/v1/events/"+live.toString().toUpperCase()).header("Cookie",cookie)).statusCode()).isEqualTo(200);

        var draft=event("준비중","DRAFT",null,1);
        member(draft,me,"CHIEF",null,true,1);
        var d=json.readTree(send(get("/api/v1/events/"+draft).header("Cookie",cookie)).body());
        assertThat(d.get("state").asString()).isEqualTo("DRAFT");
        assertThat(d.get("date").isNull()).isTrue();
        assertThat(d.get("myParticipation").get("eventRole").asString()).isEqualTo("CHIEF");

        var android=send(post("/api/v1/auth/login","{\"email\":\""+me+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                .header("X-SceneCrew-Client","ANDROID").header("Origin","https://android.example.invalid"));
        String token=json.readTree(android.body()).get("accessToken").asString();
        var a=send(get("/api/v1/events/"+live).header("Authorization","Bearer "+token).header("Origin","https://android.example.invalid"));
        assertThat(a.statusCode()).as(a.body()).isEqualTo(200);
        assertThat(json.readTree(a.body())).isEqualTo(body);
    }

    @Test void readHidesEventsIAmNotActiveMemberOf() throws Exception {
        var others=event("남의공연","LIVE",LocalDate.of(2026,11,21),1);
        member(others,other,"CHIEF",null,true,1);
        var left=event("떠난공연","RECRUITING",LocalDate.of(2026,12,1),2);
        member(left,me,"WORKER",group(left),false,9);
        String cookie=login(me);
        // 남의 행사·탈퇴한 행사·없는 행사는 같은 404로 구별되지 않는다.
        for (UUID id:List.of(others,left,UUID.randomUUID())) {
            var r=send(get("/api/v1/events/"+id).header("Cookie",cookie));
            error(r,404,"NOT_FOUND");
            assertThat(r.body()).doesNotContain(id.toString()).doesNotContain("남의공연").doesNotContain("떠난공연");
        }
    }

    @Test void readRejectsBadIdQueryAndUnauthenticated() throws Exception {
        var mine=event("내공연","LIVE",LocalDate.of(2026,11,20),1);
        member(mine,me,"CHIEF",null,true,1);
        String cookie=login(me);
        for (String path:List.of("not-a-uuid","00000000000040008000000000000001","%7B"+mine+"%7D",mine+"?limit=1",mine+"?x"))
            error(send(get("/api/v1/events/"+path).header("Cookie",cookie)),400,"INVALID_REQUEST");
        error(send(get("/api/v1/events/"+mine)),401,"UNAUTHENTICATED");
        error(send(get("/api/v1/events/"+mine).header("Cookie","__Secure-sc-access=forged")),401,"UNAUTHENTICATED");
    }

    @Test void logoutRevokesBothEventReadsOnlyForTargetSessionWithoutChangingMemberships() throws Exception {
        var eventA=event("행사 A","LIVE",LocalDate.of(2026,11,20),1);
        var eventB=event("행사 B","LIVE",LocalDate.of(2026,11,21),2);
        var memberA=member(eventA,me,"CHIEF",null,true,1);
        var memberB=member(eventB,me,"WORKER",group(eventB),true,4);
        assertThat(jdbc.update("UPDATE member SET available=false,is_reserve=true WHERE id=?",memberB)).isEqualTo(1);
        String membershipSql="""
                SELECT id,event_id,account_id,work_group_id,crew_no,event_role,display_name,
                       available,is_reserve,active,participation_revision
                  FROM member WHERE account_id=? AND event_id IN (?,?) ORDER BY event_id,id
                """;
        var before=jdbc.queryForList(membershipSql,me,eventA,eventB);
        assertThat(before).hasSize(2);
        assertThat(before.stream().map(row->row.get("id")).toList()).containsExactlyInAnyOrder(memberA,memberB);

        // Independent browsers: neither login sends a previous browser's Cookie.
        var credentials=new ArrayList<Map<String,String>>();
        var sessionIds=new ArrayList<UUID>();
        for (int i=0;i<2;i++) {
            var response=send(post("/api/v1/auth/login","{\"email\":\""+me+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")
                    .header("X-SceneCrew-Client","WEB").header("Origin","https://web.example.invalid")
                    .header("X-SceneCrew-CSRF","1"));
            assertThat(response.statusCode()).isEqualTo(200);
            var cookies=new HashMap<String,String>();
            for (String header:response.headers().allValues("Set-Cookie")) {
                String[] pair=header.split(";",2)[0].split("=",2);
                if (pair[0].equals("__Secure-sc-access") || pair[0].equals("__Secure-sc-refresh"))
                    cookies.put(pair[0],pair[1]);
            }
            // Assert credential presence without printing credential values on failure.
            assertThat(cookies.keySet()).containsExactlyInAnyOrder("__Secure-sc-access","__Secure-sc-refresh");
            sessionIds.add(UUID.fromString(cookies.get("__Secure-sc-refresh").split("\\.",2)[0]));
            credentials.add(cookies);
        }
        assertThat(sessionIds.get(0)).isNotEqualTo(sessionIds.get(1));
        String s1Access="__Secure-sc-access="+credentials.get(0).get("__Secure-sc-access");
        String s2Access="__Secure-sc-access="+credentials.get(1).get("__Secure-sc-access");
        var participation=new HashMap<UUID,JsonNode>();
        for (UUID eventId:List.of(eventA,eventB)) {
            for (String access:List.of(s1Access,s2Access)) {
                var response=send(get("/api/v1/events/"+eventId).header("Cookie",access));
                assertThat(response.statusCode()).isEqualTo(200);
                var body=json.readTree(response.body());
                assertThat(body.get("id").asString()).isEqualTo(eventId.toString());
                var expectedMember=eventId.equals(eventA)?memberA:memberB;
                assertThat(body.get("myParticipation").get("memberId").asString()).isEqualTo(expectedMember.toString());
                assertThat(body.get("myParticipation").get("eventRole").asString()).isEqualTo(eventId.equals(eventA)?"CHIEF":"WORKER");
                assertThat(body.get("myParticipation").get("participationRevision").asString()).isEqualTo(eventId.equals(eventA)?"1":"4");
                assertThat(body.get("myParticipation").get("active").asBoolean()).isTrue();
                var previous=participation.putIfAbsent(eventId,body.get("myParticipation"));
                if (previous!=null) assertThat(body.get("myParticipation")).isEqualTo(previous);
            }
        }
        var logout=send(post("/api/v1/auth/logout","{}")
                .header("X-SceneCrew-Client","WEB").header("Origin","https://web.example.invalid")
                .header("X-SceneCrew-CSRF","1").header("X-SceneCrew-Logout-Attempt","AAAAAAAAAAAAAAAAAAAAAA")
                .header("Cookie","__Secure-sc-refresh="+credentials.get(0).get("__Secure-sc-refresh")));
        assertThat(logout.statusCode()).isEqualTo(204);
        for (UUID eventId:List.of(eventA,eventB)) {
            // Replay the original Access explicitly, rather than relying on client Cookie deletion.
            error(send(get("/api/v1/events/"+eventId).header("Cookie",s1Access)),401,"UNAUTHENTICATED");
            var response=send(get("/api/v1/events/"+eventId).header("Cookie",s2Access));
            assertThat(response.statusCode()).isEqualTo(200);
            var body=json.readTree(response.body());
            assertThat(body.get("id").asString()).isEqualTo(eventId.toString());
            assertThat(body.get("myParticipation")).isEqualTo(participation.get(eventId));
        }
        var after=jdbc.queryForList(membershipSql,me,eventA,eventB);
        assertThat(after).hasSize(2).containsExactlyElementsOf(before);
    }

    UUID account() {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                encoder.encode("SyntheticPass8!"),id+"@example.invalid");
        return id;
    }
    UUID event(String name,String state,LocalDate date,long revision) {
        var id=UUID.randomUUID(); events.add(id);
        jdbc.update("INSERT INTO event (id,name,state,revision,date,last_input_modified_at) VALUES (?,?,?,?,?,now())",
                id,name,state,revision,date);
        return id;
    }
    UUID group(UUID event) {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO work_group VALUES (?, ?, ?, '', true)",id,event,"g-"+id);
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

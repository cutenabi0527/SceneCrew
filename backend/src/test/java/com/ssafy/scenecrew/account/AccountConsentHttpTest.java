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
import com.ssafy.scenecrew.account.service.ConsentRecords;
import com.ssafy.scenecrew.support.FakeFileStorage;
import com.ssafy.scenecrew.support.FileTestBeans;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 동의 조회·변경(SC-API-017, #229·#334, V9-D40)의 실제 HTTP·제품 Security·PostgreSQL·전용 Redis. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({FileTestBeans.class, AccountConsentHttpTest.Encoder.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AccountConsentHttpTest {
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
    @Autowired FakeFileStorage storage;
    final JsonMapper json=JsonMapper.builder().build();
    final HttpClient client=HttpClient.newHttpClient();
    final List<UUID> accounts=new ArrayList<>(), events=new ArrayList<>();

    @AfterEach void cleanup() {
        for (var e:events) {
            jdbc.update("DELETE FROM audio_asset WHERE event_id=?",e);
            jdbc.update("DELETE FROM device_token WHERE member_id IN (SELECT id FROM member WHERE event_id=?)",e);
            jdbc.update("DELETE FROM member WHERE event_id=?",e);
            jdbc.update("DELETE FROM event WHERE id=?",e);
        }
        for (var a:accounts) {
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",a);
            jdbc.update("DELETE FROM account WHERE id=?",a);
        }
        events.clear(); accounts.clear();
    }

    @Test void signupRecordShowsVersionAndHistory() throws Exception {
        UUID me=account(ConsentRecords.initial(true,true,false,true));
        var r=send(get("/api/v1/me/consents").header("Cookie",login(me)));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(r.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("consents","version","history");
        assertThat(body.get("consents").toString()).isEqualTo("{\"service\":true,\"voice\":true,\"push\":false,\"externalAi\":true}");
        assertThat(body.get("version").asString()).isEqualTo(ConsentRecords.VERSION);
        var first=body.get("history").get(0);
        assertThat(first.get("source").asString()).isEqualTo("SIGNUP");
        assertThat(first.get("version").asString()).isEqualTo(ConsentRecords.VERSION);
        assertThat(java.time.Instant.parse(first.get("at").asString())).isBefore(java.time.Instant.now().plusSeconds(1));
        assertThat(first.get("values").size()).isEqualTo(4);
    }

    @Test void legacyAccountWithoutHistoryShowsNullVersion() throws Exception {
        UUID me=account("{\"service\":true,\"voice\":false,\"push\":false,\"externalAi\":false}");
        var body=json.readTree(send(get("/api/v1/me/consents").header("Cookie",login(me))).body());
        assertThat(body.get("version").isNull()).isTrue();
        assertThat(body.get("history").isEmpty()).isTrue();
    }

    @Test void changeKeepsOtherConsentsAndAppendsHistory() throws Exception {
        UUID me=account(ConsentRecords.initial(true,true,true,true));
        String cookie=login(me);
        var r=patch(cookie,"{\"externalAi\":false}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json.readTree(r.body()).toString())
                .isEqualTo("{\"consents\":{\"service\":true,\"voice\":true,\"push\":true,\"externalAi\":false}}");
        // 같은 값을 다시 보내면 이력이 늘지 않는다.
        assertThat(patch(cookie,"{\"externalAi\":false,\"service\":true}").statusCode()).isEqualTo(200);
        var history=json.readTree(send(get("/api/v1/me/consents").header("Cookie",cookie)).body()).get("history");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(1).get("source").asString()).isEqualTo("CHANGE");
        assertThat(history.get(1).get("values").toString()).isEqualTo("{\"externalAi\":false}");
        // 음성 전사는 맨 위 voice 값을 그대로 읽는다.
        assertThat(jdbc.queryForObject("SELECT consent->>'voice' FROM account WHERE id=?",String.class,me)).isEqualTo("true");
    }

    @Test void pushWithdrawalTurnsOffDevicesAndVoiceWithdrawalRemovesOriginals() throws Exception {
        UUID me=account(ConsentRecords.initial(true,true,true,true));
        UUID event=event(), member=member(event,me);
        jdbc.update("INSERT INTO device_token (id,member_id,device_id,fcm_token,active,updated_at) VALUES (?,?,'d1','fcm',true,now())",
                UUID.randomUUID(),member);
        UUID audio=UUID.randomUUID(); String key="audio/"+event+"/"+audio;
        storage.put(key,new byte[]{1,2,3},"audio/webm");
        jdbc.update("""
                INSERT INTO audio_asset (id,event_id,uploader_member_id,storage_key,mime,bytes,duration_sec,transcript,created_at)
                VALUES (?,?,?,?,'audio/webm',3,1,'임시 전사',now())""",audio,event,member,key);
        String cookie=login(me);

        assertThat(patch(cookie,"{\"push\":false}").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT active FROM device_token WHERE member_id=?",Boolean.class,member)).isFalse();
        assertThat(jdbc.queryForObject("SELECT storage_key FROM audio_asset WHERE id=?",String.class,audio)).isEqualTo(key);

        assertThat(patch(cookie,"{\"voice\":false}").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForMap("SELECT storage_key, transcript FROM audio_asset WHERE id=?",audio))
                .containsEntry("storage_key",null).containsEntry("transcript",null);
        assertThat(storage.objects()).doesNotContainKey(key);
    }

    @Test void pushWithdrawalDisablesOnlyTheCurrentAccountsDevicesAcrossEvents() throws Exception {
        UUID me=account(ConsentRecords.initial(true,false,true,false));
        UUID other=account(ConsentRecords.initial(true,false,true,false));
        UUID firstEvent=event(), secondEvent=event();
        UUID firstMembership=member(firstEvent,me), secondMembership=member(secondEvent,me);
        UUID otherMembership=member(event(),other);
        String insert="""
                INSERT INTO device_token (id,member_id,device_id,fcm_token,active,updated_at)
                VALUES (?,?,?, 'synthetic-fcm-token',true,now())
                """;
        UUID device=UUID.randomUUID(), otherDevice=UUID.randomUUID();
        jdbc.update(insert,UUID.randomUUID(),firstMembership,device.toString());
        jdbc.update(insert,UUID.randomUUID(),secondMembership,device.toString());
        jdbc.update(insert,UUID.randomUUID(),otherMembership,otherDevice.toString());

        assertThat(patch(login(me),"{\"push\":false}").statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForList("SELECT active FROM device_token WHERE member_id IN (?,?)",
                Boolean.class,firstMembership,secondMembership)).containsExactly(false,false);
        assertThat(jdbc.queryForObject("SELECT active FROM device_token WHERE member_id=?",
                Boolean.class,otherMembership)).isTrue();
    }

    @Test void rejectsServiceWithdrawalAndBadInput() throws Exception {
        UUID me=account(ConsentRecords.initial(true,true,true,true));
        String cookie=login(me);
        error(patch(cookie,"{\"service\":false}"),409,"INVALID_STATE");
        for (String body:List.of("{}","[]","{\"voice\":\"false\"}","{\"other\":true}","{\"voice\":null}"))
            error(patch(cookie,body),400,"INVALID_REQUEST");
        var noCsrf=send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/me/consents"))
                .header("Content-Type","application/json").header("Cookie",cookie).header("Origin","https://web.example.invalid")
                .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"voice\":false}")));
        assertThat(noCsrf.statusCode()).as(noCsrf.body()).isEqualTo(403);
        error(send(get("/api/v1/me/consents")),401,"UNAUTHENTICATED");
        assertThat(jdbc.queryForObject("SELECT consent->>'voice' FROM account WHERE id=?",String.class,me)).isEqualTo("true");
    }

    UUID account(String consent) {
        var id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 1, ?::jsonb, now())",id,consent);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                new BCryptPasswordEncoder(4).encode(PASSWORD),id+"@example.invalid");
        return id;
    }
    UUID event() {
        var id=UUID.randomUUID(); events.add(id);
        jdbc.update("INSERT INTO event (id,name,state,revision,date,last_input_modified_at) VALUES (?,?,?,?,?,now())",
                id,"공연","LIVE",1L,LocalDate.of(2026,11,14));
        return id;
    }
    UUID member(UUID event,UUID account) {
        var id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO member (id,event_id,account_id,work_group_id,crew_no,event_role,display_name,available,is_reserve,active,participation_revision)
                VALUES (?,?,?,NULL,1,'CHIEF','fixture',true,false,true,1)""",id,event,account);
        return id;
    }
    HttpResponse<String> patch(String cookie,String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/me/consents"))
                .header("Content-Type","application/json").header("Cookie",cookie)
                .header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1")
                .method("PATCH",HttpRequest.BodyPublishers.ofString(body)));
    }
    String login(UUID account) throws Exception {
        var r=send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/login"))
                .header("Content-Type","application/json").header("X-SceneCrew-Client","WEB")
                .header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+account+"@example.invalid\",\"password\":\""+PASSWORD+"\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow();
    }
    HttpRequest.Builder get(String path) { return HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET(); }
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception { return client.send(r.build(),HttpResponse.BodyHandlers.ofString()); }
    void error(HttpResponse<String> r,int status,String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        JsonNode body=json.readTree(r.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Encoder {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
    }
}

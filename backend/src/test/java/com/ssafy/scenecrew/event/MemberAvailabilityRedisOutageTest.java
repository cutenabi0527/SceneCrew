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
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.event.service.MemberAvailabilityService;
import com.ssafy.scenecrew.invite.service.InviteJoinService;
import static org.assertj.core.api.Assertions.*;

/** 전용 PostgreSQL/Redis와 실제 HTTP. Docker가 없으면 성공/skip으로 대신하지 않고 실패한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(MemberAvailabilityRedisOutageTest.Encoder.class)
class MemberAvailabilityRedisOutageTest {
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
        r.add("scenecrew.auth.issuer", () -> "availability-outage-test");
        r.add("scenecrew.auth.audience", () -> "availability-outage-test");
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
            System.out.printf("AVAILABILITY_FIXTURE outage context=%s postgres=%s redis=%s redisPort=%s%n",
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

    @Test void actualRedisOutageNeverPermitsWrite() throws Exception {
        var c=login(account,"ANDROID"); REDIS.stop();
        error(change(c,member,false,10),503,"SERVICE_UNAVAILABLE");
        assertThat(available()).isTrue(); assertThat(revision()).isEqualTo(10);
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
    void error(HttpResponse<String> result,int status,String code) {
        assertThat(result.statusCode()).isEqualTo(status);
        assertThat(json.readTree(result.body()).get("code").asString()).isEqualTo(code);
        assertThat(result.headers().firstValue("X-Request-Id")).isPresent();
    }
}

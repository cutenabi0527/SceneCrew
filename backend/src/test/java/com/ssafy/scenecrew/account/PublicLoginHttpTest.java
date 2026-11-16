package com.ssafy.scenecrew.account;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.service.*;
import static org.assertj.core.api.Assertions.*;

/** Real socket + product Security + PostgreSQL + an exclusively owned Redis. No CSRF disable. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PublicLoginHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PublicLoginHttpTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final byte[] SIGNING=new byte[32];
    static { new java.security.SecureRandom().nextBytes(SIGNING); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("spring.data.redis.timeout",()->"500ms");
        r.add("spring.data.redis.connect-timeout",()->"500ms");
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(SIGNING));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(SIGNING));
        r.add("scenecrew.auth.issuer",()->"http-test"); r.add("scenecrew.auth.audience",()->"http-test");
        r.add("scenecrew.auth.web-origins",()->"https://web.example.invalid");
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean StringRedisTemplate redis;
    @Autowired PasswordEncoder encoder;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean LoginFailureLimiter limiter;
    @Autowired SessionAuthenticator auth;
    @Autowired LoginTokenIssuer tokenIssuer;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired org.springframework.context.ApplicationContext context;
    UUID id, identity;
    String email;
    final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    @BeforeEach void fixture() {
        id=UUID.randomUUID(); identity=UUID.randomUUID(); email=id+"@example.invalid";
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 3, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",
                identity,id,id.toString(),encoder.encode("SyntheticPass8!"),email);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM auth_identity WHERE account_id=?",id);
        jdbc.update("DELETE FROM account WHERE id=?",id);
    }
    @Test @Order(1) void h01RejectsMissingOrWrongOriginHeaderAndNonJsonWithoutCounting() throws Exception {
        error(send(post(body("SyntheticPass8!")).header("X-SceneCrew-Client","WEB")),403,"CSRF_REJECTED");
        error(send(post(body("SyntheticPass8!")).header("X-SceneCrew-Client","WEB")
                .header("Origin","https://evil.example.invalid").header("X-SceneCrew-CSRF","1")),403,"CSRF_REJECTED");
        error(send(HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type","text/plain")
                .header("X-SceneCrew-Client","WEB").header("Origin","https://web.example.invalid")
                .header("X-SceneCrew-CSRF","1").POST(HttpRequest.BodyPublishers.ofString(body("SyntheticPass8!")))),415,"UNSUPPORTED_MEDIA_TYPE");
        assertThat(redis.hasKey(limiter.keys(email).getFirst())).isFalse();
    }
    @Test @Order(1) void rawCookieDuplicatesSyntaxAndMixedEmptyCredentialsCannotBypassValidation() throws Exception {
        error(send(webRequest(body("SyntheticPass8!")).header("Cookie","__Secure-sc-access=; __Secure-sc-access=x")),400,"DUPLICATE_AUTH_COOKIE");
        error(send(webRequest(body("SyntheticPass8!")).header("Cookie","not-a-pair")),400,"MALFORMED_COOKIE_HEADER");
        error(send(webRequest(body("SyntheticPass8!")).header("Cookie","__Secure-sc-access=\"unterminated")),400,"MALFORMED_COOKIE_HEADER");
        error(send(webRequest(body("SyntheticPass8!")).header("Cookie","__Secure-sc-access=")
                .header("Authorization","Bearer forged")),400,"MIXED_AUTH_CREDENTIALS");
        assertThat(redis.hasKey(limiter.keys(email).getFirst())).isFalse();
    }
    @Test @Order(1) void invalidChannelUnknownFieldsAndMalformedJsonNeverCount() throws Exception {
        error(send(post(body("SyntheticPass8!"))),400,"INVALID_AUTH_CHANNEL");
        error(send(webRequest("{bad")),400,"INVALID_REQUEST");
        error(send(webRequest(body("SyntheticPass8!").replace("}",",\"admin\":true}"))),400,"INVALID_REQUEST");
        assertThat(redis.hasKey(limiter.keys(email).getFirst())).isFalse();
    }
    @Test @Order(1) void candidateRetainsWindowAfterBlockAndSuccessfulRegistrationResetsIt() throws Exception {
        var keys=limiter.keys(email);
        for(int i=0;i<5;i++) web("WrongPassword8!",null);
        redis.delete(keys.get(1)); // simulate the exact blocked-key expiry; no real one-minute wait
        error(web("WrongPassword8!",null),401,"INVALID_CREDENTIALS");
        assertThat(redis.opsForZSet().zCard(keys.getFirst())).isEqualTo(6);
        assertThat(redis.hasKey(keys.get(1))).isTrue();
        redis.delete(keys.get(1));
        assertThat(web("SyntheticPass8!",null).statusCode()).isEqualTo(200);
        assertThat(redis.hasKey(keys.getFirst())).isFalse(); assertThat(redis.hasKey(keys.get(1))).isFalse();
    }
    @Test @Order(1) void inactiveIncompleteUnverifiedAndUnknownEmailsAreCredentialFailures() throws Exception {
        for(String change:List.of("UPDATE account SET status='DISABLED' WHERE id=?",
                "UPDATE account SET status='UNKNOWN' WHERE id=?", "UPDATE account SET signup_completed=false WHERE id=?")) {
            jdbc.update(change,id); error(web("SyntheticPass8!",null),401,"INVALID_CREDENTIALS");
            jdbc.update("UPDATE account SET status='ACTIVE', signup_completed=true WHERE id=?",id);
        }
        jdbc.update("UPDATE auth_identity SET email_verified=false WHERE id=?",identity);
        error(web("SyntheticPass8!",null),401,"INVALID_CREDENTIALS");
        assertThat(redis.opsForZSet().zCard(limiter.keys(email).getFirst())).isEqualTo(4);
        var unknown=body("SyntheticPass8!").replace(email,"unknown-"+email);
        error(send(webRequest(unknown)),401,"INVALID_CREDENTIALS");
    }
    @Test @Order(1) void missingRouteDistinguishesWebSessionAndUnsafeRequestsKeepSpringCsrf() throws Exception {
        var login=web("SyntheticPass8!",null);
        assertThat(login.statusCode()).isEqualTo(200);
        String cookies = cookie(login);
        var before = send(get("/api/v1/me").header("Cookie", cookies));
        assertThat(before.statusCode()).isEqualTo(200);
        var missing = send(get("/api/v1/notifications").header("Cookie", cookies));
        error(missing, 404, "NOT_FOUND");
        assertThat(missing.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/json"));
        assertThat(json.readTree(missing.body()).path("message").asString()).isNotBlank();
        assertThat(missing.headers().allValues("Set-Cookie").stream()
                .map(header -> header.split("=", 2)[0]).toList())
                .doesNotContain(com.ssafy.scenecrew.account.controller.AuthTransport.ACCESS,
                        com.ssafy.scenecrew.account.controller.AuthTransport.REFRESH);
        var after = send(get("/api/v1/me").header("Cookie", cookies));
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(json.readTree(before.body()).path("id").asString()).isEqualTo(id.toString());
        assertThat(json.readTree(after.body()).path("id").asString()).isEqualTo(id.toString());
        error(send(get("/api/v1/notifications")),401,"UNAUTHENTICATED");
        // 행사 생성(#278)은 업무 쓰기로 열렸으므로, 아직 열지 않은 행사 삭제(025)로 Spring CSRF 유지를 확인한다.
        var delete=HttpRequest.newBuilder(uri("/api/v1/events/"+java.util.UUID.randomUUID())).header("Cookie",cookie(login))
                .DELETE();
        error(send(delete),403,"FORBIDDEN");
        assertThat(send(get("/actuator/health")).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void webLoginHasSecureHostOnlyCookiesAndNoTokensInJson() throws Exception {
        var login=web("SyntheticPass8!",null); assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).doesNotContain("accessToken","refreshToken","SyntheticPass8!");
        var cookies=login.headers().allValues("Set-Cookie"); assertThat(cookies).hasSize(2);
        assertThat(cookies).allSatisfy(c->assertThat(c).contains("Secure","HttpOnly","SameSite=Lax").doesNotContain("Domain="));
        assertThat(cookies.get(0)).contains("Path=/api/v1;"); assertThat(cookies.get(1)).contains("Path=/api/v1/auth;");
        assertThat(login.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(json.readTree(login.body()).get("account").size()).isEqualTo(5);
        assertThat(auth.authenticate(access(login)).accountId()).isEqualTo(id);
        // #191 now exposes /me; the #90 commit separately verified this route was closed.
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(login))).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void androidLoginReturnsTokensWithoutCookiesAndRejectsUnconfiguredOrigin() throws Exception {
        var login=android("SyntheticPass8!"); assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().allValues("Set-Cookie")).isEmpty();
        var result=json.readTree(login.body());
        assertThat(result.get("refreshToken").asString()).isNotBlank();
        assertThat(auth.authenticate(result.get("accessToken").asString()).channel()).isEqualTo("ANDROID");
        error(send(post(body("SyntheticPass8!")).header("X-SceneCrew-Client","ANDROID")),403,"AUTH_CHANNEL_MISMATCH");
    }
    @Test @Order(1) void atomicLoginReplacementNeverCallsStandaloneResetAndPreservesOtherDevice() throws Exception {
        var old=web("SyntheticPass8!",null);var other=android("SyntheticPass8!");var keys=limiter.keys(email);limiter.failure(keys);
        org.mockito.Mockito.doThrow(new com.ssafy.scenecrew.global.error.BusinessException(
                com.ssafy.scenecrew.global.error.ErrorCode.SERVICE_UNAVAILABLE)).when(limiter).success(keys);
        try {
            var next=web("SyntheticPass8!",cookie(old)); assertThat(next.statusCode()).isEqualTo(200);
            assertThat(redis.hasKey(keys.getFirst())).isFalse();
            assertThat(auth.authenticate(access(next)).accountId()).isEqualTo(id);
            assertThatThrownBy(()->auth.authenticate(access(old))).isInstanceOf(com.ssafy.scenecrew.global.error.BusinessException.class);
            assertThat(auth.authenticate(json.readTree(other.body()).get("accessToken").asString()).accountId()).isEqualTo(id);
            org.mockito.Mockito.verify(limiter,org.mockito.Mockito.never()).success(keys);
        } finally {org.mockito.Mockito.reset(limiter);}
    }
    @Test @Order(99) void actualRedisOutageReturns503WithoutLoginCookies() throws Exception {
        REDIS.stop();var failure=web("SyntheticPass8!",null);error(failure,503,"SERVICE_UNAVAILABLE");
        assertThat(failure.headers().allValues("Set-Cookie")).isEmpty();
    }
    HttpRequest.Builder post(String body) { return HttpRequest.newBuilder(uri("/api/v1/auth/login"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)); }
    HttpRequest.Builder webRequest(String body) { return post(body).header("X-SceneCrew-Client","WEB")
            .header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1"); }
    String body(String password) { return "{\"email\":\""+email+"\",\"password\":\""+password+"\"}"; }
    HttpResponse<String> web(String password,String cookie) throws Exception {
        var request=webRequest(body(password)); if(cookie!=null)request.header("Cookie",cookie); return send(request);
    }
    HttpResponse<String> android(String password) throws Exception { return send(post(body(password))
            .header("X-SceneCrew-Client","ANDROID").header("Origin","https://android.example.invalid")); }
    HttpRequest.Builder get(String path) { return HttpRequest.newBuilder(uri(path)).GET(); }
    URI uri(String path) { return URI.create("http://localhost:"+port+path); }
    HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try(var client=HttpClient.newHttpClient()) { return client.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    String cookie(HttpResponse<String> response) { return String.join("; ",response.headers().allValues("Set-Cookie")
            .stream().map(c->c.split(";",2)[0]).toList()); }
    String access(HttpResponse<String> response) { return response.headers().allValues("Set-Cookie").stream()
            .filter(c->c.startsWith("__Secure-sc-access=")).findFirst().orElseThrow().split(";",2)[0].substring(19); }
    void error(HttpResponse<String> response,int status,String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\""+code+"\"","\"requestId\":\""+
                response.headers().firstValue("X-Request-Id").orElseThrow()+"\"");
        assertThat(response.body()).doesNotContain("SyntheticPass8!","WrongPassword8!",email);
    }
    @TestConfiguration static class Encoder {
        @Bean PasswordEncoder testOnlyEncoder() { return new BCryptPasswordEncoder(4); }
    }
}

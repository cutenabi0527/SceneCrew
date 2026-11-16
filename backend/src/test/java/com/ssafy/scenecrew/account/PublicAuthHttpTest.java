package com.ssafy.scenecrew.account;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.equipment.collector.CollectorRegistry;
import static org.assertj.core.api.Assertions.*;

/** Real socket + product Security + PostgreSQL + an exclusively owned Redis. No CSRF disable. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PublicAuthHttpTest.Encoder.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PublicAuthHttpTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final byte[] SIGNING=new byte[32];
    static final String COLLECTOR_TOKEN = "collector-" + UUID.randomUUID();
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
        r.add("scenecrew.equipment.collector.enabled", () -> "true");
        r.add("scenecrew.equipment.collector.cleanup.enabled", () -> "false");
        r.add("scenecrew.equipment.collector.registrations", () -> """
                [{"collectorId":"auth-boundary","eventId":"%s","tokenSha256":"%s",
                  "sources":{"fixture":"%s"},"runs":[{"runId":"%s","active":true}]}]
                """.formatted(UUID.randomUUID(), CollectorRegistry.sha256(COLLECTOR_TOKEN),
                        UUID.randomUUID(), UUID.randomUUID()));
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
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AccountSummaryReader summaries;
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
    @Test @Order(1) void enablingLocalAuthAloneDoesNotEnableOAuth() throws Exception {
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.oauth.OAuthWebController.class)).isEmpty();
        error(send(get("/api/v1/auth/oauth/GOOGLE/authorize?purpose=LOGIN&returnRoute=HOME")),401,"UNAUTHENTICATED");
    }
    @Test @Order(1) void unknownApiReturnsNotFoundForLiveAndroidSession() throws Exception {
        String path = "/api/v1/me/352-missing";
        var androidLogin = android("SyntheticPass8!");
        assertThat(androidLogin.statusCode()).isEqualTo(200);
        String bearer = json.readTree(androidLogin.body()).get("accessToken").asString();

        String origin = "https://android.example.invalid";
        var me = get("/api/v1/me").header("Authorization", "Bearer " + bearer).header("Origin", origin);
        var before = send(me);
        assertThat(before.statusCode()).isEqualTo(200);
        var missing = send(get(path).header("Authorization", "Bearer " + bearer).header("Origin", origin));
        error(missing, 404, "NOT_FOUND");
        assertThat(missing.headers().firstValue("Access-Control-Allow-Origin")).hasValue(origin);
        var after = send(me);
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(json.readTree(before.body()).path("id").asString()).isEqualTo(id.toString());
        assertThat(json.readTree(after.body()).path("id").asString()).isEqualTo(id.toString());
    }
    @Test @Order(1) void collectorAndUserCredentialsAreNotInterchangeable() throws Exception {
        var login = android("SyntheticPass8!");
        assertThat(login.statusCode()).isEqualTo(200);
        String userToken = json.readTree(login.body()).get("accessToken").asString();
        String collectorPath = "/internal/equipment-collectors/auth-boundary/observations";
        var ingest = HttpRequest.newBuilder(uri(collectorPath))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + userToken)
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        error(send(ingest), 401, "COLLECTOR_UNAUTHENTICATED");
        error(send(get("/api/v1/me").header("Origin", "https://android.example.invalid")
                .header("Authorization", "Bearer " + COLLECTOR_TOKEN)), 401, "UNAUTHENTICATED");
        error(send(get(collectorPath).header("Authorization", "Bearer " + COLLECTOR_TOKEN)),
                401, "UNAUTHENTICATED");
    }
    @Test @Order(1) void webLoginMeUsesFreshFiveFieldsAndNoTokensOrRotation() throws Exception {
        var login=web("SyntheticPass8!",null); assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).doesNotContain("accessToken","refreshToken","SyntheticPass8!");
        var cookies=login.headers().allValues("Set-Cookie"); assertThat(cookies).hasSize(2);
        assertThat(cookies).allSatisfy(c->assertThat(c).contains("Secure","HttpOnly","SameSite=Lax").doesNotContain("Domain="));
        assertThat(cookies.get(0)).contains("Path=/api/v1;"); assertThat(cookies.get(1)).contains("Path=/api/v1/auth;");
        assertThat(login.headers().firstValue("Cache-Control")).hasValue("no-store");
        jdbc.update("UPDATE account SET display_name='updated' WHERE id=?",id);
        var me=send(get("/api/v1/me").header("Cookie",cookie(login)));
        assertThat(me.statusCode()).isEqualTo(200); var account=json.readTree(me.body());
        assertThat(account.size()).isEqualTo(5); assertThat(account.get("displayName").asString()).isEqualTo("updated");
        assertThat(account.get("primaryEmailVerified").asBoolean()).isTrue();
        assertThat(me.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(json.readTree(login.body()).get("account").get("id").asString()).isEqualTo(account.get("id").asString());
    }
    @Test @Order(1) void androidTokensAreSeparateAndActualUnconfiguredOriginsAreRejected() throws Exception {
        var login=android("SyntheticPass8!"); assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().allValues("Set-Cookie")).isEmpty();
        var token=json.readTree(login.body()).get("accessToken").asString();
        assertThat(send(get("/api/v1/me").header("Origin","https://android.example.invalid")
                .header("Authorization","Bearer "+token)).statusCode()).isEqualTo(200);
        error(send(get("/api/v1/me").header("Authorization","Bearer "+token)),403,"AUTH_CHANNEL_MISMATCH");
        error(send(get("/api/v1/me").header("Origin","https://unconfigured.example.invalid")
                .header("Authorization","Bearer "+token)),403,"AUTH_CHANNEL_MISMATCH");
        var web=web("SyntheticPass8!",null);
        error(send(get("/api/v1/me").header("Origin","https://android.example.invalid")
                .header("Authorization","Bearer "+access(web))),403,"AUTH_CHANNEL_MISMATCH");
    }
    @Test @Order(1) void missingForgedExpiredAndRevokedTokensReturnCommon401() throws Exception {
        error(send(get("/api/v1/me")),401,"UNAUTHENTICATED");
        error(send(get("/api/v1/me").header("Cookie","__Secure-sc-access=forged")),401,"UNAUTHENTICATED");
        var verified=new LocalCredentialVerifier.VerifiedAccount(id,"fixture",email,3,identity);
        var expired=new LoginTokenIssuer(new SecretKeySpec(SIGNING,"HmacSHA256"),
                Clock.offset(Clock.systemUTC(),Duration.ofHours(-2)),"http-test","http-test").issue(verified);
        error(send(get("/api/v1/me").header("Cookie","__Secure-sc-access="+expired.accessToken())),401,"UNAUTHENTICATED");
        var login=web("SyntheticPass8!",null); var principal=auth.authenticate(access(login));
        redis.delete(RedisLoginSessions.key(principal.sessionId()));
        error(send(get("/api/v1/me").header("Cookie",cookie(login))),401,"UNAUTHENTICATED");
    }
    @Test @Order(1) void onlyRejectedMeBearerCredentialsReceiveBearerChallenge() throws Exception {
        var verified = new LocalCredentialVerifier.VerifiedAccount(id, "fixture", email, 3, identity);
        var expired = new LoginTokenIssuer(new SecretKeySpec(SIGNING, "HmacSHA256"),
                Clock.offset(Clock.systemUTC(), Duration.ofHours(-2)), "http-test", "http-test").issue(verified);
        var login = android("SyntheticPass8!");
        assertThat(login.statusCode()).isEqualTo(200);
        var revoked = json.readTree(login.body()).get("accessToken").asString();
        redis.delete(RedisLoginSessions.key(auth.authenticate(revoked).sessionId()));
        for (String token : List.of("forged", expired.accessToken(), revoked)) {
            var rejected = send(get("/api/v1/me").header("Origin", "https://android.example.invalid")
                    .header("Authorization", "Bearer " + token));
            error(rejected, 401, "UNAUTHENTICATED");
            assertThat(rejected.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"invalid_token\"");
        }
        var cookieFailure = send(get("/api/v1/me").header("Cookie", "__Secure-sc-access=forged"));
        error(cookieFailure, 401, "UNAUTHENTICATED");
        assertThat(cookieFailure.headers().firstValue("WWW-Authenticate")).isEmpty();
        var loginFailure = android("WrongPassword8!");
        error(loginFailure, 401, "INVALID_CREDENTIALS");
        assertThat(loginFailure.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test @Order(1) void fifthFailureIs401Then429WithoutExtendingBlockOrRevokingExistingSession() throws Exception {
        var existing=web("SyntheticPass8!",null);
        for(int i=0;i<5;i++) error(web("WrongPassword8!",null),401,"INVALID_CREDENTIALS");
        var keys=limiter.keys(email); Long before=redis.getExpire(keys.get(1),TimeUnit.MILLISECONDS);
        error(web("SyntheticPass8!",null),429,"RATE_LIMITED");
        assertThat(redis.getExpire(keys.get(1),TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(before);
        assertThat(redis.opsForZSet().zCard(keys.getFirst())).isEqualTo(5);
        assertThat(keys.toString()).doesNotContain(email,id.toString());
        assertThat(redis.getExpire(keys.getFirst())).isBetween(895L,900L);
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(existing))).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void badPasswordPreservesOldSessionAndSuccessfulReloginOnlyReplacesProvenSession() throws Exception {
        var old=web("SyntheticPass8!",null); var other=android("SyntheticPass8!");
        error(web("WrongPassword8!",cookie(old)),401,"INVALID_CREDENTIALS");
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(old))).statusCode()).isEqualTo(200);
        var replaced=web("SyntheticPass8!",cookie(old)); assertThat(replaced.statusCode()).isEqualTo(200);
        error(send(get("/api/v1/me").header("Cookie",cookie(old))),401,"UNAUTHENTICATED");
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(replaced))).statusCode()).isEqualTo(200);
        assertThat(send(get("/api/v1/me").header("Origin","https://android.example.invalid")
                .header("Authorization","Bearer "+json.readTree(other.body()).get("accessToken").asString())).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void currentAccountRevocationIsSeenByHttpWithoutAUserSessionCache() throws Exception {
        var login=web("SyntheticPass8!",null);
        jdbc.update("UPDATE account SET token_version=4 WHERE id=?",id);
        error(send(get("/api/v1/me").header("Cookie",cookie(login))),401,"UNAUTHENTICATED");
    }

    @Test @Order(1) void profileRejectsExpiredRevokedAndStaleCredentialsWithoutChangingName() throws Exception {
        String body = "{\"displayName\":\"Rejected name\"}";
        var verified = new LocalCredentialVerifier.VerifiedAccount(id,"fixture",email,3,identity);
        var expired = new LoginTokenIssuer(new SecretKeySpec(SIGNING,"HmacSHA256"),
                Clock.offset(Clock.systemUTC(),Duration.ofHours(-2)),"http-test","http-test").issue(verified);
        new RedisLoginSessions(redis, Clock.systemUTC()).register(verified, expired, "WEB");
        try {
            error(send(webPatch(body,"__Secure-sc-access="+expired.accessToken())),401,"UNAUTHENTICATED");
        } finally {
            redis.delete(RedisLoginSessions.key(expired.sessionId()));
        }

        var revoked = web("SyntheticPass8!",null);
        var principal = auth.authenticate(access(revoked));
        assertThat(redis.delete(RedisLoginSessions.key(principal.sessionId()))).isTrue();
        error(send(webPatch(body,cookie(revoked))),401,"UNAUTHENTICATED");

        var stale = web("SyntheticPass8!",null);
        jdbc.update("UPDATE account SET token_version=4 WHERE id=?",id);
        error(send(webPatch(body,cookie(stale))),401,"UNAUTHENTICATED");
        assertThat(jdbc.queryForObject("SELECT display_name FROM account WHERE id=?",String.class,id))
                .isEqualTo("fixture");
    }
    @Test @Order(1) void provenAccountSwitchRevokesOldBrowserButNotOtherDeviceAndLateOldCookieIs401() throws Exception {
        var old=web("SyntheticPass8!",null);var other=android("SyntheticPass8!");
        var second=UUID.randomUUID();var secondIdentity=UUID.randomUUID();String secondEmail=second+"@example.invalid";
        jdbc.update("INSERT INTO account VALUES (?, 'second', 'ACTIVE', true, 0, '{}'::jsonb, now())",second);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",
                secondIdentity,second,second.toString(),encoder.encode("SyntheticPass8!"),secondEmail);
        try {
            var switched=send(webRequest(body("SyntheticPass8!").replace(email,secondEmail)).header("Cookie",cookie(old)));
            assertThat(switched.statusCode()).isEqualTo(200);
            var me=send(get("/api/v1/me").header("Cookie",cookie(switched)));
            assertThat(json.readTree(me.body()).get("id").asString()).isEqualTo(second.toString());
            error(send(get("/api/v1/me").header("Cookie",cookie(old))),401,"UNAUTHENTICATED");
            assertThat(send(get("/api/v1/me").header("Origin","https://android.example.invalid")
                    .header("Authorization","Bearer "+json.readTree(other.body()).get("accessToken").asString())).statusCode()).isEqualTo(200);
        } finally {
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?",second);jdbc.update("DELETE FROM account WHERE id=?",second);
        }
    }
    @Test @Order(1) void noProofReloginsRemainIndependentRatherThanDeletingAllAccountSessions() throws Exception {
        var first=web("SyntheticPass8!",null);var second=web("SyntheticPass8!",null);
        assertThat(auth.authenticate(access(first)).sessionId()).isNotEqualTo(auth.authenticate(access(second)).sessionId());
        // This is the documented lost/no-Cookie limitation, not proof of complete browser switch isolation.
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(first))).statusCode()).isEqualTo(200);
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(second))).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void resetFailureCannotTurnCommittedReplacementInto503() throws Exception {
        var old=web("SyntheticPass8!",null);var other=android("SyntheticPass8!");
        var keys=limiter.keys(email);limiter.failure(keys);
        org.mockito.Mockito.doThrow(new com.ssafy.scenecrew.global.error.BusinessException(
                com.ssafy.scenecrew.global.error.ErrorCode.SERVICE_UNAVAILABLE)).when(limiter).success(keys);
        try {
            var replacement=web("SyntheticPass8!",cookie(old));
            if(replacement.statusCode()==503) {
                error(send(get("/api/v1/me").header("Cookie",cookie(old))),401,"UNAUTHENTICATED");
            }
            assertThat(replacement.statusCode()).as("replacement must not fail at a second reset call").isEqualTo(200);
            assertThat(redis.hasKey(keys.getFirst())).isFalse();
            assertThat(send(get("/api/v1/me").header("Cookie",cookie(replacement))).statusCode()).isEqualTo(200);
            error(send(get("/api/v1/me").header("Cookie",cookie(old))),401,"UNAUTHENTICATED");
            assertThat(send(get("/api/v1/me").header("Origin","https://android.example.invalid")
                    .header("Authorization","Bearer "+json.readTree(other.body()).get("accessToken").asString())).statusCode()).isEqualTo(200);
        } finally { org.mockito.Mockito.reset(limiter); }
    }
    @Test @Order(1) void databaseQueryFailureReturns503WithoutIssuingOrRevokingSessions() throws Exception {
        var existing = web("SyntheticPass8!", null);
        assertThat(existing.statusCode()).isEqualTo(200);
        var principal = auth.authenticate(access(existing));
        String key = RedisLoginSessions.key(principal.sessionId());
        String stored = redis.opsForValue().get(key);
        var sessionKeys = redis.keys("auth:session:*");
        // Only the disposable Testcontainers database is affected. This is a real SQL failure,
        // not a PostgreSQL process/network outage or a mock repository exception.
        jdbc.execute("ALTER TABLE account RENAME TO auth_test_unavailable_account");
        try {
            var failedLogin = web("SyntheticPass8!", null);
            error(failedLogin, 503, "SERVICE_UNAVAILABLE");
            assertThat(failedLogin.headers().allValues("Set-Cookie")).isEmpty();
            error(send(get("/api/v1/me").header("Cookie", cookie(existing))), 503, "SERVICE_UNAVAILABLE");
            assertThat(redis.keys("auth:session:*")).isEqualTo(sessionKeys);
            assertThat(redis.opsForValue().get(key)).isEqualTo(stored);
        } finally {
            jdbc.execute("ALTER TABLE auth_test_unavailable_account RENAME TO account");
        }
        var recovered = send(get("/api/v1/me").header("Cookie", cookie(existing)));
        assertThat(recovered.statusCode()).isEqualTo(200);
        assertThat(json.readTree(recovered.body()).get("id").asString()).isEqualTo(id.toString());
    }

    @Test @Order(99) void actualRedisOutageReturns503AndNeverIssuesCookies() throws Exception {
        var login=web("SyntheticPass8!",null); REDIS.stop();
        var refreshFailure=send(webRefresh(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA"));
        error(refreshFailure,503,"SERVICE_UNAVAILABLE");
        assertThat(refreshFailure.headers().allValues("Set-Cookie")).isEmpty();
        var logoutFailure=send(webLogout(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA","{}"));
        error(logoutFailure,503,"SERVICE_UNAVAILABLE");
        assertThat(logoutFailure.headers().allValues("Set-Cookie")).isEmpty();
        error(send(get("/api/v1/me").header("Cookie",cookie(login))),503,"SERVICE_UNAVAILABLE");
        error(send(webPatch("{\"displayName\":\"Rejected name\"}",cookie(login))),503,"SERVICE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT display_name FROM account WHERE id=?",String.class,id))
                .isEqualTo("fixture");
        var failure=web("SyntheticPass8!",null); error(failure,503,"SERVICE_UNAVAILABLE");
        assertThat(failure.headers().allValues("Set-Cookie")).isEmpty();
    }
    @Test @Order(1) void profileWebUpdateReturnsFiveFreshFieldsWithoutRotatingSession() throws Exception {
        var login=web("SyntheticPass8!",null);
        var request=webPatch("{\"displayName\":\"  새 이름😀  \"}",cookie(login));
        var response=send(request);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var account=json.readTree(response.body());
        assertThat(account.size()).isEqualTo(5);
        assertThat(account.get("id").asString()).isEqualTo(id.toString());
        assertThat(account.get("displayName").asString()).isEqualTo("새 이름😀");
        assertThat(account.get("primaryEmail").asString()).isEqualTo(email);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(json.readTree(send(get("/api/v1/me").header("Cookie",cookie(login))).body())
                .get("displayName").asString()).isEqualTo("새 이름😀");
        assertThat(json.readTree(web("SyntheticPass8!",null).body()).get("account")
                .get("displayName").asString()).isEqualTo("새 이름😀");
    }
    @Test @Order(1) void profileAndroidUsesActualBearerAndDuplicateRequestsConverge() throws Exception {
        var login=android("SyntheticPass8!");
        var token=json.readTree(login.body()).get("accessToken").asString();
        for(int i=0;i<2;i++) {
            var response=send(patch("/api/v1/me","{\"displayName\":\"Android 이름\"}")
                    .header("Content-Type","application/json").header("Origin","https://android.example.invalid")
                    .header("Authorization","Bearer "+token).header("Idempotency-Key","ignored"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(json.readTree(response.body()).get("displayName").asString()).isEqualTo("Android 이름");
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
    }
    @Test @Order(1) void profileMissingOrForgedAuthenticationIs401AndAndroidLabelIsNotProof() throws Exception {
        error(send(patch("/api/v1/me","{}")),401,"UNAUTHENTICATED");
        error(send(patch("/api/v1/me","{}").header("X-SceneCrew-Client","ANDROID")),401,"UNAUTHENTICATED");
        error(send(webPatch("{}","__Secure-sc-access=forged")),401,"UNAUTHENTICATED");
        var login=web("SyntheticPass8!",null);
        error(send(patch("/api/v1/me","{}").header("Cookie",cookie(login))
                .header("X-SceneCrew-Client","ANDROID").header("Origin","https://android.example.invalid")
                .header("Content-Type","application/json")),403,"CSRF_REJECTED");
        error(send(patch("/api/v1/me","{}").header("Authorization","Bearer "+access(login))
                .header("Origin","https://android.example.invalid").header("Content-Type","application/json")),
                403,"AUTH_CHANNEL_MISMATCH");
        error(send(webPatch("{}",cookie(login)).header("Authorization","Bearer "+access(login))),
                400,"MIXED_AUTH_CREDENTIALS");
    }
    @Test @Order(1) void profileWebRequiresExactOriginSingleCsrfHeaderAndJson() throws Exception {
        var login=web("SyntheticPass8!",null); String cookies=cookie(login);
        for(String origin:new String[]{"", "https://evil.example.invalid", "https://web.example.invalid.evil.invalid"}) {
            var request=patch("/api/v1/me","{}").header("Cookie",cookies)
                    .header("X-SceneCrew-CSRF","1").header("Content-Type","application/json");
            if(!origin.isEmpty())request.header("Origin",origin);
            error(send(request),403,"CSRF_REJECTED");
        }
        for(String csrf:new String[]{"", "0"}) {
            var request=patch("/api/v1/me","{}").header("Cookie",cookies)
                    .header("Origin","https://web.example.invalid").header("Content-Type","application/json");
            if(!csrf.isEmpty())request.header("X-SceneCrew-CSRF",csrf);
            error(send(request),403,"CSRF_REJECTED");
        }
        error(send(webPatch("{}",cookies).header("X-SceneCrew-CSRF","1")),403,"CSRF_REJECTED");
        error(send(webPatch("{}",cookies).header("Origin","https://web.example.invalid")),403,"CSRF_REJECTED");
        for(String type:new String[]{"", "text/plain", "application/x-www-form-urlencoded", "application/problem+json"}) {
            var request=patch("/api/v1/me","{}").header("Cookie",cookies)
                    .header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1");
            if(!type.isEmpty())request.header("Content-Type",type);
            error(send(request),415,"UNSUPPORTED_MEDIA_TYPE");
        }
        assertThat(jdbc.queryForObject("SELECT display_name FROM account WHERE id=?",String.class,id)).isEqualTo("fixture");
    }
    @Test @Order(1) void profileAndroidRetainsOriginAndContentTypeRequirements() throws Exception {
        var token=json.readTree(android("SyntheticPass8!").body()).get("accessToken").asString();
        for(String origin:new String[]{"", "https://evil.example.invalid", "https://web.example.invalid"}) {
            var request=patch("/api/v1/me","{}").header("Authorization","Bearer "+token)
                    .header("Content-Type","application/json");
            if(!origin.isEmpty())request.header("Origin",origin);
            error(send(request),403,"AUTH_CHANNEL_MISMATCH");
        }
        error(send(patch("/api/v1/me","{}").header("Authorization","Bearer "+token)
                .header("Origin","https://android.example.invalid").header("Content-Type","text/plain")),
                415,"UNSUPPORTED_MEDIA_TYPE");
    }
    @Test @Order(1) void profileRejectsInvalidNamesAndAccountInjection() throws Exception {
        var login=web("SyntheticPass8!",null);
        var other=UUID.randomUUID();
        jdbc.update("INSERT INTO account VALUES (?, 'other', 'ACTIVE', true, 0, '{}'::jsonb, now())",other);
        try {
            for(String body:new String[]{"{}", "null", "[]", "{\"displayName\":null}", "{\"displayName\":2}",
                    "{\"displayName\":\" \"}", "{\"displayName\":\"a\\nb\"}",
                    "{\"displayName\":\""+"😀".repeat(21)+"\"}",
                    "{\"displayName\":\"stolen\",\"id\":\""+other+"\"}",
                    "{\"displayName\":\"stolen\",\"accountId\":\""+other+"\"}"})
                error(send(webPatch(body,cookie(login))),400,"INVALID_REQUEST");
            assertThat(jdbc.queryForObject("SELECT display_name FROM account WHERE id=?",String.class,other)).isEqualTo("other");
            assertThat(jdbc.queryForObject("SELECT display_name FROM account WHERE id=?",String.class,id)).isEqualTo("fixture");
        } finally { jdbc.update("DELETE FROM account WHERE id=?",other); }
    }
    @Test @Order(1) void profileDatabaseFailureReturns503AndHttpNeverReportsRolledBackName() throws Exception {
        var login=web("SyntheticPass8!",null);
        org.mockito.Mockito.doAnswer(call -> jdbc.queryForObject("SELECT 1/0",Integer.class))
                .when(summaries).read(org.mockito.ArgumentMatchers.any());
        try {
            error(send(webPatch("{\"displayName\":\"rollback\"}",cookie(login))),503,"SERVICE_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT display_name FROM account WHERE id=?",String.class,id)).isEqualTo("fixture");
        } finally { org.mockito.Mockito.reset(summaries); }
    }
    @Test @Order(1) void profileCsrfExclusionDoesNotOpenOtherMethodsOrPaths() throws Exception {
        var login=web("SyntheticPass8!",null);
        // /me/consents PATCH는 #229에서 열었다(AccountConsentHttpTest). 나머지 경로·메서드는 닫혀 있어야 한다.
        for(String path:new String[]{"/api/v1/me/", "/api/v1/me/deactivate", "/api/v1/me/consents/x"}) {
            error(send(patch(path,"{}").header("Cookie",cookie(login)).header("Origin","https://web.example.invalid")
                    .header("X-SceneCrew-CSRF","1").header("Content-Type","application/json")),403,"FORBIDDEN");
        }
        error(send(HttpRequest.newBuilder(uri("/api/v1/me")).POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Cookie",cookie(login)).header("Origin","https://web.example.invalid")
                .header("X-SceneCrew-CSRF","1").header("Content-Type","application/json")),403,"FORBIDDEN");
    }
    @Test @Order(1) void logoutRevokesOnlyRefreshProvenSessionAndReplaysTwoCookieDeletions() throws Exception {
        var login=web("SyntheticPass8!",null); var otherWeb=web("SyntheticPass8!",null); var other=android("SyntheticPass8!");
        String cookies=cookie(login), attempt="AAAAAAAAAAAAAAAAAAAAAA";
        for(int i=0;i<3;i++) {
            var response=send(webLogout(cookies,attempt,"{}"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(204); assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            var deleted=response.headers().allValues("Set-Cookie"); assertThat(deleted).hasSize(2);
            assertThat(deleted).allSatisfy(c->assertThat(c).contains("Max-Age=0","Secure","HttpOnly","SameSite=Lax").doesNotContain("Domain="));
            assertThat(deleted.get(0)).startsWith("__Secure-sc-access=").contains("Path=/api/v1;");
            assertThat(deleted.get(1)).startsWith("__Secure-sc-refresh=").contains("Path=/api/v1/auth;");
        }
        error(send(webLogout(cookies,attempt,"{}")),401,"UNAUTHENTICATED");
        error(send(webLogout(cookies,"BBBBBBBBBBBBBBBBBBBBBB","{}")),401,"UNAUTHENTICATED");
        error(send(get("/api/v1/me").header("Cookie",cookies)),401,"UNAUTHENTICATED");
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(otherWeb))).statusCode()).isEqualTo(200);
        assertThat(send(get("/api/v1/me").header("Origin","https://android.example.invalid")
                .header("Authorization","Bearer "+json.readTree(other.body()).get("accessToken").asString())).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void logoutDoesNotRequireValidAccessAndAndroidNeverSetsCookies() throws Exception {
        var verified=new LocalCredentialVerifier.VerifiedAccount(id,"fixture",email,3,identity);
        var oldIssuer=new LoginTokenIssuer(new SecretKeySpec(SIGNING,"HmacSHA256"),
                Clock.offset(Clock.systemUTC(),Duration.ofHours(-2)),"http-test","http-test");
        var expired=oldIssuer.issue(verified);
        new RedisLoginSessions(redis,Clock.systemUTC()).register(verified,expired,"WEB");
        error(send(get("/api/v1/me").header("Cookie","__Secure-sc-access="+expired.accessToken())),401,"UNAUTHENTICATED");
        assertThat(send(webLogout("__Secure-sc-access="+expired.accessToken()+"; __Secure-sc-refresh="+expired.refreshToken(),
                "AAAAAAAAAAAAAAAAAAAAAA","{}")).statusCode()).isEqualTo(204);
        var android=android("SyntheticPass8!"); var body=json.readTree(android.body());
        var response=send(androidLogout("{\"refreshToken\":\""+body.get("refreshToken").asString()+"\"}"));
        assertThat(response.statusCode()).isEqualTo(204); assertThat(response.body()).isEmpty();
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        error(send(get("/api/v1/me").header("Origin","https://android.example.invalid")
                .header("Authorization","Bearer "+body.get("accessToken").asString())),401,"UNAUTHENTICATED");
        var web=web("SyntheticPass8!",null);
        String refreshOnly=cookie(web).split("; ")[1];
        assertThat(send(webLogout(refreshOnly,"AAAAAAAAAAAAAAAAAAAAAA","{}")).statusCode()).isEqualTo(204);
    }
    @Test @Order(1) void logoutH01CookieAndBodyValidationRejectBeforeAnyRevocation() throws Exception {
        var login=web("SyntheticPass8!",null); String cookies=cookie(login), attempt="AAAAAAAAAAAAAAAAAAAAAA";
        error(send(logoutPost("{}").header("X-SceneCrew-Client","WEB").header("Cookie",cookies)
                .header("X-SceneCrew-Logout-Attempt",attempt)),403,"CSRF_REJECTED");
        error(send(webLogout(cookies,attempt,"{}").setHeader("Origin","https://evil.example.invalid")),403,"CSRF_REJECTED");
        error(send(webLogout(cookies,attempt,"{}").setHeader("Content-Type","text/plain")),415,"UNSUPPORTED_MEDIA_TYPE");
        error(send(webLogout(cookies,attempt,"{}").header("Authorization","Bearer forged")),400,"MIXED_AUTH_CREDENTIALS");
        error(send(webLogout(cookies+"; __Secure-sc-refresh=x",attempt,"{}")),400,"DUPLICATE_AUTH_COOKIE");
        error(send(webLogout(cookies+"; broken",attempt,"{}")),400,"MALFORMED_COOKIE_HEADER");
        for(String input:List.of("", "null", "[]", "{bad", "{\"refreshToken\":\"forged\"}"))
            error(send(webLogout(cookies,attempt,input)),400,"INVALID_REQUEST");
        error(send(webLogout(cookies,"bad","{}")),400,"INVALID_REQUEST");
        error(send(webLogout(cookies,attempt,"{}").header("X-SceneCrew-Logout-Attempt",attempt)),400,"INVALID_REQUEST");
        error(send(webLogout("__Secure-sc-access="+access(login),attempt,"{}")),401,"UNAUTHENTICATED");
        error(send(webLogout("__Secure-sc-refresh=forged",attempt,"{}")),401,"UNAUTHENTICATED");
        error(send(androidLogout("{}").header("Authorization","Bearer "+access(login))),400,"INVALID_REQUEST");
        error(send(androidLogout("{\"refreshToken\":\"forged\"}").header("Cookie",cookies)),403,"AUTH_CHANNEL_MISMATCH");
        assertThat(send(get("/api/v1/me").header("Cookie",cookies)).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void logoutCurrentDatabaseAccountChecksProtectInitialAndRecovery() throws Exception {
        var login=web("SyntheticPass8!",null); String cookies=cookie(login),attempt="AAAAAAAAAAAAAAAAAAAAAA";
        for(String change:List.of("UPDATE account SET token_version=4 WHERE id=?",
                "UPDATE account SET status='DISABLED' WHERE id=?", "UPDATE account SET signup_completed=false WHERE id=?")) {
            jdbc.update(change,id); error(send(webLogout(cookies,attempt,"{}")),401,"UNAUTHENTICATED");
            jdbc.update("UPDATE account SET token_version=3,status='ACTIVE',signup_completed=true WHERE id=?",id);
        }
        jdbc.update("UPDATE auth_identity SET email_verified=false WHERE id=?",identity);
        error(send(webLogout(cookies,attempt,"{}")),401,"UNAUTHENTICATED");
        jdbc.update("UPDATE auth_identity SET email_verified=true WHERE id=?",identity);
        assertThat(send(get("/api/v1/me").header("Cookie",cookies)).statusCode()).isEqualTo(200);
        assertThat(send(webLogout(cookies,attempt,"{}")).statusCode()).isEqualTo(204);
        jdbc.update("UPDATE account SET token_version=4 WHERE id=?",id);
        error(send(webLogout(cookies,attempt,"{}")),401,"UNAUTHENTICATED");
    }
    @Test @Order(1) void socialLogoutRevokesAccessAndReplaysCompletionWithoutRevokingLocalSession() throws Exception {
        var local = web("SyntheticPass8!", null);
        var social = socialWebSession();
        String cookies = socialCookies(social);
        String attempt = "AAAAAAAAAAAAAAAAAAAAAA";
        assertThat(send(get("/api/v1/me").header("Cookie", cookies)).statusCode()).isEqualTo(200);
        assertThat(send(webLogout(cookies, attempt, "{}")).statusCode()).isEqualTo(204);
        error(send(get("/api/v1/me").header("Cookie", cookies)), 401, "UNAUTHENTICATED");
        assertThat(send(webLogout(cookies, attempt, "{}")).statusCode()).isEqualTo(204);
        assertThat(send(get("/api/v1/me").header("Cookie", cookie(local))).statusCode()).isEqualTo(200);
    }

    private LoginTokens socialWebSession() {
        UUID socialIdentity = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'GOOGLE', ?, NULL, NULL, false)",
                socialIdentity, id, socialIdentity.toString());
        var verified = new LocalCredentialVerifier.VerifiedAccount(id, "fixture", email, 3, socialIdentity);
        Clock clock = Clock.systemUTC();
        var tokens = new LoginTokenIssuer(new SecretKeySpec(SIGNING, "HmacSHA256"),
                clock, "http-test", "http-test").issue(verified);
        new RedisLoginSessions(redis, clock).register(verified, tokens, "WEB");
        String key = RedisLoginSessions.key(tokens.sessionId());
        String local = redis.opsForValue().get(key);
        redis.opsForValue().set(key, "v2" + local.substring(2) + "|GOOGLE",
                Duration.between(clock.instant(), tokens.refreshExpiresAt()));
        return tokens;
    }

    private String socialCookies(LoginTokens tokens) {
        return "__Secure-sc-access=" + tokens.accessToken() + "; __Secure-sc-refresh=" + tokens.refreshToken();
    }

    @Test @Order(1) void logoutLostRedisReplyReturns503AndSameProofRecovers204OverHttp() throws Exception {
        var login=web("SyntheticPass8!",null); String cookies=cookie(login),attempt="AAAAAAAAAAAAAAAAAAAAAA";
        var principal=auth.authenticate(access(login));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call->{
            Object result=call.callRealMethod(); java.util.List<?> keys=call.getArgument(1);
            if(keys.size()==2 && keys.get(1).toString().startsWith("auth:logout:")) {
                calls.incrementAndGet(); throw new org.springframework.dao.QueryTimeoutException("synthetic reply loss");
            }
            return result;
        }).when(redis).execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any(Object[].class));
        try {
            var response=send(webLogout(cookies,attempt,"{}")); error(response,503,"SERVICE_UNAVAILABLE");
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty(); assertThat(calls.get()).isEqualTo(1);
            assertThat(redis.hasKey(RedisLoginSessions.key(principal.sessionId()))).isFalse();
            assertThat(redis.hasKey(LogoutService.receiptKey(principal.sessionId()))).isTrue();
        } finally { org.mockito.Mockito.reset(redis); }
        error(send(get("/api/v1/me").header("Cookie",cookies)),401,"UNAUTHENTICATED");
        assertThat(send(webLogout(cookies,attempt,"{}")).statusCode()).isEqualTo(204);
    }
    @Test @Order(1) void socialRotationPreservesProviderAndLogoutPreventsFurtherRotation() throws Exception {
        var local = web("SyntheticPass8!", null);
        var old = socialWebSession();
        String attempt = "AAAAAAAAAAAAAAAAAAAAAA";
        var next = refreshCore(redis).rotate(old.refreshToken(), "WEB", attempt);
        assertThat(next.sessionId()).isEqualTo(old.sessionId());
        String current = redis.opsForValue().get(RedisLoginSessions.key(next.sessionId()));
        assertThat(StoredLoginSession.parse(current, Clock.systemUTC()).provider()).isEqualTo("GOOGLE");
        assertThat(send(get("/api/v1/me").header("Cookie", cookies(next))).statusCode()).isEqualTo(200);
        refreshError(() -> refreshCore(redis).rotate(old.refreshToken(), "WEB", attempt), "REFRESH_RECOVERY_UNAVAILABLE");
        assertThat(send(webLogout(cookies(next), attempt, "{}")).statusCode()).isEqualTo(204);
        for (var proof : List.of(old.refreshToken(), next.refreshToken())) {
            refreshError(() -> refreshCore(redis).rotate(proof, "WEB", attempt), "UNAUTHENTICATED");
        }
        error(send(get("/api/v1/me").header("Cookie", cookies(next))), 401, "UNAUTHENTICATED");
        assertThat(send(get("/api/v1/me").header("Cookie", cookie(local))).statusCode()).isEqualTo(200);
    }

    @Test @Order(1) void internalRefreshWithRealDatabaseAcceptsExpiredAccessAndMeObservesNewTokens() throws Exception {
        var verified=new LocalCredentialVerifier.VerifiedAccount(id,"fixture",email,3,identity);
        var old=new LoginTokenIssuer(new SecretKeySpec(SIGNING,"HmacSHA256"),
                Clock.offset(Clock.systemUTC(),Duration.ofHours(-2)),"http-test","http-test").issue(verified);
        new RedisLoginSessions(redis,Clock.systemUTC()).register(verified,old,"WEB");
        var otherWeb=web("SyntheticPass8!",null);var otherAndroid=android("SyntheticPass8!");
        error(send(get("/api/v1/me").header("Cookie",cookies(old))),401,"UNAUTHENTICATED");
        var next=refreshCore(redis).rotate(old.refreshToken(),"WEB","AAAAAAAAAAAAAAAAAAAAAA");
        assertThat(next.sessionId()).isEqualTo(old.sessionId());
        var me=send(get("/api/v1/me").header("Cookie",cookies(next)));
        assertThat(me.statusCode()).isEqualTo(200);assertThat(json.readTree(me.body()).get("id").asString()).isEqualTo(id.toString());
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(otherWeb))).statusCode()).isEqualTo(200);
        assertThat(send(get("/api/v1/me").header("Origin","https://android.example.invalid")
                .header("Authorization","Bearer "+json.readTree(otherAndroid.body()).get("accessToken").asString())).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void internalRefreshDatabaseStateIsRecheckedForRotationAndUsedProof() throws Exception {
        var login=web("SyntheticPass8!",null);String refresh=refreshCookie(login);var principal=auth.authenticate(access(login));
        String key=RedisLoginSessions.key(principal.sessionId()),before=redis.opsForValue().get(key);
        for(String change:List.of("UPDATE account SET token_version=4 WHERE id=?",
                "UPDATE account SET status='DISABLED' WHERE id=?", "UPDATE account SET status='UNKNOWN' WHERE id=?",
                "UPDATE account SET signup_completed=false WHERE id=?")) {
            jdbc.update(change,id);refreshError(()->refreshCore(redis).rotate(refresh,"WEB","AAAAAAAAAAAAAAAAAAAAAA"),"UNAUTHENTICATED");
            assertThat(redis.opsForValue().get(key)).isEqualTo(before);
            jdbc.update("UPDATE account SET token_version=3,status='ACTIVE',signup_completed=true WHERE id=?",id);
        }
        jdbc.update("UPDATE auth_identity SET email_verified=false WHERE id=?",identity);
        refreshError(()->refreshCore(redis).rotate(refresh,"WEB","AAAAAAAAAAAAAAAAAAAAAA"),"UNAUTHENTICATED");
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
        jdbc.update("UPDATE auth_identity SET email_verified=true WHERE id=?",identity);
        var next=refreshCore(redis).rotate(refresh,"WEB","AAAAAAAAAAAAAAAAAAAAAA");
        jdbc.update("UPDATE account SET token_version=4 WHERE id=?",id);
        refreshError(()->refreshCore(redis).rotate(refresh,"WEB","AAAAAAAAAAAAAAAAAAAAAA"),"UNAUTHENTICATED");
        error(send(get("/api/v1/me").header("Cookie",cookies(next))),401,"UNAUTHENTICATED");
    }
    // #263·#274: 공개 Refresh. 결과 재전달은 없다(같은 Attempt 재시도는 409 REFRESH_RECOVERY_UNAVAILABLE).
    @Test @Order(1) void publicWebRefreshRotatesBothCookiesAndReturnsTokenFreeBody() throws Exception {
        assertThat(context.getBeansOfType(RefreshService.class)).hasSize(1);
        var login=web("SyntheticPass8!",null);String old=cookie(login);
        var other=android("SyntheticPass8!");
        var original=auth.authenticate(access(login));
        Instant started=Instant.now();
        var result=send(webRefresh(old,"AAAAAAAAAAAAAAAAAAAAAA"));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(result.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(result.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        var body=json.readTree(result.body());
        assertThat(body.get("channel").asString()).isEqualTo("WEB");
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("channel","accessExpiresAt","refreshExpiresAt");
        assertThat(result.headers().allValues("Set-Cookie")).hasSize(2).allSatisfy(c->assertThat(c)
                .contains("Secure","HttpOnly","SameSite=Lax").doesNotContain("Domain="));
        assertThat(result.headers().allValues("Set-Cookie")).anySatisfy(c->assertThat(c)
                .startsWith("__Secure-sc-access=").contains("Path=/api/v1;"));
        assertThat(result.headers().allValues("Set-Cookie")).anySatisfy(c->assertThat(c)
                .startsWith("__Secure-sc-refresh=").contains("Path=/api/v1/auth;"));
        assertThat(Instant.parse(body.get("accessExpiresAt").asString()))
                .isBetween(started.plusSeconds(3600).minusSeconds(1),Instant.now().plusSeconds(3600));
        assertThat(Instant.parse(body.get("refreshExpiresAt").asString()))
                .isBetween(started.plus(Duration.ofDays(14)).minusSeconds(1),Instant.now().plus(Duration.ofDays(14)));
        assertThat(auth.authenticate(access(result)).sessionId()).isEqualTo(original.sessionId());
        assertThat(auth.authenticate(access(login)).sessionId()).isEqualTo(original.sessionId());
        assertThat(send(get("/api/v1/me").header("Origin","https://android.example.invalid")
                .header("Authorization","Bearer "+json.readTree(other.body()).get("accessToken").asString()))
                .statusCode()).isEqualTo(200);
        assertThat(refreshCookie(result)).isNotEqualTo(refreshCookie(login));
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(result))).statusCode()).isEqualTo(200);
        // 같은 Attempt 재시도(응답 유실 가정)는 가짜 성공·추가 회전 없이 409
        error(send(webRefresh(old,"AAAAAAAAAAAAAAAAAAAAAA")),409,"REFRESH_RECOVERY_UNAVAILABLE");
        // 이미 소비된 옛 증명을 다른 Attempt로 보내면 409 REFRESH_ATTEMPT_MISMATCH
        error(send(webRefresh(old,"BBBBBBBBBBBBBBBBBBBBBB")),409,"REFRESH_ATTEMPT_MISMATCH");
        error(send(webRefresh(cookie(result),"AAAAAAAAAAAAAAAAAAAAAA")),409,"REFRESH_ATTEMPT_MISMATCH");
        // 새 증명으로는 다시 회전된다
        assertThat(send(webRefresh(cookie(result),"CCCCCCCCCCCCCCCCCCCCCC")).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void publicAndroidRefreshReturnsTokensInJsonOnlyAndRejectsOldProof() throws Exception {
        var login=json.readTree(android("SyntheticPass8!").body());String old=login.get("refreshToken").asString();
        var result=send(androidRefresh("{\"refreshToken\":\""+old+"\"}","AAAAAAAAAAAAAAAAAAAAAA"));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(result.headers().allValues("Set-Cookie")).isEmpty();
        var body=json.readTree(result.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("channel","accessExpiresAt","refreshExpiresAt",
                "accessToken","refreshToken");
        assertThat(body.get("channel").asString()).isEqualTo("ANDROID");
        assertThat(body.get("refreshToken").asString()).isNotEqualTo(old);
        var me=send(get("/api/v1/me").header("Origin","https://android.example.invalid")
                .header("Authorization","Bearer "+body.get("accessToken").asString()));
        assertThat(me.statusCode()).isEqualTo(200);
        error(send(androidRefresh("{\"refreshToken\":\""+old+"\"}","AAAAAAAAAAAAAAAAAAAAAA")),409,"REFRESH_RECOVERY_UNAVAILABLE");
    }
    @Test @Order(1) void publicRefreshKeepsH01AndRejectsBeforeRotation() throws Exception {
        var login=web("SyntheticPass8!",null);var principal=auth.authenticate(access(login));
        String key=RedisLoginSessions.key(principal.sessionId()),before=redis.opsForValue().get(key);
        error(send(webRefresh(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA").setHeader("X-SceneCrew-CSRF","0")),403,"CSRF_REJECTED");
        error(send(webRefresh(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA").setHeader("Origin","https://evil.example.invalid")),403,"CSRF_REJECTED");
        error(send(webRefresh(cookie(login),"short")),400,"INVALID_REQUEST");
        var forged=HttpRequest.newBuilder(uri("/api/v1/auth/refresh")).header("Content-Type","application/json")
                .header("X-SceneCrew-Client","WEB").header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1")
                .header("X-SceneCrew-Refresh-Attempt","AAAAAAAAAAAAAAAAAAAAAA").header("Cookie",cookie(login))
                .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"forged\"}"));
        error(send(forged),400,"INVALID_REQUEST");
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
    }
    @Test @Order(1) void publicRefreshRejectsMalformedAndMixedRequestsWithoutConsumingProof() throws Exception {
        var login=web("SyntheticPass8!",null);
        var principal=auth.authenticate(access(login));
        String key=RedisLoginSessions.key(principal.sessionId()), before=redis.opsForValue().get(key);
        String attempt="AAAAAAAAAAAAAAAAAAAAAA";
        var missingProtection=webRefresh(cookie(login),attempt).build();
        var noCsrf=HttpRequest.newBuilder(missingProtection,(name,value)->!name.equalsIgnoreCase("X-SceneCrew-CSRF"));
        var noOrigin=HttpRequest.newBuilder(missingProtection,(name,value)->!name.equalsIgnoreCase("Origin"));
        var noChannel=HttpRequest.newBuilder(missingProtection,(name,value)->!name.equalsIgnoreCase("X-SceneCrew-Client"));
        var noAttempt=HttpRequest.newBuilder(missingProtection,(name,value)->!name.equalsIgnoreCase("X-SceneCrew-Refresh-Attempt"));
        error(send(noCsrf),403,"CSRF_REJECTED");
        error(send(noOrigin),403,"CSRF_REJECTED");
        error(send(noChannel),400,"INVALID_AUTH_CHANNEL");
        error(send(noAttempt),400,"INVALID_REQUEST");
        error(send(webRefresh(cookie(login),attempt).setHeader("Content-Type","text/plain")),415,"UNSUPPORTED_MEDIA_TYPE");
        error(send(webRefresh(cookie(login),attempt).header("X-SceneCrew-Refresh-Attempt",attempt)),400,"INVALID_REQUEST");
        error(send(webRefresh(cookie(login)+"; __Secure-sc-refresh=forged",attempt)),400,"DUPLICATE_AUTH_COOKIE");
        error(send(webRefresh(cookie(login),attempt).header("Authorization","Bearer "+access(login))),400,"MIXED_AUTH_CREDENTIALS");
        error(send(androidRefresh("{}",attempt).header("Authorization","Bearer "+access(login))),400,"INVALID_REQUEST");
        error(send(androidRefresh("{\"refreshToken\":\""+refreshCookie(login)+"\"}",attempt)
                .header("Cookie",cookie(login))),403,"AUTH_CHANNEL_MISMATCH");
        for(String body:List.of("null","[]","{bad","{\"extra\":true}")) {
            var response=send(webRefresh(cookie(login),attempt).POST(HttpRequest.BodyPublishers.ofString(body)));
            error(response,400,"INVALID_REQUEST");
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
        assertThat(redis.hasKey(RefreshService.receiptKey(principal.sessionId()))).isFalse();
        assertThat(send(webRefresh(cookie(login),attempt)).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void publicRefreshRejectsExpiredForgedAndWrongChannelProofs() throws Exception {
        var login=web("SyntheticPass8!",null);var principal=auth.authenticate(access(login));
        String key=RedisLoginSessions.key(principal.sessionId()), before=redis.opsForValue().get(key);
        String attempt="AAAAAAAAAAAAAAAAAAAAAA", proof=refreshCookie(login);
        error(send(webRefresh("__Secure-sc-access="+access(login),attempt)),401,"UNAUTHENTICATED");
        String forged=proof.substring(0,37)+"A".repeat(43);
        error(send(webRefresh("__Secure-sc-refresh="+forged,attempt)),401,"UNAUTHENTICATED");
        error(send(androidRefresh("{\"refreshToken\":\""+proof+"\"}",attempt)),403,"AUTH_CHANNEL_MISMATCH");
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
        String[] fields=before.split("\\|",-1);
        fields[7]=Instant.now().minusSeconds(1).toString();
        String expired=String.join("|",fields);
        redis.opsForValue().set(key,expired,Duration.ofMinutes(1));
        var response=send(webRefresh(cookie(login),attempt));
        error(response,401,"UNAUTHENTICATED");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(redis.opsForValue().get(key)).isEqualTo(expired);
        assertThat(redis.hasKey(RefreshService.receiptKey(principal.sessionId()))).isFalse();
    }
    @Test @Order(1) void publicRefreshRejectsAlreadyDisabledAccountWithoutChangingSession() throws Exception {
        var login=web("SyntheticPass8!",null);var principal=auth.authenticate(access(login));
        String key=RedisLoginSessions.key(principal.sessionId()), before=redis.opsForValue().get(key);
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",id);
        var response=send(webRefresh(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA"));
        error(response,401,"UNAUTHENTICATED");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
        assertThat(redis.hasKey(RefreshService.receiptKey(principal.sessionId()))).isFalse();
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) @Order(1)
    void publicRefreshDoesNotRequireLiveAccessCookie(boolean includeExpiredAccess) throws Exception {
        var verified=new LocalCredentialVerifier.VerifiedAccount(id,"fixture",email,3,identity);
        var expired=new LoginTokenIssuer(new SecretKeySpec(SIGNING,"HmacSHA256"),
                Clock.offset(Clock.systemUTC(),Duration.ofHours(-2)),"http-test","http-test").issue(verified);
        new RedisLoginSessions(redis,Clock.systemUTC()).register(verified,expired,"WEB");
        error(send(get("/api/v1/me").header("Cookie",cookies(expired))),401,"UNAUTHENTICATED");
        String proof=includeExpiredAccess ? cookies(expired) : "__Secure-sc-refresh="+expired.refreshToken();
        var rotated=send(webRefresh(proof,"AAAAAAAAAAAAAAAAAAAAAA"));
        assertThat(rotated.statusCode()).isEqualTo(200);
        assertThat(auth.authenticate(access(rotated)).sessionId()).isEqualTo(expired.sessionId());
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(rotated))).statusCode()).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(strings={"AAAAAAAAAAAAAAAAAAAAAA","BBBBBBBBBBBBBBBBBBBBBB"}) @Order(1)
    void concurrentPublicRefreshHasOneSuccessAndNoSecondRotation(String secondAttempt) throws Exception {
        var login=web("SyntheticPass8!",null);var principal=auth.authenticate(access(login));
        var entered=new CountDownLatch(2);var resume=new CountDownLatch(1);
        pauseRotation(entered,resume);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->send(webRefresh(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA")));
            var second=pool.submit(()->send(webRefresh(cookie(login),secondAttempt)));
            try { assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue(); }
            finally { resume.countDown(); }
            var responses=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200,409);
            var success=responses.stream().filter(r->r.statusCode()==200).findFirst().orElseThrow();
            var rejected=responses.stream().filter(r->r.statusCode()==409).findFirst().orElseThrow();
            error(rejected,409,"AAAAAAAAAAAAAAAAAAAAAA".equals(secondAttempt)
                    ? "REFRESH_RECOVERY_UNAVAILABLE" : "REFRESH_ATTEMPT_MISMATCH");
            assertThat(rejected.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(auth.authenticate(access(success)).sessionId()).isEqualTo(principal.sessionId());
            assertThat(redis.hasKey(RefreshService.receiptKey(principal.sessionId()))).isTrue();
        } finally { resume.countDown();org.mockito.Mockito.reset(redis); }
    }
    @Test @Order(1) void publicLogoutBeforePausedRefreshPreventsSessionResurrection() throws Exception {
        var login=web("SyntheticPass8!",null);var other=web("SyntheticPass8!",null);
        var principal=auth.authenticate(access(login));
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        pauseRotation(entered,resume);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=pool.submit(()->send(webRefresh(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA")));
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(send(webLogout(cookie(login),"BBBBBBBBBBBBBBBBBBBBBB","{}")).statusCode()).isEqualTo(204);
            } finally { resume.countDown(); }
            var rejected=pending.get(15,TimeUnit.SECONDS);
            error(rejected,401,"UNAUTHENTICATED");
            assertThat(rejected.headers().allValues("Set-Cookie")).isEmpty();
        } finally { resume.countDown();org.mockito.Mockito.reset(redis); }
        assertThat(redis.hasKey(RedisLoginSessions.key(principal.sessionId()))).isFalse();
        error(send(get("/api/v1/me").header("Cookie",cookie(login))),401,"UNAUTHENTICATED");
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(other))).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void publicRefreshBeforeLogoutRequiresCurrentProofAndRevokesBothAccessTokens() throws Exception {
        var login=web("SyntheticPass8!",null);
        var rotated=send(webRefresh(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA"));
        assertThat(rotated.statusCode()).isEqualTo(200);
        error(send(webLogout(cookie(login),"BBBBBBBBBBBBBBBBBBBBBB","{}")),401,"UNAUTHENTICATED");
        assertThat(send(webLogout(cookie(rotated),"BBBBBBBBBBBBBBBBBBBBBB","{}")).statusCode()).isEqualTo(204);
        error(send(webRefresh(cookie(rotated),"CCCCCCCCCCCCCCCCCCCCCC")),401,"UNAUTHENTICATED");
        error(send(get("/api/v1/me").header("Cookie",cookie(login))),401,"UNAUTHENTICATED");
        error(send(get("/api/v1/me").header("Cookie",cookie(rotated))),401,"UNAUTHENTICATED");
    }
    @Test @Order(1) void publicRefreshLostRedisReplyCannotReplayCredentialsOrRotateAgain() throws Exception {
        var login=web("SyntheticPass8!",null);var principal=auth.authenticate(access(login));
        String key=RedisLoginSessions.key(principal.sessionId()), before=redis.opsForValue().get(key);
        org.mockito.Mockito.doAnswer(call->{
            call.callRealMethod();throw new org.springframework.dao.QueryTimeoutException("synthetic refresh reply loss");
        }).when(redis).execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any(Object[].class));
        HttpResponse<String> response;
        try { response=send(webRefresh(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA")); }
        finally { org.mockito.Mockito.reset(redis); }
        error(response,503,"SERVICE_UNAVAILABLE");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        String after=redis.opsForValue().get(key);
        assertThat(after).isNotNull().isNotEqualTo(before);
        var retry=send(webRefresh(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA"));
        error(retry,409,"REFRESH_RECOVERY_UNAVAILABLE");
        assertThat(retry.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(redis.opsForValue().get(key)).isEqualTo(after);
    }
    @Test @Order(1)
    @DisplayName("#228: Refresh 발급이 먼저 잠그면 상태 변경이 기다리고 이후 자격은 거절된다")
    void refreshIssuanceBeforeStatusOnlyDeactivationSerializesAndRejectsSubsequentCredentials() throws Exception {
        var login=web("SyntheticPass8!",null);
        var principal=auth.authenticate(access(login));
        String sessionKey=RedisLoginSessions.key(principal.sessionId());
        String before=redis.opsForValue().get(sessionKey);
        assertThat(before).isNotNull();
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        pauseRotation(entered,resume);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var pending=pool.submit(()->send(webRefresh(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA")));
            HttpResponse<String> rotated;
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                var writerStarted=new CountDownLatch(1);
                var writerPid=new java.util.concurrent.atomic.AtomicInteger();
                var disabled=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                    writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    writerStarted.countDown();
                    // Real row UPDATE, deliberately without a tokenVersion increase.
                    return jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",id);
                }));
                assertThat(writerStarted.await(10,TimeUnit.SECONDS)).isTrue();
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->
                        assertThat(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))",Integer.class,writerPid.get())).isPositive());
                assertThat(disabled.isDone()).isFalse();
                // Release issuance before waiting for the writer; never wait on our own latch/row-lock cycle.
                resume.countDown();
                rotated=pending.get(15,TimeUnit.SECONDS);
                assertThat(disabled.get(15,TimeUnit.SECONDS)).isEqualTo(1);
            } finally { resume.countDown(); }
            // 200 is legitimate only because this issuance held the lock BEFORE deactivation.
            assertThat(rotated.statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id=?",String.class,id)).isEqualTo("DISABLED");
            assertThat(jdbc.queryForObject("SELECT token_version FROM account WHERE id=?",Long.class,id)).isEqualTo(3L);
            assertThat(refreshCookie(rotated).substring(0,36)).isEqualTo(principal.sessionId().toString());
            String retained=redis.opsForValue().get(sessionKey);
            assertThat(retained).isNotNull().isNotEqualTo(before);
            assertThat(redis.hasKey(RefreshService.receiptKey(principal.sessionId()))).isTrue();
            error(send(get("/api/v1/me").header("Cookie",cookie(rotated))),401,"UNAUTHENTICATED");
            error(send(webRefresh(cookie(rotated),"BBBBBBBBBBBBBBBBBBBBBB")),401,"UNAUTHENTICATED");
            assertThat(redis.opsForValue().get(sessionKey)).isEqualTo(retained);
        } finally { resume.countDown();org.mockito.Mockito.reset(redis); }
    }
    void pauseRotation(CountDownLatch entered,CountDownLatch resume) {
        org.mockito.Mockito.doAnswer(call->{
            List<?> keys=call.getArgument(1);
            if(keys.size()==3) {
                entered.countDown();
                assertThat(resume.await(10,TimeUnit.SECONDS)).isTrue();
            }
            return call.callRealMethod();
        }).when(redis).execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any(Object[].class));
    }
    @Test @Order(1) void publicRefreshPreflightUsesActualAndroidHeadersWithoutAllowingWebCsrfHeader() throws Exception {
        var preflight=HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
                .method("OPTIONS",HttpRequest.BodyPublishers.noBody())
                .header("Origin","https://android.example.invalid")
                .header("Access-Control-Request-Method","POST")
                .header("Access-Control-Request-Headers","content-type,x-scenecrew-client,x-scenecrew-refresh-attempt");
        var allowed=send(preflight);
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://android.example.invalid");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().toLowerCase(Locale.ROOT)
                .split("\\s*,\\s*"))
                .containsExactlyInAnyOrder("content-type","x-scenecrew-client","x-scenecrew-refresh-attempt");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Methods").orElseThrow().split("\\s*,\\s*"))
                .contains("POST").doesNotContain("*");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        var forbidden=send(HttpRequest.newBuilder(preflight.build(),(name,value)->true)
                .setHeader("Access-Control-Request-Headers","content-type,x-scenecrew-client,x-scenecrew-refresh-attempt,x-scenecrew-csrf"));
        // Mixed lists receive the permitted subset. Browsers require every requested header to be allowed.
        assertThat(forbidden.statusCode()).isEqualTo(200);
        assertThat(forbidden.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://android.example.invalid");
        assertThat(forbidden.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().toLowerCase(Locale.ROOT)
                .split("\\s*,\\s*"))
                .containsExactlyInAnyOrder("content-type","x-scenecrew-client","x-scenecrew-refresh-attempt")
                .doesNotContain("x-scenecrew-csrf","*");
        assertThat(forbidden.headers().firstValue("Access-Control-Allow-Methods").orElseThrow().split("\\s*,\\s*"))
                .contains("POST");
        assertThat(forbidden.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        var unknown=send(HttpRequest.newBuilder(preflight.build(),(name,value)->true)
                .setHeader("Origin","https://evil.example.invalid"));
        assertThat(unknown.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(unknown.headers().firstValue("Access-Control-Allow-Methods")).isEmpty();
        assertThat(unknown.headers().firstValue("Access-Control-Allow-Headers")).isEmpty();
        assertThat(unknown.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        var unsupportedMethod=send(HttpRequest.newBuilder(preflight.build(),(name,value)->true)
                .setHeader("Access-Control-Request-Method","TRACE"));
        assertThat(unsupportedMethod.statusCode()).isEqualTo(403);
        assertThat(unsupportedMethod.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(unsupportedMethod.headers().firstValue("Access-Control-Allow-Methods")).isEmpty();
        assertThat(unsupportedMethod.headers().firstValue("Access-Control-Allow-Headers")).isEmpty();
        assertThat(unsupportedMethod.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        var login=json.readTree(android("SyntheticPass8!").body());
        var rotated=send(androidRefresh("{\"refreshToken\":\""+login.get("refreshToken").asString()+"\"}","AAAAAAAAAAAAAAAAAAAAAA"));
        assertThat(rotated.statusCode()).isEqualTo(200);
        assertThat(rotated.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://android.example.invalid");
        assertThat(rotated.headers().allValues("Set-Cookie")).isEmpty();
        var failure=send(androidRefresh("{\"refreshToken\":\"forged\"}","BBBBBBBBBBBBBBBBBBBBBB"));
        error(failure,401,"UNAUTHENTICATED");
        assertThat(failure.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://android.example.invalid");
    }
    // #263 CORS: 앱 출처만 허용하고 Cookie 자격은 허용하지 않는다.
    @Test @Order(1) void corsAllowsOnlyConfiguredAppOriginWithoutCredentials() throws Exception {
        var preflight=send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .method("OPTIONS",HttpRequest.BodyPublishers.noBody())
                .header("Origin","https://android.example.invalid").header("Access-Control-Request-Method","POST")
                .header("Access-Control-Request-Headers","content-type,x-scenecrew-client,authorization,idempotency-key"));
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://android.example.invalid");
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        var denied=send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .method("OPTIONS",HttpRequest.BodyPublishers.noBody())
                .header("Origin","https://evil.example.invalid").header("Access-Control-Request-Method","POST"));
        // 낯선 출처에는 CORS를 적용하지 않는다. 허용 헤더가 없으니 브라우저가 실제 요청을 보내지 않는다.
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(denied.headers().firstValue("Access-Control-Allow-Methods")).isEmpty();
        var evilPost=send(post(body("SyntheticPass8!")).header("X-SceneCrew-Client","ANDROID")
                .header("Origin","https://evil.example.invalid"));
        assertThat(evilPost.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        error(evilPost,403,"AUTH_CHANNEL_MISMATCH");
        var csrf=send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .method("OPTIONS",HttpRequest.BodyPublishers.noBody())
                .header("Origin","https://android.example.invalid").header("Access-Control-Request-Method","POST")
                .header("Access-Control-Request-Headers","x-scenecrew-csrf"));
        assertThat(csrf.statusCode()).isEqualTo(403);
        var login=android("SyntheticPass8!");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://android.example.invalid");
        assertThat(login.headers().firstValue("Access-Control-Expose-Headers").orElse("")).contains("X-Request-Id");
        var failure=send(get("/api/v1/me").header("Origin","https://android.example.invalid"));
        assertThat(failure.statusCode()).isEqualTo(401);
        assertThat(failure.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://android.example.invalid");
        var web=web("SyntheticPass8!",null);
        assertThat(web.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }
    @Test @Order(1) void internalRotationThenHttpLogoutNeedsCurrentRefreshAndRevokesBothAccessTokens() throws Exception {
        var login=web("SyntheticPass8!",null);var other=web("SyntheticPass8!",null);String old=refreshCookie(login);
        var next=refreshCore(redis).rotate(old,"WEB","AAAAAAAAAAAAAAAAAAAAAA");
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(login))).statusCode()).isEqualTo(200);
        error(send(webLogout(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA","{}")),401,"UNAUTHENTICATED");
        assertThat(send(get("/api/v1/me").header("Cookie",cookies(next))).statusCode()).isEqualTo(200);
        assertThat(send(webLogout(cookies(next),"AAAAAAAAAAAAAAAAAAAAAA","{}")).statusCode()).isEqualTo(204);
        error(send(get("/api/v1/me").header("Cookie",cookie(login))),401,"UNAUTHENTICATED");
        error(send(get("/api/v1/me").header("Cookie",cookies(next))),401,"UNAUTHENTICATED");
        refreshError(()->refreshCore(redis).rotate(old,"WEB","AAAAAAAAAAAAAAAAAAAAAA"),"UNAUTHENTICATED");
        assertThat(redis.hasKey(RefreshService.receiptKey(next.sessionId()))).isTrue();
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(other))).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void internalRotationLostReplyWithRealDatabaseRemainsUnknownAndCannotFakeRecovery() throws Exception {
        var login=web("SyntheticPass8!",null);String old=refreshCookie(login);var principal=auth.authenticate(access(login));
        org.mockito.Mockito.doAnswer(call->{call.callRealMethod();throw new org.springframework.dao.QueryTimeoutException("synthetic refresh reply loss");})
                .when(redis).execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                        org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any(Object[].class));
        try {refreshError(()->refreshCore(redis).rotate(old,"WEB","AAAAAAAAAAAAAAAAAAAAAA"),"SERVICE_UNAVAILABLE");}
        finally {org.mockito.Mockito.reset(redis);}
        assertThat(redis.hasKey(RefreshService.receiptKey(principal.sessionId()))).isTrue();
        refreshError(()->refreshCore(redis).rotate(old,"WEB","AAAAAAAAAAAAAAAAAAAAAA"),"REFRESH_RECOVERY_UNAVAILABLE");
        error(send(webLogout(cookie(login),"AAAAAAAAAAAAAAAAAAAAAA","{}")),401,"UNAUTHENTICATED");
        // SID-preserving rotation does not invent immediate Access revocation when delivery is lost.
        assertThat(send(get("/api/v1/me").header("Cookie",cookie(login))).statusCode()).isEqualTo(200);
    }
    @Test @Order(1) void internalAndroidRotationUsesItsOwnSessionAndBearerMeRemainsCookieFree() throws Exception {
        var login=android("SyntheticPass8!");var body=json.readTree(login.body());
        var next=refreshCore(redis).rotate(body.get("refreshToken").asString(),"ANDROID","AAAAAAAAAAAAAAAAAAAAAA");
        var me=send(get("/api/v1/me").header("Origin","https://android.example.invalid").header("Authorization","Bearer "+next.accessToken()));
        assertThat(me.statusCode()).isEqualTo(200);assertThat(me.headers().allValues("Set-Cookie")).isEmpty();
        var result=send(androidLogout("{\"refreshToken\":\""+next.refreshToken()+"\"}"));
        assertThat(result.statusCode()).isEqualTo(204);assertThat(result.headers().allValues("Set-Cookie")).isEmpty();
    }
    RefreshService refreshCore(StringRedisTemplate client) { return new RefreshService(client,
            new CurrentAccountReader(jdbc,transactions),tokenIssuer,Clock.systemUTC()); }
    void refreshError(Runnable action,String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(com.ssafy.scenecrew.global.error.BusinessException.class,
                e->assertThat(e.getErrorCode().name()).isEqualTo(code));
    }
    String refreshCookie(HttpResponse<String> response) { return response.headers().allValues("Set-Cookie").stream()
            .filter(c->c.startsWith("__Secure-sc-refresh=")).findFirst().orElseThrow().split(";",2)[0].substring("__Secure-sc-refresh=".length()); }
    String cookies(LoginTokens tokens) { return "__Secure-sc-access="+tokens.accessToken()+"; __Secure-sc-refresh="+tokens.refreshToken(); }
    HttpRequest.Builder webRefresh(String cookies,String attempt) { return HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
            .header("Content-Type","application/json").header("X-SceneCrew-Client","WEB").header("Origin","https://web.example.invalid")
            .header("X-SceneCrew-CSRF","1").header("X-SceneCrew-Refresh-Attempt",attempt).header("Cookie",cookies)
            .POST(HttpRequest.BodyPublishers.ofString("{}")); }
    HttpRequest.Builder androidRefresh(String body,String attempt) { return HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
            .header("Content-Type","application/json").header("X-SceneCrew-Client","ANDROID")
            .header("Origin","https://android.example.invalid").header("X-SceneCrew-Refresh-Attempt",attempt)
            .POST(HttpRequest.BodyPublishers.ofString(body)); }
    HttpRequest.Builder logoutPost(String body) { return HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)); }
    HttpRequest.Builder webLogout(String cookies,String attempt,String body) { return logoutPost(body)
            .header("X-SceneCrew-Client","WEB").header("Origin","https://web.example.invalid")
            .header("X-SceneCrew-CSRF","1").header("X-SceneCrew-Logout-Attempt",attempt).header("Cookie",cookies); }
    HttpRequest.Builder androidLogout(String body) { return logoutPost(body).header("X-SceneCrew-Client","ANDROID")
            .header("Origin","https://android.example.invalid").header("X-SceneCrew-Logout-Attempt","AAAAAAAAAAAAAAAAAAAAAA"); }
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
    HttpRequest.Builder patch(String path,String body) { return HttpRequest.newBuilder(uri(path))
            .method("PATCH",HttpRequest.BodyPublishers.ofString(body)); }
    HttpRequest.Builder webPatch(String body,String cookies) { return patch("/api/v1/me",body)
            .header("Cookie",cookies).header("Origin","https://web.example.invalid")
            .header("X-SceneCrew-CSRF","1").header("Content-Type","application/json"); }
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
    @Test @Order(1) void registrationWithoutProductionDeliveryReturns503AndCreatesNoAccount() throws Exception {
        assertThat(context.getBeansOfType(RegistrationDelivery.class)).isEmpty();
        int before=jdbc.queryForObject("SELECT count(*) FROM account",Integer.class);
        String body="{\"email\":\""+UUID.randomUUID()+"@example.invalid\",\"password\":\"SyntheticPass8!\","
                +"\"displayName\":\"fixture\",\"consent\":{\"service\":true,\"voice\":false,\"push\":false,\"externalAi\":false}}";
        var response=send(HttpRequest.newBuilder(uri("/api/v1/auth/register"))
                .header("Content-Type","application/json").header("X-SceneCrew-Client","WEB")
                .header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        error(response,503,"SERVICE_UNAVAILABLE");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account",Integer.class)).isEqualTo(before);
        var resend=send(HttpRequest.newBuilder(uri("/api/v1/auth/resend-verification"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"requestId\":\""+UUID.randomUUID()+"\"}")));
        error(resend,503,"SERVICE_UNAVAILABLE");
        assertThat(resend.headers().allValues("Set-Cookie")).isEmpty();
    }
    @TestConfiguration static class Encoder {
        @Bean PasswordEncoder testOnlyEncoder() { return new BCryptPasswordEncoder(4); }
    }
}

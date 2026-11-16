package com.ssafy.scenecrew.account.oauth;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;

/** Real product HTTP security, PostgreSQL and Redis; only provider HTTP responses are synthetic. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(OAuthWebHttpTest.Fixtures.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OAuthWebHttpTest {
    private static final long WEB_STATE_TTL_SECONDS = 600;
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final HttpServer PROVIDER;
    static final Map<String,String> challenges=new ConcurrentHashMap<>();
    static final Map<SocialProvider,String> subjects=new ConcurrentHashMap<>();
    static final AtomicInteger tokenCalls=new AtomicInteger(), profileCalls=new AtomicInteger();
    static volatile boolean tokenFailure, invalidProfile;
    static volatile String tokenOverride;
    static volatile int tokenStatus=200;
    static {
        try {
            PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PROVIDER.createContext("/", exchange->{
                String[] path=exchange.getRequestURI().getPath().split("/");
                var provider=SocialProvider.valueOf(path[1]);
                String body;
                int status=200;
                if (path[2].equals("token")) {
                    tokenCalls.incrementAndGet();
                    var form=params(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    boolean valid="POST".equals(exchange.getRequestMethod()) && form.get("client_secret").equals("synthetic-secret")
                            && form.get("redirect_uri").equals("https://api.example.invalid/api/v1/auth/oauth/"+provider+"/callback")
                            && "authorization_code".equals(form.get("grant_type"))
                            && Objects.equals(challenges.get(form.get("code")),HttpOAuthProviderClient.challenge(form.get("code_verifier")));
                    if (!valid || tokenFailure) { status=400; body="{\"error\":\"invalid_grant\"}"; }
                    else { status=tokenStatus; body=tokenOverride==null?"{\"access_token\":\"synthetic-provider-token\",\"token_type\":\"bearer\"}":tokenOverride; }
                } else {
                    profileCalls.incrementAndGet();
                    if (!"Bearer synthetic-provider-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) status=401;
                    body=invalidProfile?"{}":switch(provider) {
                        case GOOGLE -> "{\"sub\":\""+subjects.get(provider)+"\",\"email\":\"same@example.invalid\",\"email_verified\":true}";
                        case KAKAO -> "{\"id\":"+subjects.get(provider)+",\"kakao_account\":{\"email\":\"same@example.invalid\",\"is_email_verified\":true}}";
                        case NAVER -> "{\"resultcode\":\"00\",\"response\":{\"id\":\""+subjects.get(provider)+"\",\"email\":\"same@example.invalid\"}}";
                    };
                }
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            PROVIDER.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start();
        r.add("spring.data.redis.host",REDIS::getHost); r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true"); r.add("scenecrew.auth.oauth.web-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("scenecrew.auth.issuer",()->"oauth-test"); r.add("scenecrew.auth.audience",()->"oauth-test");
        r.add("scenecrew.auth.web-origins",()->"https://web.example.invalid");
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
        r.add("scenecrew.auth.oauth.web-home-uri",()->"https://web.example.invalid/login/oauth");
        r.add("scenecrew.auth.oauth.android-enabled",()->"true");
        r.add("scenecrew.auth.oauth.android-return-uri",()->"https://android.example.invalid/app/oauth");
        r.add("scenecrew.auth.oauth.state-ttl-seconds",()->Long.toString(WEB_STATE_TTL_SECONDS));
    }
    @AfterAll static void stop() { REDIS.stop(); PROVIDER.stop(0); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired OAuthLoginService service;
    @Autowired RedisOAuthAttempts attempts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean OAuthAccountReader accounts;
    @Autowired LoginTokenIssuer tokens;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean RedisLoginSessions sessions;
    @Autowired Clock clock;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;
    final JsonMapper json=JsonMapper.builder().build();
    UUID account;
    final HttpClient browser=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    @BeforeEach void setup() {
        account=UUID.randomUUID(); tokenFailure=false; invalidProfile=false; tokenOverride=null; tokenStatus=200; tokenCalls.set(0); profileCalls.set(0); challenges.clear();
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 3, '{}'::jsonb, now())",account);
        for (var provider:SocialProvider.values()) {
            String subject=provider==SocialProvider.KAKAO?"123456789":UUID.randomUUID().toString(); subjects.put(provider,subject);
            jdbc.update("INSERT INTO auth_identity VALUES (?, ?, ?, ?, NULL, NULL, false)",UUID.randomUUID(),account,provider.name(),subject);
        }
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM auth_identity WHERE account_id=?",account); jdbc.update("DELETE FROM account WHERE id=?",account);
        var keys=redis.keys("auth:*"); if (keys!=null && !keys.isEmpty()) redis.delete(keys); // exclusively owned container
    }
    @ParameterizedTest @EnumSource(SocialProvider.class)
    void realStoreMockProviderWebRoundTripAndCurrentSession(SocialProvider provider) throws Exception {
        var a=start(provider);
        assertThat(redis.getExpire(RedisOAuthAttempts.key("state",a.state())))
                .isBetween(WEB_STATE_TTL_SECONDS-10,WEB_STATE_TTL_SECONDS);
        var callback=callback(provider,a,a.cookie()); assertThat(callback.statusCode()).isEqualTo(303);
        assertThat(callback.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        String ticket=ticket(callback);
        assertThat(redis.getExpire(RedisOAuthAttempts.key("ticket",ticket))).isBetween(50L,60L);
        assertThat(callback.headers().firstValue("Location").orElseThrow()).startsWith("https://web.example.invalid/login/oauth?ticket=")
                .doesNotContain("provider-token","code=","state=");
        var result=exchange(ticket,a.cookie()); assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).doesNotContain("accessToken","refreshToken","provider-token");
        var summary=json.readTree(result.body()).get("account");
        assertThat(summary.get("id").asString()).isEqualTo(account.toString());
        assertThat(summary.get("primaryEmail").isNull()).isTrue(); assertThat(summary.get("primaryEmailVerified").asBoolean()).isFalse();
        var cookies=result.headers().allValues("Set-Cookie"); assertThat(cookies).hasSize(3);
        assertThat(cookies).allSatisfy(c->assertThat(c).contains("Secure","HttpOnly","SameSite=Lax").doesNotContain("Domain="));
        assertThat(cookies).anySatisfy(c->assertThat(c).contains("__Secure-sc-oauth=;","Max-Age=0"));
        String session=cookies.stream().filter(c->!c.startsWith("__Secure-sc-oauth=")).map(c->c.split(";",2)[0])
                .collect(java.util.stream.Collectors.joining("; "));
        assertThat(send(get("/api/v1/me").header("Cookie",session)).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE account SET token_version=4 WHERE id=?",account);
        error(send(get("/api/v1/me").header("Cookie",session)),401,"UNAUTHENTICATED");
        error(exchange(ticket,a.cookie()),401,"UNAUTHENTICATED");
        assertThat(tokenCalls.get()).isEqualTo(1); assertThat(profileCalls.get()).isEqualTo(1);
        assertThat(redis.keys("auth:session:*")).hasSize(1);
    }
    @Test void stateCookieProviderAndDuplicateQueryBindingBeforeExternalCalls() throws Exception {
        var a=start(SocialProvider.GOOGLE); var other=start(SocialProvider.GOOGLE);
        error(callback(SocialProvider.GOOGLE,a,other.cookie()),400,"INVALID_REQUEST");
        error(callback(SocialProvider.KAKAO,a,a.cookie()),400,"INVALID_REQUEST");
        error(send(get(callbackPath(SocialProvider.GOOGLE,a)+"&state=forged").header("Cookie",a.cookie())),400,"INVALID_REQUEST");
        error(send(get(callbackPath(SocialProvider.GOOGLE,a)+"&error=access_denied").header("Cookie",a.cookie())),400,"INVALID_REQUEST");
        error(callback(SocialProvider.GOOGLE,new Attempt("A".repeat(43),a.cookie()),a.cookie()),400,"INVALID_REQUEST");
        error(callback(SocialProvider.GOOGLE,a,null),400,"INVALID_REQUEST");
        assertThat(tokenCalls.get()).isZero();
        assertThat(callback(SocialProvider.GOOGLE,a,a.cookie()).statusCode()).isEqualTo(303);
        error(callback(SocialProvider.GOOGLE,a,a.cookie()),400,"INVALID_REQUEST"); assertThat(tokenCalls.get()).isEqualTo(1);
    }
    @Test void ticketCannotBeExchangedByAnotherBrowserOrAndroid() throws Exception {
        var a=start(SocialProvider.NAVER); var other=start(SocialProvider.NAVER);
        String ticket=ticket(callback(SocialProvider.NAVER,a,a.cookie()));
        error(exchange(ticket,other.cookie()),401,"UNAUTHENTICATED");
        error(exchange(ticket,null),400,"INVALID_REQUEST");
        error(send(androidExchange(ticket,attempts.secret())),401,"UNAUTHENTICATED");
        error(send(get("/api/v1/auth/oauth/GOOGLE/authorize?purpose=LOGIN&returnRoute=HOME")
                .header("X-SceneCrew-Client","ANDROID").header("Origin","https://android.example.invalid")),400,"INVALID_REQUEST");
        assertThat(exchange(ticket,a.cookie()).statusCode()).isEqualTo(200);
    }
    @Test void actualRedisExpiryRejectsStateAndTicket() throws Exception {
        var a=start(SocialProvider.GOOGLE); String key=RedisOAuthAttempts.key("state",a.state());
        redis.expire(key,Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(()->!Boolean.TRUE.equals(redis.hasKey(key)));
        error(callback(SocialProvider.GOOGLE,a,a.cookie()),400,"INVALID_REQUEST"); assertThat(tokenCalls.get()).isZero();
        var b=start(SocialProvider.GOOGLE); String ticket=ticket(callback(SocialProvider.GOOGLE,b,b.cookie()));
        String ticketKey=RedisOAuthAttempts.key("ticket",ticket); redis.expire(ticketKey,Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(()->!Boolean.TRUE.equals(redis.hasKey(ticketKey)));
        error(exchange(ticket,b.cookie()),401,"UNAUTHENTICATED"); assertThat(redis.keys("auth:session:*")).isEmpty();
    }
    @ParameterizedTest @EnumSource(SocialProvider.class)
    void tokenExchangeFailureNeverIssuesTicketAndIsNotRetried(SocialProvider provider) throws Exception {
        var a=start(provider); tokenFailure=true;
        error(callback(provider,a,a.cookie()),401,"UNAUTHENTICATED"); error(callback(provider,a,a.cookie()),400,"INVALID_REQUEST");
        assertThat(tokenCalls.get()).isEqualTo(1); assertThat(profileCalls.get()).isZero();
        assertThat(redis.keys("auth:oauth:ticket:*")).isEmpty(); assertThat(redis.keys("auth:session:*")).isEmpty();
    }
    @ParameterizedTest @EnumSource(SocialProvider.class)
    void invalidProviderSubjectIsRejected(SocialProvider provider) throws Exception {
        var a=start(provider); invalidProfile=true;
        error(callback(provider,a,a.cookie()),401,"UNAUTHENTICATED"); assertThat(redis.keys("auth:oauth:ticket:*")).isEmpty();
    }
    @Test void sameProviderCodeAcrossAttemptsIsRedeemedOnce() throws Exception {
        var a=start(SocialProvider.GOOGLE); var b=start(SocialProvider.GOOGLE);
        assertThat(callback(SocialProvider.GOOGLE,a,a.cookie()).statusCode()).isEqualTo(303);
        // Use a second valid state with the already redeemed code.
        var path="/api/v1/auth/oauth/GOOGLE/callback?state="+b.state()+"&code="+a.state();
        error(send(get(path).header("Cookie",b.cookie())),400,"INVALID_REQUEST"); assertThat(tokenCalls.get()).isEqualTo(1);
    }
    @Test void publicSignupBindsBrowserAndCreatesOneAccountAndSession() throws Exception {
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, 'synthetic-hash', 'same@example.invalid', true)",
                UUID.randomUUID(), account, account.toString());
        String subject = UUID.randomUUID().toString();
        subjects.put(SocialProvider.GOOGLE, subject);
        var first = start(SocialProvider.GOOGLE);
        var firstTicket = ticket(callback(SocialProvider.GOOGLE, first, first.cookie()));
        var pending = exchange(firstTicket, first.cookie());
        var second = start(SocialProvider.GOOGLE);
        var secondTicket = ticket(callback(SocialProvider.GOOGLE, second, second.cookie()));
        var secondPending = exchange(secondTicket, second.cookie());

        try {
            assertThat(pending.statusCode()).isEqualTo(200);
            assertThat(secondPending.statusCode()).isEqualTo(200);
            var body = json.readTree(pending.body());
            String signupTicket = body.get("signupTicket").asString();
            assertThat(body.size()).isEqualTo(5);
            assertThat(body.get("signupCompleted").isBoolean()).isTrue();
            assertThat(body.get("signupCompleted").asBoolean()).isFalse();
            assertThat(body.get("provider").asString()).isEqualTo("GOOGLE");
            assertThat(body.get("availableActions").toString()).isEqualTo("[\"LOGIN_EXISTING\",\"COMPLETE_SIGNUP\"]");
            var stored = attempts.read("signup", signupTicket, ErrorCode.UNAUTHENTICATED);
            assertThat(Instant.parse(body.get("signupExpiresAt").asString())).isEqualTo(stored.attempt().expiresAt());
            assertThat(redis.getExpire(stored.key())).isBetween(290L, 300L);
            assertThat(pending.headers().firstValue("Cache-Control")).hasValue("no-store");
            var pendingCookies = pending.headers().allValues("Set-Cookie");
            assertThat(pendingCookies).singleElement().asString()
                    .startsWith(first.cookie()+";")
                    .contains("Path=/api/v1/auth/oauth", "Max-Age=300", "Secure", "HttpOnly", "SameSite=Lax");
            assertThat(redis.keys("auth:session:*")).isEmpty();
            error(exchange(firstTicket, first.cookie()),401,"UNAUTHENTICATED");
            error(completeSignup(signupTicket, second.cookie(), true),401,"UNAUTHENTICATED");
            error(completeSignup(signupTicket, first.cookie(), false),400,"INVALID_REQUEST");

            var signup = completeSignup(signupTicket, first.cookie(), true);
            assertThat(signup.statusCode()).isEqualTo(200);
            var result = json.readTree(signup.body());
            assertThat(result.get("channel").asString()).isEqualTo("WEB");
            var summary = result.get("account");
            String newId = summary.get("id").asString();
            assertThat(newId).isNotEqualTo(account.toString());
            assertThat(summary.get("primaryEmail").isNull()).isTrue();
            assertThat(jdbc.queryForObject("SELECT consent->>'push' FROM account WHERE id=?", String.class,
                    UUID.fromString(newId))).isEqualTo("true");
            assertThat(jdbc.queryForObject("SELECT consent->>'voice' FROM account WHERE id=?", String.class,
                    UUID.fromString(newId))).isEqualTo("false");
            var cookies = signup.headers().allValues("Set-Cookie");
            assertThat(cookies).hasSize(3).anySatisfy(c -> assertThat(c)
                    .startsWith(OAuthWebController.COOKIE+"=;").contains("Max-Age=0"));
            String session = cookies.stream().filter(c -> !c.startsWith(OAuthWebController.COOKIE+"="))
                    .map(c -> c.split(";",2)[0]).collect(java.util.stream.Collectors.joining("; "));
            assertThat(send(get("/api/v1/me").header("Cookie", session)).statusCode()).isEqualTo(200);
            error(completeSignup(signupTicket, first.cookie(), true),401,"UNAUTHENTICATED");
            error(completeSignup(json.readTree(secondPending.body()).get("signupTicket").asString(),
                    second.cookie(), true),409,"AUTH_CONTEXT_CONFLICT");

            var next = start(SocialProvider.GOOGLE);
            var login = exchange(ticket(callback(SocialProvider.GOOGLE,next,next.cookie())),next.cookie());
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(json.readTree(login.body()).get("account").get("id").asString()).isEqualTo(newId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE provider='GOOGLE' AND subject=?",
                    Long.class, subject)).isEqualTo(1);
        } finally {
            var ids = jdbc.queryForList("SELECT account_id FROM auth_identity WHERE provider='GOOGLE' AND subject=?",
                    UUID.class, subject);
            jdbc.update("DELETE FROM auth_identity WHERE provider='GOOGLE' AND subject=?",subject);
            for (var id : ids) jdbc.update("DELETE FROM account WHERE id=?",id);
        }
    }

    @Test void expiredSignupTicketCannotCreateAnAccount() throws Exception {
        String subject = UUID.randomUUID().toString();
        subjects.put(SocialProvider.GOOGLE, subject);
        var attempt = start(SocialProvider.GOOGLE);
        var pending = exchange(ticket(callback(SocialProvider.GOOGLE, attempt, attempt.cookie())), attempt.cookie());
        String signupTicket = json.readTree(pending.body()).get("signupTicket").asString();
        String key = RedisOAuthAttempts.key("signup", signupTicket);
        redis.expire(key, Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        error(completeSignup(signupTicket, attempt.cookie(), true),401,"UNAUTHENTICATED");
        assertThat(redis.keys("auth:session:*")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE provider='GOOGLE' AND subject=?",
                Long.class, subject)).isZero();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"same@example.invalid","different@example.invalid"})
    void verifiedProviderEmailCannotSelectExistingAccountDuringInternalSignup(String localEmail) throws Exception {
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, 'synthetic-hash', ?, true)",
                UUID.randomUUID(),account,account.toString(),localEmail);
        String subject=UUID.randomUUID().toString();subjects.put(SocialProvider.GOOGLE,subject);
        var a=start(SocialProvider.GOOGLE);String ticket=ticket(callback(SocialProvider.GOOGLE,a,a.cookie()));
        var signup=new SocialSignupService(attempts,new SocialSignupAccounts(jdbc,manager),tokens,sessions,clock,Duration.ofSeconds(83));
        try {
            var pending=(OAuthLoginService.OAuthPending)service.continueLogin(ticket,"WEB",a.cookie().split("=",2)[1],signup);
            assertThat(redis.keys("auth:session:*")).isEmpty();
            var result=signup.complete(pending.signupTicket(),"WEB",a.cookie().split("=",2)[1],"새 가입자",
                    new SocialSignupService.Consent(true,false,false,false));
            assertThat(result.account().accountId()).isNotEqualTo(account);assertThat(result.account().email()).isNull();
            assertThat(tokenCalls.get()).isEqualTo(1);assertThat(profileCalls.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id=?",Long.class,account)).isEqualTo(4);
        } finally {
            var ids=jdbc.queryForList("SELECT account_id FROM auth_identity WHERE provider='GOOGLE' AND subject=?",UUID.class,subject);
            jdbc.update("DELETE FROM auth_identity WHERE provider='GOOGLE' AND subject=?",subject);
            for(var id:ids)jdbc.update("DELETE FROM account WHERE id=?",id);
        }
    }
    @Test void disabledOrIncompleteAccountNeverGetsSession() throws Exception {
        for (String update:List.of("status='DISABLED'","status='UNKNOWN'","status='ACTIVE',signup_completed=false")) {
            jdbc.update("UPDATE account SET "+update+" WHERE id=?",account);
            var a=start(SocialProvider.KAKAO); String ticket=ticket(callback(SocialProvider.KAKAO,a,a.cookie()));
            error(exchange(ticket,a.cookie()),401,"UNAUTHENTICATED");
        }
        assertThat(redis.keys("auth:session:*")).isEmpty();
    }
    @Test void concurrentCallbackAndExchangeHaveOneWinner() throws Exception {
        var a=start(SocialProvider.GOOGLE);
        var first=CompletableFuture.supplyAsync(()->uncheckedCallback(a));
        var second=CompletableFuture.supplyAsync(()->uncheckedCallback(a));
        var callbacks=List.of(first.join(),second.join());
        assertThat(callbacks.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(303,400);
        assertThat(tokenCalls.get()).isEqualTo(1);
        String ticket=ticket(callbacks.stream().filter(r->r.statusCode()==303).findFirst().orElseThrow());
        var x=CompletableFuture.supplyAsync(()->uncheckedExchange(ticket,a.cookie()));
        var y=CompletableFuture.supplyAsync(()->uncheckedExchange(ticket,a.cookie()));
        assertThat(List.of(x.join().statusCode(),y.join().statusCode())).containsExactlyInAnyOrder(200,401);
        assertThat(redis.keys("auth:session:*")).hasSize(1);
    }
    @Test void csrfDuplicateCookiesPurposesRoutesAndSignupAreRejected() throws Exception {
        var a=start(SocialProvider.GOOGLE);
        error(send(get(callbackPath(SocialProvider.GOOGLE,a)).header("Cookie",a.cookie()+"; "+a.cookie())),400,"DUPLICATE_AUTH_COOKIE");
        error(send(get(callbackPath(SocialProvider.GOOGLE,a)).header("Cookie",OAuthWebController.COOKIE+"=forged")),400,"INVALID_REQUEST");
        error(send(get("/api/v1/auth/oauth/GOOGLE/authorize?purpose=LOGIN&returnRoute=HOME")
                .header("X-SceneCrew-Client","WEB").header("Origin","https://evil.example.invalid").header("X-SceneCrew-CSRF","1")),403,"CSRF_REJECTED");
        error(send(web(get("/api/v1/auth/oauth/GOOGLE/authorize?purpose=LINK&returnRoute=HOME"))),400,"INVALID_REQUEST");
        error(send(web(get("/api/v1/auth/oauth/GOOGLE/authorize?purpose=LOGIN&returnRoute=https://evil.example.invalid"))),400,"INVALID_REQUEST");
        error(send(web(get("/api/v1/auth/oauth/GOOGLE/authorize?purpose=LOGIN&returnRoute=INVITE"))),400,"INVALID_REQUEST");
        String ticket=ticket(callback(SocialProvider.GOOGLE,a,a.cookie()));
        error(send(post("/api/v1/auth/oauth/exchange","{\"ticket\":\""+ticket+"\",\"action\":\"LOGIN\"}")
                .header("Cookie",a.cookie()).header("X-SceneCrew-Client","WEB").header("Origin","https://web.example.invalid")),403,"CSRF_REJECTED");
        error(send(web(post("/api/v1/auth/oauth/exchange","{\"ticket\":\""+ticket+"\",\"action\":\"COMPLETE_SIGNUP\"}"))
                .header("Cookie",a.cookie())),400,"INVALID_REQUEST");
        assertThat(redis.keys("auth:session:*")).isEmpty();
    }
    @Test void deniedCallbackIsBoundAndReturnsOnlyFixedErrorOnce() throws Exception {
        var a=start(SocialProvider.NAVER);
        var r=send(get("/api/v1/auth/oauth/NAVER/callback?state="+a.state()+"&error=access_denied&error_description=untrusted")
                .header("Cookie",a.cookie()));
        assertThat(r.statusCode()).isEqualTo(303);
        assertThat(r.headers().firstValue("Location")).hasValue("https://web.example.invalid/login/oauth?error=access_denied");
        error(callback(SocialProvider.NAVER,a,a.cookie()),400,"INVALID_REQUEST");
        assertThat(tokenCalls.get()).isZero(); assertThat(redis.keys("auth:oauth:ticket:*")).isEmpty();
    }
    @Test void sameOriginBrowserGetWithoutOriginRequiresApprovedRefererAndFetchMetadata() throws Exception {
        String path="/api/v1/auth/oauth/GOOGLE/authorize?purpose=LOGIN&returnRoute=HOME";
        var valid=get(path).header("X-SceneCrew-Client","WEB").header("X-SceneCrew-CSRF","1")
                .header("Sec-Fetch-Site","same-origin").header("Referer","https://web.example.invalid/login");
        assertThat(send(valid).statusCode()).isEqualTo(200);
        for (String site:List.of("cross-site","same-site","none"))
            error(send(get(path).header("X-SceneCrew-Client","WEB").header("X-SceneCrew-CSRF","1")
                    .header("Sec-Fetch-Site",site).header("Referer","https://web.example.invalid/login")),403,"CSRF_REJECTED");
        error(send(get(path).header("X-SceneCrew-Client","WEB").header("X-SceneCrew-CSRF","1")
                .header("Sec-Fetch-Site","same-origin").header("Referer","https://evil.example.invalid/login")),403,"CSRF_REJECTED");
        error(send(get(path).header("X-SceneCrew-Client","WEB").header("X-SceneCrew-CSRF","1")
                .header("Sec-Fetch-Site","same-origin").header("Origin","https://evil.example.invalid")
                .header("Referer","https://web.example.invalid/login")),403,"CSRF_REJECTED");
    }
    @Test void changingProviderOfIssuingIdentityRevokesSocialSession() throws Exception {
        var a=start(SocialProvider.GOOGLE); var r=exchange(ticket(callback(SocialProvider.GOOGLE,a,a.cookie())),a.cookie());
        assertThat(r.statusCode()).isEqualTo(200);
        String cookie=r.headers().allValues("Set-Cookie").stream().filter(c->c.startsWith("__Secure-sc-access="))
                .map(c->c.split(";",2)[0]).findFirst().orElseThrow();
        assertThat(send(get("/api/v1/me").header("Cookie",cookie)).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM auth_identity WHERE account_id=? AND provider='KAKAO'",account);
        jdbc.update("UPDATE auth_identity SET provider='KAKAO' WHERE account_id=? AND provider='GOOGLE'",account);
        error(send(get("/api/v1/me").header("Cookie",cookie)),401,"UNAUTHENTICATED");
    }
    @Test void providerOutageMalformedAndOversizedResponsesFailClosed() throws Exception {
        tokenStatus=503;
        var a=start(SocialProvider.GOOGLE); error(callback(SocialProvider.GOOGLE,a,a.cookie()),503,"SERVICE_UNAVAILABLE");
        tokenStatus=200;
        for (String body:List.of("not-json","x".repeat(65537))) {
            tokenOverride=body; var b=start(SocialProvider.GOOGLE);
            error(callback(SocialProvider.GOOGLE,b,b.cookie()),503,"SERVICE_UNAVAILABLE");
        }
        tokenOverride="{\"access_token\":\"\",\"token_type\":\"bearer\"}";
        var c=start(SocialProvider.GOOGLE); error(callback(SocialProvider.GOOGLE,c,c.cookie()),401,"UNAUTHENTICATED");
        assertThat(redis.keys("auth:oauth:ticket:*")).isEmpty(); assertThat(redis.keys("auth:session:*")).isEmpty();
    }
    @Test void malformedProviderAccessTokenNeverReachesProfileOrIssuesTicket() throws Exception {
        var a=start(SocialProvider.GOOGLE);
        tokenOverride="{\"access_token\":\"synthetic\\r\\nvalue\",\"token_type\":\"bearer\"}";
        error(callback(SocialProvider.GOOGLE,a,a.cookie()),401,"UNAUTHENTICATED");
        error(callback(SocialProvider.GOOGLE,a,a.cookie()),400,"INVALID_REQUEST");
        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(profileCalls.get()).isZero();
        assertThat(redis.keys("auth:oauth:ticket:*")).isEmpty();
        assertThat(redis.keys("auth:session:*")).isEmpty();
    }
    @Test void ticketExpiringBetweenAccountCheckAndCommitCannotCreateSession() throws Exception {
        var a=start(SocialProvider.GOOGLE); String ticket=ticket(callback(SocialProvider.GOOGLE,a,a.cookie()));
        org.mockito.Mockito.doAnswer(invocation->{
            var result=invocation.callRealMethod();
            redis.delete(RedisOAuthAttempts.key("ticket",ticket));
            return result;
        }).when(accounts).linked(SocialProvider.GOOGLE,subjects.get(SocialProvider.GOOGLE));
        error(exchange(ticket,a.cookie()),401,"UNAUTHENTICATED"); assertThat(redis.keys("auth:session:*")).isEmpty();
    }
    @Test void androidSharesExistingRoutesWithoutSeparateAdapterBean() {
        assertThat(context.getBeanNamesForType(OAuthAndroidContinuation.class)).isEmpty();
        assertThat(mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()))
                .noneMatch(path -> path.startsWith("/api/v1/auth/oauth/android"));
    }
    @ParameterizedTest @EnumSource(SocialProvider.class)
    void androidPublicExchangeReusesSessionRefreshAndLogout(SocialProvider provider) throws Exception {
        var attempt=androidStart(provider);
        String ticket=androidTicket(provider,attempt);
        error(send(androidExchange(ticket,attempts.secret())),401,"UNAUTHENTICATED");
        error(exchange(ticket,start(provider).cookie()),401,"UNAUTHENTICATED");

        var response=send(androidExchange(ticket,attempt.proof()));
        var result=androidLoginResult(response);
        assertThat(result.get("account").get("id").asString()).isEqualTo(account.toString());
        String access=result.get("accessToken").asString();
        assertThat(send(android(get("/api/v1/me")).header("Authorization","Bearer "+access)).statusCode())
                .isEqualTo(200);
        error(send(androidExchange(ticket,attempt.proof())),401,"UNAUTHENTICATED");

        var refresh=send(android(post("/api/v1/auth/refresh",refreshBody(result)))
                .header("X-SceneCrew-Refresh-Attempt",attempts.secret().substring(0,22)));
        assertThat(refresh.statusCode()).as(refresh.body()).isEqualTo(200);
        assertThat(refresh.headers().allValues("Set-Cookie")).isEmpty();
        var rotated=json.readTree(refresh.body());
        String nextAccess=rotated.get("accessToken").asString();
        assertThat(nextAccess).isNotEqualTo(access);
        assertThat(send(android(get("/api/v1/me")).header("Authorization","Bearer "+nextAccess)).statusCode())
                .isEqualTo(200);
        var logout=send(android(post("/api/v1/auth/logout",refreshBody(rotated)))
                .header("X-SceneCrew-Logout-Attempt",attempts.secret().substring(0,22)));
        assertThat(logout.statusCode()).as(logout.body()).isEqualTo(204);
        error(send(android(get("/api/v1/me")).header("Authorization","Bearer "+nextAccess)),401,"UNAUTHENTICATED");
    }
    @ParameterizedTest @EnumSource(SocialProvider.class)
    void androidPublicSignupThenProtectedHttpAndNextLogin(SocialProvider provider) throws Exception {
        String subject=provider==SocialProvider.KAKAO?"987654321":UUID.randomUUID().toString();
        subjects.put(provider,subject);
        try {
            var attempt=androidStart(provider);
            String ticket=androidTicket(provider,attempt);
            var response=send(androidExchange(ticket,attempt.proof()));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            var pending=json.readTree(response.body());
            assertThat(pending.get("signupCompleted").asBoolean()).isFalse();
            assertThat(pending.get("provider").asString()).isEqualTo(provider.name());
            assertThat(response.body()).doesNotContain("accessToken","refreshToken","\"account\"");
            assertThat(redis.keys("auth:session:*")).isEmpty();
            String signupTicket=pending.get("signupTicket").asString();
            assertThat(redis.getExpire(RedisOAuthAttempts.key("signup",signupTicket))).isBetween(290L,300L);
            error(send(androidExchange(ticket,attempt.proof())),401,"UNAUTHENTICATED");
            error(send(androidSignup(signupTicket,attempts.secret(),"Android signup")),401,"UNAUTHENTICATED");
            error(send(androidSignup(signupTicket,attempt.proof()," ")),400,"INVALID_REQUEST");
            var result=androidLoginResult(send(androidSignup(signupTicket,attempt.proof(),"Android signup")));
            String accountId=result.get("account").get("id").asString();
            var me=send(android(get("/api/v1/me")).header("Authorization","Bearer "+result.get("accessToken").asString()));
            assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
            assertThat(json.readTree(me.body()).get("id").asString()).isEqualTo(accountId);
            error(send(androidSignup(signupTicket,attempt.proof(),"Android signup")),401,"UNAUTHENTICATED");

            var next=androidStart(provider);
            var linked=androidLoginResult(send(androidExchange(androidTicket(provider,next),next.proof())));
            assertThat(linked.get("account").get("id").asString()).isEqualTo(accountId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE provider=? AND subject=?",
                    Long.class,provider.name(),subject)).isEqualTo(1);
        } finally {
            deleteSignupFixture(provider,subject);
        }
    }
    @Test void androidSignupSessionFailureRecoversThroughFreshOAuthWithoutDuplicateAccount() throws Exception {
        var provider=SocialProvider.GOOGLE;
        String subject=UUID.randomUUID().toString();
        subjects.put(provider,subject);
        try {
            var attempt=androidStart(provider);
            var pending=send(androidExchange(androidTicket(provider,attempt),attempt.proof()));
            assertThat(pending.statusCode()).isEqualTo(200);
            String signupTicket=json.readTree(pending.body()).get("signupTicket").asString();
            org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE))
                    .doCallRealMethod().when(sessions).registerConsumingOAuthTicket(org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("ANDROID"),
                            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString());
            error(send(androidSignup(signupTicket,attempt.proof(),"Android signup")),503,"SERVICE_UNAVAILABLE");
            error(send(androidSignup(signupTicket,attempt.proof(),"Android signup")),401,"UNAUTHENTICATED");
            assertThat(redis.keys("auth:session:*")).isEmpty();
            UUID savedAccount=jdbc.queryForObject("SELECT account_id FROM auth_identity WHERE provider=? AND subject=?",
                    UUID.class,provider.name(),subject);

            var next=androidStart(provider);
            var recovered=androidLoginResult(send(androidExchange(androidTicket(provider,next),next.proof())));
            assertThat(recovered.get("account").get("id").asString()).isEqualTo(savedAccount.toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE provider=? AND subject=?",
                    Long.class,provider.name(),subject)).isEqualTo(1);
        } finally {
            deleteSignupFixture(provider,subject);
        }
    }
    private void deleteSignupFixture(SocialProvider provider,String subject) {
        var ids=jdbc.queryForList("SELECT account_id FROM auth_identity WHERE provider=? AND subject=?",
                UUID.class,provider.name(),subject);
        jdbc.update("DELETE FROM auth_identity WHERE provider=? AND subject=?",provider.name(),subject);
        for (var id:ids) jdbc.update("DELETE FROM account WHERE id=?",id);
    }
    @Test void androidNewVerifierCannotRedeemPreviousAttemptTicket() throws Exception {
        var provider=SocialProvider.GOOGLE;
        var previous=androidStart(provider);
        var latest=androidStart(provider);
        String previousTicket=androidTicket(provider,previous);
        error(send(androidExchange(previousTicket,latest.proof())),401,"UNAUTHENTICATED");
        assertThat(redis.keys("auth:session:*")).isEmpty();
        androidLoginResult(send(androidExchange(androidTicket(provider,latest),latest.proof())));
        // Binding is per attempt. An older proof still works for its own unconsumed ticket.
        var ignoredLogin=androidLoginResult(send(androidExchange(previousTicket,previous.proof())));
        var sessionId=UUID.fromString(ignoredLogin.get("refreshToken").asString().split("\\.",2)[0]);
        long sessionTtl=Duration.ofDays(14).toSeconds();
        assertThat(redis.getExpire(RedisLoginSessions.key(sessionId))).isBetween(sessionTtl-10,sessionTtl);
    }
    @Test void androidCallbackValidatesStateProviderAndCancellation() throws Exception {
        var attempt=androidStart(SocialProvider.GOOGLE);
        var path="/api/v1/auth/oauth/GOOGLE/callback?state="+attempt.state();
        error(send(get(path.replace("GOOGLE","KAKAO")+"&code="+attempt.state())),400,"INVALID_REQUEST");
        error(send(get(path+"&code="+attempt.state()+"&error=access_denied")),400,"INVALID_REQUEST");
        var denied=send(get(path+"&error=access_denied&error_description=ignored"));
        assertThat(denied.statusCode()).isEqualTo(303);
        assertThat(denied.headers().firstValue("Location")).hasValue("https://android.example.invalid/app/oauth?error=access_denied");
        assertThat(denied.headers().allValues("Set-Cookie")).isEmpty();
        error(send(get(path+"&error=access_denied")),400,"INVALID_REQUEST");
        var unknown=androidStart(SocialProvider.GOOGLE);
        var rejected=send(get("/api/v1/auth/oauth/GOOGLE/callback?state="+unknown.state()+"&error=unexpected"));
        error(rejected,400,"INVALID_REQUEST");
        assertThat(rejected.headers().firstValue("Location")).isEmpty();
        assertThat(tokenCalls.get()).isZero();
        assertThat(redis.keys("auth:oauth:ticket:*")).isEmpty();
    }
    @Test void androidAuthorizeRejectsMissingOriginAndInvalidChallengeBeforeState() throws Exception {
        String path="/api/v1/auth/oauth/GOOGLE/authorize?purpose=LOGIN&returnRoute=HOME"
                +"&codeChallenge="+HttpOAuthProviderClient.challenge(attempts.secret());
        error(send(get(path+"&codeChallengeMethod=S256").header("X-SceneCrew-Client","ANDROID")),403,"AUTH_CHANNEL_MISMATCH");
        error(send(android(get(path+"&codeChallengeMethod=plain"))),400,"INVALID_REQUEST");
        error(send(android(get(path+"&codeChallengeMethod=S256&codeChallengeMethod=S256"))),400,"INVALID_REQUEST");
        assertThat(redis.keys("auth:oauth:android-state:*")).isEmpty();
    }
    @Test void expiredAndroidStateAndTicketAreRejected() throws Exception {
        var expired=androidStart(SocialProvider.GOOGLE);
        expireOAuthKey("android-state",expired.state());
        error(send(get("/api/v1/auth/oauth/GOOGLE/callback?state="+expired.state()+"&code="+expired.state())),
                400,"INVALID_REQUEST");
        var attempt=androidStart(SocialProvider.GOOGLE);
        String ticket=androidTicket(SocialProvider.GOOGLE,attempt);
        expireOAuthKey("ticket",ticket);
        error(send(androidExchange(ticket,attempt.proof())),401,"UNAUTHENTICATED");
        assertThat(redis.keys("auth:session:*")).isEmpty();
    }
    void expireOAuthKey(String kind,String value) {
        String key=RedisOAuthAttempts.key(kind,value);
        redis.expire(key,Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->!Boolean.TRUE.equals(redis.hasKey(key)));
    }
    AndroidAttempt androidStart(SocialProvider provider) throws Exception {
        String proof=attempts.secret(), challenge=HttpOAuthProviderClient.challenge(proof);
        var response=send(android(get("/api/v1/auth/oauth/"+provider+"/authorize?purpose=LOGIN&returnRoute=HOME"
                +"&codeChallenge="+challenge+"&codeChallengeMethod=S256")));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        var payload=json.readTree(response.body());
        assertThat(payload.propertyNames()).containsExactly("authorizationUrl");
        var query=params(URI.create(payload.get("authorizationUrl").asString()).getRawQuery());
        assertThat(query.get("code_challenge_method")).isEqualTo("S256");
        assertThat(query.get("code_challenge")).hasSize(43).isNotEqualTo(challenge);
        assertThat(redis.getExpire(RedisOAuthAttempts.key("android-state",query.get("state")))).isBetween(290L,300L);
        var saved=attempts.read(OAuthLoginService.ANDROID_STATE,query.get("state"),ErrorCode.INVALID_REQUEST);
        assertThat(Duration.between(clock.instant(),saved.attempt().expiresAt()))
                .isBetween(Duration.ofSeconds(290),Duration.ofSeconds(300));
        challenges.put(query.get("state"),query.get("code_challenge"));
        return new AndroidAttempt(query.get("state"),proof);
    }
    String androidTicket(SocialProvider provider,AndroidAttempt attempt) throws Exception {
        String path="/api/v1/auth/oauth/"+provider+"/callback?state="+attempt.state()+"&code="+attempt.state();
        var response=send(get(path));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(303);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        var location=URI.create(response.headers().firstValue("Location").orElseThrow());
        assertThat(location.getPath()).isEqualTo("/app/oauth");
        assertThat(location.getHost()).isEqualTo("android.example.invalid");
        var query=params(location.getRawQuery());
        assertThat(query).containsOnlyKeys("ticket");
        String ticket=query.get("ticket");
        assertThat(redis.getExpire(RedisOAuthAttempts.key("ticket",ticket))).isBetween(50L,60L);
        error(send(get(path)),400,"INVALID_REQUEST");
        return ticket;
    }
    HttpRequest.Builder androidExchange(String ticket,String proof) {
        return android(post("/api/v1/auth/oauth/exchange",
                json.writeValueAsString(Map.of("action","LOGIN","ticket",ticket,"codeVerifier",proof))));
    }
    HttpRequest.Builder androidSignup(String ticket,String proof,String name) {
        return android(post("/api/v1/auth/oauth/exchange",json.writeValueAsString(Map.of(
                "action","COMPLETE_SIGNUP","ticket",ticket,"codeVerifier",proof,"displayName",name,
                "consent",Map.of("service",true,"voice",false,"push",false,"externalAi",false)))));
    }
    tools.jackson.databind.JsonNode androidLoginResult(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        var result=json.readTree(response.body());
        assertThat(result.propertyNames()).containsExactlyInAnyOrder(
                "account","channel","accessToken","refreshToken","accessExpiresAt","refreshExpiresAt");
        assertThat(result.get("channel").asString()).isEqualTo("ANDROID");
        return result;
    }
    String refreshBody(tools.jackson.databind.JsonNode body) {
        return json.writeValueAsString(Map.of("refreshToken",body.get("refreshToken").asString()));
    }
    record AndroidAttempt(String state,String proof) {
        @Override public String toString(){return "AndroidAttempt[redacted]";}
    }
    HttpRequest.Builder android(HttpRequest.Builder request) {
        return request.header("X-SceneCrew-Client","ANDROID").header("Origin","https://android.example.invalid");
    }
    @Test void failedOAuthDoesNotDeleteExistingHealthySession() throws Exception {
        var a=start(SocialProvider.GOOGLE); var result=exchange(ticket(callback(SocialProvider.GOOGLE,a,a.cookie())),a.cookie());
        assertThat(result.statusCode()).isEqualTo(200);
        var keys=new HashSet<>(redis.keys("auth:session:*"));
        var b=start(SocialProvider.KAKAO); tokenFailure=true;
        error(callback(SocialProvider.KAKAO,b,b.cookie()),401,"UNAUTHENTICATED");
        assertThat(redis.keys("auth:session:*")).isEqualTo(keys);
    }
    // #227 WEB 연결: 014 비밀번호 재확인 → 015 시작 → 010 callback 완료 → 같은 소셜 계정으로 로그인
    @Autowired PasswordEncoder passwords;
    record Linker(UUID account,String session) { }
    Linker linker() throws Exception {
        var id=UUID.randomUUID();
        jdbc.update("INSERT INTO account VALUES (?, 'linker', 'ACTIVE', true, 1, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",UUID.randomUUID(),id,id.toString(),
                passwords.encode("SyntheticPass8!"),"linker-"+id+"@example.invalid");
        var r=send(web(post("/api/v1/auth/login","{\"email\":\"linker-"+id+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return new Linker(id,setCookies(r,"__Secure-sc-access"));
    }
    void dropLinker(Linker l) {
        jdbc.update("DELETE FROM auth_identity WHERE account_id=?",l.account()); jdbc.update("DELETE FROM account WHERE id=?",l.account());
    }
    static String setCookies(HttpResponse<String> r,String... names) {
        return r.headers().allValues("Set-Cookie").stream().map(c->c.split(";",2)[0])
                .filter(c->Arrays.stream(names).anyMatch(n->c.startsWith(n+"="))).collect(java.util.stream.Collectors.joining("; "));
    }
    HttpResponse<String> reauth(Linker l,String password,String provider) throws Exception {
        return send(web(post("/api/v1/me/reauthenticate","{\"password\":\""+password+"\",\"operation\":\"LINK_IDENTITY\",\"provider\":\""+provider+"\"}"))
                .header("Cookie",l.session()));
    }
    HttpResponse<String> linkStart(String cookies,SocialProvider provider) throws Exception {
        return send(web(post("/api/v1/me/identities/"+provider,"{}")).header("Cookie",cookies));
    }
    Attempt linkAttempt(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var query=params(URI.create(json.readTree(r.body()).get("authorizationUrl").asString()).getRawQuery());
        challenges.put(query.get("state"),query.get("code_challenge"));
        return new Attempt(query.get("state"),setCookies(r,OAuthWebController.COOKIE));
    }
    @Test void webLinkRequiresRecentPasswordThenLetsSocialLoginReachThatAccount() throws Exception {
        var l=linker();
        try {
            String subject=UUID.randomUUID().toString(); subjects.put(SocialProvider.GOOGLE,subject);
            error(linkStart(l.session(),SocialProvider.GOOGLE),403,"REAUTHENTICATION_REQUIRED");
            var proof=reauth(l,"SyntheticPass8!","GOOGLE");
            assertThat(proof.statusCode()).as(proof.body()).isEqualTo(200);
            assertThat(proof.body()).contains("expiresAt").doesNotContain("SyntheticPass8!");
            assertThat(proof.headers().firstValue("Set-Cookie").orElseThrow())
                    .contains("__Secure-sc-reauth=","Path=/api/v1","Max-Age=300","Secure","HttpOnly","SameSite=Lax");
            String cookies=l.session()+"; "+setCookies(proof,"__Secure-sc-reauth");
            var a=linkAttempt(linkStart(cookies,SocialProvider.GOOGLE));
            assertThat(redis.getExpire(RedisOAuthAttempts.key("link-state",a.state())))
                    .isBetween(WEB_STATE_TTL_SECONDS-10,WEB_STATE_TTL_SECONDS);
            var done=send(get(callbackPath(SocialProvider.GOOGLE,a)).header("Cookie",cookies+"; "+a.cookie()));
            assertThat(done.statusCode()).isEqualTo(303);
            assertThat(done.headers().firstValue("Location")).hasValue("https://web.example.invalid/account/profile?link=GOOGLE&result=linked");
            assertThat(done.headers().allValues("Set-Cookie")).anySatisfy(c->assertThat(c).startsWith("__Secure-sc-reauth=;").contains("Max-Age=0"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id=? AND provider='GOOGLE' AND subject=?",
                    Long.class,l.account(),subject)).isEqualTo(1);
            // 성공한 재확인 증명은 다시 쓸 수 없다.
            error(linkStart(cookies,SocialProvider.KAKAO),403,"REAUTHENTICATION_REQUIRED");
            assertThat(redis.keys("auth:reauth:*")).isEmpty();
            // 이제 같은 Google 계정으로 소셜 로그인이 이 계정에 도달한다.
            var login=start(SocialProvider.GOOGLE);
            var result=exchange(ticket(callback(SocialProvider.GOOGLE,login,login.cookie())),login.cookie());
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(json.readTree(result.body()).get("account").get("id").asString()).isEqualTo(l.account().toString());
        } finally { dropLinker(l); }
    }
    @Test void reauthenticationRejectsWrongPasswordCsrfAndAnotherProvider() throws Exception {
        var l=linker();
        try {
            error(reauth(l,"WrongPass8!","GOOGLE"),401,"INVALID_CREDENTIALS");
            assertThat(redis.keys("auth:reauth:*")).isEmpty();
            error(send(post("/api/v1/me/reauthenticate","{\"password\":\"SyntheticPass8!\",\"operation\":\"LINK_IDENTITY\",\"provider\":\"GOOGLE\"}")
                    .header("Cookie",l.session()).header("Origin","https://web.example.invalid")),403,"CSRF_REJECTED");
            error(reauth(l,"SyntheticPass8!","LOCAL"),400,"INVALID_REQUEST");
            error(send(web(post("/api/v1/me/reauthenticate","{\"password\":\"SyntheticPass8!\",\"operation\":\"DELETE_ACCOUNT\",\"provider\":\"GOOGLE\"}"))
                    .header("Cookie",l.session())),400,"INVALID_REQUEST");
            error(send(web(post("/api/v1/me/reauthenticate","{\"password\":\"SyntheticPass8!\",\"operation\":\"LINK_IDENTITY\",\"provider\":\"GOOGLE\"}"))),401,"UNAUTHENTICATED");
            String proof=setCookies(reauth(l,"SyntheticPass8!","KAKAO"),"__Secure-sc-reauth");
            String cookies=l.session()+"; "+proof;
            error(linkStart(cookies,SocialProvider.GOOGLE),403,"REAUTHENTICATION_REQUIRED");
            error(send(web(post("/api/v1/me/identities/LOCAL","{}")).header("Cookie",cookies)),400,"INVALID_REQUEST");
            error(send(web(post("/api/v1/me/identities/KAKAO","{\"x\":1}")).header("Cookie",cookies)),400,"INVALID_REQUEST");
            // 다른 세션은 이 증명을 쓸 수 없다.
            var other=send(web(post("/api/v1/auth/login","{\"email\":\"linker-"+l.account()+"@example.invalid\",\"password\":\"SyntheticPass8!\"}")));
            error(linkStart(setCookies(other,"__Secure-sc-access")+"; "+proof,SocialProvider.KAKAO),403,"REAUTHENTICATION_REQUIRED");
            assertThat(tokenCalls.get()).isZero();
        } finally { dropLinker(l); }
    }
    @Test void linkCallbackReportsAlreadyLinkedCancelAndForgedBrowserWithoutChangingIdentities() throws Exception {
        var l=linker();
        try {
            String cookies=l.session()+"; "+setCookies(reauth(l,"SyntheticPass8!","NAVER"),"__Secure-sc-reauth");
            // 제공자 계정이 이미 다른 계정(fixture)에 연결돼 있다.
            var a=linkAttempt(linkStart(cookies,SocialProvider.NAVER));
            var taken=send(get(callbackPath(SocialProvider.NAVER,a)).header("Cookie",cookies+"; "+a.cookie()));
            assertThat(taken.headers().firstValue("Location")).hasValue("https://web.example.invalid/account/profile?link=NAVER&result=identity_linked");
            var b=linkAttempt(linkStart(cookies,SocialProvider.NAVER));
            var cancelled=send(get("/api/v1/auth/oauth/NAVER/callback?state="+b.state()+"&error=access_denied")
                    .header("Cookie",cookies+"; "+b.cookie()));
            assertThat(cancelled.headers().firstValue("Location")).hasValue("https://web.example.invalid/account/profile?link=NAVER&result=cancelled");
            assertThat(redis.hasKey(RedisOAuthAttempts.key("link-state",b.state()))).isFalse();
            // 다른 브라우저 증명으로는 완료되지 않는다.
            var c=linkAttempt(linkStart(cookies,SocialProvider.NAVER)); var d=linkAttempt(linkStart(cookies,SocialProvider.NAVER));
            var forged=send(get(callbackPath(SocialProvider.NAVER,c)).header("Cookie",cookies+"; "+d.cookie()));
            assertThat(forged.headers().firstValue("Location")).hasValue("https://web.example.invalid/account/profile?link=NAVER&result=failed");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id=?",Long.class,l.account())).isEqualTo(1);
        } finally { dropLinker(l); }
    }
    // #227 016 해제: 해제용 재확인 → 204 → 그 수단 세션 거절 → 같은 증명 재사용 불가
    HttpResponse<String> unlink(String cookies,SocialProvider provider) throws Exception {
        return send(web(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/me/identities/"+provider))
                .header("Content-Type","application/json").method("DELETE",HttpRequest.BodyPublishers.ofString("{}"))).header("Cookie",cookies));
    }
    HttpResponse<String> reauthFor(Linker l,String operation,String provider) throws Exception {
        return send(web(post("/api/v1/me/reauthenticate","{\"password\":\"SyntheticPass8!\",\"operation\":\""+operation+"\",\"provider\":\""+provider+"\"}"))
                .header("Cookie",l.session()));
    }
    @Test void unlinkDoesNotCommitWhenOneTimeProofCannotBeConsumed() throws Exception {
        var l=linker();
        try {
            jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'GOOGLE', ?, NULL, NULL, false)",
                    UUID.randomUUID(),l.account(),UUID.randomUUID().toString());
            String cookies=l.session()+"; "+setCookies(reauthFor(l,"UNLINK_IDENTITY","GOOGLE"),"__Secure-sc-reauth");
            assertThat(REDIS.execInContainer("redis-cli","ACL","SETUSER","default","-del").getExitCode()).isZero();
            try {
                error(unlink(cookies,SocialProvider.GOOGLE),503,"SERVICE_UNAVAILABLE");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id=? AND provider='GOOGLE'",
                        Long.class,l.account())).isEqualTo(1);
                assertThat(redis.keys("auth:reauth:*")).hasSize(1);
            } finally {
                assertThat(REDIS.execInContainer("redis-cli","ACL","SETUSER","default","+del").getExitCode()).isZero();
            }
            assertThat(unlink(cookies,SocialProvider.GOOGLE).statusCode()).isEqualTo(204);
            assertThat(redis.keys("auth:reauth:*")).isEmpty();
        } finally { dropLinker(l); }
    }
    @Test void webUnlinkNeedsUnlinkReauthenticationAndRevokesThatProvidersSession() throws Exception {
        var l=linker();
        try {
            String subject=UUID.randomUUID().toString(); subjects.put(SocialProvider.GOOGLE,subject);
            jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'GOOGLE', ?, NULL, NULL, false)",UUID.randomUUID(),l.account(),subject);
            // Google로 로그인한 세션
            var login=start(SocialProvider.GOOGLE);
            var google=exchange(ticket(callback(SocialProvider.GOOGLE,login,login.cookie())),login.cookie());
            assertThat(google.statusCode()).isEqualTo(200);
            String googleSession=setCookies(google,"__Secure-sc-access");
            assertThat(send(get("/api/v1/me").header("Cookie",googleSession)).statusCode()).isEqualTo(200);

            error(unlink(l.session(),SocialProvider.GOOGLE),403,"REAUTHENTICATION_REQUIRED");
            // 연결용 증명으로는 해제할 수 없다.
            String linkProof=setCookies(reauthFor(l,"LINK_IDENTITY","GOOGLE"),"__Secure-sc-reauth");
            error(unlink(l.session()+"; "+linkProof,SocialProvider.GOOGLE),403,"REAUTHENTICATION_REQUIRED");
            String cookies=l.session()+"; "+setCookies(reauthFor(l,"UNLINK_IDENTITY","GOOGLE"),"__Secure-sc-reauth");
            error(send(web(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/me/identities/GOOGLE"))
                    .header("Content-Type","application/json").method("DELETE",HttpRequest.BodyPublishers.ofString("{}")))
                    .setHeader("X-SceneCrew-CSRF","0").header("Cookie",cookies)),403,"CSRF_REJECTED");
            var done=unlink(cookies,SocialProvider.GOOGLE);
            assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
            assertThat(done.headers().allValues("Set-Cookie")).anySatisfy(c->assertThat(c).startsWith("__Secure-sc-reauth=;").contains("Max-Age=0"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id=? AND provider='GOOGLE'",Long.class,l.account())).isZero();
            // 해제한 수단의 세션은 거절되고, 이메일 세션은 그대로다.
            error(send(get("/api/v1/me").header("Cookie",googleSession)),401,"UNAUTHENTICATED");
            assertThat(send(get("/api/v1/me").header("Cookie",l.session())).statusCode()).isEqualTo(200);
            // 같은 증명은 다시 쓸 수 없고, 연결되지 않은 제공자는 404다.
            error(unlink(cookies,SocialProvider.GOOGLE),403,"REAUTHENTICATION_REQUIRED");
            String again=l.session()+"; "+setCookies(reauthFor(l,"UNLINK_IDENTITY","KAKAO"),"__Secure-sc-reauth");
            error(unlink(again,SocialProvider.KAKAO),404,"NOT_FOUND");
            error(send(web(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/me/identities/LOCAL"))
                    .header("Content-Type","application/json").method("DELETE",HttpRequest.BodyPublishers.ofString("{}"))).header("Cookie",again)),400,"INVALID_REQUEST");
        } finally { dropLinker(l); }
    }
    HttpResponse<String> uncheckedCallback(Attempt a) { try {return callback(SocialProvider.GOOGLE,a,a.cookie());} catch(Exception e){throw new CompletionException(e);} }
    HttpResponse<String> uncheckedExchange(String t,String c) { try {return exchange(t,c);} catch(Exception e){throw new CompletionException(e);} }
    Attempt start(SocialProvider provider) throws Exception {
        var r=send(web(get("/api/v1/auth/oauth/"+provider+"/authorize?purpose=LOGIN&returnRoute=HOME")));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
        String cookie=r.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("Secure","HttpOnly","SameSite=Lax","Path=/api/v1/auth/oauth",
                "Max-Age="+WEB_STATE_TTL_SECONDS);
        var url=URI.create(json.readTree(r.body()).get("authorizationUrl").asString()); var query=params(url.getRawQuery());
        assertThat(query.get("code_challenge_method")).isEqualTo("S256"); assertThat(query.get("code_challenge")).hasSize(43);
        assertThat(query.get("state")).hasSize(43); assertThat(r.body()).doesNotContain("code_verifier","synthetic-secret");
        challenges.put(query.get("state"),query.get("code_challenge"));
        return new Attempt(query.get("state"),cookie.split(";",2)[0]);
    }
    HttpResponse<String> callback(SocialProvider p,Attempt a,String cookie) throws Exception {
        var r=get(callbackPath(p,a)); if(cookie!=null)r.header("Cookie",cookie); return send(r);
    }
    String callbackPath(SocialProvider p,Attempt a) { return "/api/v1/auth/oauth/"+p+"/callback?state="+a.state()+"&code="+a.state(); }
    String ticket(HttpResponse<String> r) { return params(URI.create(r.headers().firstValue("Location").orElseThrow()).getRawQuery()).get("ticket"); }
    HttpResponse<String> exchange(String ticket,String cookie) throws Exception {
        var r=web(post("/api/v1/auth/oauth/exchange","{\"ticket\":\""+ticket+"\",\"action\":\"LOGIN\"}"));
        if(cookie!=null)r.header("Cookie",cookie); return send(r);
    }
    HttpResponse<String> completeSignup(String ticket, String cookie, boolean serviceConsent) throws Exception {
        String body = """
                {"ticket":"%s","action":"COMPLETE_SIGNUP","displayName":"Social User",
                 "consent":{"service":%s,"voice":false,"push":true,"externalAi":false}}
                """.formatted(ticket, serviceConsent);
        return send(web(post("/api/v1/auth/oauth/exchange", body)).header("Cookie", cookie));
    }

    HttpRequest.Builder web(HttpRequest.Builder r) {return r.header("X-SceneCrew-Client","WEB").header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1");}
    HttpRequest.Builder get(String path) {return HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET();}
    HttpRequest.Builder post(String path,String body) {return HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));}
    HttpResponse<String> send(HttpRequest.Builder r) throws Exception {return browser.send(r.build(),HttpResponse.BodyHandlers.ofString());}
    void error(HttpResponse<String> r,int status,String code) {assertThat(r.statusCode()).as(r.body()).isEqualTo(status);assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo(code);}
    static Map<String,String> params(String query) {
        var result=new HashMap<String,String>();
        for(String part:query.split("&")){var pair=part.split("=",2);result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}return result;
    }
    record Attempt(String state,String cookie) { @Override public String toString(){return "Attempt[redacted]";} }
    @TestConfiguration(proxyBeanMethods=false) static class Fixtures {
        @Bean PasswordEncoder encoder(){return new BCryptPasswordEncoder(4);}
        @Bean @Primary OAuthLoginService mockProviderOAuth(RedisOAuthAttempts attempts,OAuthAccountReader accounts,
                LoginTokenIssuer tokens,RedisLoginSessions sessions,Clock clock,org.springframework.core.env.Environment env) {
            return new OAuthLoginService(clients(),OAuthWebConfiguration.loginReturns(env),
                    attempts,accounts,tokens,sessions,clock,OAuthWebConfiguration.stateTtl(env));
        }
        static List<OAuthProviderClient> clients() {
            var clients=new ArrayList<OAuthProviderClient>();
            for(var p:SocialProvider.values()) {
                String base="http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/"+p;
                clients.add(new HttpOAuthProviderClient(p,new OAuthClientSettings("synthetic-client","synthetic-secret",
                        URI.create("https://api.example.invalid/api/v1/auth/oauth/"+p+"/callback"),"openid"),
                        URI.create(base+"/authorize"),URI.create(base+"/token"),URI.create(base+"/profile")));
            }
            return clients;
        }
    }
}

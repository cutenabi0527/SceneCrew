package com.ssafy.scenecrew.account;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.*;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.dto.Credentials;
import com.ssafy.scenecrew.account.dto.RegisterRequest;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.infra.RedisPasswordResets;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PasswordResetIntegrationTest.Config.class)
class PasswordResetIntegrationTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final byte[] SECRET=new byte[32];
    static { new java.security.SecureRandom().nextBytes(SECRET); }
    static final String OLD="SyntheticPass8!", NEW="ChangedPass9!";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start(); r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.issuer",()->"reset-test"); r.add("scenecrew.auth.audience",()->"reset-test");
        r.add("scenecrew.auth.web-origins",()->"https://web.example.invalid");
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PasswordResetService service;
    @Autowired EmailLoginService login;
    @Autowired SessionAuthenticator authenticator;
    @Autowired PasswordEncoder encoder;
    @Autowired EmailRegistrationService registrations;
    @Autowired EmailVerificationService verification;
    @Autowired FlushFailure failureFilter;
    @MockitoSpyBean RedisPasswordResets resets;
    @MockitoSpyBean PasswordResetAccountWriter writer;
    @MockitoSpyBean(name="transactionManager") PlatformTransactionManager manager;
    @MockitoSpyBean com.ssafy.scenecrew.account.repository.AuthIdentityRepository identities;
    @MockitoBean PasswordResetDelivery mail;
    @MockitoBean RegistrationDelivery registrationMail;
    final Map<String,PasswordResetDelivery.Message> messages=new ConcurrentHashMap<>();
    final Map<UUID,RegistrationDelivery.Message> registrationMessages=new ConcurrentHashMap<>();
    final List<UUID> fixtures=new ArrayList<>();
    final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    UUID account,identity; String email;
    @BeforeEach void setup() {
        account=UUID.randomUUID(); identity=UUID.randomUUID(); email=account+"@example.invalid"; fixtures.add(account);
        jdbc.update("INSERT INTO account VALUES (?,?,'ACTIVE',true,3,'{}'::jsonb,now())",account,"fixture");
        jdbc.update("INSERT INTO auth_identity VALUES (?,?,'LOCAL',?,?,?,true)",identity,account,
                account.toString(),encoder.encode(OLD),email);
        when(mail.accept(any())).thenAnswer(c->{ var m=(PasswordResetDelivery.Message)c.getArgument(0);
            messages.put(m.email(),m); return RegistrationDelivery.Outcome.ACCEPTED; });
        when(registrationMail.accept(any())).thenAnswer(c->{ var m=(RegistrationDelivery.Message)c.getArgument(0);
            registrationMessages.put(m.requestId(),m); return RegistrationDelivery.Outcome.ACCEPTED; });
        failureFilter.armed.set(false); failureFilter.failures.set(0);
    }
    @AfterEach void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS reset_test_failure ON auth_identity");
        jdbc.execute("DROP FUNCTION IF EXISTS reset_test_failure()");
        for(UUID id:fixtures) { jdbc.update("DELETE FROM auth_identity WHERE account_id=?",id); jdbc.update("DELETE FROM account WHERE id=?",id); }
        fixtures.clear();
    }
    void failure(Runnable action,ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e->assertThat(e.getErrorCode()).isEqualTo(code));
    }
    String proof() { service.request(email); return messages.get(email).recoveryProof(); }
    long version() { return jdbc.queryForObject("SELECT token_version FROM account WHERE id=?",Long.class,account); }
    String hash() { return jdbc.queryForObject("SELECT password_hash FROM auth_identity WHERE id=?",String.class,identity); }
    HttpResponse<String> post(String path,String body) throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/"+path))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
    HttpResponse<String> confirm(String proof,String password) throws Exception {
        return post("password-reset/confirm",json.writeValueAsString(Map.of("recoveryProof",proof,"password",password)));
    }
    void ageBudget(String address) {
        String key=resets.budgetKey(address);
        for(String member:redis.opsForZSet().range(key,0,-1)) redis.opsForZSet().incrementScore(key,member,-61000);
    }
    @Test void existingTestEncoderRemainsTheOnlyEncoderAtCostFour() {
        assertThat(encoder.encode(OLD)).startsWith("$2a$04$");
    }
    @Test void requestConfirmInvalidatesAllExistingAccessButLeavesRedisKeysForExpiry() throws Exception {
        var oldWeb=login.login(email,OLD,"WEB"); var oldAndroid=login.login(email,OLD,"ANDROID");
        var response=post("password-reset/request",json.writeValueAsString(Map.of("email","  "+email.toUpperCase(Locale.ROOT)+"  ")));
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEqualTo(json.writeValueAsString(Map.of("message",PasswordResetService.ACCEPTED)));
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        String proof=messages.get(email).recoveryProof();
        assertThat(redis.opsForValue().get(RedisPasswordResets.key(proof.substring(0,36)))).doesNotContain(proof,OLD,NEW);
        var completed=confirm(proof,NEW);
        assertThat(completed.statusCode()).isEqualTo(204); assertThat(completed.body()).isEmpty();
        assertThat(completed.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(completed.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(version()).isEqualTo(4); assertThat(hash()).isNotEqualTo(NEW); assertThat(encoder.matches(NEW,hash())).isTrue();
        for(var old:List.of(oldWeb,oldAndroid)) {
            assertThat(redis.hasKey(RedisLoginSessions.key(old.tokens().sessionId()))).isTrue();
            failure(()->authenticator.authenticate(old.tokens().accessToken()),ErrorCode.UNAUTHENTICATED);
        }
        failure(()->login.login(email,OLD,"WEB"),ErrorCode.INVALID_CREDENTIALS);
        var current=login.login(email,NEW,"ANDROID");
        assertThat(authenticator.authenticate(current.tokens().accessToken()).tokenVersion()).isEqualTo(4);
        assertThat(confirm(proof,"DifferentPass0!").statusCode()).isEqualTo(409); assertThat(version()).isEqualTo(4);
    }
    @Test void missingDisabledIncompleteUnverifiedAndSocialEmailHaveSameResponseAndBudget() throws Exception {
        String missing=UUID.randomUUID()+"@example.invalid";
        var known=post("password-reset/request",json.writeValueAsString(Map.of("email",email)));
        var unknown=post("password-reset/request",json.writeValueAsString(Map.of("email",missing)));
        assertThat(unknown.statusCode()).isEqualTo(known.statusCode()); assertThat(unknown.body()).isEqualTo(known.body());
        verify(mail,times(1)).accept(any());
        for(String address:List.of(email,missing)) assertThat(post("password-reset/request",
                json.writeValueAsString(Map.of("email",address))).statusCode()).isEqualTo(429);
        for(String state:List.of("DISABLED","INCOMPLETE","UNVERIFIED","SOCIAL")) {
            String address=state.toLowerCase()+UUID.randomUUID()+"@example.invalid";
            jdbc.update("UPDATE auth_identity SET email=?,provider=?,email_verified=? WHERE id=?",address,
                    state.equals("SOCIAL")?"GOOGLE":"LOCAL",!state.equals("UNVERIFIED"),identity);
            jdbc.update("UPDATE account SET status=?,signup_completed=? WHERE id=?",state.equals("DISABLED")?"DISABLED":"ACTIVE",
                    !state.equals("INCOMPLETE"),account);
            var response=post("password-reset/request",json.writeValueAsString(Map.of("email",address)));
            assertThat(response.statusCode()).isEqualTo(202); assertThat(response.body()).isEqualTo(known.body());
        }
        verify(mail,times(1)).accept(any());
    }
    @Test void resendReusesExactProofAndOriginalExpiryAndHasSeparateRegistrationBudget() {
        String first=proof(); var original=messages.get(email); String key=RedisPasswordResets.key(first.substring(0,36));
        long ttl=redis.getExpire(key,TimeUnit.MILLISECONDS); ageBudget(email); service.request(email);
        assertThat(messages.get(email).recoveryProof()).isEqualTo(first);
        assertThat(messages.get(email).expiresAt()).isEqualTo(original.expiresAt());
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        assertThat(redis.opsForZSet().zCard(resets.budgetKey(email))).isEqualTo(2);
        var registration=registrations.register(new RegisterRequest(UUID.randomUUID()+"@example.invalid","SyntheticPass8!","fixture",
                new RegisterRequest.Consent(true,false,false,false)));
        assertThat(registration.requestId()).isNotNull();
    }
    @Test void fiveAttemptsAreAllowedInRollingWindowAndSixthDoesNotExtendExpiry() {
        String first=proof(); var original=messages.get(email);
        for(int n=1;n<5;n++) { ageBudget(email); service.request(email); }
        ageBudget(email); failure(()->service.request(email),ErrorCode.RATE_LIMITED);
        assertThat(messages.get(email).expiresAt()).isEqualTo(original.expiresAt());
        assertThat(messages.get(email).recoveryProof()).isEqualTo(first);
        verify(mail,times(5)).accept(any());
    }
    @Test void wrongExpiredAndMalformedProofCannotChangePasswordAndReadingDoesNotConsume() throws Exception {
        String proof=proof(); String initial=hash();
        resets.read(proof); resets.read(proof);
        String wrong=proof.substring(0,37)+"A".repeat(43);
        assertThat(confirm(wrong,NEW).statusCode()).isEqualTo(401);
        assertThat(post("password-reset/confirm","{\"recoveryProof\":123,\"password\":\"ChangedPass9!\"}").statusCode()).isEqualTo(400);
        for(String password:List.of("short"," "+NEW,NEW+" ","a".repeat(65),"한글Password8!"))
            assertThat(confirm(proof,password).statusCode()).isEqualTo(400);
        assertThat(hash()).isEqualTo(initial); assertThat(version()).isEqualTo(3);
        redis.expire(RedisPasswordResets.key(proof.substring(0,36)),Duration.ofMillis(1)); Thread.sleep(10);
        assertThat(confirm(proof,NEW).statusCode()).isEqualTo(409); assertThat(hash()).isEqualTo(initial);
    }
    @Test void expiryIsRecheckedAtTheDatabaseMutationAfterProofRead() {
        String proof=proof(); var verified=resets.read(proof);
        failure(()->writer.change(verified.target(),java.time.Instant.now().minusSeconds(1),encoder.encode(NEW)),ErrorCode.INVALID_STATE);
        assertThat(version()).isEqualTo(3); assertThat(encoder.matches(OLD,hash())).isTrue();
    }
    @Test void concurrentCompletionOnlyCommitsOnePasswordChange() throws Exception {
        String proof=proof(); var both=new CountDownLatch(2);
        doAnswer(c->{both.countDown(); assertThat(both.await(10,TimeUnit.SECONDS)).isTrue(); return c.callRealMethod();})
                .when(writer).change(any(),any(),anyString());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->confirm(proof,NEW).statusCode());
            var b=pool.submit(()->confirm(proof,"OtherPassword9!").statusCode());
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(204,409);
        }
        assertThat(version()).isEqualTo(4);
    }
    @Test void databaseFailureRollsBackPasswordAndVersionAndPermitsExplicitRetry() {
        String proof=proof(),original=hash();
        jdbc.execute("CREATE FUNCTION reset_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic reset failure'; END $$");
        jdbc.execute("CREATE TRIGGER reset_test_failure BEFORE UPDATE ON auth_identity FOR EACH ROW EXECUTE FUNCTION reset_test_failure()");
        failure(()->service.confirm(proof,NEW),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(version()).isEqualTo(3); assertThat(hash()).isEqualTo(original);
        jdbc.execute("DROP TRIGGER reset_test_failure ON auth_identity");
        service.confirm(proof,NEW); assertThat(version()).isEqualTo(4);
    }
    @Test void lostCommitAcknowledgementNeverChangesPasswordTwice() {
        String proof=proof(); AtomicBoolean armed=new AtomicBoolean(true);
        doAnswer(c->{ c.callRealMethod(); if(armed.compareAndSet(true,false)) throw new TransactionSystemException("synthetic lost commit reply"); return null; })
                .when(manager).commit(any());
        failure(()->service.confirm(proof,NEW),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(version()).isEqualTo(4); assertThat(encoder.matches(NEW,hash())).isTrue();
        failure(()->service.confirm(proof,"OtherPassword9!"),ErrorCode.INVALID_STATE); assertThat(version()).isEqualTo(4);
    }
    @Test void redisReadFailureDoesNotChangePasswordAndRetryIsSafe() {
        String proof=proof(); doThrow(new DataAccessResourceFailureException("synthetic Redis failure")).when(resets).read(proof);
        failure(()->service.confirm(proof,NEW),ErrorCode.SERVICE_UNAVAILABLE); assertThat(version()).isEqualTo(3);
        doCallRealMethod().when(resets).read(proof); service.confirm(proof,NEW); assertThat(version()).isEqualTo(4);
    }
    @Test void recipientFailuresAndUnknownDeliveryKeepSameResponseBudgetAndProof() throws Exception {
        for(var outcome:List.of(RegistrationDelivery.Outcome.DEFINITELY_NOT_SENT,RegistrationDelivery.Outcome.UNKNOWN)) {
            doAnswer(c->{var m=(PasswordResetDelivery.Message)c.getArgument(0);messages.put(m.email(),m);return outcome;}).when(mail).accept(any());
            var response=post("password-reset/request",json.writeValueAsString(Map.of("email",email)));
            assertThat(response.statusCode()).isEqualTo(202); var sent=messages.get(email); ageBudget(email);
            service.request(email); assertThat(messages.get(email)).isEqualTo(sent); ageBudget(email);
        }
        assertThat(redis.opsForZSet().zCard(resets.budgetKey(email))).isEqualTo(4);
    }
    @Test void globalMailMisconfigurationAndRedisReservationFailureAre503ForAllEmails() throws Exception {
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(mail).requireAvailable();
        for(String address:List.of(email,"missing@example.invalid"))
            assertThat(post("password-reset/request",json.writeValueAsString(Map.of("email",address))).statusCode()).isEqualTo(503);
        doNothing().when(mail).requireAvailable();
        doThrow(new DataAccessResourceFailureException("synthetic Redis failure")).when(resets).reserve(anyString(),any());
        for(String address:List.of(email,"missing@example.invalid"))
            assertThat(post("password-reset/request",json.writeValueAsString(Map.of("email",address))).statusCode()).isEqualTo(503);
        assertThat(version()).isEqualTo(3);
    }
    @Test void transportAndBodyValidationDoNotRequireSessionOrIssueCookies() throws Exception {
        assertThat(post("password-reset/request","{\"email\":123}").statusCode()).isEqualTo(400);
        assertThat(post("password-reset/request","{\"email\":\"bad\"}").statusCode()).isEqualTo(400);
        assertThat(post("password-reset/request","{\"email\":\"a@example.invalid\",\"extra\":true}").statusCode()).isEqualTo(400);
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/password-reset/request"))
                    .header("Content-Type","application/json").header("Cookie","__Secure-sc-access=a; __Secure-sc-access=b")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email",email)))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
        }
        verify(mail,never()).accept(any());
    }
    @Test void responseFlushFailureAfterCommitCannotCauseSecondChange() throws Exception {
        String proof=proof(); failureFilter.armed.set(true);
        try { confirm(proof,NEW); } catch(java.io.IOException expected) { }
        assertThat(failureFilter.failures.get()).isEqualTo(1); assertThat(version()).isEqualTo(4);
        assertThat(confirm(proof,"OtherPassword9!").statusCode()).isEqualTo(409); assertThat(version()).isEqualTo(4);
    }
    @Test void normalRegistrationAndVerificationStillIssueWorkingLogin() {
        String address=UUID.randomUUID()+"@example.invalid";
        var receipt=registrations.register(new RegisterRequest(address,OLD,"fixture",new RegisterRequest.Consent(true,false,false,false)));
        var result=verification.verify(receipt.requestId(),registrationMessages.get(receipt.requestId()).code(),new Credentials("ANDROID",null));
        fixtures.add(result.account().accountId());
        assertThat(authenticator.authenticate(result.tokens().accessToken())).isNotNull();
        assertThat(login.login(address,OLD,"ANDROID").account().accountId()).isEqualTo(result.account().accountId());
    }
    @Test void newlyRegisteredAccountCanResetPasswordThroughPublicHttp() throws Exception {
        String address = UUID.randomUUID() + "@example.invalid";
        try (var client = HttpClient.newHttpClient()) {
            var registered = androidPost(client, "register", Map.of(
                    "email", address, "password", OLD, "displayName", "fixture",
                    "consent", Map.of("service", true, "voice", false, "push", false, "externalAi", false)));
            assertThat(registered.statusCode()).isEqualTo(202);
            UUID requestId = UUID.fromString(json.readTree(registered.body()).get("requestId").asString());
            String code = registrationMessages.get(requestId).code();
            assertThat(code).matches("[0-9]{6}");
            assertThat(registered.body()).doesNotContain("\"" + code + "\"", OLD, "accessToken");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE email=?", Integer.class, address))
                    .isZero();

            var verified = androidPost(client, "verify-email", Map.of("requestId", requestId, "code", code));
            assertThat(verified.statusCode()).isEqualTo(201);
            UUID createdAccount = UUID.fromString(json.readTree(verified.body()).get("account").get("id").asString());
            fixtures.add(createdAccount);
            assertThat(jdbc.queryForObject("SELECT account_id FROM auth_identity WHERE email=?", UUID.class, address))
                    .isEqualTo(createdAccount);

            var credentials = Map.of("email", address, "password", OLD);
            var loginResponse = androidPost(client, "login", credentials);
            assertThat(loginResponse.statusCode()).isEqualTo(200);
            String access = json.readTree(loginResponse.body()).get("accessToken").asString();
            assertThat(json.readTree(androidMe(client, access, 200).body()).get("id").asString())
                    .isEqualTo(createdAccount.toString());

            var otherLogin = androidPost(client, "login", Map.of("email", email, "password", OLD));
            assertThat(otherLogin.statusCode()).isEqualTo(200);
            String otherAccess = json.readTree(otherLogin.body()).get("accessToken").asString();
            var reset = post("password-reset/request", json.writeValueAsString(Map.of("email", address)));
            assertThat(reset.statusCode()).isEqualTo(202);
            String proof = messages.get(address).recoveryProof();
            assertThat(reset.body()).doesNotContain(proof, OLD);
            assertThat(confirm(proof, NEW).statusCode()).isEqualTo(204);
            androidMe(client, access, 401);
            androidMe(client, json.readTree(verified.body()).get("accessToken").asString(), 401);
            assertThat(json.readTree(androidMe(client, otherAccess, 200).body()).get("id").asString())
                    .isEqualTo(account.toString());
            assertThat(androidPost(client, "login", credentials).statusCode()).isEqualTo(401);
            var freshLogin = androidPost(client, "login", Map.of("email", address, "password", NEW));
            assertThat(freshLogin.statusCode()).isEqualTo(200);
            assertThat(json.readTree(androidMe(client,
                    json.readTree(freshLogin.body()).get("accessToken").asString(), 200).body())
                    .get("id").asString()).isEqualTo(createdAccount.toString());
        }
    }

    private HttpResponse<String> androidPost(HttpClient client, String path, Map<String, ?> body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/" + path))
                .header("Content-Type", "application/json").header("X-SceneCrew-Client", "ANDROID")
                .header("Origin", "https://android.example.invalid")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> androidMe(HttpClient client, String access, int expectedStatus) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me"))
                .header("Origin", "https://android.example.invalid").header("Authorization", "Bearer " + access)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        return response;
    }
    @Test void everyProofFromEarlierVersionIsInvalidAfterOneCompletion() {
        String first=proof(); ageBudget(email);
        redis.delete(resets.budgetKey(email).replace("auth:reset:send:","auth:reset:current:"));
        String second=proof(); assertThat(second).isNotEqualTo(first);
        service.confirm(first,NEW);
        failure(()->service.confirm(second,"OtherPassword9!"),ErrorCode.INVALID_STATE);
        assertThat(version()).isEqualTo(4);
        ageBudget(email); String next=proof(); service.confirm(next,"OtherPassword9!"); assertThat(version()).isEqualTo(5);
    }
    @Test void loginStartedBeforeResetCannotIssueUsableOldPasswordSession() {
        String proof=proof(); AtomicBoolean armed=new AtomicBoolean(true);
        var snapshot=identities.findLocalCredentials(email);
        doAnswer(c->{
            if(armed.compareAndSet(true,false)) service.confirm(proof,NEW);
            return snapshot;
        }).when(identities).findLocalCredentials(email);
        failure(()->login.login(email,OLD,"ANDROID"),ErrorCode.INVALID_CREDENTIALS);
        assertThat(version()).isEqualTo(4);
        assertThat(encoder.matches(NEW,hash())).isTrue();
    }
    @Test void actualRedisReadDenialReturns503WithoutPasswordMutation() {
        String proof=proof();
        try {
            acl("-get");
            failure(()->service.confirm(proof,NEW),ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(version()).isEqualTo(3); assertThat(encoder.matches(OLD,hash())).isTrue();
        } finally { acl("+get"); }
        service.confirm(proof,NEW); assertThat(version()).isEqualTo(4);
    }
    void acl(String permission) {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c->c.execute("ACL",
                "SETUSER".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                "default".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                permission.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
    static class FlushFailure extends org.springframework.web.filter.OncePerRequestFilter {
        final AtomicBoolean armed=new AtomicBoolean(); final AtomicInteger failures=new AtomicInteger();
        @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest r,jakarta.servlet.http.HttpServletResponse s,
                jakarta.servlet.FilterChain chain) throws jakarta.servlet.ServletException,java.io.IOException {
            if(!r.getRequestURI().equals("/api/v1/auth/password-reset/confirm") || !armed.get()) { chain.doFilter(r,s); return; }
            chain.doFilter(r,s);
            // 204 has no body writer. Fail after controller/DB return, before the container finishes the response.
            if(armed.compareAndSet(true,false)) { failures.incrementAndGet(); throw new java.io.IOException("synthetic response loss"); }

        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Config {
        @Bean PasswordEncoder resetTestEncoder() { return new BCryptPasswordEncoder(4); }
        @Bean FlushFailure resetFlushFailure() { return new FlushFailure(); }
    }
}

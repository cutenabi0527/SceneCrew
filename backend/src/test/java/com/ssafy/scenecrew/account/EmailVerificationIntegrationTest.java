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
import com.ssafy.scenecrew.account.infra.RedisPendingRegistrations;
import com.ssafy.scenecrew.account.infra.RedisVerificationRequests;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL 17 + isolated Redis; fake mail, explicit fault-injection spies at failure boundaries. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(EmailVerificationIntegrationTest.Config.class)
class EmailVerificationIntegrationTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final byte[] SECRET=new byte[32];
    static { new java.security.SecureRandom().nextBytes(SECRET); }
    static final Credentials ANDROID=new Credentials("ANDROID",null);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start(); r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.issuer",()->"verification-test"); r.add("scenecrew.auth.audience",()->"verification-test");
        r.add("scenecrew.auth.web-origins",()->"https://web.example.invalid");
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired StringRedisTemplate redis;
    @Autowired EmailRegistrationService registrations;
    @Autowired EmailVerificationService verification;
    @Autowired RedisPendingRegistrations pending;
    @Autowired RegistrationCodes codes;
    @Autowired SessionAuthenticator authenticator;
    @Autowired FailingResponseFilter failingResponse;
    @MockitoSpyBean RegistrationAccountWriter writer;
    @MockitoSpyBean RedisVerificationRequests requests;
    @MockitoSpyBean RegistrationSessionIssuer sessions;
    @MockitoBean RegistrationDelivery delivery;
    final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    final Map<UUID,RegistrationDelivery.Message> mails=new ConcurrentHashMap<>();
    final Set<String> emails=new HashSet<>();
    String email;
    @BeforeEach void setup() {
        email=UUID.randomUUID()+"@example.invalid";
        failingResponse.armed.set(false); failingResponse.failures.set(0);
        when(delivery.accept(any())).thenAnswer(call -> {
            var mail=(RegistrationDelivery.Message)call.getArgument(0); mails.put(mail.requestId(),mail);
            return RegistrationDelivery.Outcome.ACCEPTED;
        });
    }
    @AfterEach void cleanup() {
        for(String address:emails) {
            var ids=jdbc.queryForList("SELECT account_id FROM auth_identity WHERE email=?",UUID.class,address);
            jdbc.update("DELETE FROM auth_identity WHERE email=?",address);
            for(var id:ids) jdbc.update("DELETE FROM account WHERE id=?",id);
        }
        // Test-owned Redis only. Sessions from a previous test must not affect counts.
        for(String key:redis.keys("auth:session:*")) redis.delete(key);
    }
    RedisPendingRegistrations.Accepted register() { return register(email); }
    RedisPendingRegistrations.Accepted register(String address) {
        emails.add(address);
        return registrations.register(new RegisterRequest(address,"SyntheticPass8!","fixture",
                new RegisterRequest.Consent(true,false,false,false)));
    }
    String code(UUID id) { return mails.get(id).code(); }
    String raw(UUID id) { return redis.opsForValue().get(RedisPendingRegistrations.key(id)); }
    int accounts() { return jdbc.queryForObject("SELECT count(*) FROM account",Integer.class); }
    int identities() { return jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE email=?",Integer.class,email); }
    int sessionCount() { return redis.keys("auth:session:*").size(); }
    void failure(Runnable action,ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));
    }
    void verify(UUID id) { verification.verify(id,code(id),ANDROID); }
    void ageBudget() {
        String key=pending.sendBudgetKey(email);
        for(String member:redis.opsForZSet().range(key,0,-1)) redis.opsForZSet().incrementScore(key,member,-61000);
    }
    String proof(UUID id) { return "{\"requestId\":\""+id+"\",\"code\":\""+code(id)+"\"}"; }
    HttpRequest.Builder post(String path,String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }
    HttpRequest.Builder web(String path,String body) {
        return post(path,body).header("X-SceneCrew-Client","WEB").header("Origin","https://web.example.invalid")
                .header("X-SceneCrew-CSRF","1");
    }
    HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try(var client=HttpClient.newHttpClient()) { return client.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    @Test void normalWebCompletionReturns201CookiesAndExistingEmailLoginWorks() throws Exception {
        var receipt=register(); assertThat(sessionCount()).isZero();
        var response=send(web("/api/v1/auth/verify-email",proof(receipt.requestId())));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().allValues("Set-Cookie")).hasSize(2);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        var body=json.readTree(response.body()); assertThat(body.get("channel").asString()).isEqualTo("WEB");
        assertThat(body.get("account").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.get("account").get("primaryEmailVerified").asBoolean()).isTrue();
        assertThat(response.body()).doesNotContain("accessToken","refreshToken","passwordHash","SyntheticPass8!","\"code\"");
        assertThat(identities()).isEqualTo(1); assertThat(sessionCount()).isEqualTo(1);
        var login=send(web("/api/v1/auth/login","{\"email\":\""+email+"\",\"password\":\"SyntheticPass8!\"}"));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(sessionCount()).isEqualTo(2); // Explicit normal login is permitted; verification replay is not.
    }
    @Test void androidFirstCompletionReturns201TokensWithoutCookiesThenReplayIsRejected() throws Exception {
        var receipt=register();
        var response=send(post("/api/v1/auth/verify-email",proof(receipt.requestId()))
                .header("X-SceneCrew-Client","ANDROID").header("Origin","https://android.example.invalid"));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        var body=json.readTree(response.body()); assertThat(body.has("accessToken")).isTrue();
        assertThat(body.has("refreshToken")).isTrue();
        failure(()->verify(receipt.requestId()),ErrorCode.INVALID_STATE);
        assertThat(sessionCount()).isEqualTo(1); assertThat(identities()).isEqualTo(1);
    }
    @Test void channelCsrfAndCookieRejectionsPrecedeCodeClaimAndDoNotTouchSession() throws Exception {
        var r=register(); String original=raw(r.requestId());
        assertThat(send(post("/api/v1/auth/verify-email",proof(r.requestId()))).statusCode()).isEqualTo(400);
        assertThat(send(post("/api/v1/auth/verify-email",proof(r.requestId()))
                .header("X-SceneCrew-Client","WEB")).statusCode()).isEqualTo(403);
        assertThat(send(web("/api/v1/auth/verify-email",proof(r.requestId()))
                .header("Cookie","__Secure-sc-access=a; __Secure-sc-access=b")).statusCode()).isEqualTo(400);
        assertThat(send(post("/api/v1/auth/verify-email",proof(r.requestId()))
                .header("X-SceneCrew-Client","ANDROID").header("Origin","https://android.example.invalid")
                .header("Cookie","__Secure-sc-access=a")).statusCode()).isEqualTo(403);
        assertThat(send(post("/api/v1/auth/resend-verification","{\"requestId\":\""+r.requestId()+"\"}")
                .header("Cookie","__Secure-sc-access=a; __Secure-sc-access=b")).statusCode()).isEqualTo(400);
        assertThat(raw(r.requestId())).isEqualTo(original); assertThat(sessionCount()).isZero(); assertThat(identities()).isZero();
    }
    @Test void numericAndMalformedProofNeverConsumesAttemptOrCreatesAccount() throws Exception {
        var r=register(); int before=accounts(); String original=raw(r.requestId());
        for(String codeValue:List.of("123456","null","\"12345\"","\"１２３４５６\"")) {
            var response=send(web("/api/v1/auth/verify-email","{\"requestId\":\""+r.requestId()+"\",\"code\":"+codeValue+"}"));
            assertThat(response.statusCode()).isEqualTo(400);
        }
        assertThat(raw(r.requestId())).isEqualTo(original); assertThat(accounts()).isEqualTo(before);
        assertThat(sessionCount()).isZero();
    }
    @Test void fifthMismatchAndExpiryRejectAtProductBoundary() {
        var r=register(); int before=accounts();
        String wrong=code(r.requestId()).equals("000000")?"000001":"000000";
        for(int i=0;i<5;i++) failure(()->verification.verify(r.requestId(),wrong,ANDROID),ErrorCode.INVALID_REQUEST);
        failure(()->verify(r.requestId()),ErrorCode.INVALID_STATE);
        failure(()->verification.resend(r.requestId()),ErrorCode.INVALID_STATE);
        var next=register(UUID.randomUUID()+"@example.invalid");
        redis.delete(RedisPendingRegistrations.key(next.requestId()));
        failure(()->verify(next.requestId()),ErrorCode.INVALID_STATE);
        failure(()->verification.resend(next.requestId()),ErrorCode.INVALID_STATE);
        assertThat(accounts()).isEqualTo(before); assertThat(sessionCount()).isZero();
    }
    @Test void confirmedRollbackReleasesOnlyOwnerAndRetainsAttemptsBudgetAndDeadline() {
        var r=register(); int before=accounts();
        String wrong=code(r.requestId()).equals("000000")?"000001":"000000";
        failure(()->verification.verify(r.requestId(),wrong,ANDROID),ErrorCode.INVALID_REQUEST);
        long ttl=redis.getExpire(RedisPendingRegistrations.key(r.requestId()),TimeUnit.MILLISECONDS);
        var failing=spy(jdbc);
        doThrow(new DataAccessResourceFailureException("synthetic identity insert outage"))
                .when(failing).update(contains("INSERT INTO auth_identity"),any(Object[].class));
        doAnswer(call -> new RegistrationAccountWriter(failing,manager).create(call.getArgument(0),call.getArgument(1),
                call.getArgument(2),call.getArgument(3))).when(writer).create(anyString(),anyString(),anyString(),any());
        failure(()->verify(r.requestId()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(accounts()).isEqualTo(before); assertThat(identities()).isZero();
        assertThat(raw(r.requestId())).contains("PENDING","\"failures\":1");
        assertThat(json.readTree(raw(r.requestId())).get("expiresAtEpochMilli").asLong()).isEqualTo(r.expiresAt().toEpochMilli());
        assertThat(redis.getExpire(RedisPendingRegistrations.key(r.requestId()),TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        assertThat(redis.opsForZSet().zCard(pending.sendBudgetKey(email))).isEqualTo(1);
        assertThat(sessionCount()).isZero();
        doCallRealMethod().when(writer).create(anyString(),anyString(),anyString(),any());
        verify(r.requestId()); assertThat(identities()).isEqualTo(1); assertThat(sessionCount()).isEqualTo(1);
    }
    @Test void dbCommitThenRedisCompletionFailurePreservesAccountAndPreventsReplaySession() {
        var r=register();
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(requests).finish(eq(r.requestId()),any(),eq("COMPLETED"));
        failure(()->verify(r.requestId()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(identities()).isEqualTo(1); assertThat(raw(r.requestId())).contains("VERIFYING");
        failure(()->verify(r.requestId()),ErrorCode.INVALID_STATE); assertThat(sessionCount()).isZero();
    }
    @Test void lostRedisCompletionAcknowledgementIsNotAReasonToMintSessionOnRetry() {
        var r=register();
        doAnswer(call -> { call.callRealMethod(); throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); })
                .when(requests).finish(eq(r.requestId()),any(),eq("COMPLETED"));
        failure(()->verify(r.requestId()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(identities()).isEqualTo(1); assertThat(raw(r.requestId())).contains("COMPLETED");
        failure(()->verify(r.requestId()),ErrorCode.INVALID_STATE); assertThat(sessionCount()).isZero();
    }
    @Test void sessionFailureAfterCommitPreservesAccountAndVerificationCannotReissue() throws Exception {
        var r=register();
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(sessions).issue(any(),any());
        failure(()->verify(r.requestId()),ErrorCode.SERVICE_UNAVAILABLE);
        failure(()->verify(r.requestId()),ErrorCode.INVALID_STATE);
        assertThat(identities()).isEqualTo(1); assertThat(sessionCount()).isZero();
        var login=send(web("/api/v1/auth/login","{\"email\":\""+email+"\",\"password\":\"SyntheticPass8!\"}"));
        assertThat(login.statusCode()).isEqualTo(200); assertThat(sessionCount()).isEqualTo(1);
    }
    @Test void lostSessionAcknowledgementLeavesOneSessionAndReplayNeverCreatesAnother() {
        var r=register();
        doAnswer(call -> { call.callRealMethod(); throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); })
                .when(sessions).issue(any(),any());
        failure(()->verify(r.requestId()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(sessionCount()).isEqualTo(1); assertThat(identities()).isEqualTo(1);
        failure(()->verify(r.requestId()),ErrorCode.INVALID_STATE); assertThat(sessionCount()).isEqualTo(1);
    }
    PlatformTransactionManager uncertain(boolean commit,Runnable after) {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
            public void commit(TransactionStatus status) {
                if(commit) manager.commit(status); else manager.rollback(status);
                after.run(); throw new TransactionSystemException("synthetic uncertain commit acknowledgement");
            }
        };
    }
    void uncertainWriter(PlatformTransactionManager adapter) {
        doAnswer(call -> new RegistrationAccountWriter(jdbc,adapter).create(call.getArgument(0),call.getArgument(1),
                call.getArgument(2),call.getArgument(3))).when(writer).create(anyString(),anyString(),anyString(),any());
    }
    @Test void unknownCommitIsReobservedByExactAttemptIdsAndNeverReturnsFirstLogin() {
        var r=register(); uncertainWriter(uncertain(true,()->{}));
        failure(()->verify(r.requestId()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(identities()).isEqualTo(1); assertThat(raw(r.requestId())).contains("COMPLETED");
        failure(()->verify(r.requestId()),ErrorCode.INVALID_STATE); assertThat(sessionCount()).isZero();
        org.mockito.Mockito.verify(writer).committed(any());
    }
    @Test void unknownWithoutOwnRowsRemainsVerifyingEvenIfAnotherAccountHasSameEmail() {
        var r=register();
        uncertainWriter(uncertain(false,()->new RegistrationAccountWriter(jdbc,manager)
                .create(email,"another-request","synthetic-hash",new RegisterRequest.Consent(true,false,false,false))));
        failure(()->verify(r.requestId()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(identities()).isEqualTo(1); assertThat(raw(r.requestId())).contains("VERIFYING");
        failure(()->verify(r.requestId()),ErrorCode.INVALID_STATE); assertThat(sessionCount()).isZero();
        org.mockito.Mockito.verify(writer,times(1)).create(anyString(),anyString(),anyString(),any());
    }
    @Test void unknownAndUnavailableReobservationDoesNotReleaseClaimOrInsertAgain() {
        var r=register(); uncertainWriter(uncertain(true,()->{}));
        doThrow(new DataAccessResourceFailureException("synthetic observation outage")).when(writer).committed(any());
        failure(()->verify(r.requestId()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(identities()).isEqualTo(1); assertThat(raw(r.requestId())).contains("VERIFYING");
        failure(()->verify(r.requestId()),ErrorCode.INVALID_STATE); assertThat(sessionCount()).isZero();
    }
    @Test void processStoppedAfterClaimDoesNotAllowOwnershipStealOrExtendExpiry() {
        var r=register(); int before=accounts();
        var oldOwner=UUID.randomUUID(); requests.claim(r.requestId(),code(r.requestId()),oldOwner);
        long ttl=redis.getExpire(RedisPendingRegistrations.key(r.requestId()),TimeUnit.MILLISECONDS);
        failure(()->verify(r.requestId()),ErrorCode.INVALID_STATE);
        failure(()->verification.resend(r.requestId()),ErrorCode.INVALID_STATE);
        failure(()->requests.finish(r.requestId(),UUID.randomUUID(),"PENDING"),ErrorCode.INVALID_STATE);
        assertThat(raw(r.requestId())).contains(oldOwner.toString());
        assertThat(redis.getExpire(RedisPendingRegistrations.key(r.requestId()),TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        assertThat(accounts()).isEqualTo(before); assertThat(sessionCount()).isZero();
    }
    @Test void sameRequestConcurrentProductVerificationCreatesOneAccountAndOneSession() throws Exception {
        var r=register(); int before=accounts();
        var atWriter=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call->{ atWriter.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return call.callRealMethod(); })
                .when(writer).create(anyString(),anyString(),anyString(),any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->verify(r.requestId())); assertThat(atWriter.await(10,TimeUnit.SECONDS)).isTrue();
            try { failure(()->verify(r.requestId()),ErrorCode.INVALID_STATE); }
            finally { release.countDown(); }
            a.get(15,TimeUnit.SECONDS);
        }
        assertThat(accounts()).isEqualTo(before+1); assertThat(identities()).isEqualTo(1); assertThat(sessionCount()).isEqualTo(1);
    }
    @Test void differentRequestsSameEmailCompeteAtUniqueConstraintWithNoOrphanAccount() throws Exception {
        var a=register(); ageBudget(); var b=register(); int before=accounts();
        var barrier=new CyclicBarrier(2); var traced=spy(jdbc);
        doAnswer(call->{ Object result=call.callRealMethod(); barrier.await(10,TimeUnit.SECONDS); return result; })
                .when(traced).queryForList(anyString(),eq(Boolean.class),eq(email));
        doAnswer(call->new RegistrationAccountWriter(traced,manager).create(call.getArgument(0),call.getArgument(1),
                call.getArgument(2),call.getArgument(3))).when(writer).create(anyString(),anyString(),anyString(),any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->attempt(a.requestId())); var second=pool.submit(()->attempt(b.requestId()));
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK","EMAIL_ALREADY_REGISTERED");
        }
        assertThat(accounts()).isEqualTo(before+1); assertThat(identities()).isEqualTo(1); assertThat(sessionCount()).isEqualTo(1);
    }
    String attempt(UUID id) { try { verify(id); return "OK"; } catch(BusinessException e) { return e.getErrorCode().name(); } }
    @Test void resendRequiresOnlyRequestReferenceAndKeepsCodeDeadlineAndNoSession() throws Exception {
        var r=register(); ageBudget();
        var response=send(post("/api/v1/auth/resend-verification","{\"requestId\":\""+r.requestId()+"\"}"));
        assertThat(response.statusCode()).isEqualTo(202); assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(json.readTree(response.body()).get("expiresAt").asString()).isEqualTo(r.expiresAt().toString());
        assertThat(mails.get(r.requestId()).code().equals(codes.derive(r.requestId()))).isTrue();
        assertThat(response.body()).doesNotContain("\"code\"",email); assertThat(sessionCount()).isZero();
        assertThat(send(post("/api/v1/auth/resend-verification","{\"requestId\":\""+r.requestId()+"\"}")).statusCode()).isEqualTo(429);
    }
    @Test void definiteAndUncertainNonAcceptanceNeverReturns202OrRefundsReservation() {
        for(var outcome:List.of(RegistrationDelivery.Outcome.DEFINITELY_NOT_SENT,RegistrationDelivery.Outcome.UNKNOWN)) {
            var r=register(); ageBudget(); doReturn(outcome).when(delivery).accept(any());
            failure(()->verification.resend(r.requestId()),ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(redis.opsForZSet().zCard(pending.sendBudgetKey(email))).isEqualTo(2);
            failure(()->verification.resend(r.requestId()),ErrorCode.RATE_LIMITED);
            email=UUID.randomUUID()+"@example.invalid";
            doAnswer(call->{ var mail=(RegistrationDelivery.Message)call.getArgument(0);
                mails.put(mail.requestId(),mail); return RegistrationDelivery.Outcome.ACCEPTED; }).when(delivery).accept(any());
        }
        assertThat(sessionCount()).isZero();
    }
    @Test void discardedHttpResponseCannotBeReplayedForAnotherSession() throws Exception {
        var r=register();
        try(var client=HttpClient.newHttpClient()) {
            client.send(web("/api/v1/auth/verify-email",proof(r.requestId())).build(),HttpResponse.BodyHandlers.discarding());
        }
        assertThat(sessionCount()).isEqualTo(1);
        var retry=send(web("/api/v1/auth/verify-email",proof(r.requestId())));
        assertThat(retry.statusCode()).isEqualTo(409); assertThat(retry.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(sessionCount()).isEqualTo(1); assertThat(identities()).isEqualTo(1);
    }
    @Test void verifiedCodeWithLeadingZeroSurvivesHttpAndCreatesOnlyOneAccount() throws Exception {
        RedisPendingRegistrations.Accepted selected=null;
        for(int i=0;i<100;i++) {
            var r=register(UUID.randomUUID()+"@example.invalid");
            if(code(r.requestId()).startsWith("0")) { selected=r; break; }
        }
        assertThat(selected).isNotNull();
        var response=send(web("/api/v1/auth/verify-email",proof(selected.requestId())));
        assertThat(response.statusCode()).isEqualTo(201); assertThat(sessionCount()).isEqualTo(1);
    }
    @Test void existingSessionSurvivesWrongProofAndIsReplacedOnlyByFirstValidCompletion() {
        var oldRequest=register();
        var old=verification.verify(oldRequest.requestId(),code(oldRequest.requestId()),ANDROID);
        var next=register(UUID.randomUUID()+"@example.invalid");
        var credentials=new Credentials("ANDROID",old.tokens().accessToken());
        String wrong=code(next.requestId()).equals("000000")?"000001":"000000";
        failure(()->verification.verify(next.requestId(),wrong,credentials),ErrorCode.INVALID_REQUEST);
        assertThat(authenticator.authenticate(old.tokens().accessToken())).isNotNull();
        verification.verify(next.requestId(),code(next.requestId()),credentials);
        failure(()->authenticator.authenticate(old.tokens().accessToken()),ErrorCode.UNAUTHENTICATED);
        assertThat(sessionCount()).isEqualTo(1);
        failure(()->verification.verify(next.requestId(),code(next.requestId()),credentials),ErrorCode.INVALID_STATE);
        assertThat(sessionCount()).isEqualTo(1);
    }
    @Test void mismatchedExistingSessionChannelIsRejectedBeforeClaimOrAccountCreation() {
        var oldRequest=register();
        var old=verification.verify(oldRequest.requestId(),code(oldRequest.requestId()),ANDROID);
        var next=register(UUID.randomUUID()+"@example.invalid");
        String original=raw(next.requestId()); int before=accounts();
        failure(()->verification.verify(next.requestId(),code(next.requestId()),
                new Credentials("WEB",old.tokens().accessToken())),ErrorCode.AUTH_CHANNEL_MISMATCH);
        assertThat(raw(next.requestId())).isEqualTo(original);
        assertThat(accounts()).isEqualTo(before); assertThat(sessionCount()).isEqualTo(1);
        assertThat(authenticator.authenticate(old.tokens().accessToken())).isNotNull();
    }
    @Test void actualServletBodyWriteFailureAfterCommitDoesNotIssueAgainOnRetry() throws Exception {
        var r=register(); failingResponse.armed.set(true);
        try { send(web("/api/v1/auth/verify-email",proof(r.requestId()))); }
        catch(java.io.IOException expectedTransportFailure) { /* Response may be truncated or replaced with 500. */ }
        assertThat(failingResponse.failures.get()).isEqualTo(1);
        assertThat(identities()).isEqualTo(1); assertThat(sessionCount()).isEqualTo(1);
        var retry=send(web("/api/v1/auth/verify-email",proof(r.requestId())));
        assertThat(retry.statusCode()).isEqualTo(409); assertThat(retry.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(sessionCount()).isEqualTo(1);
    }
    /** Test-only failure exactly at Servlet output write; no production response hook. */
    static class FailingResponseFilter extends org.springframework.web.filter.OncePerRequestFilter {
        final AtomicBoolean armed=new AtomicBoolean(); final AtomicInteger failures=new AtomicInteger();
        @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response,jakarta.servlet.FilterChain chain)
                throws jakarta.servlet.ServletException,java.io.IOException {
            if(!request.getRequestURI().equals("/api/v1/auth/verify-email") || !armed.get()) {
                chain.doFilter(request,response); return;
            }
            var wrapped=new jakarta.servlet.http.HttpServletResponseWrapper(response) {
                @Override public jakarta.servlet.ServletOutputStream getOutputStream() throws java.io.IOException {
                    var delegate=super.getOutputStream();
                    return new jakarta.servlet.ServletOutputStream() {
                        public boolean isReady() { return delegate.isReady(); }
                        public void setWriteListener(jakarta.servlet.WriteListener listener) { delegate.setWriteListener(listener); }
                        public void write(int value) throws java.io.IOException {
                            if(armed.compareAndSet(true,false)) {
                                failures.incrementAndGet(); throw new java.io.IOException("synthetic HTTP write failure");
                            }
                            delegate.write(value);
                        }
                    };
                }
            };
            chain.doFilter(request,wrapped);
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Config {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(4); }
        @Bean FailingResponseFilter failingResponse() { return new FailingResponseFilter(); }
    }
}

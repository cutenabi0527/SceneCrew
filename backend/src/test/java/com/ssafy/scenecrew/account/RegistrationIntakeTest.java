package com.ssafy.scenecrew.account;

import java.util.*;
import java.util.concurrent.*;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.dto.RegisterRequest;
import com.ssafy.scenecrew.account.infra.RedisPendingRegistrations;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL and exclusively owned Redis. Fake delivery exists only in this test. */
@SpringBootTest
@ActiveProfiles("test")
class RegistrationIntakeTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start(); r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired Validator validator;
    @Autowired com.ssafy.scenecrew.account.repository.AuthIdentityRepository identities;
    @Autowired com.ssafy.scenecrew.account.repository.AccountRepository accountRepository;
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    final List<UUID> accounts = new ArrayList<>();
    final Set<UUID> delivered = ConcurrentHashMap.newKeySet();
    RedisPendingRegistrations pending;
    String email;
    final RegistrationDelivery fake = new RegistrationDelivery() {
        public void requireAvailable() {}
        public Outcome accept(Message mail) { delivered.add(mail.requestId()); return Outcome.ACCEPTED; }
    };
    @BeforeEach void setup() {
        email = UUID.randomUUID() + "@example.invalid";
        pending = new RedisPendingRegistrations(redis, new byte[32]);
        delivered.clear();
    }
    @AfterEach void cleanup() {
        for (UUID id : accounts) {
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?", id);
            jdbc.update("DELETE FROM account WHERE id=?", id);
        }
    }
    EmailRegistrationService service(RegistrationDelivery delivery) {
        return new EmailRegistrationService(jdbc, encoder, validator, pending, delivery);
    }
    RegisterRequest input(String email, String name, String password) {
        return new RegisterRequest(email, password, name, new RegisterRequest.Consent(true, false, false, false));
    }
    RegisterRequest input() { return input(email, " 합성계정 ", "SyntheticPass8!"); }
    String value(UUID id) { return redis.opsForValue().get(RedisPendingRegistrations.key(id)); }
    long now() { return redis.execute(new DefaultRedisScript<>(
            "local t=redis.call('TIME'); return t[1]*1000+math.floor(t[2]/1000)", Long.class), List.of()); }
    void failure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test void intakeStoresOnlyHashAndRequestInputForTenMinutesWithoutAccountOrSession() {
        int before = jdbc.queryForObject("SELECT count(*) FROM account", Integer.class);
        var result = service(fake).register(input());
        var stored = json.readTree(value(result.requestId()));
        assertThat(encoder.matches("SyntheticPass8!", stored.get("passwordHash").asString())).isTrue();
        assertThat(stored.get("email").asString()).isEqualTo(email);
        assertThat(stored.get("displayName").asString()).isEqualTo("합성계정");
        assertThat(stored.get("consent").get("voice").asBoolean()).isFalse();
        assertThat(stored.get("expiresAtEpochMilli").asLong() - stored.get("createdAtEpochMilli").asLong()).isEqualTo(600000);
        assertThat(result.expiresAt().toEpochMilli()).isEqualTo(stored.get("expiresAtEpochMilli").asLong());
        assertThat(redis.getExpire(RedisPendingRegistrations.key(result.requestId()), TimeUnit.MILLISECONDS)).isBetween(1L,600000L);
        assertThat(value(result.requestId())).doesNotContain("SyntheticPass8!", "\"password\":", "code", "session");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Integer.class)).isEqualTo(before);
        assertThat(delivered).containsExactly(result.requestId());
        assertThat(input().toString()).doesNotContain("SyntheticPass8!", email);
        assertThat(json.writeValueAsString(input())).doesNotContain("SyntheticPass8!");
        assertThat(pending.sendBudgetKey(email)).doesNotContain(email);
    }

    @Test void normalizedConcurrentRequestsReserveOneSlotAndCreateNoAccount() throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> attempt(gate, input()));
            var b = pool.submit(() -> attempt(gate, input("  " + email.toUpperCase(Locale.ROOT) + " ", "별도입력", "OtherPassword8!")));
            gate.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS), b.get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACCEPTED", "RATE_LIMITED");
        }
        assertThat(delivered).hasSize(1);
        assertThat(redis.opsForZSet().zCard(pending.sendBudgetKey(email))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE lower(btrim(email))=?", Integer.class, email)).isZero();
    }

    @Test void pendingHashWorksWithExistingLoginVerifierAfterSyntheticCompletionFixture() {
        var receipt=service(fake).register(input());
        String hash=json.readTree(value(receipt.requestId())).get("passwordHash").asString();
        UUID id=UUID.randomUUID(); accounts.add(id);
        // Only a fixture: #223 does not create completed accounts or implement #224.
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 0, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",
                UUID.randomUUID(),id,id.toString(),hash,email);
        var verifier=new LocalCredentialVerifier(identities,accountRepository,encoder);
        assertThat(verifier.verify(email,"SyntheticPass8!").orElseThrow().accountId()).isEqualTo(id);
        assertThat(verifier.verify(email,"WrongPassword8!")).isEmpty();
    }
    String attempt(CountDownLatch gate, RegisterRequest input) throws Exception {
        gate.await();
        try { service(fake).register(input); return "ACCEPTED"; }
        catch (BusinessException e) { return e.getErrorCode().name(); }
    }

    @Test void repeatedPendingRequestNeverOverwritesOriginalPasswordNameOrExpiry() {
        var first = service(fake).register(input());
        String original = value(first.requestId());
        long ttl = redis.getExpire(RedisPendingRegistrations.key(first.requestId()),TimeUnit.MILLISECONDS);
        failure(() -> service(fake).register(input(email, "별도", "OtherPassword8!")), ErrorCode.RATE_LIMITED);
        assertThat(value(first.requestId())).isEqualTo(original);
        assertThat(redis.getExpire(RedisPendingRegistrations.key(first.requestId()),TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
    }

    @Test void laterIntakeHasIndependentPayloadAndKeepsEarlierPendingRequest() {
        var first = service(fake).register(input());
        String original = value(first.requestId());
        // Seed a prior send to reach the allowed branch without a real one-minute wait.
        redis.opsForZSet().add(pending.sendBudgetKey(email), first.requestId().toString(), now()-60001);
        var second = service(fake).register(input(email, "다른입력", "OtherPassword8!"));
        assertThat(second.requestId()).isNotEqualTo(first.requestId());
        assertThat(value(first.requestId())).isEqualTo(original);
        assertThat(encoder.matches("OtherPassword8!",json.readTree(value(second.requestId())).get("passwordHash").asString())).isTrue();
    }

    @Test void fiveSendsInWindowRejectWithoutExtendingBudgetOrPendingTtl() {
        String key = pending.sendBudgetKey(email); long time = now();
        for (int i=0;i<5;i++) redis.opsForZSet().add(key,"prior-"+i,time-120000-i*60001);
        redis.expire(key,java.time.Duration.ofMinutes(8));
        long ttl=redis.getExpire(key,TimeUnit.MILLISECONDS);
        failure(() -> service(fake).register(input()),ErrorCode.RATE_LIMITED);
        assertThat(redis.opsForZSet().zCard(key)).isEqualTo(5);
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        assertThat(delivered).isEmpty();
    }

    @Test void expiredWindowAllowsFreshIntake() {
        redis.opsForZSet().add(pending.sendBudgetKey(email),"expired",now()-600001);
        var result=service(fake).register(input());
        assertThat(redis.opsForZSet().range(pending.sendBudgetKey(email),0,-1)).containsExactly(result.requestId().toString());
    }

    @Test void completedAndDisabledLocalEmailsAreRejectedAndNeverReactivated() {
        for (String status: List.of("ACTIVE","DISABLED")) {
            String address=UUID.randomUUID()+"@example.invalid";
            UUID id=account(status,true,"LOCAL",address);
            failure(() -> service(fake).register(input(" "+address.toUpperCase(Locale.ROOT)+" ","합성","SyntheticPass8!")),
                    ErrorCode.EMAIL_ALREADY_REGISTERED);
            assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id=?",String.class,id)).isEqualTo(status);
        }
        assertThat(delivered).isEmpty();
    }

    @Test void incompleteLegacyLocalIsPreservedAndReportedAsInvalidState() {
        UUID id=account("ACTIVE",false,"LOCAL",email);
        failure(() -> service(fake).register(input()),ErrorCode.INVALID_STATE);
        assertThat(jdbc.queryForObject("SELECT signup_completed FROM account WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(delivered).isEmpty();
    }

    @Test void socialReferenceEmailDoesNotBlockOrMergeLocalIntake() {
        UUID id=account("ACTIVE",true,"GOOGLE",email);
        var result=service(fake).register(input());
        assertThat(value(result.requestId())).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id=?",Integer.class,id)).isEqualTo(1);
    }

    @Test void unavailableDeliveryFailsBeforeAnyPendingWriteOrReservation() {
        failure(() -> service(RegistrationDelivery.unavailable()).register(input()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(redis.hasKey(pending.sendBudgetKey(email))).isFalse();
    }

    @Test void failedOrUncertainDeliveryIsNotAcceptedAndDoesNotReleaseBudgetForDuplicateSend() {
        var failing=new RegistrationDelivery() {
            public void requireAvailable() {}
            public Outcome accept(Message mail) { delivered.add(mail.requestId()); throw new IllegalStateException("synthetic delivery failure"); }
        };
        failure(() -> service(failing).register(input()),ErrorCode.SERVICE_UNAVAILABLE);
        UUID id=delivered.iterator().next();
        assertThat(value(id)).doesNotContain("SyntheticPass8!");
        failure(() -> service(fake).register(input()),ErrorCode.RATE_LIMITED);
    }

    @Test void redisLostAcknowledgementReturns503WithoutDispatchOrAutomaticRetry() {
        StringRedisTemplate failing=spy(redis);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new DataAccessResourceFailureException("synthetic lost reply"); })
                .when(failing).execute(any(RedisScript.class),anyList(),any(Object[].class));
        pending=new RedisPendingRegistrations(failing,new byte[32]);
        failure(() -> service(fake).register(input()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(delivered).isEmpty();
        assertThat(redis.opsForZSet().zCard(pending.sendBudgetKey(email))).isEqualTo(1);
        verify(failing,times(1)).execute(any(RedisScript.class),anyList(),any(Object[].class));
    }

    @Test void corruptBudgetFailsClosedBeforePendingOrDispatch() {
        redis.opsForValue().set(pending.sendBudgetKey(email),"wrong-type");
        failure(() -> service(fake).register(input()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(delivered).isEmpty();
        assertThat(redis.opsForValue().get(pending.sendBudgetKey(email))).isEqualTo("wrong-type");
    }

    @Test void databaseFailureNeverReservesPendingOrDispatches() {
        JdbcTemplate failing=mock(JdbcTemplate.class);
        when(failing.queryForList(anyString(),eq(Boolean.class),eq(email)))
                .thenThrow(new DataAccessResourceFailureException("synthetic DB outage"));
        var service=new EmailRegistrationService(failing,encoder,validator,pending,fake);
        failure(() -> service.register(input()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(redis.hasKey(pending.sendBudgetKey(email))).isFalse();
        assertThat(delivered).isEmpty();
    }

    @Test void redisFailureBeforeWriteNeverDispatches() {
        StringRedisTemplate failing=mock(StringRedisTemplate.class);
        when(failing.execute(any(RedisScript.class),anyList(),any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("synthetic Redis outage"));
        pending=new RedisPendingRegistrations(failing,new byte[32]);
        failure(() -> service(fake).register(input()),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(redis.hasKey(pending.sendBudgetKey(email))).isFalse();
        assertThat(delivered).isEmpty();
    }

    @Test void invalidInputsNeverReachStorageOrDelivery() {
        for (RegisterRequest input : Arrays.asList(input("bad-email","합성","SyntheticPass8!"),
                input(email," ","SyntheticPass8!"),
                input(email,"a".repeat(21),"SyntheticPass8!"), input(email,"합성","short"),
                input(email,"합성"," has space! "), input(email,null,"SyntheticPass8!"),
                new RegisterRequest(email,"SyntheticPass8!","합성",null))) {
            failure(() -> service(fake).register(input),ErrorCode.INVALID_REQUEST);
        }
        assertThat(redis.hasKey(pending.sendBudgetKey(email))).isFalse();
        assertThat(delivered).isEmpty();
    }

    @Test void rawInvalidNamesNeverReserveOrDispatchOrCreateAccounts() {
        int before = jdbc.queryForObject("SELECT count(*) FROM account", Integer.class);
        for (String control : List.of("\n", "\t", "\u0000", "\u2028", "\u2029")) {
            for (String name : List.of(control + "name", "name" + control, "na" + control + "me")) {
                failure(() -> service(fake).register(input(email, name, "SyntheticPass8!")),
                        ErrorCode.INVALID_REQUEST);
            }
        }
        failure(() -> service(fake).register(input(email, "😀".repeat(21), "SyntheticPass8!")),
                ErrorCode.INVALID_REQUEST);
        assertThat(redis.hasKey(pending.sendBudgetKey(email))).isFalse();
        assertThat(delivered).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Integer.class)).isEqualTo(before);
    }

    @Test void unicodeNameUsesCodePointsAndAliasesRemainDistinct() {
        var result=service(fake).register(input(email,"  " + "😀".repeat(20) + "  ","SyntheticPass8!"));
        assertThat(json.readTree(value(result.requestId())).get("displayName").asString()).isEqualTo("😀".repeat(20));
        assertThat(pending.sendBudgetKey("a+b@example.invalid")).isNotEqualTo(pending.sendBudgetKey("a@example.invalid"));
    }

    UUID account(String status,boolean complete,String provider,String email) {
        UUID id=UUID.randomUUID(); accounts.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', ?, ?, 0, '{}'::jsonb, now())",id,status,complete);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, ?, ?, ?, ?, true)",UUID.randomUUID(),id,provider,id.toString(),
                "LOCAL".equals(provider)?encoder.encode("SyntheticPass8!"):null,email);
        return id;
    }
}

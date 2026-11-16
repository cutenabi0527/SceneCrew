package com.ssafy.scenecrew.account;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.dto.RegisterRequest;
import com.ssafy.scenecrew.account.infra.RedisPendingRegistrations;
import com.ssafy.scenecrew.account.infra.RedisVerificationRequests;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;

/** Real isolated Redis state machine; no claims about provider mail or PostgreSQL commits. */
@SpringBootTest
@ActiveProfiles("test")
class VerificationStateTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start(); r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @Autowired StringRedisTemplate redis;
    final RegistrationCodes codes=new RegistrationCodes(new byte[32]);
    final JsonMapper json=JsonMapper.builder().build();
    RedisPendingRegistrations pending;
    RedisVerificationRequests state;
    RedisPendingRegistrations.Accepted accepted;
    String email;
    @BeforeEach void setup() {
        pending=new RedisPendingRegistrations(redis,new byte[32]);
        state=new RedisVerificationRequests(redis,pending,codes);
        email=UUID.randomUUID()+"@example.invalid";
        accepted=pending.save(new RegisterRequest(email,"SyntheticPass8!","fixture",
                new RegisterRequest.Consent(true,false,false,false)),"synthetic-hash");
    }
    long now() { return redis.execute(new DefaultRedisScript<>(
            "local t=redis.call('TIME'); return t[1]*1000+math.floor(t[2]/1000)",Long.class),List.of()); }
    void ageBudget() {
        String key=pending.sendBudgetKey(email);
        for(String member:redis.opsForZSet().range(key,0,-1)) redis.opsForZSet().incrementScore(key,member,-61000);
    }
    String raw() { return redis.opsForValue().get(RedisPendingRegistrations.key(accepted.requestId())); }
    void error(Runnable run,ErrorCode code) {
        assertThatThrownBy(run::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));
    }
    @Test void sameRequestProducesSameSixDigitsAndLeadingZeroIsPreserved() {
        UUID id=null;
        for(int i=0;i<1000;i++) { var candidate=new UUID(0,i); if(codes.derive(candidate).startsWith("0")) { id=candidate; break; } }
        assertThat(id).isNotNull();
        String value=codes.derive(id);
        assertThat(value).matches("0[0-9]{5}");
        assertThat(codes.derive(id)).isEqualTo(value);
        assertThat(codes.matches(id,value)).isTrue();
        var message=pending.message(accepted.requestId());
        assertThat(message.code()).matches("[0-9]{6}");
        assertThat(message.toString()).doesNotContain(message.code(),email);
        assertThat(json.writeValueAsString(message)).doesNotContain("\"code\"");
        assertThat(raw()).doesNotContain("\"code\"","SyntheticPass8!");
    }
    @Test void fifthMismatchTerminatesWithoutExtendingExpiry() {
        String wrong=codes.derive(accepted.requestId()).equals("000000")?"000001":"000000";
        long ttl=redis.getExpire(RedisPendingRegistrations.key(accepted.requestId()),TimeUnit.MILLISECONDS);
        for(int i=0;i<5;i++) error(()->state.claim(accepted.requestId(),wrong,UUID.randomUUID()),ErrorCode.INVALID_REQUEST);
        assertThat(raw()).contains("TERMINATED");
        error(()->state.claim(accepted.requestId(),codes.derive(accepted.requestId()),UUID.randomUUID()),ErrorCode.INVALID_STATE);
        error(()->state.reserveResend(accepted.requestId()),ErrorCode.INVALID_STATE);
        assertThat(redis.getExpire(RedisPendingRegistrations.key(accepted.requestId()),TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
    }
    @Test void sameRequestConcurrentClaimHasOnlyOneOwner() throws Exception {
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->claim(gate)); var b=pool.submit(()->claim(gate)); gate.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        error(()->state.reserveResend(accepted.requestId()),ErrorCode.INVALID_STATE);
    }
    boolean claim(CountDownLatch gate) throws Exception {
        gate.await();
        try { state.claim(accepted.requestId(),codes.derive(accepted.requestId()),UUID.randomUUID()); return true; }
        catch(BusinessException e) { assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE); return false; }
    }
    @Test void resendKeepsOriginalExpiryAndCodeAndUsesSharedEmailBudget() {
        error(()->state.reserveResend(accepted.requestId()),ErrorCode.RATE_LIMITED);
        ageBudget(); long ttl=redis.getExpire(RedisPendingRegistrations.key(accepted.requestId()),TimeUnit.MILLISECONDS);
        var message=pending.message(accepted.requestId());
        assertThat(state.reserveResend(accepted.requestId())).isEqualTo(accepted);
        assertThat(pending.message(accepted.requestId()).code()).isEqualTo(message.code());
        assertThat(redis.getExpire(RedisPendingRegistrations.key(accepted.requestId()),TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
        assertThat(redis.opsForZSet().zCard(pending.sendBudgetKey(email))).isEqualTo(2);
        error(()->pending.save(new RegisterRequest(email,"SyntheticPass8!","fixture",new RegisterRequest.Consent(true,false,false,false)),"hash"),ErrorCode.RATE_LIMITED);
    }
    @Test void fifthEmailSendBlocksSixthWithoutReservingIt() {
        String key=pending.sendBudgetKey(email);
        // Seed timestamps inside the real Redis window rather than waiting ten minutes.
        redis.delete(key);
        for(int i=0;i<5;i++) redis.opsForZSet().add(key,"prior-"+i,now()-65000-i*61000);
        error(()->state.reserveResend(accepted.requestId()),ErrorCode.RATE_LIMITED);
        assertThat(redis.opsForZSet().zCard(key)).isEqualTo(5);
    }
    @Test void originalTenMinuteDeadlineCheckedEvenIfPhysicalTtlSurvives() {
        var p=json.readTree(raw());
        var updated=(tools.jackson.databind.node.ObjectNode)p;
        updated.put("createdAtEpochMilli",now()-600001); updated.put("expiresAtEpochMilli",now()-1);
        redis.opsForValue().set(RedisPendingRegistrations.key(accepted.requestId()),json.writeValueAsString(updated),Duration.ofMinutes(1));
        error(()->state.claim(accepted.requestId(),codes.derive(accepted.requestId()),UUID.randomUUID()),ErrorCode.INVALID_STATE);
        error(()->state.reserveResend(accepted.requestId()),ErrorCode.INVALID_STATE);
    }
    @Test void completedAndCanceledStatesRejectBothOperations() {
        var owner=UUID.randomUUID(); state.claim(accepted.requestId(),codes.derive(accepted.requestId()),owner);
        state.finish(accepted.requestId(),owner,"COMPLETED");
        error(()->state.claim(accepted.requestId(),codes.derive(accepted.requestId()),UUID.randomUUID()),ErrorCode.INVALID_STATE);
        error(()->state.reserveResend(accepted.requestId()),ErrorCode.INVALID_STATE);
        assertThat(raw()).doesNotContain("synthetic-hash",email,"displayName","consent");
        redis.opsForValue().set(RedisPendingRegistrations.key(accepted.requestId()),"{\"expiresAtEpochMilli\":"+(now()+10000)+",\"state\":\"CANCELED\",\"proofKeyId\":\""+codes.keyId()+"\"}",Duration.ofSeconds(10));
        error(()->state.claim(accepted.requestId(),codes.derive(accepted.requestId()),UUID.randomUUID()),ErrorCode.INVALID_STATE);
        error(()->state.reserveResend(accepted.requestId()),ErrorCode.INVALID_STATE);
    }
    @Test void changedKeyFailsClosedInsteadOfSendingOrAcceptingDifferentCode() {
        byte[] different=new byte[32]; Arrays.fill(different,(byte)1);
        var changed=new RedisVerificationRequests(redis,pending,new RegistrationCodes(different));
        error(()->changed.claim(accepted.requestId(),codes.derive(accepted.requestId()),UUID.randomUUID()),ErrorCode.SERVICE_UNAVAILABLE);
        ageBudget(); error(()->changed.reserveResend(accepted.requestId()),ErrorCode.SERVICE_UNAVAILABLE);
        error(()->new RedisPendingRegistrations(redis,different).message(accepted.requestId()),ErrorCode.SERVICE_UNAVAILABLE);
    }
    @Test void onlyCurrentOwnerCanReleaseClaimAfterKnownRollback() {
        var owner=UUID.randomUUID(); state.claim(accepted.requestId(),codes.derive(accepted.requestId()),owner);
        error(()->state.finish(accepted.requestId(),UUID.randomUUID(),"PENDING"),ErrorCode.INVALID_STATE);
        state.finish(accepted.requestId(),owner,"PENDING");
        state.claim(accepted.requestId(),codes.derive(accepted.requestId()),UUID.randomUUID());
    }
}

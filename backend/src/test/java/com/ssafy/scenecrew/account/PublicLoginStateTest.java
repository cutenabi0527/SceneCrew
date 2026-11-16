package com.ssafy.scenecrew.account;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Own standalone Redis. Controls both terminal orderings and lost-ack boundaries. */
class PublicLoginStateTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    final Clock clock=Clock.systemUTC();
    final LocalCredentialVerifier.VerifiedAccount account=new LocalCredentialVerifier.VerifiedAccount(
            UUID.randomUUID(),"fixture",UUID.randomUUID()+"@example.invalid",3,UUID.randomUUID());
    LoginFailureLimiter limiter;
    RedisLoginSessions store;
    @BeforeAll static void start() {
        REDIS.start(); factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();factory.start(); redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();
    }
    @AfterAll static void stop() { if(factory!=null)factory.destroy(); REDIS.stop(); }
    @BeforeEach void setup() { limiter=new LoginFailureLimiter(redis,new byte[32]);store=new RedisLoginSessions(redis,clock); }
    LoginTokens tokens() { return new LoginTokens(UUID.randomUUID(),"synthetic-access",UUID.randomUUID().toString(),
            clock.instant().plusSeconds(3600),clock.instant().plusSeconds(1209600)); }
    List<String> keys() { return limiter.keys(account.email()); }
    @Test void concurrentFailuresCapCounterAtFiveAndDoNotExtendBlock() throws Exception {
        var keys=keys();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(10)) {
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<10;i++) tasks.add(pool.submit(()->{gate.await();limiter.failure(keys);return null;}));
            gate.countDown(); for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }
        assertThat(redis.opsForZSet().zCard(keys.getFirst())).isEqualTo(5);
        long before=redis.getExpire(keys.get(1),TimeUnit.MILLISECONDS);
        limiter.failure(keys);
        assertThat(redis.getExpire(keys.get(1),TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(before);
        assertThatThrownBy(()->limiter.check(keys)).isInstanceOfSatisfying(BusinessException.class,
                e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }
    @Test void terminalSuccessOrderResetsEarlierFailuresButKeepsLaterFailures() {
        var keys=keys();limiter.failure(keys);
        var first=tokens();store.registerReplacing(account,first,"WEB",null,null);limiter.success(keys);
        assertThat(redis.hasKey(keys.getFirst())).isFalse();
        limiter.failure(keys);
        assertThat(redis.opsForZSet().zCard(keys.getFirst())).isEqualTo(1);
        store.registerReplacing(account,tokens(),"WEB",null,null);limiter.success(keys);
        assertThat(redis.hasKey(keys.getFirst())).isFalse();
    }
    @Test void competingSuccessResetAndFailureRespectBothForcedTerminalOrders() throws Exception {
        for(boolean successFirst:List.of(true,false)) {
            var keys=limiter.keys(UUID.randomUUID()+"@example.invalid");
            var committed=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var success=pool.submit(()->{
                    if(!successFirst)assertThat(committed.await(10,TimeUnit.SECONDS)).isTrue();
                    store.registerReplacingAndReset(account,tokens(),"WEB",null,null,keys);
                    if(successFirst)committed.countDown();return null;
                });
                var failure=pool.submit(()->{
                    if(successFirst)assertThat(committed.await(10,TimeUnit.SECONDS)).isTrue();
                    limiter.failure(keys);if(!successFirst)committed.countDown();return null;
                });
                success.get(15,TimeUnit.SECONDS);failure.get(15,TimeUnit.SECONDS);
            }
            assertThat(redis.opsForZSet().zCard(keys.getFirst())).isEqualTo(successFirst?1L:0L);
        }
    }
    @Test void parallelReplacementHasOneWinnerAndNeverTouchesAnotherDevice() throws Exception {
        var old=tokens();var other=tokens();store.register(account,old,"WEB");store.register(account,other,"ANDROID");
        String expected=redis.opsForValue().get(RedisLoginSessions.key(old.sessionId()));
        var a=tokens();var b=tokens();var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->replaceAfter(gate,a,old,expected));
            var second=pool.submit(()->replaceAfter(gate,b,old,expected));gate.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK","AUTH_CONTEXT_CONFLICT");
        }
        assertThat(redis.hasKey(RedisLoginSessions.key(old.sessionId()))).isFalse();
        assertThat(redis.hasKey(RedisLoginSessions.key(other.sessionId()))).isTrue();
        assertThat(List.of(a,b).stream().filter(t->Boolean.TRUE.equals(redis.hasKey(RedisLoginSessions.key(t.sessionId())))).count()).isEqualTo(1);
    }
    String replaceAfter(CountDownLatch gate,LoginTokens next,LoginTokens old,String expected) throws Exception {
        gate.await();try {store.registerReplacingAndReset(account,next,"WEB",old.sessionId(),expected,keys());return "OK";}
        catch(BusinessException e){return e.getErrorCode().name();}
    }
    @Test void newKeyCollisionPreservesOldSessionAndFailureHistory() {
        var old=tokens();var next=tokens();store.register(account,old,"WEB");store.register(account,next,"ANDROID");
        String oldKey=RedisLoginSessions.key(old.sessionId()), expected=redis.opsForValue().get(oldKey);
        limiter.failure(keys());
        assertThatThrownBy(()->store.registerReplacing(account,next,"WEB",old.sessionId(),expected))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(redis.opsForValue().get(oldKey)).isEqualTo(expected);
        assertThat(redis.opsForZSet().zCard(keys().getFirst())).isEqualTo(1);
    }
    @Test void staleProofCannotDeleteAChangedSessionOrResetFailures() {
        var old=tokens();store.register(account,old,"WEB");String key=RedisLoginSessions.key(old.sessionId());
        String expected=redis.opsForValue().get(key);redis.opsForValue().set(key,"different-owner");limiter.failure(keys());
        var next=tokens();
        assertThatThrownBy(()->store.registerReplacing(account,next,"WEB",old.sessionId(),expected))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_CONTEXT_CONFLICT));
        assertThat(redis.opsForValue().get(key)).isEqualTo("different-owner");
        assertThat(redis.hasKey(RedisLoginSessions.key(next.sessionId()))).isFalse();
        assertThat(redis.opsForZSet().zCard(keys().getFirst())).isEqualTo(1);
    }
    @Test void expiredFailuresLeaveTheSlidingWindowWithoutChangingTtlPolicy() {
        var key=keys().getFirst();redis.opsForZSet().add(key,"old",clock.millis()-600001);
        limiter.failure(keys());assertThat(redis.opsForZSet().range(key,0,-1)).doesNotContain("old");
        assertThat(redis.opsForZSet().zCard(key)).isEqualTo(1);
    }
    @Test void lostAcknowledgementFailsClosedAndCleansOnlyNewOwnedValueWithoutResurrectingOld() {
        var old=tokens();var next=tokens();store.register(account,old,"WEB");String oldKey=RedisLoginSessions.key(old.sessionId());
        String expected=redis.opsForValue().get(oldKey);limiter.failure(keys());
        StringRedisTemplate failing=spy(redis);
        doAnswer(invocation->{
            Object result=invocation.callRealMethod();
            List<?> scriptKeys=invocation.getArgument(1);
            if(scriptKeys.size()==2)throw new org.springframework.dao.DataAccessResourceFailureException("synthetic lost acknowledgement");
            return result;
        }).when(failing).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertThatThrownBy(()->new RedisLoginSessions(failing,clock)
                .registerReplacing(account,next,"WEB",old.sessionId(),expected))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(redis.hasKey(oldKey)).isFalse();
        assertThat(redis.hasKey(RedisLoginSessions.key(next.sessionId()))).isFalse();
        // Session result is unknown to caller: success reset must not have run.
        assertThat(redis.opsForZSet().zCard(keys().getFirst())).isEqualTo(1);
    }
    @Test void lostAckCleanupCannotDeleteAReplacementWrittenByAnotherOwner() {
        var next=tokens();String key=RedisLoginSessions.key(next.sessionId());limiter.failure(keys());
        StringRedisTemplate failing=spy(redis);
        doAnswer(invocation->{
            Object result=invocation.callRealMethod();List<?> scriptKeys=invocation.getArgument(1);
            if(scriptKeys.size()==2) {
                redis.opsForValue().set(key,"another-owner");
                throw new org.springframework.dao.DataAccessResourceFailureException("synthetic lost acknowledgement");
            }
            return result;
        }).when(failing).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertThatThrownBy(()->new RedisLoginSessions(failing,clock).registerReplacing(account,next,"WEB",null,null))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(redis.opsForValue().get(key)).isEqualTo("another-owner");
        assertThat(redis.opsForZSet().zCard(keys().getFirst())).isEqualTo(1);
    }
    @Test void atomicSuccessReplacesOnlyProvenSessionAndClearsBothFailureKeys() {
        var old=tokens();var other=tokens();var next=tokens();
        store.register(account,old,"WEB");store.register(account,other,"ANDROID");
        for(int i=0;i<5;i++)limiter.failure(keys());
        String expected=redis.opsForValue().get(RedisLoginSessions.key(old.sessionId()));
        store.registerReplacingAndReset(account,next,"WEB",old.sessionId(),expected,keys());
        assertThat(redis.hasKey(RedisLoginSessions.key(old.sessionId()))).isFalse();
        assertThat(redis.hasKey(RedisLoginSessions.key(next.sessionId()))).isTrue();
        assertThat(redis.hasKey(RedisLoginSessions.key(other.sessionId()))).isTrue();
        assertThat(redis.hasKey(keys().getFirst())).isFalse();assertThat(redis.hasKey(keys().get(1))).isFalse();
    }
    @Test void atomicCollisionAndStaleProofDoNotResetFailuresOrRevokeOldSession() {
        var old=tokens();var collision=tokens();store.register(account,old,"WEB");store.register(account,collision,"ANDROID");
        String oldKey=RedisLoginSessions.key(old.sessionId()),expected=redis.opsForValue().get(oldKey);limiter.failure(keys());
        assertThatThrownBy(()->store.registerReplacingAndReset(account,collision,"WEB",old.sessionId(),expected,keys()))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThatThrownBy(()->store.registerReplacingAndReset(account,tokens(),"WEB",old.sessionId(),"stale",keys()))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_CONTEXT_CONFLICT));
        assertThat(redis.opsForValue().get(oldKey)).isEqualTo(expected);
        assertThat(redis.opsForZSet().zCard(keys().getFirst())).isEqualTo(1);
    }
    @Test void atomicCommitLostReplyDoesNotRetryOrResurrectOldSessionAndResetMayAlreadyHaveCommitted() {
        var old=tokens();var next=tokens();var other=tokens();store.register(account,old,"WEB");store.register(account,other,"ANDROID");
        String oldKey=RedisLoginSessions.key(old.sessionId()),expected=redis.opsForValue().get(oldKey);limiter.failure(keys());
        var calls=new java.util.concurrent.atomic.AtomicInteger();StringRedisTemplate failing=spy(redis);
        doAnswer(invocation->{
            Object result=invocation.callRealMethod();List<?> scriptKeys=invocation.getArgument(1);
            if(scriptKeys.size()==4){calls.incrementAndGet();throw new org.springframework.dao.DataAccessResourceFailureException("synthetic reply loss");}
            return result;
        }).when(failing).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertThatThrownBy(()->new RedisLoginSessions(failing,clock).registerReplacingAndReset(account,next,"WEB",old.sessionId(),expected,keys()))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(calls.get()).isEqualTo(1);assertThat(redis.hasKey(oldKey)).isFalse();
        assertThat(redis.hasKey(RedisLoginSessions.key(next.sessionId()))).isFalse();
        assertThat(redis.hasKey(RedisLoginSessions.key(other.sessionId()))).isTrue();
        assertThat(redis.hasKey(keys().getFirst())).isFalse();
    }
    @Test void realRedisDeniedDeleteIsDetectedBeforeAnySessionMutation() {
        var old=tokens();var next=tokens();store.register(account,old,"WEB");limiter.failure(keys());
        String oldKey=RedisLoginSessions.key(old.sessionId()),expected=redis.opsForValue().get(oldKey);
        String user="fixture-"+UUID.randomUUID(),password=UUID.randomUUID().toString();
        redis.execute((RedisCallback<Object>)connection->connection.execute("ACL",bytes("SETUSER"),bytes(user),
                bytes("on"),bytes(">"+password),bytes("~*"),bytes("+@all"),bytes("-del")));
        var config=new org.springframework.data.redis.connection.RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379));
        config.setUsername(user);config.setPassword(password);var restricted=new LettuceConnectionFactory(config);
        try {
            restricted.afterPropertiesSet();restricted.start();var client=new StringRedisTemplate(restricted);client.afterPropertiesSet();
            assertThatThrownBy(()->new RedisLoginSessions(client,clock).registerReplacingAndReset(account,next,"WEB",old.sessionId(),expected,keys()))
                    .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
            assertThat(redis.opsForValue().get(oldKey)).isEqualTo(expected);
            assertThat(redis.hasKey(RedisLoginSessions.key(next.sessionId()))).isFalse();
            assertThat(redis.opsForZSet().zCard(keys().getFirst())).isEqualTo(1);
        } finally {
            restricted.destroy();redis.execute((RedisCallback<Object>)connection->connection.execute("ACL",bytes("SETUSER"),bytes(user),bytes("reset")));
        }
    }
    static byte[] bytes(String value){return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);}

}

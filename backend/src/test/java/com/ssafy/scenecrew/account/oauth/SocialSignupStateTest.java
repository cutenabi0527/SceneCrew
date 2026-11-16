package com.ssafy.scenecrew.account.oauth;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.*;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL 17 + Redis 8. Only provider proofs and boundary failures are synthetic. */
@SpringBootTest
@ActiveProfiles("test")
class SocialSignupStateTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    @BeforeAll static void start() {
        REDIS.start(); connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        connection.afterPropertiesSet(); connection.start(); redis=new StringRedisTemplate(connection);
    }
    @AfterAll static void stop(){if(connection!=null)connection.destroy();REDIS.stop();}
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    final Clock clock=Clock.systemUTC();
    RedisOAuthAttempts attempts;
    LoginTokenIssuer tokens;
    RedisLoginSessions sessions;
    SocialSignupService signup;
    OAuthLoginService login;
    final Map<String,String> providerCodes=new ConcurrentHashMap<>();
    final Set<String> subjects=ConcurrentHashMap.newKeySet();
    final List<UUID> localAccounts=new ArrayList<>();
    @BeforeEach void setup() {
        attempts=spy(new RedisOAuthAttempts(redis,clock));
        tokens=spy(new LoginTokenIssuer(new SecretKeySpec(new byte[32],"HmacSHA256"),clock,"test","test"));
        sessions=spy(new RedisLoginSessions(redis,clock)); signup=service(new SocialSignupAccounts(jdbc,manager));
        var clients=new ArrayList<OAuthProviderClient>();
        for(var p:SocialProvider.values())clients.add(new OAuthProviderClient(){
            public SocialProvider provider(){return p;}
            public URI authorizationUrl(String state,String verifier){return URI.create("https://provider.example.invalid/?state="+state);}
            public String verifiedSubject(String code,String state,String verifier){return providerCodes.get(code);}
        });
        login=new OAuthLoginService(clients,Map.of(channel()+":HOME",URI.create("https://web.example.invalid/callback")),
                attempts,new OAuthAccountReader(jdbc,manager),tokens,sessions,clock,Duration.ofSeconds(90));
    }
    SocialSignupService service(SocialSignupAccounts writer){
        // Arbitrary isolated test TTL; not an approved public lifetime.
        return new SocialSignupService(attempts,writer,tokens,sessions,clock,Duration.ofSeconds(83));
    }
    @AfterEach void clean(){
        for(var subject:subjects){
            var ids=jdbc.queryForList("SELECT account_id FROM auth_identity WHERE subject=?",UUID.class,subject);
            jdbc.update("DELETE FROM auth_identity WHERE subject=?",subject);
            for(var id:ids)jdbc.update("DELETE FROM account WHERE id=?",id);
        }
        for(var id:localAccounts){jdbc.update("DELETE FROM auth_identity WHERE account_id=?",id);jdbc.update("DELETE FROM account WHERE id=?",id);}
        var keys=redis.keys("auth:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);login.close();
    }
    String callback(SocialProvider provider,String subject,String proof){
        subjects.add(subject);
        String state=login.authorize(provider,"LOGIN",channel(),"HOME",proof).getQuery().substring(6);
        String code=UUID.randomUUID().toString();providerCodes.put(code,subject);
        return login.callback(provider,state,code,null,proof).getQuery().substring(7);
    }
    Pending pending(SocialProvider provider,String subject){
        String proof=attempts.secret(),ticket=callback(provider,subject,proof);
        var r=(OAuthLoginService.OAuthPending)continueLogin(ticket,proof);
        assertThat(r.signupCompleted()).isFalse();assertThat(redis.hasKey(RedisOAuthAttempts.key("ticket",ticket))).isFalse();
        return new Pending(r.signupTicket(),proof);
    }
    String channel(){return "WEB";}
    String otherChannel(){return "WEB".equals(channel())?"ANDROID":"WEB";}
    OAuthLoginService.Continuation continueLogin(String ticket,String proof){
        return login.continueLogin(ticket,channel(),proof,signup);
    }
    EmailLoginService.Result complete(String ticket,String proof,String name,SocialSignupService.Consent consent){
        return signup.complete(ticket,channel(),proof,name,consent);
    }
    Pending pending(){return pending(SocialProvider.GOOGLE,UUID.randomUUID().toString());}
    EmailLoginService.Result complete(Pending p){return complete(p.ticket(),p.proof(),"  가입자 😀  ",consent());}
    static SocialSignupService.Consent consent(){return new SocialSignupService.Consent(true,false,false,false);}
    static void error(Runnable action,ErrorCode code){
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));
    }
    long countAccounts(){return jdbc.queryForObject("SELECT count(*) FROM account",Long.class);}
    @ParameterizedTest @EnumSource(SocialProvider.class)
    void emailFreeSignupAndExistingLinkedLogin(SocialProvider provider){
        String subject=UUID.randomUUID().toString();var p=pending(provider,subject);
        assertThat(redis.keys("auth:session:*")).isEmpty();
        var saved=attempts.read("signup",p.ticket(),ErrorCode.UNAUTHENTICATED).attempt();
        assertThat(saved.purpose()).isEqualTo("COMPLETE_SIGNUP");assertThat(saved.subject()).isEqualTo(subject);
        assertThat(saved.initiatorHash()).isEqualTo(RedisOAuthAttempts.hash(p.proof()));assertThat(saved.codeVerifier()).isNull();
        assertThat(redis.getExpire(RedisOAuthAttempts.key("signup",p.ticket()))).isBetween(75L,83L);
        var requestedConsent=new SocialSignupService.Consent(true,provider==SocialProvider.GOOGLE,
                provider==SocialProvider.KAKAO,provider==SocialProvider.NAVER);
        Instant beforeSignup=clock.instant();
        var r=complete(p.ticket(),p.proof(),"  가입자 😀  ",requestedConsent);
        Instant afterSignup=clock.instant();
        assertThat(r.account().email()).isNull();assertThat(r.account().displayName()).isEqualTo("가입자 😀");
        assertThat(jdbc.queryForMap("SELECT * FROM account WHERE id=?",r.account().accountId()))
                .containsEntry("status","ACTIVE").containsEntry("signup_completed",true);
        assertThat(jdbc.queryForMap("SELECT * FROM auth_identity WHERE id=?",r.account().identityId()))
                .containsEntry("email",null).containsEntry("password_hash",null).containsEntry("email_verified",false);
        assertThat(jdbc.queryForObject("SELECT consent->>'service' FROM account WHERE id=?",String.class,r.account().accountId())).isEqualTo("true");
        var storedConsent=JsonMapper.builder().build().readTree(jdbc.queryForObject(
                "SELECT consent::text FROM account WHERE id=?",String.class,r.account().accountId()));
        assertThat(ConsentRecords.VERSION)
                .isEqualTo("2026-11-06");
        assertThat(storedConsent.get("version").asString()).isEqualTo(ConsentRecords.VERSION);
        var history=storedConsent.get("history");
        assertThat(history.isArray()).isTrue();assertThat(history.size()).isEqualTo(1);
        var initial=history.get(0);
        assertThat(initial.get("version").asString()).isEqualTo(ConsentRecords.VERSION);
        assertThat(initial.get("source").asString()).isEqualTo("SIGNUP");
        assertThat(Instant.parse(initial.get("at").asString()))
                .isBetween(beforeSignup.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),afterSignup);
        var expectedConsent=Map.of("service",true,"voice",requestedConsent.voice(),
                "push",requestedConsent.push(),"externalAi",requestedConsent.externalAi());
        assertThat(initial.get("values").propertyNames()).containsExactlyInAnyOrderElementsOf(expectedConsent.keySet());
        expectedConsent.forEach((key,value)->{
            assertThat(storedConsent.get(key).isBoolean()).isTrue();
            assertThat(storedConsent.get(key).asBoolean()).isEqualTo(value);
            assertThat(initial.get("values").get(key)).isEqualTo(storedConsent.get(key));
        });
        assertThat(redis.opsForValue().get(RedisLoginSessions.key(r.tokens().sessionId()))).endsWith("|"+provider);
        error(()->complete(p),ErrorCode.UNAUTHENTICATED);
        String proof=attempts.secret(),t=callback(provider,subject,proof);
        var linked=(OAuthLoginService.Authenticated)continueLogin(t,proof);
        assertThat(linked.result().account().accountId()).isEqualTo(r.account().accountId());
        assertThat(redis.keys("auth:session:*")).hasSize(2);
    }
    @Test void nameAndServiceConsentValidatedBeforeConsumption(){
        var p=pending();
        long accountsBefore=countAccounts();
        long identitiesBefore=jdbc.queryForObject("SELECT count(*) FROM auth_identity",Long.class);
        for(String name:List.of(" ","x".repeat(21),"a\nb","a\tb","a\u2028b"))
            error(()->complete(p.ticket(),p.proof(),name,consent()),ErrorCode.INVALID_REQUEST);
        error(()->complete(p.ticket(),p.proof(),null,consent()),ErrorCode.INVALID_REQUEST);
        error(()->complete(p.ticket(),p.proof(),"이름",null),ErrorCode.INVALID_REQUEST);
        error(()->complete(p.ticket(),p.proof(),"이름",new SocialSignupService.Consent(false,true,true,true)),ErrorCode.INVALID_REQUEST);
        assertThat(countAccounts()).isEqualTo(accountsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity",Long.class)).isEqualTo(identitiesBefore);
        assertThat(redis.keys("auth:session:*")).isEmpty();
        var r=complete(p.ticket(),p.proof(),"😀".repeat(20),consent());
        assertThat(r.account().displayName().codePointCount(0,r.account().displayName().length())).isEqualTo(20);
    }
    @Test void forgedExpiredWrongBrowserChannelAndPurposeRejected(){
        var p=pending();
        error(()->complete(new Pending(attempts.secret(),p.proof())),ErrorCode.UNAUTHENTICATED);
        error(()->complete(new Pending("forged",p.proof())),ErrorCode.UNAUTHENTICATED);
        error(()->complete(new Pending(p.ticket(),attempts.secret())),ErrorCode.UNAUTHENTICATED);
        error(()->signup.complete(p.ticket(),otherChannel(),p.proof(),"이름",consent()),ErrorCode.UNAUTHENTICATED);
        error(()->login.exchange(p.ticket(),"LOGIN",channel(),p.proof()),ErrorCode.UNAUTHENTICATED);
        String t=callback(SocialProvider.NAVER,UUID.randomUUID().toString(),p.proof());
        error(()->complete(t,p.proof(),"이름",consent()),ErrorCode.UNAUTHENTICATED);
        var a=attempts.read("signup",p.ticket(),ErrorCode.UNAUTHENTICATED).attempt();
        String wrong=attempts.save("signup",new RedisOAuthAttempts.Attempt(a.provider(),"LOGIN",a.channel(),a.attemptId(),
                a.initiatorHash(),a.returnUri(),clock.instant().plusSeconds(10),a.subject(),null),Duration.ofSeconds(10));
        error(()->complete(new Pending(wrong,p.proof())),ErrorCode.UNAUTHENTICATED);
        String key=RedisOAuthAttempts.key("signup",p.ticket());redis.expire(key,Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->!Boolean.TRUE.equals(redis.hasKey(key)));
        error(()->complete(p),ErrorCode.UNAUTHENTICATED);verify(tokens,never()).issue(any());
    }
    @Test void concurrentSameTicketHasOneWinner(){
        var p=pending();long before=countAccounts();var results=concurrently(()->complete(p),()->complete(p));
        assertThat(results.stream().filter(EmailLoginService.Result.class::isInstance).count()).isEqualTo(1);
        assertThat(countAccounts()).isEqualTo(before+1);assertThat(redis.keys("auth:session:*")).hasSize(1);
        verify(tokens,times(1)).issue(any());
    }
    @Test void differentTicketsSameSubjectRaceRollsBackLosingAccount(){
        String subject=UUID.randomUUID().toString();var p=pending(SocialProvider.GOOGLE,subject);var q=pending(SocialProvider.GOOGLE,subject);
        var barrier=new CyclicBarrier(2);var raceJdbc=spy(jdbc);
        doAnswer(i->{if(((String)i.getArgument(0)).contains("INSERT INTO auth_identity"))barrier.await(10,TimeUnit.SECONDS);
            return i.callRealMethod();}).when(raceJdbc).update(anyString(),any(Object[].class));
        signup=service(new SocialSignupAccounts(raceJdbc,manager));
        long before=countAccounts();var results=concurrently(()->complete(p),()->complete(q));
        assertThat(results.stream().filter(EmailLoginService.Result.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(BusinessException.class::isInstance).map(BusinessException.class::cast)
                .map(BusinessException::getErrorCode)).containsExactly(ErrorCode.AUTH_CONTEXT_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE subject=?",Long.class,subject)).isEqualTo(1);
        assertThat(countAccounts()).isEqualTo(before+1);verify(tokens,times(1)).issue(any());
    }
    @Test void existingOrDisabledIdentityDoesNotBecomeSignup(){
        String subject=UUID.randomUUID().toString();var p=pending(SocialProvider.GOOGLE,subject);var q=pending(SocialProvider.GOOGLE,subject);
        var r=complete(p);error(()->complete(q),ErrorCode.AUTH_CONTEXT_CONFLICT);
        jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",r.account().accountId());
        String proof=attempts.secret(),t=callback(SocialProvider.GOOGLE,subject,proof);
        error(()->continueLogin(t,proof),ErrorCode.UNAUTHENTICATED);
        assertThat(redis.keys("auth:session:*")).hasSize(1);
    }
    @Test void localEmailNeverCausesAutomaticMerge(){
        long before=countAccounts();
        for(String email:List.of("same@example.invalid","different@example.invalid")){
            var id=UUID.randomUUID();localAccounts.add(id);
            jdbc.update("INSERT INTO account VALUES (?,'기존','ACTIVE',true,0,'{}'::jsonb,now())",id);
            jdbc.update("INSERT INTO auth_identity VALUES (?,?,'LOCAL',?,'synthetic-hash',?,true)",UUID.randomUUID(),id,id.toString(),email);
        }
        var a=complete(pending());var b=complete(pending());
        assertThat(localAccounts).doesNotContain(a.account().accountId(),b.account().accountId());
        assertThat(a.account().accountId()).isNotEqualTo(b.account().accountId());
        assertThat(countAccounts()).isEqualTo(before+4);assertThat(a.account().email()).isNull();assertThat(b.account().email()).isNull();
    }
    @Test void rollbackAndUnknownCommitCannotRetryOrIssueSession(){
        for(boolean committed:List.of(false,true)){
            var p=pending();long before=countAccounts();
            String subject=attempts.read("signup",p.ticket(),ErrorCode.UNAUTHENTICATED).attempt().subject();
            var faulty=new PlatformTransactionManager(){
                public TransactionStatus getTransaction(TransactionDefinition d){return manager.getTransaction(d);}
                public void rollback(TransactionStatus s){manager.rollback(s);}
                public void commit(TransactionStatus s){if(committed)manager.commit(s);else manager.rollback(s);
                    throw new TransactionSystemException("synthetic acknowledgement failure");}
            };
            signup=service(new SocialSignupAccounts(jdbc,faulty));
            error(()->complete(p),ErrorCode.SERVICE_UNAVAILABLE);assertThat(countAccounts()).isEqualTo(before+(committed?1:0));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE provider='GOOGLE' AND subject=?",
                    Long.class,subject)).isEqualTo(committed?1L:0L);
            error(()->complete(p),ErrorCode.UNAUTHENTICATED);assertThat(redis.keys("auth:session:*")).isEmpty();
        }
        verify(tokens,never()).issue(any());
    }
    @Test void consumeFailureBeforeOrAfterEffectCannotWriteDatabase(){
        var p=pending();long before=countAccounts();
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(attempts).consume(any(),any());
        error(()->complete(p),ErrorCode.SERVICE_UNAVAILABLE);assertThat(countAccounts()).isEqualTo(before);
        doAnswer(i->{i.callRealMethod();throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);}).when(attempts).consume(any(),any());
        error(()->complete(p),ErrorCode.SERVICE_UNAVAILABLE);assertThat(countAccounts()).isEqualTo(before);
        error(()->complete(p),ErrorCode.UNAUTHENTICATED);verify(tokens,never()).issue(any());
    }
    @Test void sessionAcknowledgementLossAndResponseLossCannotDuplicateEffects(){
        var healthy=complete(pending());var keys=new HashSet<>(redis.keys("auth:session:*"));
        var p=pending();long before=countAccounts();
        doAnswer(i->{i.callRealMethod();throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);})
                .when(sessions).registerConsumingOAuthTicket(any(),any(),anyString(),anyString(),anyString(),anyString());
        error(()->complete(p),ErrorCode.SERVICE_UNAVAILABLE);assertThat(countAccounts()).isEqualTo(before+1);
        assertThat(redis.keys("auth:session:*")).hasSize(2).containsAll(keys);
        error(()->complete(p),ErrorCode.UNAUTHENTICATED);assertThat(redis.keys("auth:session:*")).hasSize(2);
        assertThat(redis.hasKey(RedisLoginSessions.key(healthy.tokens().sessionId()))).isTrue();
    }
    @Test void sessionFailureBeforeEffectPreservesOldSessionAndNewOAuthRecoversCommittedAccount(){
        var healthy=complete(pending());
        String subject=UUID.randomUUID().toString();
        var p=pending(SocialProvider.GOOGLE,subject);
        long before=countAccounts();
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).doCallRealMethod().when(sessions)
                .registerConsumingOAuthTicket(any(),any(),anyString(),anyString(),anyString(),anyString());
        error(()->complete(p),ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(countAccounts()).isEqualTo(before+1);assertThat(redis.keys("auth:session:*")).hasSize(1);
        error(()->complete(p),ErrorCode.UNAUTHENTICATED);
        assertThat(redis.hasKey(RedisLoginSessions.key(healthy.tokens().sessionId()))).isTrue();

        UUID committedAccountId=jdbc.queryForObject(
                "SELECT account_id FROM auth_identity WHERE provider='GOOGLE' AND subject=?",UUID.class,subject);
        String proof=attempts.secret();
        String ticket=callback(SocialProvider.GOOGLE,subject,proof);
        assertThat(continueLogin(ticket,proof))
                .isInstanceOfSatisfying(OAuthLoginService.Authenticated.class,recovered->{
                    assertThat(recovered.result().account().accountId()).isEqualTo(committedAccountId);
                    assertThat(redis.hasKey(RedisLoginSessions.key(recovered.result().tokens().sessionId()))).isTrue();
                });
        assertThat(countAccounts()).isEqualTo(before+1);
        assertThat(redis.keys("auth:session:*")).hasSize(2).contains(RedisLoginSessions.key(healthy.tokens().sessionId()));
    }
    @Test void lostSignupResponseCannotReplayCredentialsAndNewOAuthUsesSameAccount(){
        String subject=UUID.randomUUID().toString();
        var p=pending(SocialProvider.GOOGLE,subject);
        long before=countAccounts();
        var completed=complete(p); // The client never receives this result.
        error(()->complete(p),ErrorCode.UNAUTHENTICATED);
        assertThat(countAccounts()).isEqualTo(before+1);assertThat(redis.keys("auth:session:*")).hasSize(1);
        verify(tokens,times(1)).issue(any());

        String proof=attempts.secret();
        String ticket=callback(SocialProvider.GOOGLE,subject,proof);
        assertThat(continueLogin(ticket,proof))
                .isInstanceOfSatisfying(OAuthLoginService.Authenticated.class,recovered->{
                    assertThat(recovered.result().account().accountId()).isEqualTo(completed.account().accountId());
                    assertThat(recovered.result().tokens().sessionId()).isNotEqualTo(completed.tokens().sessionId());
                    assertThat(redis.hasKey(RedisLoginSessions.key(recovered.result().tokens().sessionId()))).isTrue();
                });
        assertThat(countAccounts()).isEqualTo(before+1);
        assertThat(redis.keys("auth:session:*")).hasSize(2);
    }
    @Test void pendingTransitionAcknowledgementLossCannotCreateMoreTickets(){
        String proof=attempts.secret(),t=callback(SocialProvider.GOOGLE,UUID.randomUUID().toString(),proof);
        doAnswer(i->{i.callRealMethod();throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);}).when(attempts).pending(any(),any());
        error(()->continueLogin(t,proof),ErrorCode.SERVICE_UNAVAILABLE);
        error(()->continueLogin(t,proof),ErrorCode.UNAUTHENTICATED);
        assertThat(redis.keys("auth:oauth:signup:*")).hasSize(1);assertThat(redis.keys("auth:session:*")).isEmpty();
    }
    static List<Object> concurrently(Callable<?> a,Callable<?> b){
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            Callable<Object> x=()->{start.await();try{return a.call();}catch(BusinessException e){return e;}};
            Callable<Object> y=()->{start.await();try{return b.call();}catch(BusinessException e){return e;}};
            var first=executor.submit(x);var second=executor.submit(y);start.countDown();
            return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        }catch(Exception e){throw new AssertionError(e);}
    }
    record Pending(String ticket,String proof){@Override public String toString(){return "Pending[redacted]";}}
}

package com.ssafy.scenecrew.account.oauth;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;

import org.testcontainers.containers.GenericContainer;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.account.service.SessionAuthenticator.Principal;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static com.ssafy.scenecrew.account.oauth.IdentityChangeException.Reason.*;

/** PostgreSQL/Redis are real and isolated. Only the unapproved reauth adapter and provider are test doubles.
 * These are internal service tests, not public LINK/UNLINK HTTP acceptance.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class IdentityChangesIntegrationTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start(); r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.context.ApplicationContext context;
    final Clock clock=Clock.systemUTC();
    final List<UUID> ids=new ArrayList<>();
    IdentityChanges changes;
    SessionAuthenticator auth;
    LoginTokenIssuer issuer;
    RedisOAuthAttempts attempts;
    RedisLoginSessions sessions;
    Principal principal;
    LoginTokens local;
    AtomicInteger providerCalls;
    OAuthIdentityChanges flow;
    // Synthetic boundary double; it proves rejection/propagation, not a real freshness or proof issuer.
    final IdentityReauthentication reauth=reusable((p,op,provider,proof)->{
        if (!Objects.equals(proof,"synthetic:"+p.sessionId()+":"+op+":"+provider))
            throw new IdentityChangeException(REAUTHENTICATION_REQUIRED);
    });
    @FunctionalInterface interface ProofCheck {
        void require(Principal p,IdentityReauthentication.Operation op,SocialProvider provider,String proof);
    }
    // Deliberately reusable boundary double. Real one-time consumption is tested in OAuthWebHttpTest.
    static IdentityReauthentication reusable(ProofCheck check) {
        return new IdentityReauthentication() {
            public void require(Principal p,Operation o,SocialProvider target,String proof) { check.require(p,o,target,proof); }
            public void consume(Principal p,Operation o,SocialProvider target,String proof) { check.require(p,o,target,proof); }
        };
    }
    @BeforeEach void setup() {
        var key=new SecretKeySpec(new byte[32],"HmacSHA256");
        issuer=new LoginTokenIssuer(key,clock,"identity-test","identity-test");
        auth=new SessionAuthenticator(new AccessJwtVerifier(key,clock,"identity-test","identity-test"),redis,new CurrentAccountReader(jdbc,manager),clock);
        attempts=new RedisOAuthAttempts(redis,clock); sessions=new RedisLoginSessions(redis,clock);
        changes=new IdentityChanges(jdbc,manager,reauth);
        UUID account=account(); UUID identity=identity(account,"LOCAL",account.toString(),true);
        local=login(account,identity,"LOCAL"); principal=auth.authenticate(local.accessToken());
        providerCalls=new AtomicInteger();
        flow=flow(changes,reauth);
    }
    @AfterEach void cleanup() {
        for (UUID id:ids) { jdbc.update("DELETE FROM auth_identity WHERE account_id=?",id); jdbc.update("DELETE FROM account WHERE id=?",id); }
        var keys=redis.keys("auth:*"); if (keys!=null && !keys.isEmpty()) redis.delete(keys); // exclusively owned container
    }
    OAuthIdentityChanges flow(IdentityChanges store,IdentityReauthentication check) {
        List<OAuthProviderClient> clients=new ArrayList<>();
        for (var p:SocialProvider.values()) clients.add(new OAuthProviderClient() {
            public SocialProvider provider() { return p; }
            public URI authorizationUrl(String state,String verifier) {
                return URI.create("https://provider.example.invalid/?state="+state+"&challenge="+HttpOAuthProviderClient.challenge(verifier));
            }
            public String verifiedSubject(String code,String state,String verifier) {
                providerCalls.incrementAndGet();
                if (code.equals("logout-during-provider")) redis.delete(RedisLoginSessions.key(local.sessionId()));
                if (code.equals("fail")) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
                if (code.equals("blank")) return " ";
                return code;
            }
        });
        return new OAuthIdentityChanges(auth,store,check,attempts,clients,clock,Duration.ofSeconds(300));
    }
    UUID account() {
        UUID id=UUID.randomUUID(); ids.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 3, '{}'::jsonb, now())",id); return id;
    }
    UUID identity(UUID account,String provider,String subject,boolean verified) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, ?, ?, ?, ?, ?)",id,account,provider,subject,
                provider.equals("LOCAL")?"synthetic-hash":null,provider.equals("LOCAL")?account+"@example.invalid":null,verified);
        return id;
    }
    LoginTokens login(UUID account,UUID identity,String provider) {
        var verified=new LocalCredentialVerifier.VerifiedAccount(account,"fixture",null,3,identity);
        var tokens=issuer.issue(verified);
        if (provider.equals("LOCAL")) sessions.register(verified,tokens,"WEB");
        else {
            String ticket=attempts.save("ticket",new RedisOAuthAttempts.Attempt(SocialProvider.valueOf(provider),"LOGIN","WEB",
                    UUID.randomUUID().toString(),"synthetic","",clock.instant().plusSeconds(60),"subject",null),Duration.ofSeconds(60));
            var saved=attempts.read("ticket",ticket,ErrorCode.INVALID_REQUEST);
            sessions.registerConsumingOAuthTicket(verified,tokens,"WEB",provider,saved.key(),saved.value());
        }
        return tokens;
    }
    String proof(Principal p,IdentityReauthentication.Operation op,SocialProvider provider) {
        return "synthetic:"+p.sessionId()+":"+op+":"+provider;
    }
    String linkProof(SocialProvider p) { return proof(principal,IdentityReauthentication.Operation.LINK_IDENTITY,p); }
    String unlinkProof(SocialProvider p) { return proof(principal,IdentityReauthentication.Operation.UNLINK_IDENTITY,p); }
    String start(SocialProvider provider) {
        return flow.authorize(local.accessToken(),linkProof(provider),provider,"A".repeat(43)).getQuery().split("&")[0].substring(6);
    }
    UUID complete(SocialProvider provider,String state,String code) {
        return flow.complete(local.accessToken(),linkProof(provider),provider,state,code,"A".repeat(43));
    }
    void reason(ThrowingCallable work,IdentityChangeException.Reason reason) {
        assertThatThrownBy(work).isInstanceOfSatisfying(IdentityChangeException.class,e->assertThat(e.reason()).isEqualTo(reason));
    }
    void denied(ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
    @ParameterizedTest @EnumSource(SocialProvider.class)
    void connectsVerifiedSubjectWithoutEmailAndCanLogin(SocialProvider provider) {
        String subject=UUID.randomUUID().toString();
        UUID identity=complete(provider,start(provider),subject);
        assertThat(new OAuthAccountReader(jdbc,manager).linked(provider,subject).identityId()).isEqualTo(identity);
        assertThat(jdbc.queryForObject("SELECT email FROM auth_identity WHERE id=?",String.class,identity)).isNull();
        assertThat(auth.authenticate(login(principal.accountId(),identity,provider.name()).accessToken()).identityId()).isEqualTo(identity);
        assertThat(auth.authenticate(local.accessToken()).identityId()).isEqualTo(principal.identityId());
    }
    @Test void sameProviderAndOtherAccountSubjectAreRejected() {
        changes.link(principal,SocialProvider.GOOGLE,"first",linkProof(SocialProvider.GOOGLE));
        reason(()->changes.link(principal,SocialProvider.GOOGLE,"second",linkProof(SocialProvider.GOOGLE)),PROVIDER_ALREADY_LINKED);
        identity(account(),"NAVER","owned",false);
        reason(()->complete(SocialProvider.NAVER,start(SocialProvider.NAVER),"owned"),IDENTITY_ALREADY_LINKED);
        assertThat(count(principal.accountId())).isEqualTo(2);
    }
    @Test void defaultAdapterAndMissingWrongPurposeProofNeverMutate() {
        var unavailable=new IdentityChanges(jdbc,manager);
        reason(()->unavailable.link(principal,SocialProvider.GOOGLE,"subject","anything"),REAUTHENTICATION_REQUIRED);
        reason(()->unavailable.unlink(principal,SocialProvider.GOOGLE,"anything"),REAUTHENTICATION_REQUIRED);
        reason(()->flow.authorize(local.accessToken(),null,SocialProvider.GOOGLE,"A".repeat(43)),REAUTHENTICATION_REQUIRED);
        reason(()->flow.authorize(local.accessToken(),unlinkProof(SocialProvider.GOOGLE),SocialProvider.GOOGLE,"A".repeat(43)),REAUTHENTICATION_REQUIRED);
        reason(()->flow.unlink(local.accessToken(),null,SocialProvider.GOOGLE),REAUTHENTICATION_REQUIRED);
        assertThat(count(principal.accountId())).isEqualTo(1); assertThat(providerCalls.get()).isZero();
    }
    @Test void lastUsableIdentityCannotBeRemovedIncludingUnverifiedLocal() {
        UUID id=account(); UUID google=identity(id,"GOOGLE","only",false); identity(id,"LOCAL",id.toString(),false);
        var p=auth.authenticate(login(id,google,"GOOGLE").accessToken());
        reason(()->changes.unlink(p,SocialProvider.GOOGLE,proof(p,IdentityReauthentication.Operation.UNLINK_IDENTITY,SocialProvider.GOOGLE)),LAST_IDENTITY);
        assertThat(count(id)).isEqualTo(2);
    }
    @Test void unlinkRejectsAllTargetSessionsAndNewLoginButPreservesOtherProvidersAndRelinkDoesNotRevive() {
        UUID google=changes.link(principal,SocialProvider.GOOGLE,"subject",linkProof(SocialProvider.GOOGLE));
        UUID kakao=changes.link(principal,SocialProvider.KAKAO,"42",linkProof(SocialProvider.KAKAO));
        var first=login(principal.accountId(),google,"GOOGLE"); var second=login(principal.accountId(),google,"GOOGLE");
        var other=login(principal.accountId(),kakao,"KAKAO");
        flow.unlink(local.accessToken(),unlinkProof(SocialProvider.GOOGLE),SocialProvider.GOOGLE);
        denied(()->auth.authenticate(first.accessToken())); denied(()->auth.authenticate(second.accessToken()));
        denied(()->new OAuthAccountReader(jdbc,manager).linked(SocialProvider.GOOGLE,"subject"));
        assertThat(redis.hasKey(RedisLoginSessions.key(first.sessionId()))).isTrue(); // logically revoked, TTL cleanup
        assertThat(auth.authenticate(local.accessToken()).accountId()).isEqualTo(principal.accountId());
        assertThat(auth.authenticate(other.accessToken()).identityId()).isEqualTo(kakao);
        UUID replacement=changes.link(principal,SocialProvider.GOOGLE,"subject",linkProof(SocialProvider.GOOGLE));
        assertThat(replacement).isNotEqualTo(google); denied(()->auth.authenticate(first.accessToken()));
        assertThat(jdbc.queryForObject("SELECT token_version FROM account WHERE id=?",Long.class,principal.accountId())).isEqualTo(3);
    }
    @Test void mayUnlinkCurrentSocialSessionWhenAnotherUsableIdentityRemains() {
        UUID google=changes.link(principal,SocialProvider.GOOGLE,"self",linkProof(SocialProvider.GOOGLE));
        var social=login(principal.accountId(),google,"GOOGLE"); var p=auth.authenticate(social.accessToken());
        flow.unlink(social.accessToken(),proof(p,IdentityReauthentication.Operation.UNLINK_IDENTITY,SocialProvider.GOOGLE),SocialProvider.GOOGLE);
        denied(()->auth.authenticate(social.accessToken())); assertThat(auth.authenticate(local.accessToken())).isNotNull();
    }
    @Test void anotherAccountsIdentityCannotBeUnlinkedAndInactiveOrStalePrincipalCannotWrite() {
        UUID other=account(); identity(other,"GOOGLE","owned",false);
        reason(()->flow.unlink(local.accessToken(),unlinkProof(SocialProvider.GOOGLE),SocialProvider.GOOGLE),TARGET_NOT_FOUND);
        assertThat(count(other)).isEqualTo(1);
        jdbc.update("UPDATE account SET token_version=4 WHERE id=?",principal.accountId());
        denied(()->changes.link(principal,SocialProvider.GOOGLE,"new",linkProof(SocialProvider.GOOGLE)));
        denied(()->flow.unlink(local.accessToken(),unlinkProof(SocialProvider.GOOGLE),SocialProvider.GOOGLE));
        jdbc.update("UPDATE account SET token_version=3,status='DISABLED' WHERE id=?",principal.accountId());
        denied(()->changes.link(principal,SocialProvider.GOOGLE,"new",linkProof(SocialProvider.GOOGLE)));
    }
    @Test void linkStateRejectsLoginPurposeWrongProviderBrowserSessionAndReplay() {
        String state=start(SocialProvider.GOOGLE);
        assertThatThrownBy(()->complete(SocialProvider.KAKAO,state,"subject")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->flow.complete(local.accessToken(),linkProof(SocialProvider.GOOGLE),SocialProvider.GOOGLE,state,"subject","B".repeat(43)))
                .isInstanceOf(BusinessException.class);
        var second=login(principal.accountId(),principal.identityId(),"LOCAL"); var p=auth.authenticate(second.accessToken());
        assertThatThrownBy(()->flow.complete(second.accessToken(),proof(p,IdentityReauthentication.Operation.LINK_IDENTITY,SocialProvider.GOOGLE),SocialProvider.GOOGLE,state,"subject","A".repeat(43)))
                .isInstanceOf(BusinessException.class);
        String loginState=attempts.save("state",new RedisOAuthAttempts.Attempt(SocialProvider.GOOGLE,"LOGIN","WEB",principal.sessionId().toString(),"", "",
                clock.instant().plusSeconds(60),null,"A".repeat(43)),Duration.ofSeconds(60));
        assertThatThrownBy(()->complete(SocialProvider.GOOGLE,loginState,"subject")).isInstanceOf(BusinessException.class);
        complete(SocialProvider.GOOGLE,state,"subject");
        assertThatThrownBy(()->complete(SocialProvider.GOOGLE,state,"subject")).isInstanceOf(BusinessException.class);
        assertThat(providerCalls.get()).isEqualTo(1);
    }
    @Test void providerFailureOrInvalidSubjectConsumesStateWithoutChangingDatabase() {
        for (String code:List.of("fail","blank")) {
            String state=start(SocialProvider.GOOGLE);
            assertThatThrownBy(()->complete(SocialProvider.GOOGLE,state,code)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->complete(SocialProvider.GOOGLE,state,code)).isInstanceOf(BusinessException.class);
        }
        assertThat(count(principal.accountId())).isEqualTo(1); assertThat(auth.authenticate(local.accessToken())).isNotNull();
    }
    @Test void revokedSessionOrProofAtCompletionNeverCallsProvider() {
        String state=start(SocialProvider.GOOGLE);
        reason(()->flow.complete(local.accessToken(),"expired",SocialProvider.GOOGLE,state,"subject","A".repeat(43)),REAUTHENTICATION_REQUIRED);
        redis.delete(RedisLoginSessions.key(local.sessionId()));
        denied(()->complete(SocialProvider.GOOGLE,state,"subject")); assertThat(providerCalls.get()).isZero();
    }
    @Test void rollbackAfterInsertLeavesNoIdentity() {
        var failing=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public int update(String sql,Object... args) {
                int result=super.update(sql,args);
                if (sql.startsWith("INSERT INTO auth_identity")) throw new DataRetrievalFailureException("synthetic failure after insert");
                return result;
            }
        };
        assertThatThrownBy(()->new IdentityChanges(failing,manager,reauth).link(principal,SocialProvider.GOOGLE,"rollback",linkProof(SocialProvider.GOOGLE)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(count(principal.accountId())).isEqualTo(1);
    }
    @Test void internalServicesAreNotAutomaticallyRegistered() {
        assertThat(context.getBeansOfType(IdentityChanges.class)).isEmpty();
        assertThat(context.getBeansOfType(OAuthIdentityChanges.class)).isEmpty();
        assertThat(context.getBeansOfType(IdentityReauthentication.class)).isEmpty();
    }
    @Test void sessionRevocationDuringExternalExchangePreventsDatabaseWrite() {
        String state=start(SocialProvider.GOOGLE);
        denied(()->complete(SocialProvider.GOOGLE,state,"logout-during-provider"));
        assertThat(providerCalls.get()).isEqualTo(1); assertThat(count(principal.accountId())).isEqualTo(1);
    }
    @Test void reauthenticationIsRecheckedAtCommitBoundaryAndFailureKeepsIdentity() {
        var calls=new AtomicInteger();
        IdentityReauthentication expiring=reusable((p,op,provider,proof)->{
            reauth.require(p,op,provider,proof);
            if (calls.incrementAndGet()==3) throw new IdentityChangeException(REAUTHENTICATION_REQUIRED);
        });
        var candidate=flow(new IdentityChanges(jdbc,manager,expiring),expiring);
        String state=candidate.authorize(local.accessToken(),linkProof(SocialProvider.GOOGLE),SocialProvider.GOOGLE,"A".repeat(43))
                .getQuery().split("&")[0].substring(6);
        reason(()->candidate.complete(local.accessToken(),linkProof(SocialProvider.GOOGLE),SocialProvider.GOOGLE,state,"late","A".repeat(43)),REAUTHENTICATION_REQUIRED);
        assertThat(providerCalls.get()).isEqualTo(1); assertThat(count(principal.accountId())).isEqualTo(1);
    }
    @Test void rollbackAfterDeleteKeepsIdentityAndItsSessionValid() {
        UUID identity=changes.link(principal,SocialProvider.GOOGLE,"rollback-delete",linkProof(SocialProvider.GOOGLE));
        var token=login(principal.accountId(),identity,"GOOGLE");
        var failing=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public int update(String sql,Object... args) {
                int result=super.update(sql,args);
                if (sql.startsWith("DELETE FROM auth_identity")) throw new DataRetrievalFailureException("synthetic failure after delete");
                return result;
            }
        };
        assertThatThrownBy(()->new IdentityChanges(failing,manager,reauth).unlink(principal,SocialProvider.GOOGLE,unlinkProof(SocialProvider.GOOGLE)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThat(auth.authenticate(token.accessToken()).identityId()).isEqualTo(identity);
    }
    int count(UUID account) { return jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id=?",Integer.class,account); }
    @Test void crossAccountUniqueRaceRollsBackLoser() throws Exception {
        UUID other=account(); UUID identity=identity(other,"LOCAL",other.toString(),true);
        Principal p=auth.authenticate(login(other,identity,"LOCAL").accessToken());
        var barrier=new CyclicBarrier(2);
        var racing=new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
                T result=super.queryForObject(sql,type,args);
                if (sql.equals("SELECT count(*) FROM auth_identity WHERE provider=? AND subject=?")) await(barrier);
                return result;
            }
        };
        var store=new IdentityChanges(racing,manager,reauth);
        var results=race(()->store.link(principal,SocialProvider.GOOGLE,"race",linkProof(SocialProvider.GOOGLE)),
                ()->store.link(p,SocialProvider.GOOGLE,"race",proof(p,IdentityReauthentication.Operation.LINK_IDENTITY,SocialProvider.GOOGLE)));
        assertThat(results).containsExactlyInAnyOrder("OK",IDENTITY_ALREADY_LINKED.name());
        assertThat(count(other)+count(principal.accountId())).isEqualTo(3);
    }
    @Test void sameAccountSameProviderCompetitionSerializes() throws Exception {
        var result=race(()->changes.link(principal,SocialProvider.GOOGLE,"one",linkProof(SocialProvider.GOOGLE)),
                ()->changes.link(principal,SocialProvider.GOOGLE,"two",linkProof(SocialProvider.GOOGLE)));
        assertThat(result).containsExactlyInAnyOrder("OK",PROVIDER_ALREADY_LINKED.name()); assertThat(count(principal.accountId())).isEqualTo(2);
    }
    @Test void competingUnlinksLeaveOneUsableIdentity() throws Exception {
        UUID id=account(); UUID g=identity(id,"GOOGLE","g",false); UUID k=identity(id,"KAKAO","k",false);
        var gp=auth.authenticate(login(id,g,"GOOGLE").accessToken()); var kp=auth.authenticate(login(id,k,"KAKAO").accessToken());
        var result=race(()->{changes.unlink(gp,SocialProvider.GOOGLE,proof(gp,IdentityReauthentication.Operation.UNLINK_IDENTITY,SocialProvider.GOOGLE));return null;},
                ()->{changes.unlink(kp,SocialProvider.KAKAO,proof(kp,IdentityReauthentication.Operation.UNLINK_IDENTITY,SocialProvider.KAKAO));return null;});
        assertThat(result).containsExactlyInAnyOrder("OK",LAST_IDENTITY.name()); assertThat(count(id)).isEqualTo(1);
    }
    @Test void linkAndUnlinkRecheckAfterBothCommitOrders() throws Exception {
        for (boolean unlinkFirst:List.of(true,false)) {
            UUID google=changes.link(principal,SocialProvider.GOOGLE,UUID.randomUUID().toString(),linkProof(SocialProvider.GOOGLE));
            ordered(unlinkFirst,
                    ()->{changes.unlink(principal,SocialProvider.GOOGLE,unlinkProof(SocialProvider.GOOGLE));return null;},
                    ()->changes.link(principal,SocialProvider.KAKAO,UUID.randomUUID().toString(),linkProof(SocialProvider.KAKAO)));
            assertThat(count(principal.accountId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE id=?",Integer.class,google)).isZero();
            changes.unlink(principal,SocialProvider.KAKAO,unlinkProof(SocialProvider.KAKAO));
        }
    }
    void ordered(boolean unlinkFirst,Callable<?> unlink,Callable<?> link) throws Exception {
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        IdentityReauthentication gate=reusable((p,op,provider,proof)->{
            reauth.require(p,op,provider,proof);
            if (first.compareAndSet(true,false)) { held.countDown(); await(release); }
        });
        // The real service lock is held at the port call; second writer must wait at Account FOR UPDATE.
        var store=new IdentityChanges(jdbc,manager,gate);
        Callable<?> a=unlinkFirst?()->{store.unlink(principal,SocialProvider.GOOGLE,unlinkProof(SocialProvider.GOOGLE));return null;}:
                ()->store.link(principal,SocialProvider.KAKAO,UUID.randomUUID().toString(),linkProof(SocialProvider.KAKAO));
        Callable<?> b=unlinkFirst?link:unlink;
        try (var pool=Executors.newFixedThreadPool(2)) {
            Future<?> one=pool.submit(a);
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue(); Future<?> two=pool.submit(b);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean blocked=false;
                while (System.nanoTime()<deadline) {
                    if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT token_version FROM account%'",Integer.class)>0) {blocked=true;break;}
                    Thread.yield();
                }
                assertThat(blocked).as("second writer blocked on real Account row lock").isTrue();
                release.countDown(); one.get(10,TimeUnit.SECONDS); two.get(10,TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }
    List<String> race(Callable<?> a,Callable<?> b) throws Exception {
        var barrier=new CyclicBarrier(2);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->outcome(a,barrier)); var two=pool.submit(()->outcome(b,barrier));
            return List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));
        }
    }
    String outcome(Callable<?> work,CyclicBarrier barrier) throws Exception {
        await(barrier); try { work.call(); return "OK"; } catch (IdentityChangeException e) { return e.reason().name(); }
    }
    static void await(CyclicBarrier barrier) { try { barrier.await(10,TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); } }
    static void await(CountDownLatch latch) { try { if (!latch.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
    @FunctionalInterface interface ThrowingCallable extends org.assertj.core.api.ThrowableAssert.ThrowingCallable { }
}

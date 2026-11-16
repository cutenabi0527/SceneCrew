package com.ssafy.scenecrew.account;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import com.ssafy.scenecrew.account.domain.Account;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionAuthenticationIntegrationTest {
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start(); int port=REDIS.getMappedPort(6379);
        r.add("spring.data.redis.host", REDIS::getHost); r.add("spring.data.redis.port", () -> port);
        r.add("spring.data.redis.timeout", () -> "500ms"); r.add("spring.data.redis.connect-timeout", () -> "500ms");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager manager;
    @PersistenceContext EntityManager em;
    final Clock clock=Clock.systemUTC();
    final List<UUID> accountIds=new ArrayList<>();
    final List<String> keys=new ArrayList<>();
    LocalCredentialVerifier.VerifiedAccount account;
    LoginTokenIssuer issuer;
    SessionAuthenticator auth;
    @BeforeEach void setup() {
        var id=UUID.randomUUID(); var identity=UUID.randomUUID(); accountIds.add(id);
        jdbc.update("INSERT INTO account VALUES (?, 'fixture', 'ACTIVE', true, 3, '{}'::jsonb, now())",id);
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, 'synthetic-hash', ?, true)",
                identity,id,id.toString(),id+"@example.invalid");
        account=new LocalCredentialVerifier.VerifiedAccount(id,"fixture",id+"@example.invalid",3,identity);
        var key=AccessAuthenticationTest.key();
        issuer=new LoginTokenIssuer(key,clock,"issuer","audience");
        auth=new SessionAuthenticator(new AccessJwtVerifier(key,clock,"issuer","audience"),redis,
                new CurrentAccountReader(jdbc,manager),clock);
    }
    @AfterEach void cleanup() {
        if (REDIS.isRunning()) for (String key:keys) redis.delete(key);
        for (UUID id:accountIds) { jdbc.update("DELETE FROM auth_identity WHERE account_id=?",id); jdbc.update("DELETE FROM account WHERE id=?",id); }
    }
    LoginTokens login() {
        return login("WEB");
    }
    LoginTokens login(String channel) {
        var token=issuer.issue(account); keys.add(RedisLoginSessions.key(token.sessionId()));
        new RedisLoginSessions(redis,clock).register(account,token,channel); return token;
    }
    void denied(String token) {
        assertThatThrownBy(() -> auth.authenticate(token)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED)).hasNoCause();
    }
    @Test @Order(1) void validatesIssuerAndStoreWithoutExtendingTtlAndRevokesOnlyOneDevice() {
        var first=login(); var second=login();
        var key=RedisLoginSessions.key(first.sessionId()); var before=redis.getExpire(key);
        var principal=auth.authenticate(first.accessToken());
        assertThat(principal.accountId()).isEqualTo(account.accountId());
        assertThat(principal.identityId()).isEqualTo(account.identityId());
        assertThat(principal.toString()).doesNotContain(first.accessToken(), first.refreshToken(), account.email());
        assertThat(redis.getExpire(key)).isPositive().isLessThanOrEqualTo(before);
        redis.delete(key); denied(first.accessToken());
        assertThat(auth.authenticate(second.accessToken()).sessionId()).isEqualTo(second.sessionId());
    }
    @Test @Order(1) void androidRevocationAndNewLoginPreserveExistingWebSession() {
        var web=login("WEB"); var android=login("ANDROID");
        var webPrincipal=auth.authenticate(web.accessToken());
        var androidPrincipal=auth.authenticate(android.accessToken());
        assertThat(webPrincipal.accountId()).isEqualTo(account.accountId());
        assertThat(androidPrincipal.accountId()).isEqualTo(account.accountId());
        assertThat(webPrincipal.channel()).isEqualTo("WEB");
        assertThat(androidPrincipal.channel()).isEqualTo("ANDROID");
        assertThat(web.sessionId()).isNotEqualTo(android.sessionId());
        String webKey=RedisLoginSessions.key(web.sessionId());
        String webStored=redis.opsForValue().get(webKey);
        assertThat(redis.delete(RedisLoginSessions.key(android.sessionId()))).isTrue();
        denied(android.accessToken());
        assertThat(auth.authenticate(web.accessToken())).isEqualTo(webPrincipal);

        var replacement=login("ANDROID");
        var replacementPrincipal=auth.authenticate(replacement.accessToken());
        assertThat(replacementPrincipal.accountId()).isEqualTo(account.accountId());
        assertThat(replacementPrincipal.channel()).isEqualTo("ANDROID");
        assertThat(replacement.sessionId()).isNotEqualTo(android.sessionId()).isNotEqualTo(web.sessionId());
        denied(android.accessToken());
        assertThat(auth.authenticate(web.accessToken())).isEqualTo(webPrincipal);
        assertThat(redis.opsForValue().get(webKey)).isEqualTo(webStored);
    }
    @ParameterizedTest @Order(2)
    @ValueSource(strings={"status","signup","version","verified","provider","identityMissing","accountMissing","owner"})
    void rejectsCurrentDatabaseChanges(String change) {
        var token=login(); assertThat(auth.authenticate(token.accessToken())).isNotNull();
        switch(change) {
            case "status" -> jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",account.accountId());
            case "signup" -> jdbc.update("UPDATE account SET signup_completed=false WHERE id=?",account.accountId());
            case "version" -> jdbc.update("UPDATE account SET token_version=4 WHERE id=?",account.accountId());
            case "verified" -> jdbc.update("UPDATE auth_identity SET email_verified=false WHERE id=?",account.identityId());
            case "provider" -> jdbc.update("UPDATE auth_identity SET provider='GOOGLE' WHERE id=?",account.identityId());
            case "identityMissing" -> jdbc.update("DELETE FROM auth_identity WHERE id=?",account.identityId());
            case "accountMissing" -> { jdbc.update("DELETE FROM auth_identity WHERE id=?",account.identityId()); jdbc.update("DELETE FROM account WHERE id=?",account.accountId()); }
            case "owner" -> {
                var other=UUID.randomUUID(); accountIds.add(other);
                jdbc.update("INSERT INTO account VALUES (?, 'other', 'ACTIVE', true, 3, '{}'::jsonb, now())",other);
                jdbc.update("UPDATE auth_identity SET account_id=? WHERE id=?",other,account.identityId());
            }
            default -> throw new AssertionError(change);
        }
        denied(token.accessToken());
    }
    @Test @Order(3) void seesCommittedRevocationDespiteOuterJpaCache() {
        var token=login();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var cached=em.find(Account.class,account.accountId()); assertThat(cached.getTokenVersion()).isEqualTo(3L);
            CompletableFuture.runAsync(() -> jdbc.update("UPDATE account SET token_version=4 WHERE id=?",account.accountId())).join();
            assertThat(em.find(Account.class,account.accountId()).getTokenVersion()).isEqualTo(3L);
            denied(token.accessToken());
        });
    }
    @Test @Order(4) void expiredRedisKeyIsRejectedEvenWithValidAccessJwt() {
        var token=login(); String key=RedisLoginSessions.key(token.sessionId());
        redis.expire(key,Duration.ofMillis(100));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        denied(token.accessToken());
    }
    @Test @Order(99) void actualRedisOutageNeverFallsBackToJwtOnly() {
        var token=login(); REDIS.stop();
        assertThatThrownBy(() -> auth.authenticate(token.accessToken())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasNoCause().hasMessageNotContaining(token.accessToken());
    }
}

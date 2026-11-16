package com.ssafy.scenecrew.account;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.springframework.transaction.annotation.Transactional;
import com.ssafy.scenecrew.account.repository.AccountRepository;
import com.ssafy.scenecrew.account.repository.AuthIdentityRepository;
import com.ssafy.scenecrew.account.service.LocalCredentialVerifier;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalCredentialDatabaseTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        String host = REDIS.getHost();
        int port = REDIS.getMappedPort(6379);
        registry.add("spring.data.redis.host", () -> host);
        registry.add("spring.data.redis.port", () -> port);
        registry.add("spring.data.redis.timeout", () -> "500ms");
        registry.add("spring.data.redis.connect-timeout", () -> "500ms");
    }

    @AfterAll static void stopRedis() { REDIS.stop(); }

    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired AuthIdentityRepository identities;
    @Autowired AccountRepository accounts;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test void serviceIssuesTokensOnlyWithRealDatabaseAndRedisSession() throws Exception {
        var id = UUID.randomUUID();
        var email = "integrated-" + id + "@example.invalid";
        var encoder = new BCryptPasswordEncoder(4);
        jdbc.update("INSERT INTO account VALUES (?, ?, 'ACTIVE', true, 2, '{}'::jsonb, now())", id, "합성계정");
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",
                UUID.randomUUID(), id, id.toString(), encoder.encode("SyntheticPass8!"), email);
        // Issuance uses an independent transaction; expose only committed registration state.
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();
        var clock = java.time.Clock.systemUTC();
        var generator = javax.crypto.KeyGenerator.getInstance("HmacSHA256");
        generator.init(256);
        var issuer = new com.ssafy.scenecrew.account.service.LoginTokenIssuer(
                generator.generateKey(), clock, "test-issuer", "scenecrew");
        var store = new com.ssafy.scenecrew.account.infra.RedisLoginSessions(redis, clock);
        var service = new com.ssafy.scenecrew.account.service.EmailLoginService(
                new LocalCredentialVerifier(identities, accounts, encoder), accounts, identities, issuer, store,
                new com.ssafy.scenecrew.account.service.AccountIssuanceGuard(jdbc, manager));
        String key = null;
        try {
            var result = service.login(email, "SyntheticPass8!", "WEB");
            key = com.ssafy.scenecrew.account.infra.RedisLoginSessions.key(result.tokens().sessionId());
            assertThat(redis.opsForValue().get(key)).contains(id.toString())
                    .doesNotContain(result.tokens().refreshToken());
            assertThat(redis.getExpire(key)).isBetween(1209500L, 1209600L);
            assertThat(jdbc.queryForObject("SELECT token_version FROM account WHERE id = ?", Long.class, id))
                    .isEqualTo(2L);
        } finally {
            if (key != null) redis.delete(key);
            jdbc.update("DELETE FROM auth_identity WHERE account_id=?", id);
            jdbc.update("DELETE FROM account WHERE id=?", id);
        }
    }

    @Test void queriesNormalizedLocalIdentityAndPreservesStoredState() {
        var id = UUID.randomUUID();
        var email = "db-" + id + "@example.invalid";
        var encoder = new BCryptPasswordEncoder(4);
        jdbc.update("INSERT INTO account VALUES (?, ?, 'ACTIVE', true, 3, '{}'::jsonb, now())",
                id, "합성계정");
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'LOCAL', ?, ?, ?, true)",
                UUID.randomUUID(), id, id.toString(), encoder.encode("SyntheticPass8!"),
                "  " + email.toUpperCase(java.util.Locale.ROOT) + "  ");
        var verifier = new LocalCredentialVerifier(identities, accounts, encoder);
        assertThat(verifier.verify(email, "SyntheticPass8!").orElseThrow().accountId()).isEqualTo(id);
        assertThat(verifier.verify(email, "WrongPassword8!")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT token_version FROM account WHERE id = ?", Long.class, id))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE account_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test void socialEmailIsNeverUsedForLocalLogin() {
        var id = UUID.randomUUID();
        var email = "social-" + id + "@example.invalid";
        jdbc.update("INSERT INTO account VALUES (?, ?, 'ACTIVE', true, 0, '{}'::jsonb, now())",
                id, "합성계정");
        jdbc.update("INSERT INTO auth_identity VALUES (?, ?, 'GOOGLE', ?, NULL, ?, true)",
                UUID.randomUUID(), id, id.toString(), email);
        assertThat(identities.findLocalByNormalizedEmail(email)).isEmpty();
    }
}

package com.ssafy.scenecrew.account;
import java.time.*;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.account.dto.LoginResponses;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginTokenTest {
    @Test void validatesSignatureExpiryClaimsAndChannelResponses() throws Exception {
        var gen = KeyGenerator.getInstance("HmacSHA256"); gen.init(256);
        var key = gen.generateKey();
        var clock = Clock.systemUTC();
        var account = new LocalCredentialVerifier.VerifiedAccount(UUID.randomUUID(), "합성계정",
                "fixture@example.invalid", 3, UUID.randomUUID());
        var issuer = new LoginTokenIssuer(key, clock, "test-issuer", "scenecrew");
        var first = issuer.issue(account);
        var second = issuer.issue(account);
        var jwt = NimbusJwtDecoder.withSecretKey(key).build().decode(first.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(account.accountId().toString());
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(first.sessionId().toString());
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256").containsEntry("typ", "JWT");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("test-issuer");
        assertThat(jwt.getAudience()).containsExactly("scenecrew");
        assertThat(jwt.getClaimAsString("token_use")).isEqualTo("access");
        assertThat(((Number) jwt.getClaim("tokenVersion")).longValue()).isEqualTo(3L);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
        assertThat(first.refreshExpiresAt().minus(Duration.ofDays(14)))
                .isBetween(clock.instant().minusSeconds(10), clock.instant());
        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
        assertThat(jwt.getClaims()).doesNotContainKeys("email", "password", "refreshToken");
        var result = new EmailLoginService.Result(account, first);
        var web = LoginResponses.web(result, clock);
        assertThat(web.getHeaders().get("Set-Cookie")).hasSize(2).allSatisfy(cookie -> {
            assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Lax").doesNotContain("Domain=");
        });
        assertThat(web.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(LoginResponses.android(result).getHeaders().get("Set-Cookie")).isNull();
        assertThat(first.toString()).doesNotContain(first.accessToken(), first.refreshToken());
    }

    @Test void rejectsMissingShortOrUnextractableKeyAndMissingIssuerOrAudience() throws Exception {
        var gen = KeyGenerator.getInstance("HmacSHA256"); gen.init(256);
        var valid = gen.generateKey();
        var shortKey = new SecretKeySpec(new byte[31], "HmacSHA256");
        var unextractable = mock(SecretKey.class);
        for (SecretKey key : new SecretKey[]{null, shortKey, unextractable}) {
            assertThatThrownBy(() -> new LoginTokenIssuer(key, Clock.systemUTC(), "issuer", "audience"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid token configuration");
        }
        for (String missing : new String[]{null, "", " "}) {
            assertThatThrownBy(() -> new LoginTokenIssuer(valid, Clock.systemUTC(), missing, "audience"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LoginTokenIssuer(valid, Clock.systemUTC(), "issuer", missing))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void issuedTokenHasExactExpiryAndCannotBeVerifiedWithAnotherKey() throws Exception {
        var gen = KeyGenerator.getInstance("HmacSHA256"); gen.init(256);
        var key = gen.generateKey();
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var account = new LocalCredentialVerifier.VerifiedAccount(UUID.randomUUID(), "합성계정",
                "fixture@example.invalid", 0, UUID.randomUUID());
        var issued = new LoginTokenIssuer(key, Clock.fixed(now, ZoneOffset.UTC), "issuer", "audience").issue(account);
        assertThat(issued.accessExpiresAt()).isEqualTo(now.plusSeconds(3600));
        assertThat(issued.refreshExpiresAt()).isEqualTo(now.plus(Duration.ofDays(14)));
        assertThatThrownBy(() -> NimbusJwtDecoder.withSecretKey(gen.generateKey()).build().decode(issued.accessToken()))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        var expired = new LoginTokenIssuer(key, Clock.fixed(now.minusSeconds(7200), ZoneOffset.UTC),
                "issuer", "audience").issue(account);
        assertThatThrownBy(() -> NimbusJwtDecoder.withSecretKey(key).build().decode(expired.accessToken()))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtValidationException.class);
    }
}

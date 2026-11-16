package com.ssafy.scenecrew.account.service;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

public final class LoginTokenIssuer {
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final String audience;
    private final SecureRandom random = new SecureRandom();

    public LoginTokenIssuer(SecretKey key, Clock clock, String issuer, String audience) {
        if (key == null || key.getEncoded() == null || key.getEncoded().length < 32
                || issuer == null || issuer.isBlank()
                || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("Invalid token configuration");
        }
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
    }

    public LoginTokens issue(LocalCredentialVerifier.VerifiedAccount account) {
        return issue(account.accountId(), account.tokenVersion(), UUID.randomUUID(), false);
    }

    /** Rotation retains SID so still-valid Access credentials are not implicitly revoked. */
    LoginTokens rotate(StoredLoginSession session, UUID sessionId) {
        return issue(session.accountId(), session.tokenVersion(), sessionId, true);
    }

    private LoginTokens issue(UUID accountId, long tokenVersion, UUID sid, boolean rotation) {
        var now = clock.instant();
        var accessExpiry = now.plus(Duration.ofHours(1));
        var refreshExpiry = now.plus(Duration.ofDays(14));
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String refresh = sid + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(java.util.List.of(audience))
                .subject(accountId.toString()).issuedAt(now).expiresAt(accessExpiry)
                .claim("sid", sid.toString()).claim("tokenVersion", tokenVersion)
                .claim("token_use", "access");
        // Same-second rotations must still issue a distinct Access token; existing JWT checks are unchanged.
        if (rotation) claims.id(UUID.randomUUID().toString());
        var access = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims.build())).getTokenValue();
        return new LoginTokens(sid, access, refresh, accessExpiry, refreshExpiry);
    }
}

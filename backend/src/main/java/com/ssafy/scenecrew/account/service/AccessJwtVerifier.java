package com.ssafy.scenecrew.account.service;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.SecretKey;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import com.ssafy.scenecrew.global.error.*;

/** 내부 검증 전용. HTTP 전달 방식이나 Security 등록은 포함하지 않는다. */
public final class AccessJwtVerifier {
    // Spring Security 7.1.1 JwtTimestampValidator 기본값과 같다.
    private static final Duration SKEW = Duration.ofSeconds(60);
    private final NimbusJwtDecoder decoder;
    private final Clock clock;
    private final String issuer;
    private final String audience;

    public AccessJwtVerifier(SecretKey key, Clock clock, String issuer, String audience) {
        if (key == null || key.getEncoded() == null || key.getEncoded().length < 32
                || issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("Invalid token configuration");
        }
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var time = new JwtTimestampValidator(SKEW);
        time.setClock(clock);
        time.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(time);
    }

    public Claims verify(String token) {
        if (token == null || token.isBlank()) throw denied();
        try {
            var jwt = decoder.decode(token);
            var raw = SignedJWT.parse(token).getPayload().toJSONObject();
            if (!issuer.equals(jwt.getClaimAsString("iss")) || !jwt.getAudience().contains(audience)
                    || !"access".equals(raw.get("token_use"))
                    || !integer(raw.get("iat")) || !integer(raw.get("exp"))
                    || (raw.containsKey("nbf") && !integer(raw.get("nbf")))
                    || jwt.getIssuedAt() == null || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || jwt.getIssuedAt().isAfter(clock.instant().plus(SKEW))
                    || !integer(raw.get("tokenVersion"))) throw denied();
            long version = ((Number) raw.get("tokenVersion")).longValue();
            if (version < 0) throw denied();
            return new Claims(uuid(raw.get("sub")), uuid(raw.get("sid")), version);
        } catch (JwtException | java.text.ParseException | IllegalArgumentException | NullPointerException failure) {
            throw denied();
        }
    }

    private static boolean integer(Object value) { return value instanceof Long || value instanceof Integer; }
    static UUID uuid(Object value) {
        if (!(value instanceof String s)) throw denied();
        try {
            UUID id = UUID.fromString(s);
            if (!id.toString().equalsIgnoreCase(s)) throw denied();
            return id;
        } catch (IllegalArgumentException failure) { throw denied(); }
    }
    static BusinessException denied() { return new BusinessException(ErrorCode.UNAUTHENTICATED); }
    public record Claims(UUID accountId, UUID sessionId, long tokenVersion) {
        @Override public String toString() { return "AccessClaims[redacted]"; }
    }
}

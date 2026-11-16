package com.ssafy.scenecrew.account;

import java.time.*;
import java.util.*;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.*;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessAuthenticationTest {
    final Instant now = Instant.parse("2026-10-31T10:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final UUID account = UUID.randomUUID(), sid = UUID.randomUUID(), identity = UUID.randomUUID();
    final SecretKey key = key();
    final AccessJwtVerifier verifier = new AccessJwtVerifier(key, clock, "issuer", "audience");
    static SecretKey key() {
        try { var g = KeyGenerator.getInstance("HmacSHA256"); g.init(256); return g.generateKey(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    Map<String,Object> claims() {
        var c = new HashMap<String,Object>();
        c.put("iss", "issuer"); c.put("aud", List.of("audience"));
        c.put("iat", now.getEpochSecond()); c.put("exp", now.plusSeconds(3600).getEpochSecond());
        c.put("sub", account.toString()); c.put("sid", sid.toString());
        c.put("tokenVersion", 3L); c.put("token_use", "access"); return c;
    }
    String sign(Map<String,Object> c) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), JWTClaimsSet.parse(c));
        jwt.sign(new MACSigner(key)); return jwt.serialize();
    }
    String session() {
        return String.join("|", "v1", UUID.randomUUID().toString(), account.toString(), identity.toString(),
                "3", "WEB", "a".repeat(64), now.plusSeconds(1000).toString());
    }
    void denied(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED)).hasNoCause();
    }
    @Test void acceptsIssuerOutputAndRedactsResult() {
        var a = new LocalCredentialVerifier.VerifiedAccount(account, "fixture", "fixture@example.invalid", 3, identity);
        var token = new LoginTokenIssuer(key, clock, "issuer", "audience").issue(a);
        assertThat(verifier.verify(token.accessToken()).accountId()).isEqualTo(account);
        assertThat(verifier.verify(token.accessToken()).toString()).doesNotContain(token.accessToken(), account.toString());
        denied(() -> verifier.verify(token.refreshToken()));
    }
    @Test void rejectsMissingMalformedAndForged() throws Exception {
        denied(() -> verifier.verify(null)); denied(() -> verifier.verify("")); denied(() -> verifier.verify("broken"));
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), JWTClaimsSet.parse(claims()));
        jwt.sign(new MACSigner(key())); denied(() -> verifier.verify(jwt.serialize()));
        var algorithm = new SignedJWT(new JWSHeader(JWSAlgorithm.HS384), JWTClaimsSet.parse(claims()));
        algorithm.sign(new MACSigner(new byte[48])); denied(() -> verifier.verify(algorithm.serialize()));
    }
    @ParameterizedTest @ValueSource(strings={"iss","aud","iat","exp","sub","sid","tokenVersion","token_use"})
    void rejectsEachMissingClaim(String name) throws Exception {
        var c=claims(); c.remove(name); String token=sign(c); denied(() -> verifier.verify(token));
    }
    @ParameterizedTest @ValueSource(strings={"iss","aud","sub","sid","token_use","negative","fraction","string","future","expired","reversed","nbf"})
    void rejectsInvalidClaims(String kind) throws Exception {
        var c=claims();
        switch(kind) {
            case "iss", "aud", "sub", "sid", "token_use" -> c.put(kind, "invalid");
            case "negative" -> c.put("tokenVersion", -1L);
            case "fraction" -> c.put("tokenVersion", 1.5);
            case "string" -> c.put("tokenVersion", "3");
            case "future" -> c.put("iat", now.plusSeconds(61).getEpochSecond());
            case "expired" -> { c.put("iat", now.minusSeconds(3600).getEpochSecond()); c.put("exp",now.minusSeconds(61).getEpochSecond()); }
            case "reversed" -> c.put("exp", now.minusSeconds(1).getEpochSecond());
            case "nbf" -> c.put("nbf", now.plusSeconds(61).getEpochSecond());
            default -> throw new AssertionError(kind);
        }
        String token=sign(c); denied(() -> verifier.verify(token));
    }
    @Test void timeSkewBoundaryIsSixtySeconds() throws Exception {
        var c=claims(); c.put("iat",now.minusSeconds(3600).getEpochSecond());
        c.put("exp",now.minusSeconds(60).getEpochSecond()); assertThat(verifier.verify(sign(c))).isNotNull();
        c=claims(); c.put("iat",now.plusSeconds(60).getEpochSecond());
        c.put("nbf",now.plusSeconds(60).getEpochSecond()); assertThat(verifier.verify(sign(c))).isNotNull();
    }
    @ParameterizedTest @ValueSource(strings={"version","owner","account","identity","count","negative","overflow","channel","hash","expiry","empty"})
    void rejectsCorruptSessions(String kind) {
        String[] f=session().split("\\|");
        switch(kind) {
            case "version" -> f[0]="v2";
            case "owner" -> f[1]="1-1-1-1-1";
            case "account" -> f[2]="bad";
            case "identity" -> f[3]="bad";
            case "negative" -> f[4]="-1";
            case "overflow" -> f[4]="999999999999999999999";
            case "channel" -> f[5]="IOS";
            case "hash" -> f[6]="raw-refresh";
            case "expiry" -> f[7]=now.toString();
            case "empty" -> f[7]="";
            case "count" -> f=Arrays.copyOf(f,7);
            default -> throw new AssertionError(kind);
        }
        String value=String.join("|",f); denied(() -> StoredLoginSession.parse(value,clock));
    }
    @SuppressWarnings("unchecked")
    @Test void storageFailuresAndMismatchesNeverProducePrincipal() throws Exception {
        var redis=mock(StringRedisTemplate.class); ValueOperations<String,String> ops=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        var reader=mock(CurrentAccountReader.class);
        var auth=new SessionAuthenticator(verifier,redis,reader,clock);
        String token=sign(claims());
        denied(() -> auth.authenticate(token));
        when(ops.get(anyString())).thenReturn(session().replace(account.toString(),UUID.randomUUID().toString()));
        denied(() -> auth.authenticate(token)); verifyNoInteractions(reader);
        when(ops.get(anyString())).thenReturn(session().replace("|3|","|4|")); denied(() -> auth.authenticate(token));
        when(ops.get(anyString())).thenReturn(session()); denied(() -> auth.authenticate(token));
        when(reader.isActive(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("sensitive"));
        unavailable(() -> auth.authenticate(token));
        when(ops.get(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("sensitive"));
        unavailable(() -> auth.authenticate(token));
        clearInvocations(redis,reader); denied(() -> auth.authenticate("invalid")); verifyNoInteractions(redis,reader);
    }
    void unavailable(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasNoCause().hasMessageNotContaining("sensitive");
    }
}

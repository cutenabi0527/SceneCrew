package com.ssafy.scenecrew.account;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.infra.RedisPendingRegistrations;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PublicRegistrationHttpTest.TestEncoder.class)
class PublicRegistrationHttpTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final byte[] SECRET=new byte[32];
    static { new java.security.SecureRandom().nextBytes(SECRET); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start(); r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.issuer",()->"registration-test"); r.add("scenecrew.auth.audience",()->"registration-test");
        r.add("scenecrew.auth.web-origins",()->"https://web.example.invalid");
        r.add("scenecrew.auth.android-origins",()->"https://android.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @MockitoBean RegistrationDelivery delivery;
    final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    String email;
    @BeforeEach void setup() { email=UUID.randomUUID()+"@example.invalid";
        when(delivery.accept(any())).thenReturn(RegistrationDelivery.Outcome.ACCEPTED); }
    String body() { return "{\"email\":\""+email+"\",\"password\":\"SyntheticPass8!\",\"displayName\":\"합성\","
            +"\"consent\":{\"service\":true,\"voice\":false,\"push\":false,\"externalAi\":false}}"; }
    HttpRequest.Builder post(String body) { return HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/register"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)); }
    HttpRequest.Builder web(String body) { return post(body).header("X-SceneCrew-Client","WEB")
            .header("Origin","https://web.example.invalid").header("X-SceneCrew-CSRF","1"); }
    HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try(var client=HttpClient.newHttpClient()) { return client.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    void error(HttpResponse<String> result,int status,String code) {
        assertThat(result.statusCode()).as(result.body()).isEqualTo(status);
        assertThat(json.readTree(result.body()).get("code").asString()).isEqualTo(code);
        assertThat(json.readTree(result.body()).get("requestId").asString())
                .isEqualTo(result.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(result.body()).doesNotContain(email,"SyntheticPass8!","passwordHash");
        assertThat(result.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test void acknowledgedTestAdapterAllows202WithExactlyReceiptFieldsAndNoSession() throws Exception {
        int count=jdbc.queryForObject("SELECT count(*) FROM account",Integer.class);
        var result=send(web(body()).header("Idempotency-Key","ignored-by-register"));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(202);
        var receipt=json.readTree(result.body()); assertThat(receipt.size()).isEqualTo(2);
        UUID id=UUID.fromString(receipt.get("requestId").asString());
        java.time.Instant.parse(receipt.get("expiresAt").asString());
        assertThat(redis.hasKey(RedisPendingRegistrations.key(id))).isTrue();
        verify(delivery).accept(argThat(mail -> mail.requestId().equals(id) && mail.code().matches("[0-9]{6}")));
        assertThat(result.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(result.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(result.body()).doesNotContain(email,"SyntheticPass8!","passwordHash","Token");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account",Integer.class)).isEqualTo(count);
    }

    @Test void androidUsesSameReceiptAndNeverSetsCookies() throws Exception {
        var result=send(post(body()).header("X-SceneCrew-Client","ANDROID").header("Origin","https://android.example.invalid"));
        assertThat(result.statusCode()).isEqualTo(202);
        assertThat(json.readTree(result.body()).size()).isEqualTo(2);
        assertThat(result.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test void rawNameControlsAndCodePointOverflowReturn400WithoutEffects() throws Exception {
        int before = jdbc.queryForObject("SELECT count(*) FROM account", Integer.class);
        for (String c : List.of("\n", "\t", "\u0000", "\u2028", "\u2029")) {
            for (String name : List.of(c + "name", "name" + c)) {
                String payload = body().replace(json.writeValueAsString("합성"), json.writeValueAsString(name));
                error(send(web(payload)), 400, "INVALID_REQUEST");
            }
        }
        error(send(web(body().replace(json.writeValueAsString("합성"),
                json.writeValueAsString("😀".repeat(21))))), 400, "INVALID_REQUEST");
        assertThat(redis.hasKey(new RedisPendingRegistrations(redis, SECRET).sendBudgetKey(email))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Integer.class)).isEqualTo(before);
        verifyNoInteractions(delivery);
    }

    @Test void ordinarySpacesPreserveTwentyCodePointPendingName() throws Exception {
        String name = "😀".repeat(20);
        var result = send(web(body().replace(json.writeValueAsString("합성"),
                json.writeValueAsString("  " + name + "  "))));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(202);
        UUID id = UUID.fromString(json.readTree(result.body()).get("requestId").asString());
        var stored = json.readTree(redis.opsForValue().get(RedisPendingRegistrations.key(id)));
        assertThat(stored.get("displayName").asString()).isEqualTo(name);
        verify(delivery).accept(argThat(message -> id.equals(message.requestId())));
    }

    @Test void h01ChannelAndRawCookieChecksRunBeforeIntake() throws Exception {
        error(send(post(body()).header("X-SceneCrew-Client","WEB")),403,"CSRF_REJECTED");
        error(send(post(body())),400,"INVALID_AUTH_CHANNEL");
        error(send(web(body()).header("Cookie","__Secure-sc-access=a; __Secure-sc-access=b")),400,"DUPLICATE_AUTH_COOKIE");
        error(send(web(body()).header("Cookie","__Secure-sc-access=a").header("Authorization","Bearer b")),400,"MIXED_AUTH_CREDENTIALS");
        verifyNoInteractions(delivery);
    }

    @Test void strictJsonRejectsExtraFieldsNullsAndWrongBooleanTypes() throws Exception {
        for(String body:List.of(body().replace("\"displayName\":\"합성\"","\"displayName\":null"),
                body().replace("\"voice\":false","\"voice\":\"false\""),body().replace("\"service\":true","\"service\":true,\"other\":true"),
                body().replace("\"email\":", "\"extra\":1,\"email\":"))) {
            error(send(web(body)),400,"INVALID_REQUEST");
        }
        verifyNoInteractions(delivery);
    }

    @Test void repeatedIntakeIs429AndDoesNotReinvokeDelivery() throws Exception {
        assertThat(send(web(body())).statusCode()).isEqualTo(202);
        error(send(web(body())),429,"RATE_LIMITED");
        verify(delivery,times(1)).accept(any());
    }

    @Test void missingAndFailingDeliveryNeverMasqueradeAsAccepted() throws Exception {
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(delivery).requireAvailable();
        error(send(web(body())),503,"SERVICE_UNAVAILABLE");
        verify(delivery,never()).accept(any());
        reset(delivery);
        doThrow(new IllegalStateException("synthetic send failure")).when(delivery).accept(any());
        error(send(web(body())),503,"SERVICE_UNAVAILABLE");
    }
    @TestConfiguration static class TestEncoder {
        @Bean PasswordEncoder testOnlyEncoder() { return new BCryptPasswordEncoder(4); }
    }
}

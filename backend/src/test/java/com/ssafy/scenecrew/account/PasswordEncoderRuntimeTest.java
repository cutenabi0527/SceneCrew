package com.ssafy.scenecrew.account;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import com.ssafy.scenecrew.account.dto.RegisterRequest;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.account.dto.Credentials;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Main application wiring with no test encoder; PostgreSQL/Redis isolated, only mail delivery is replaced. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PasswordEncoderRuntimeTest {
    static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);
    static final byte[] SECRET=new byte[32];
    static { new java.security.SecureRandom().nextBytes(SECRET); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        REDIS.start(); r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("scenecrew.auth.public-enabled",()->"true");
        r.add("scenecrew.auth.signing-key-base64",()->Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.failure-hmac-key-base64",()->Base64.getEncoder().encodeToString(SECRET));
        r.add("scenecrew.auth.issuer",()->"encoder-runtime"); r.add("scenecrew.auth.audience",()->"encoder-runtime");
        r.add("scenecrew.auth.web-origins",()->"https://web.example.invalid");
    }
    @AfterAll static void stop() { REDIS.stop(); }
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired EmailLoginService login;
    @Autowired EmailRegistrationService registration;
    @Autowired EmailVerificationService verification;
    @Autowired PasswordResetService resets;
    @Autowired SessionAuthenticator auth;
    @MockitoBean RegistrationDelivery registrationMail;
    @MockitoBean PasswordResetDelivery resetMail;
    final Map<UUID,RegistrationDelivery.Message> registrationMessages=new ConcurrentHashMap<>();
    final Map<String,PasswordResetDelivery.Message> resetMessages=new ConcurrentHashMap<>();
    final Set<UUID> accounts=new HashSet<>();
    static final String OLD="SyntheticPass8!", NEW="ChangedPass9!";
    @BeforeEach void mail() {
        when(registrationMail.accept(any())).thenAnswer(c->{var m=(RegistrationDelivery.Message)c.getArgument(0);
            registrationMessages.put(m.requestId(),m);return RegistrationDelivery.Outcome.ACCEPTED;});
        when(resetMail.accept(any())).thenAnswer(c->{var m=(PasswordResetDelivery.Message)c.getArgument(0);
            resetMessages.put(m.email(),m);return RegistrationDelivery.Outcome.ACCEPTED;});
    }
    @AfterEach void cleanup() {
        for(UUID id:accounts) { jdbc.update("DELETE FROM auth_identity WHERE account_id=?",id);jdbc.update("DELETE FROM account WHERE id=?",id); }
    }
    @Test void normalMainContextStartsWithSingleCostTenEncoderAndPublicServices() {
        assertThat(context.getBeansOfType(PasswordEncoder.class)).containsOnlyKeys("defaultPasswordEncoder");
        assertThat(encoder).isInstanceOf(BCryptPasswordEncoder.class);
        assertThat(encoder.encode(OLD)).startsWith("$2a$10$");
        assertThat(context.getBean(EmailLoginService.class)).isSameAs(login);
        assertThat(context.getBean(EmailRegistrationService.class)).isSameAs(registration);
        assertThat(context.getBean(PasswordResetService.class)).isSameAs(resets);
        assertThat(context.getBeansOfType(RefreshService.class)).hasSize(1);
        assertThat(context.getBeansOfType(com.ssafy.scenecrew.account.controller.PublicAuthController.class)).hasSize(1);
    }
    @Test void existingCostFourHashCanLoginAndResetToDefaultCostTen() {
        UUID id=UUID.randomUUID();accounts.add(id);String email=id+"@example.invalid";
        jdbc.update("INSERT INTO account VALUES (?,?,'ACTIVE',true,0,'{}'::jsonb,now())",id,"fixture");
        jdbc.update("INSERT INTO auth_identity VALUES (?,?,'LOCAL',?,?,?,true)",UUID.randomUUID(),id,id.toString(),
                new BCryptPasswordEncoder(4).encode(OLD),email);
        var old=login.login(email,OLD,"ANDROID");
        resets.request(email);resets.confirm(resetMessages.get(email).recoveryProof(),NEW);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM auth_identity WHERE account_id=?",String.class,id)).startsWith("$2a$10$");
        assertThatThrownBy(()->auth.authenticate(old.tokens().accessToken())).isInstanceOf(com.ssafy.scenecrew.global.error.BusinessException.class);
        assertThat(auth.authenticate(login.login(email,NEW,"ANDROID").tokens().accessToken()).tokenVersion()).isEqualTo(1);
    }
    @Test void registrationVerificationLoginAndResetAllUseDefaultCostTen() {
        String email=UUID.randomUUID()+"@example.invalid";
        var accepted=registration.register(new RegisterRequest(email,OLD,"fixture",new RegisterRequest.Consent(true,false,false,false)));
        var completed=verification.verify(accepted.requestId(),registrationMessages.get(accepted.requestId()).code(),new Credentials("ANDROID",null));
        UUID id=completed.account().accountId(); accounts.add(id);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM auth_identity WHERE account_id=?",String.class,id)).startsWith("$2a$10$");
        assertThat(auth.authenticate(login.login(email,OLD,"ANDROID").tokens().accessToken())).isNotNull();
        resets.request(email);resets.confirm(resetMessages.get(email).recoveryProof(),NEW);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM auth_identity WHERE account_id=?",String.class,id)).startsWith("$2a$10$");
        assertThat(auth.authenticate(login.login(email,NEW,"ANDROID").tokens().accessToken()).tokenVersion()).isEqualTo(1);
    }
}

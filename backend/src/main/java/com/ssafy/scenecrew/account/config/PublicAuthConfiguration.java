package com.ssafy.scenecrew.account.config;

import java.time.Clock;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.HandlerExceptionResolver;
import com.ssafy.scenecrew.account.controller.AuthTransport;
import com.ssafy.scenecrew.account.filter.PublicAuthFilter;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.repository.*;
import com.ssafy.scenecrew.account.service.*;

/** Off by default. Only an explicitly configured candidate environment enables these routes.
 * No default signing/HMAC secret, Origin, CORS or Android exception.
 * PasswordEncoderAutoConfiguration supplies BCrypt(10) only when no custom encoder exists.
 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="scenecrew.auth.public-enabled", havingValue="true")
public class PublicAuthConfiguration {
    @Bean Clock authClock() { return Clock.systemUTC(); }
    @Bean LoginTokenIssuer loginTokenIssuer(Environment env, Clock clock) {
        return new LoginTokenIssuer(signingKey(env), clock, required(env,"issuer"), required(env,"audience"));
    }
    @Bean RedisLoginSessions loginSessions(StringRedisTemplate redis, Clock clock) { return new RedisLoginSessions(redis,clock); }
    @Bean LocalCredentialVerifier localCredentials(AuthIdentityRepository identities, AccountRepository accounts, PasswordEncoder encoder) {
        return new LocalCredentialVerifier(identities,accounts,encoder);
    }
    @Bean EmailLoginService emailLoginService(LocalCredentialVerifier verifier, AccountRepository accounts,
            AuthIdentityRepository identities, LoginTokenIssuer tokens, RedisLoginSessions sessions,
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new EmailLoginService(verifier,accounts,identities,tokens,sessions,new AccountIssuanceGuard(jdbc,manager));
    }
    @Bean SessionAuthenticator sessionAuthenticator(Environment env, Clock clock, StringRedisTemplate redis,
            JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new SessionAuthenticator(new AccessJwtVerifier(signingKey(env),clock,required(env,"issuer"),required(env,"audience")),
                redis,new CurrentAccountReader(jdbc,manager),clock);
    }
    @Bean LoginFailureLimiter loginFailureLimiter(StringRedisTemplate redis, Environment env) {
        return new LoginFailureLimiter(redis, secret(env,"failure-hmac-key-base64"));
    }
    @Bean AccountSummaryReader accountSummaryReader(JdbcTemplate jdbc) { return new AccountSummaryReader(jdbc); }
    @Bean AccountProfileService accountProfileService(JdbcTemplate jdbc, AccountSummaryReader accounts,
            PlatformTransactionManager manager) { return new AccountProfileService(jdbc, accounts, manager); }
    @Bean AccountDeactivationService accountDeactivationService(JdbcTemplate jdbc, LocalCredentialVerifier verifier,
            LoginFailureLimiter limiter, PlatformTransactionManager manager, Clock clock,
            com.ssafy.scenecrew.safety.service.SafetySupportProtection support) {
        return new AccountDeactivationService(jdbc, verifier, limiter, manager, clock, support);
    }
    @Bean AccountConsentService accountConsentService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            com.ssafy.scenecrew.issue.service.AudioAssetService audio, Clock clock) {
        return new AccountConsentService(jdbc, manager, audio, clock);
    }
    @Bean LogoutService logoutService(StringRedisTemplate redis, JdbcTemplate jdbc,
            PlatformTransactionManager manager, Clock clock) {
        return new LogoutService(redis, new CurrentAccountReader(jdbc, manager), clock);
    }
    /** 공개 Refresh(#263·#274). 응답 유실 뒤 같은 결과 재전달은 없다: 같은 Attempt 재시도는 409 REFRESH_RECOVERY_UNAVAILABLE. */
    @Bean RefreshService refreshService(StringRedisTemplate redis, JdbcTemplate jdbc, PlatformTransactionManager manager,
            LoginTokenIssuer issuer, Clock clock) {
        return new RefreshService(redis, new CurrentAccountReader(jdbc, manager), issuer, clock);
    }
    @Bean PublicLoginService publicLoginService(EmailLoginService login, LoginFailureLimiter limiter,
            SessionAuthenticator auth, RedisLoginSessions sessions) {
        return new PublicLoginService(login,limiter,auth,sessions);
    }
    @Bean AuthTransport authTransport(Environment env) {
        return new AuthTransport(origins(required(env,"web-origins")), origins(env.getProperty("scenecrew.auth.android-origins", "")));
    }
    @Bean PublicAuthFilter publicAuthFilter(AuthTransport transport, SessionAuthenticator authenticator,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        return new PublicAuthFilter(transport,authenticator,errors);
    }
    @Bean FilterRegistrationBean<PublicAuthFilter> authFilterRegistration(PublicAuthFilter filter) {
        var bean=new FilterRegistrationBean<>(filter); bean.setEnabled(false); return bean;
    }
    private static String required(Environment env,String key) { return env.getRequiredProperty("scenecrew.auth."+key); }
    private static byte[] secret(Environment env,String key) {
        try {
            byte[] bytes=Base64.getDecoder().decode(required(env,key));
            if (bytes.length<32) throw new IllegalArgumentException();
            return bytes;
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid authentication secret configuration"); }
    }
    private static SecretKeySpec signingKey(Environment env) { return new SecretKeySpec(secret(env,"signing-key-base64"),"HmacSHA256"); }
    private static Set<String> origins(String value) {
        var result=new HashSet<String>();
        if (value.isBlank()) return result;
        for (String origin:value.split(",")) {
            var uri=java.net.URI.create(origin.trim());
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || !uri.getRawPath().isEmpty()) throw new IllegalArgumentException("Invalid exact authentication Origin");
            result.add(uri.toString());
        }
        return result;
    }
}

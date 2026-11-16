package com.ssafy.scenecrew.account.config;

import java.util.Base64;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.infra.RedisPendingRegistrations;
import com.ssafy.scenecrew.account.infra.RedisVerificationRequests;
import com.ssafy.scenecrew.account.service.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="scenecrew.auth.public-enabled", havingValue="true")
public class PublicRegistrationConfiguration {
    @Bean RedisPendingRegistrations pendingRegistrations(StringRedisTemplate redis, Environment env) {
        return new RedisPendingRegistrations(redis,
                Base64.getDecoder().decode(env.getRequiredProperty("scenecrew.auth.failure-hmac-key-base64")));
    }

    @Bean EmailRegistrationService emailRegistrationService(JdbcTemplate jdbc, PasswordEncoder passwords,
            Validator validator, RedisPendingRegistrations pending, ObjectProvider<RegistrationDelivery> delivery) {
        return new EmailRegistrationService(jdbc, passwords, validator, pending,
                delivery.getIfAvailable(RegistrationDelivery::unavailable));
    }
    @Bean RegistrationCodes registrationCodes(Environment env) {
        return new RegistrationCodes(Base64.getDecoder().decode(
                env.getRequiredProperty("scenecrew.auth.failure-hmac-key-base64")));
    }
    @Bean RedisVerificationRequests verificationRequests(StringRedisTemplate redis,
            RedisPendingRegistrations pending,RegistrationCodes codes) {
        return new RedisVerificationRequests(redis,pending,codes);
    }
    @Bean RegistrationAccountWriter registrationAccountWriter(JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager manager) {
        return new RegistrationAccountWriter(jdbc,manager);
    }
    @Bean RegistrationSessionIssuer registrationSessionIssuer(EmailLoginService login,SessionAuthenticator auth,
            RedisLoginSessions sessions,LoginFailureLimiter limiter) {
        return new RegistrationSessionIssuer(login,auth,sessions,limiter);
    }
    @Bean EmailVerificationService emailVerificationService(RedisVerificationRequests requests,
            RegistrationAccountWriter accounts,RegistrationSessionIssuer sessions,RedisPendingRegistrations pending,
            ObjectProvider<RegistrationDelivery> delivery) {
        return new EmailVerificationService(requests,accounts,sessions,pending,
                delivery.getIfAvailable(RegistrationDelivery::unavailable));
    }
}

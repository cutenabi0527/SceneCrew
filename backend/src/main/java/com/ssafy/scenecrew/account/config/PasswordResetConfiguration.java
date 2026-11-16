package com.ssafy.scenecrew.account.config;

import java.util.Base64;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import com.ssafy.scenecrew.account.infra.RedisPasswordResets;
import com.ssafy.scenecrew.account.infra.SmtpPasswordResetDelivery;
import com.ssafy.scenecrew.account.service.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="scenecrew.auth.public-enabled",havingValue="true")
public class PasswordResetConfiguration {
    @Bean RedisPasswordResets passwordResets(StringRedisTemplate redis,Environment env) {
        return new RedisPasswordResets(redis,Base64.getDecoder().decode(
                env.getRequiredProperty("scenecrew.auth.failure-hmac-key-base64")));
    }
    @Bean PasswordResetAccountWriter passwordResetAccountWriter(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        return new PasswordResetAccountWriter(jdbc,manager);
    }
    @Bean PasswordResetService passwordResetService(RedisPasswordResets resets,PasswordResetAccountWriter writer,
            PasswordEncoder encoder,ObjectProvider<PasswordResetDelivery> delivery,Validator validator) {
        return new PasswordResetService(resets,writer,encoder,delivery.getIfAvailable(PasswordResetDelivery::unavailable),validator);
    }
    @Bean @ConditionalOnProperty(name="scenecrew.auth.mail.smtp.enabled",havingValue="true")
    @ConditionalOnMissingBean(PasswordResetDelivery.class)
    PasswordResetDelivery passwordResetDelivery(Environment env) { return new SmtpPasswordResetDelivery(env); }
}

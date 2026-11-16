package com.ssafy.scenecrew.account.config;

import com.ssafy.scenecrew.account.service.RegistrationDelivery;
import com.ssafy.scenecrew.account.infra.SmtpRegistrationDelivery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class RegistrationMailConfiguration {
    @Bean
    @ConditionalOnProperty(name = "scenecrew.auth.mail.smtp.enabled", havingValue = "true")
    @ConditionalOnMissingBean(RegistrationDelivery.class)
    RegistrationDelivery registrationDelivery(Environment environment) {
        return new SmtpRegistrationDelivery(environment);
    }
}

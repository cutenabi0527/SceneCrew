package com.ssafy.scenecrew.account.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Evaluated after user configuration so custom and test encoders take precedence without @Primary. */
@AutoConfiguration
@ConditionalOnProperty(name="scenecrew.auth.public-enabled",havingValue="true")
public class PasswordEncoderAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    PasswordEncoder defaultPasswordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}

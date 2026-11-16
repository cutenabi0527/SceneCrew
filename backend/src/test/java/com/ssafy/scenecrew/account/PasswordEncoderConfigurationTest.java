package com.ssafy.scenecrew.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.ssafy.scenecrew.account.config.PasswordEncoderAutoConfiguration;
import static org.assertj.core.api.Assertions.*;

class PasswordEncoderConfigurationTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PasswordEncoderAutoConfiguration.class));
    @Test void enabledContextHasExactlyOneDefaultEncoderAtCostTen() {
        context.withPropertyValues("scenecrew.auth.public-enabled=true").run(c->{
            assertThat(c).hasNotFailed().hasSingleBean(PasswordEncoder.class);
            assertThat(c.getBean(PasswordEncoder.class)).isInstanceOf(BCryptPasswordEncoder.class);
            assertThat(c.getBean(PasswordEncoder.class).encode("SyntheticPass8!")).startsWith("$2a$10$");
        });
    }
    @Test void defaultDisabledContextDoesNotCreateEncoderOrEnableAuthentication() {
        context.run(c->assertThat(c).hasNotFailed().doesNotHaveBean(PasswordEncoder.class));
    }
    @Test void customUserConfigurationDeclaredAfterAutoConfigurationKeepsItsBean() {
        context.withPropertyValues("scenecrew.auth.public-enabled=true").withUserConfiguration(Custom.class)
                .run(PasswordEncoderConfigurationTest::assertCustom);
    }
    @Test void customUserConfigurationDeclaredBeforeAutoConfigurationKeepsItsBean() {
        new ApplicationContextRunner().withUserConfiguration(Custom.class)
                .withConfiguration(AutoConfigurations.of(PasswordEncoderAutoConfiguration.class))
                .withPropertyValues("scenecrew.auth.public-enabled=true")
                .run(PasswordEncoderConfigurationTest::assertCustom);
    }
    private static void assertCustom(org.springframework.boot.test.context.assertj.AssertableApplicationContext c) {
        assertThat(c).hasNotFailed().hasSingleBean(PasswordEncoder.class).doesNotHaveBean("defaultPasswordEncoder");
        assertThat(c.getBean(PasswordEncoder.class)).isSameAs(c.getBean("customEncoder"));
        assertThat(c.getBean(PasswordEncoder.class).encode("SyntheticPass8!")).startsWith("$2a$06$");
    }
    @Configuration(proxyBeanMethods=false) static class Custom {
        @Bean PasswordEncoder customEncoder() { return new BCryptPasswordEncoder(6); }
    }
}

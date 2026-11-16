package com.ssafy.scenecrew.invite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ssafy.scenecrew.event.service.InviteTokens;
import com.ssafy.scenecrew.invite.config.InviteConfiguration;
import com.ssafy.scenecrew.invite.service.InviteService;
import com.ssafy.scenecrew.invite.service.StoredInviteTokens;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class InviteConfigurationTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(InviteConfiguration.class)
            .withBean(JdbcTemplate.class,()->mock(JdbcTemplate.class));
    @Test void publicModeCannotStartWithoutEncryptionKeys() {
        context.withPropertyValues("scenecrew.auth.public-enabled=true","scenecrew.invite.web-origin=https://web.example.invalid")
                .run(c->assertThat(c).hasFailed().getFailure().hasRootCauseMessage("Invalid invite encryption key configuration"));
    }
    @Test void publicModeRequiresTrustedOriginAndRealIssuer() {
        var configured=context.withPropertyValues("scenecrew.auth.public-enabled=true",
                "SCENECREW_DATA_ENCRYPTION_KEYS=test:"+InviteCipherTest.key(1),"SCENECREW_DATA_ENCRYPTION_ACTIVE_KEY_ID=test");
        for(String origin:new String[]{"", "https://web.example.invalid/path", "https://user@web.example.invalid", "https://web.example.invalid?query", "http://web.example.invalid"})
            configured.withPropertyValues("scenecrew.invite.web-origin="+origin)
                    .run(c->assertThat(c).hasFailed().getFailure().hasRootCauseMessage("Invalid invite WEB origin configuration"));
        configured.withPropertyValues("scenecrew.invite.web-origin=https://web.example.invalid")
                .run(c->{assertThat(c).hasNotFailed().hasSingleBean(InviteTokens.class).hasSingleBean(InviteService.class);
                    assertThat(c.getBean(InviteTokens.class)).isInstanceOf(StoredInviteTokens.class);});
    }
    @Test void closedPublicModeDoesNotRequireProductionKeys() {
        context.withPropertyValues("scenecrew.auth.public-enabled=false").run(c->assertThat(c).hasNotFailed().doesNotHaveBean(InviteTokens.class));
    }
}

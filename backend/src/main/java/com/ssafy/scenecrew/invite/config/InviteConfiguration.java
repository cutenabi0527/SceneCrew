package com.ssafy.scenecrew.invite.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ssafy.scenecrew.event.service.InviteTokens;
import com.ssafy.scenecrew.invite.service.InviteCipher;
import com.ssafy.scenecrew.invite.service.InviteService;
import com.ssafy.scenecrew.invite.service.StoredInviteTokens;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class InviteConfiguration {
    @Bean InviteCipher inviteCipher(
            @Value("${SCENECREW_DATA_ENCRYPTION_KEYS:}") String keys,
            @Value("${SCENECREW_DATA_ENCRYPTION_ACTIVE_KEY_ID:}") String active) {
        return new InviteCipher(keys, active);
    }
    @Bean @ConditionalOnMissingBean(InviteTokens.class)
    InviteTokens inviteTokens(JdbcTemplate jdbc, InviteCipher cipher) { return new StoredInviteTokens(jdbc, cipher); }

    @Bean InviteService inviteService(JdbcTemplate jdbc, InviteCipher cipher,
            @Value("${scenecrew.invite.web-origin:}") String origin) {
        try {
            URI uri = URI.create(origin);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid invite WEB origin configuration"); }
        return new InviteService(jdbc, cipher, origin.replaceAll("/$", ""));
    }
}

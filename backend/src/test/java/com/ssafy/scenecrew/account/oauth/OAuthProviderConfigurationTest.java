package com.ssafy.scenecrew.account.oauth;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import com.ssafy.scenecrew.account.controller.AuthTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OAuthProviderConfigurationTest {
    @Test void androidReturnRequiresExplicitEnablementAndExactAppPath() {
        var env=new MockEnvironment()
                .withProperty("scenecrew.auth.oauth.web-home-uri","https://web.example.invalid/login/oauth")
                .withProperty("scenecrew.auth.oauth.android-return-uri","https://android.example.invalid/app/oauth");
        assertThat(OAuthWebConfiguration.loginReturns(env)).containsOnlyKeys("WEB:HOME");
        env.setProperty("scenecrew.auth.oauth.android-enabled","true");
        assertThat(OAuthWebConfiguration.loginReturns(env)).containsEntry("ANDROID:HOME",
                URI.create("https://android.example.invalid/app/oauth"));
        env.setProperty("scenecrew.auth.oauth.android-return-uri","https://android.example.invalid/login/oauth");
        assertThatThrownBy(()->OAuthWebConfiguration.loginReturns(env)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void androidAuthorizeAndExchangeRejectWhenFeatureFlagIsAbsent() throws Exception {
        var env = new MockEnvironment()
                .withProperty("scenecrew.auth.oauth.web-home-uri", "https://web.example.invalid/login/oauth")
                .withProperty("scenecrew.auth.oauth.android-return-uri", "https://android.example.invalid/app/oauth")
                .withProperty("scenecrew.auth.oauth.state-ttl-seconds", "600");
        var attempts = mock(RedisOAuthAttempts.class);
        var signup = mock(SocialSignupService.class);
        var clock = Clock.systemUTC();
        var transport = new AuthTransport(Set.of("https://web.example.invalid"), Set.of("https://localhost"));
        try (var login = new OAuthLoginService(List.of(), OAuthWebConfiguration.loginReturns(env),
                attempts, null, null, null, clock, OAuthWebConfiguration.stateTtl(env))) {
            var controller = new OAuthWebController(login, signup, mock(OAuthIdentityChanges.class),
                    attempts, transport, clock, env);
            var mvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(get("/api/v1/auth/oauth/GOOGLE/authorize")
                    .header("X-SceneCrew-Client", "ANDROID").header("Origin", "https://localhost")
                    .param("purpose", "LOGIN").param("returnRoute", "HOME")
                    .param("codeChallenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
                    .param("codeChallengeMethod", "S256"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AUTH_CHANNEL"));
            mvc.perform(post("/api/v1/auth/oauth/exchange")
                    .header("X-SceneCrew-Client", "ANDROID").header("Origin", "https://localhost")
                    .contentType("application/json").content("""
                            {"action":"LOGIN","ticket":"%s","codeVerifier":"%s"}
                            """.formatted("A".repeat(43), "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AUTH_CHANNEL"));
            verifyNoInteractions(attempts, signup);
        }
    }

    @ParameterizedTest @EnumSource(SocialProvider.class) void noSettingsNeverProducesFakeSuccess(SocialProvider provider) {
        var client=new HttpOAuthProviderClient(provider,new OAuthClientSettings(null,null,null,null));
        assertThatThrownBy(()->client.authorizationUrl("state","A".repeat(43)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        assertThatThrownBy(()->client.verifiedSubject("code","state","A".repeat(43))).isInstanceOf(BusinessException.class);
    }
    @Test void knownRfcPkceS256Vector() {
        assertThat(HttpOAuthProviderClient.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }
    @Test void invalidConfiguredRedirectFailsClosed() {
        var client=new HttpOAuthProviderClient(SocialProvider.GOOGLE,new OAuthClientSettings("id","secret",
                URI.create("https://evil.example.invalid/unregistered?secret=bad"),"openid"));
        assertThatThrownBy(()->client.authorizationUrl("state","A".repeat(43))).isInstanceOf(BusinessException.class);
    }
}

package com.ssafy.scenecrew.account.oauth;

import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="scenecrew.auth",name={"public-enabled","oauth.web-enabled"},havingValue="true")
public class OAuthWebConfiguration {
    static final Duration SIGNUP_TTL = Duration.ofMinutes(5);

    @Bean SocialSignupService socialSignup(RedisOAuthAttempts attempts, JdbcTemplate jdbc,
            PlatformTransactionManager manager, LoginTokenIssuer tokens, RedisLoginSessions sessions, Clock clock) {
        return new SocialSignupService(attempts, new SocialSignupAccounts(jdbc, manager),
                tokens, sessions, clock, SIGNUP_TTL);
    }

    @Bean RedisOAuthAttempts oauthAttempts(StringRedisTemplate redis,Clock clock) { return new RedisOAuthAttempts(redis,clock); }
    @Bean OAuthAccountReader oauthAccounts(JdbcTemplate jdbc,PlatformTransactionManager manager) { return new OAuthAccountReader(jdbc,manager); }
    @Bean OAuthLoginService oauthLogin(Environment env,RedisOAuthAttempts attempts,OAuthAccountReader accounts,
            LoginTokenIssuer tokens,RedisLoginSessions sessions,Clock clock) {
        var clients=new ArrayList<OAuthProviderClient>();
        for (var provider:SocialProvider.values()) {
            String prefix="scenecrew.auth.oauth."+provider.name().toLowerCase(Locale.ROOT)+".";
            String redirect=env.getProperty(prefix+"redirect-uri");
            clients.add(new HttpOAuthProviderClient(provider,new OAuthClientSettings(env.getProperty(prefix+"client-id"),
                    env.getProperty(prefix+"client-secret"),redirect==null||redirect.isBlank()?null:URI.create(redirect),
                    env.getProperty(prefix+"scope"))));
        }
        return new OAuthLoginService(clients,loginReturns(env),attempts,accounts,tokens,sessions,clock,stateTtl(env));
    }
    static Map<String,URI> loginReturns(Environment env) {
        var web=URI.create(env.getRequiredProperty("scenecrew.auth.oauth.web-home-uri"));
        if (!env.getProperty("scenecrew.auth.oauth.android-enabled",Boolean.class,false)) return Map.of("WEB:HOME",web);
        var app=URI.create(env.getRequiredProperty("scenecrew.auth.oauth.android-return-uri"));
        if (!"/app/oauth".equals(app.getRawPath())) throw new IllegalArgumentException("Invalid Android OAuth return path");
        return Map.of("WEB:HOME",web,"ANDROID:HOME",app);
    }
    // #227 WEB 연결(014·015). 재확인 증명과 연결 상태는 로그인 OAuth와 같은 Redis·제공자 client를 쓴다.
    @Bean RedisIdentityReauthentication identityReauthentication(StringRedisTemplate redis,JdbcTemplate jdbc,
            LocalCredentialVerifier verifier,LoginFailureLimiter limiter,RedisOAuthAttempts attempts,Clock clock) {
        return new RedisIdentityReauthentication(redis,jdbc,verifier,limiter,attempts,clock);
    }
    @Bean OAuthIdentityChanges oauthIdentityChanges(SessionAuthenticator authentication,JdbcTemplate jdbc,
            PlatformTransactionManager manager,RedisIdentityReauthentication reauthentication,RedisOAuthAttempts attempts,
            OAuthLoginService login,Clock clock,Environment env) {
        return new OAuthIdentityChanges(authentication,new IdentityChanges(jdbc,manager,reauthentication),reauthentication,
                attempts,login.clients(),clock,stateTtl(env));
    }
    /** 연결 결과로 돌아갈 WEB 내 정보 화면. 로그인 복귀 주소와 같은 출처를 쓴다. */
    static URI linkReturn(Environment env) {
        return URI.create(env.getRequiredProperty("scenecrew.auth.oauth.web-home-uri")).resolve("/account/profile");
    }
    static Duration stateTtl(Environment env) {
        return Duration.ofSeconds(env.getRequiredProperty("scenecrew.auth.oauth.state-ttl-seconds",Long.class));
    }
}

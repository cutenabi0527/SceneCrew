package com.ssafy.scenecrew.account.oauth;

import java.net.URI;
import com.ssafy.scenecrew.global.error.*;

/** No default credentials, callback host or scope. Values come from approved deployment settings. */
public record OAuthClientSettings(String clientId, String clientSecret, URI redirectUri, String scope) {
    public void requireConfigured(SocialProvider provider) {
        if (clientId == null || clientId.isBlank() || redirectUri == null || scope == null
                || (provider != SocialProvider.KAKAO && (clientSecret == null || clientSecret.isBlank())))
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        if (!"https".equals(redirectUri.getScheme()) || redirectUri.getHost() == null
                || redirectUri.getUserInfo() != null || redirectUri.getQuery() != null || redirectUri.getFragment() != null
                || !redirectUri.getPath().equals("/api/v1/auth/oauth/" + provider.name() + "/callback"))
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        if ((provider == SocialProvider.GOOGLE || provider == SocialProvider.NAVER) && !java.util.List.of(scope.split(" +")).contains("openid"))
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    }
    @Override public String toString() { return "OAuthClientSettings[redacted]"; }
}

package com.ssafy.scenecrew.account.oauth;

import java.net.URI;

/** Server-side code redemption only. Never accepts a client-supplied provider access token. */
public interface OAuthProviderClient extends AutoCloseable {
    @Override default void close() { }
    SocialProvider provider();
    URI authorizationUrl(String state, String codeVerifier);
    String verifiedSubject(String code, String state, String codeVerifier);
}

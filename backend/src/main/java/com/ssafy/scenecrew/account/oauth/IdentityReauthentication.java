package com.ssafy.scenecrew.account.oauth;

import com.ssafy.scenecrew.account.service.SessionAuthenticator.Principal;

/** An adapter must verify freshness, account/session/tokenVersion, purpose/target and replay rules.
 * No Access Token or OAuth LOGIN ticket is itself a reauthentication proof.
 */
public interface IdentityReauthentication {
    enum Operation { LINK_IDENTITY, UNLINK_IDENTITY }
    void require(Principal principal, Operation operation, SocialProvider provider, String proof);
    /** Atomically validate and consume while the caller still holds the Account write lock. */
    void consume(Principal principal, Operation operation, SocialProvider provider, String proof);
    static IdentityReauthentication unavailable() {
        return new IdentityReauthentication() {
            public void require(Principal p, Operation o, SocialProvider target, String proof) { throw required(); }
            public void consume(Principal p, Operation o, SocialProvider target, String proof) { throw required(); }
            private IdentityChangeException required() {
                return new IdentityChangeException(IdentityChangeException.Reason.REAUTHENTICATION_REQUIRED);
            }
        };
    }
}

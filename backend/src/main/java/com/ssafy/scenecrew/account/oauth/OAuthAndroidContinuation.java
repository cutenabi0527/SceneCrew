package com.ssafy.scenecrew.account.oauth;

import com.ssafy.scenecrew.account.service.EmailLoginService;

/**
 * Android continuation adapter. The transport supplies the app's retained verifier.
 * Ticket validation, atomic consumption and account/session writes remain in the shared services.
 */
public final class OAuthAndroidContinuation {
    private final OAuthLoginService login;
    private final SocialSignupService signup;

    public OAuthAndroidContinuation(OAuthLoginService login, SocialSignupService signup) {
        this.login = login;
        this.signup = signup;
    }

    public OAuthLoginService.Continuation exchange(String ticket, String initiatorProof) {
        return login.continueLogin(ticket, "ANDROID", initiatorProof, signup);
    }

    public EmailLoginService.Result complete(String signupTicket, String initiatorProof,
            String displayName, SocialSignupService.Consent consent) {
        return signup.complete(signupTicket, "ANDROID", initiatorProof, displayName, consent);
    }
}

package com.ssafy.scenecrew.account.oauth;

/** Internal outcomes only; no HTTP status or response wire is defined by #227. */
public final class IdentityChangeException extends RuntimeException {
    public enum Reason { REAUTHENTICATION_REQUIRED, PROVIDER_ALREADY_LINKED, IDENTITY_ALREADY_LINKED,
        LAST_IDENTITY, TARGET_NOT_FOUND, INVALID_SUBJECT }
    private final Reason reason;
    public IdentityChangeException(Reason reason) { super(reason.name()); this.reason=reason; }
    public Reason reason() { return reason; }
}

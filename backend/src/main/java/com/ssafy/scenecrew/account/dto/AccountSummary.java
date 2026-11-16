package com.ssafy.scenecrew.account.dto;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.scenecrew.account.service.LocalCredentialVerifier.VerifiedAccount;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AccountSummary(UUID id, String displayName, String primaryEmail,
        boolean primaryEmailVerified, String status) {
    public static AccountSummary fromVerifiedLocal(VerifiedAccount account) {
        return new AccountSummary(account.accountId(), account.displayName(), account.email(), account.email() != null, "ACTIVE");
    }

    @Override public String toString() { return "AccountSummary[redacted]"; }
}

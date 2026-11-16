package com.ssafy.scenecrew.account.dto;

import java.time.Instant;

public record WebLoginResult(AccountSummary account, String channel,
        Instant accessExpiresAt, Instant refreshExpiresAt) {
    public WebLoginResult(AccountSummary account, Instant accessExpiresAt, Instant refreshExpiresAt) {
        this(account, "WEB", accessExpiresAt, refreshExpiresAt);
    }
    public WebLoginResult {
        if (!"WEB".equals(channel)) throw new IllegalArgumentException("Invalid response channel");
    }
    @Override public String toString() { return "WebLoginResult[redacted]"; }
}

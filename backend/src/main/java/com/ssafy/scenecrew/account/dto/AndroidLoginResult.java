package com.ssafy.scenecrew.account.dto;

import java.time.Instant;

public record AndroidLoginResult(AccountSummary account, String channel,
        Instant accessExpiresAt, Instant refreshExpiresAt, String accessToken, String refreshToken) {
    public AndroidLoginResult(AccountSummary account, Instant accessExpiresAt, Instant refreshExpiresAt,
            String accessToken, String refreshToken) {
        this(account, "ANDROID", accessExpiresAt, refreshExpiresAt, accessToken, refreshToken);
    }
    public AndroidLoginResult {
        if (!"ANDROID".equals(channel)) throw new IllegalArgumentException("Invalid response channel");
    }
    @Override public String toString() { return "AndroidLoginResult[redacted]"; }
}

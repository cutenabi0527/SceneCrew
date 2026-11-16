package com.ssafy.scenecrew.account.service;
import java.time.Instant;
import java.util.UUID;
public record LoginTokens(UUID sessionId, String accessToken, String refreshToken,
        Instant accessExpiresAt, Instant refreshExpiresAt) {
    @Override public String toString() { return "LoginTokens[redacted]"; }
}

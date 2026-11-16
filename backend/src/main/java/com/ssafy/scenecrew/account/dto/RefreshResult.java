package com.ssafy.scenecrew.account.dto;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 공개 Refresh 응답(AuthRefreshWeb·AuthRefreshAndroid). WEB 본문에는 토큰을 싣지 않는다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefreshResult(String channel, Instant accessExpiresAt, Instant refreshExpiresAt,
        String accessToken, String refreshToken) {
    public static RefreshResult web(Instant accessExpiresAt, Instant refreshExpiresAt) {
        return new RefreshResult("WEB", accessExpiresAt, refreshExpiresAt, null, null);
    }
    public static RefreshResult android(Instant accessExpiresAt, Instant refreshExpiresAt, String access, String refresh) {
        return new RefreshResult("ANDROID", accessExpiresAt, refreshExpiresAt, access, refresh);
    }
    @Override public String toString() { return "RefreshResult[redacted]"; }
}

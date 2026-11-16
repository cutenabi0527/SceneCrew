package com.ssafy.scenecrew.account.dto;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import com.ssafy.scenecrew.account.service.EmailLoginService;
import com.ssafy.scenecrew.account.service.LoginTokens;

/** 자격·Redis 저장이 성공한 서비스 결과를 전달하는 어댑터. 공개 Controller 연결은 보류. */
public final class LoginResponses {
    private LoginResponses() {}
    public static ResponseEntity<?> web(EmailLoginService.Result result, Clock clock) {
        var tokens = result.tokens();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE,
                        cookie("__Secure-sc-access", tokens.accessToken(), "/api/v1",
                                Duration.between(clock.instant(), tokens.accessExpiresAt())),
                        cookie("__Secure-sc-refresh", tokens.refreshToken(), "/api/v1/auth",
                                Duration.between(clock.instant(), tokens.refreshExpiresAt())))
                .body(new WebLoginResult(AccountSummary.fromVerifiedLocal(result.account()),
                        tokens.accessExpiresAt(), tokens.refreshExpiresAt()));
    }
    public static ResponseEntity<?> android(EmailLoginService.Result result) {
        var t = result.tokens();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AndroidLoginResult(AccountSummary.fromVerifiedLocal(result.account()),
                        t.accessExpiresAt(), t.refreshExpiresAt(), t.accessToken(), t.refreshToken()));
    }
    /** 공개 Refresh 성공. WEB은 두 Cookie를 새 값으로 교체하고, Android는 JSON으로만 전달한다. */
    public static ResponseEntity<RefreshResult> refresh(LoginTokens t, String channel, Clock clock) {
        var ok = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
        if ("WEB".equals(channel))
            return ok.header(HttpHeaders.SET_COOKIE,
                            cookie("__Secure-sc-access", t.accessToken(), "/api/v1",
                                    Duration.between(clock.instant(), t.accessExpiresAt())),
                            cookie("__Secure-sc-refresh", t.refreshToken(), "/api/v1/auth",
                                    Duration.between(clock.instant(), t.refreshExpiresAt())))
                    .body(RefreshResult.web(t.accessExpiresAt(), t.refreshExpiresAt()));
        return ok.body(RefreshResult.android(t.accessExpiresAt(), t.refreshExpiresAt(), t.accessToken(), t.refreshToken()));
    }
    private static String cookie(String name, String value, String path, Duration remaining) {
        return ResponseCookie.from(name, value).path(path).secure(true).httpOnly(true)
                .sameSite("Lax").maxAge(Math.max(0, remaining.toSeconds())).build().toString();
    }
}

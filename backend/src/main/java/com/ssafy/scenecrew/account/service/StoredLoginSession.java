package com.ssafy.scenecrew.account.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** #90 v1 저장값의 읽기 모델. refresh hash와 요청 소유값은 검증 후 보존하지 않는다. */
public record StoredLoginSession(UUID accountId, UUID identityId, long tokenVersion, String channel, String provider) {
    /** Existing v1 sessions were issued exclusively for LOCAL. */
    public StoredLoginSession(UUID accountId, UUID identityId, long tokenVersion, String channel) {
        this(accountId, identityId, tokenVersion, channel, "LOCAL");
    }
    public static StoredLoginSession parse(String value, Clock clock) {
        return parse(value, clock, true);
    }
    /** Only a still-live, atomically committed Logout receipt may outlive the original Refresh expiry.
     * This is never an Access/Refresh authentication path.
     */
    static StoredLoginSession completedLogout(String value, Clock clock) {
        return parse(value, clock, false);
    }
    private static StoredLoginSession parse(String value, Clock clock, boolean requireUnexpired) {
        if (value == null) throw AccessJwtVerifier.denied();
        try {
            String[] f = value.split("\\|", -1);
            boolean local=f.length==8 && "v1".equals(f[0]);
            boolean social=f.length==9 && "v2".equals(f[0]) && java.util.Set.of("GOOGLE","KAKAO","NAVER").contains(f[8]);
            if (!(local || social)
                    || !f[4].matches("0|[1-9][0-9]*")
                    || !("WEB".equals(f[5]) || "ANDROID".equals(f[5]))
                    || !f[6].matches("[0-9a-f]{64}")) throw AccessJwtVerifier.denied();
            Instant expiresAt = Instant.parse(f[7]);
            if (requireUnexpired && !expiresAt.isAfter(clock.instant())) throw AccessJwtVerifier.denied();
            AccessJwtVerifier.uuid(f[1]);
            return new StoredLoginSession(AccessJwtVerifier.uuid(f[2]), AccessJwtVerifier.uuid(f[3]),
                    Long.parseLong(f[4]), f[5], local ? "LOCAL" : f[8]);
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            throw AccessJwtVerifier.denied();
        }
    }
    @Override public String toString() { return "StoredLoginSession[redacted]"; }
}

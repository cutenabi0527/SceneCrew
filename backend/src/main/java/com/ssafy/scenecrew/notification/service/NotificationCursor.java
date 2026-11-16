package com.ssafy.scenecrew.notification.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.notification.dto.NotificationView;

/** Account-bound, purpose-separated HMAC cursor; no snapshot or time expiry. */
public final class NotificationCursor {
    private final byte[] key;
    public NotificationCursor(byte[] signingKey) {
        if (signingKey.length < 32) throw new IllegalArgumentException("Cursor key is too short");
        key = mac(signingKey, "scenecrew:notifications:cursor:v1");
    }
    public record Position(Instant createdAt, UUID id) {}
    public String encode(UUID account, NotificationView last) {
        String value = "1|" + account + "|" + last.createdAt() + "|" + last.id();
        return base64(value.getBytes(StandardCharsets.UTF_8)) + "." + base64(mac(key, value));
    }
    public Position decode(UUID account, String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 512 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.");
            byte[] decoded = Base64.getUrlDecoder().decode(parts[0]);
            String value = new String(decoded, StandardCharsets.UTF_8);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!base64(decoded).equals(parts[0]) || !base64(signature).equals(parts[1])
                    || !MessageDigest.isEqual(mac(key, value), signature))
                throw new IllegalArgumentException();
            String[] fields = value.split("\\|", -1);
            if (fields.length != 4 || !fields[0].equals("1") || !fields[1].equals(account.toString()))
                throw new IllegalArgumentException();
            return new Position(Instant.parse(fields[2]), UUID.fromString(fields[3]));
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
    private static String base64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static byte[] mac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException(failure); }
    }
}

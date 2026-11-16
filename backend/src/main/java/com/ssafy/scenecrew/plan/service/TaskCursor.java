package com.ssafy.scenecrew.plan.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/** Live keyset traversal, not a cross-page snapshot. Uses a purpose-separated existing signing key. */
public final class TaskCursor {
    public record Scope(UUID eventId, UUID accountId, UUID memberId, long round, String role) {
        private String value() { return eventId + "|" + accountId + "|" + memberId + "|" + round + "|" + role; }
    }
    public record Position(Instant startAt, UUID id) { }
    private final byte[] key;

    public TaskCursor(byte[] signingKey) {
        if (signingKey.length < 32) throw new IllegalArgumentException("Cursor key is too short");
        key = mac(signingKey, "scenecrew:tasks:cursor:v1");
    }

    public String encode(Scope scope, Position position) {
        String value = "1|" + scope.value() + "|" + (position.startAt() == null ? "-" : position.startAt()) + "|" + position.id();
        return base64(value.getBytes(StandardCharsets.UTF_8)) + "." + base64(mac(key, value));
    }

    public Position decode(Scope scope, String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 512 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.");
            byte[] decoded = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            String value = new String(decoded, StandardCharsets.UTF_8);
            if (!base64(decoded).equals(parts[0]) || !base64(signature).equals(parts[1])
                    || !MessageDigest.isEqual(mac(key, value), signature)) throw new IllegalArgumentException();
            String[] fields = value.split("\\|", -1);
            if (fields.length != 8 || !fields[0].equals("1")
                    || !String.join("|", java.util.Arrays.copyOfRange(fields, 1, 6)).equals(scope.value()))
                throw new IllegalArgumentException();
            UUID id = UUID.fromString(fields[7]);
            if (!id.toString().equals(fields[7])) throw new IllegalArgumentException();
            return new Position(fields[6].equals("-") ? null : Instant.parse(fields[6]), id);
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

package com.ssafy.scenecrew.global.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 목록 다음 쪽 cursor. 마지막 항목의 (정렬 값, id)를 담고 서명한다. 업무 cursor(TaskCursor)와 같은 방식이다.
 *
 * <p>용도(purpose)마다 키를 나눠 다른 목록의 cursor를 받지 않는다. scope(행사·계정 등)가 다르면 거절한다.
 * 쪽 사이의 일관 스냅샷이 아니라 현재 값 기준으로 이어서 읽는다. 잘못된 cursor는 400이다.
 */
public final class SignedCursor {
    /** sortKey는 '|'를 담지 않는 문자열(시각 ISO 표기, 정규화한 이름의 해시 등) */
    public record Position(String sortKey, UUID id) { }

    private final byte[] key;

    public SignedCursor(byte[] signingKey, String purpose) {
        if (signingKey.length < 32) throw new IllegalArgumentException("Cursor key is too short");
        key = mac(signingKey, "scenecrew:" + purpose + ":cursor:v1");
    }

    public String encode(String scope, Position position) {
        if (position.sortKey().contains("|") || scope.contains("|")) throw new IllegalArgumentException("'|' in cursor");
        String value = "1|" + scope + "|" + position.sortKey() + "|" + position.id();
        return base64(value.getBytes(StandardCharsets.UTF_8)) + "." + base64(mac(key, value));
    }

    /** cursor가 null이면 null(첫 쪽) */
    public Position decode(String scope, String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 1024 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.");
            byte[] decoded = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            String value = new String(decoded, StandardCharsets.UTF_8);
            if (!base64(decoded).equals(parts[0]) || !base64(signature).equals(parts[1])
                    || !MessageDigest.isEqual(mac(key, value), signature)) throw new IllegalArgumentException();
            String[] fields = value.split("\\|", -1);
            if (fields.length != 4 || !fields[0].equals("1") || !fields[1].equals(scope)) throw new IllegalArgumentException();
            UUID id = UUID.fromString(fields[3]);
            if (!id.toString().equals(fields[3])) throw new IllegalArgumentException();
            return new Position(fields[2], id);
        } catch (IllegalArgumentException invalid) {
            throw StrictJson.invalid("cursor", "FORMAT");
        }
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] mac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }
}

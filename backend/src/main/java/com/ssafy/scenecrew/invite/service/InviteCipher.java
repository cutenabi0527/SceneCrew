package com.ssafy.scenecrew.invite.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/** v1:keyId:base64(iv):base64(ciphertext):base64(tag). Never include input in exceptions. */
public final class InviteCipher {
    private final Map<String, SecretKeySpec> keys = new HashMap<>();
    private final String active;
    private final SecureRandom random = new SecureRandom();

    public InviteCipher(String configured, String active) {
        this.active = active;
        try {
            for (String entry : configured.split(",", -1)) {
                String[] pair = entry.split(":", -1);
                if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException();
                byte[] key = Base64.getDecoder().decode(pair[1]);
                if (key.length != 32 || keys.putIfAbsent(pair[0], new SecretKeySpec(key, "AES")) != null)
                    throw new IllegalArgumentException();
            }
            if (!keys.containsKey(active)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid invite encryption key configuration");
        }
    }

    public String newToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String encrypt(UUID event, String hash, String token) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(active), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(event, hash));
            byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return String.join(":", "v1", active, encode(iv), encode(Arrays.copyOf(sealed, sealed.length - 16)),
                    encode(Arrays.copyOfRange(sealed, sealed.length - 16, sealed.length)));
        } catch (Exception failure) { throw unavailable(); }
    }

    public String decrypt(UUID event, String hash, String envelope) {
        try {
            String[] parts = envelope.split(":", -1);
            if (parts.length != 5 || !parts[0].equals("v1") || !keys.containsKey(parts[1])) throw new IllegalArgumentException();
            byte[] iv = decode(parts[2]), ciphertext = decode(parts[3]), tag = decode(parts[4]);
            if (iv.length != 12 || tag.length != 16) throw new IllegalArgumentException();
            byte[] sealed = Arrays.copyOf(ciphertext, ciphertext.length + tag.length);
            System.arraycopy(tag, 0, sealed, ciphertext.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(parts[1]), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(event, hash));
            String token = new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
            if (!token.matches("[A-Za-z0-9_-]{43}") || !InviteJoinService.tokenHash(token).equals(hash))
                throw new IllegalArgumentException();
            return token;
        } catch (Exception failure) { throw unavailable(); }
    }

    // Fixed ASCII UUID and 64 lowercase hex hash, separated by LF; version is domain separation.
    private byte[] aad(UUID event, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
        return ("scenecrew-invite-v1\n" + event + "\n" + hash).getBytes(StandardCharsets.US_ASCII);
    }
    private static String encode(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static byte[] decode(String value) { return Base64.getDecoder().decode(value); }
    static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
}

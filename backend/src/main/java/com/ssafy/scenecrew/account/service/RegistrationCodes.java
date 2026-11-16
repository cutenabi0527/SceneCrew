package com.ssafy.scenecrew.account.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** #224 personal selection, not team security acceptance. Never serialize or log a derived code. */
public final class RegistrationCodes {
    private final SecretKeySpec key;
    public RegistrationCodes(byte[] secret) {
        if (secret.length < 32) throw new IllegalArgumentException("Missing registration proof key");
        key = new SecretKeySpec(secret.clone(), "HmacSHA256");
    }
    public String keyId() {
        return HexFormat.of().formatHex(hmac("scenecrew:registration-email-code:key-id:v1"));
    }
    public String derive(UUID requestId) {
        // Rejection sampling avoids modulo bias. All callers use exactly the same canonical UUID.
        for (int counter=0;;counter++) {
            byte[] value=hmac("scenecrew:registration-email-code:v1:"+requestId+":"+counter);
            long number=Integer.toUnsignedLong(ByteBuffer.wrap(value).getInt());
            if (number < 4294000000L) return String.format(Locale.ROOT,"%06d",number % 1000000);
        }
    }
    public boolean matches(UUID requestId,String supplied) {
        return supplied != null && supplied.matches("[0-9]{6}") && MessageDigest.isEqual(
                derive(requestId).getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII));
    }
    private byte[] hmac(String input) {
        try { var mac=Mac.getInstance("HmacSHA256"); mac.init(key);
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Proof configuration unavailable"); }
    }
}

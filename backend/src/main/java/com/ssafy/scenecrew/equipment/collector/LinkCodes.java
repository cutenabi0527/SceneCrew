package com.ssafy.scenecrew.equipment.collector;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 장비 연결 코드(#394). 사람이 노트북 프로그램에 옮겨 적는 8자(4자-4자)이고, 헷갈리는 글자(0·O·1·I·L)는 쓰지 않는다.
 * 대소문자·하이픈·공백은 무시한다. 서버에는 정규화한 값의 SHA-256만 둔다.
 */
public final class LinkCodes {
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private LinkCodes() {
    }

    public static String generate() {
        StringBuilder out = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) out.append('-');
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    /** 정규화한 코드의 SHA-256. 형식이 아니면 null */
    public static String hash(String code) {
        String normalized = code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (normalized.length() != 8) return null;
        for (char c : normalized.toCharArray()) if (ALPHABET.indexOf(c) < 0) return null;
        return CollectorRegistry.sha256(normalized);
    }
}

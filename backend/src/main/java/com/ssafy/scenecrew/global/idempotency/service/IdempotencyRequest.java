package com.ssafy.scenecrew.global.idempotency.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 요청 키 처리에 넘기는 요청 정보.
 *
 * @param actorAccountId        요청한 계정
 * @param operation             API 구분. 예: SC-API-060
 * @param idempotencyKey        Idempotency-Key 헤더 값(UUID). 소문자로 맞춘다.
 * @param requestHash           같은 요청인지 비교할 값. {@link #hash(Object...)}로 대상·본문·기대 버전을 묶어 만든다.
 * @param eventId               대상 행사. 행사 생성(022)처럼 아직 없으면 null
 * @param actorMemberId         요청한 Member. 행사 밖 요청이면 null
 * @param participationRevision 요청 당시 참여 회차. 다른 회차의 재요청은 결과를 돌려주지 않는다(403).
 */
public record IdempotencyRequest(UUID actorAccountId, String operation, String idempotencyKey, String requestHash,
        UUID eventId, UUID actorMemberId, Long participationRevision) {

    public IdempotencyRequest {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(requestHash, "requestHash");
        idempotencyKey = normalizeKey(idempotencyKey);
    }

    /** 헤더 값이 UUID가 아니면 400. 대소문자만 다른 키는 같은 키로 본다. */
    private static String normalizeKey(String key) {
        if (key == null || key.length() != 36) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key는 UUID여야 합니다.");
        }
        try {
            return UUID.fromString(key).toString();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key는 UUID여야 합니다.");
        }
    }

    /**
     * 요청 비교 값(SHA-256 hex). 값마다 길이를 앞에 붙여 이어 붙이므로 ("ab","c")와 ("a","bc")가 다르게 나온다.
     * null은 빈 값과 구분한다. byte[]는 내용 그대로 넣는다.
     */
    public static String hash(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                byte[] bytes = part == null ? null
                        : part instanceof byte[] b ? b : part.toString().getBytes(StandardCharsets.UTF_8);
                String length = bytes == null ? "-1:" : bytes.length + ":";
                digest.update(length.getBytes(StandardCharsets.US_ASCII));
                if (bytes != null) {
                    digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

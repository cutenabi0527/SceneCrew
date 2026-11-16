package com.ssafy.scenecrew.global.error;

import java.util.Map;

import lombok.Getter;

/**
 * 429 RATE_LIMITED와 함께 Retry-After(초)를 보내는 예외. 전역 오류 처리기가 헤더를 붙이고 본문 모양은 다른 오류와 같다.
 * ErrorCode.RATE_LIMITED의 기본 문구는 로그인용이라, 이 예외를 쓰는 곳은 자기 문구를 넘긴다.
 */
@Getter
public class RateLimitedException extends BusinessException {
    private final long retryAfterSeconds;

    public RateLimitedException(String message, Map<String, Object> details, long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, message, details);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
}

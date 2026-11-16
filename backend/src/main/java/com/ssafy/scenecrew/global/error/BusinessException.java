package com.ssafy.scenecrew.global.error;

import java.util.Map;

import lombok.Getter;

/**
 * 업무 규칙 위반을 알리는 예외. message는 사용자에게 그대로 보이므로 내부 정보를 넣지 않는다.
 */
@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }
}

package com.ssafy.scenecrew.global.error;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 4xx/5xx 응답 본문. details는 코드별로 정한 키만 담고, 없으면 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String requestId, Map<String, Object> details) {

    public static ErrorResponse of(ErrorCode code, String requestId) {
        return new ErrorResponse(code.name(), code.getMessage(), requestId, null);
    }
}

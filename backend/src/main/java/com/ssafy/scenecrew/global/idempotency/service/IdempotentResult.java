package com.ssafy.scenecrew.global.idempotency.service;

import java.util.Map;

/**
 * 업무가 끝난 결과. 최초 응답이자 같은 키 재요청에 그대로 돌려줄 값이다.
 *
 * @param httpStatus 최초 HTTP 상태. 예: 201, 202
 * @param body       응답 본문 DTO. 서버가 만든 안전한 값만 넣는다(외부 AI 원문·음성·토큰 금지, 사전 B IdempotencyRecord).
 * @param resultType 결과 종류. 예: TRANSCRIPTION_ACCEPTED
 * @param resultRefs 결과로 만든 행 ID들. 예: {audioId, jobId}
 * @param replayed   같은 키 재요청이라 기록에서 꺼낸 결과인지
 */
public record IdempotentResult<T>(int httpStatus, T body, String resultType, Map<String, Object> resultRefs,
        boolean replayed) {

    public static <T> IdempotentResult<T> of(int httpStatus, T body, String resultType, Map<String, Object> resultRefs) {
        return new IdempotentResult<>(httpStatus, body, resultType, Map.copyOf(resultRefs), false);
    }
}

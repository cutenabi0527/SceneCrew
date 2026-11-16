package com.ssafy.scenecrew.aijob.kind;

import java.util.Map;

/**
 * ai_job.error에 저장하는 실패 내용. AI가 보낸 error를 그대로 옮기거나, BE가 응답을 받지 못했을 때 만든다.
 *
 * <p>BE가 만드는 코드
 * <ul>
 *   <li>AI_TIMEOUT: 마감이 지남. 다시 시도하지 않는다.</li>
 *   <li>AI_UNAVAILABLE: 연결 실패·서버 오류(다시 시도 가능), 인증 실패·계약과 다른 응답·반영 실패(다시 시도 불가).
 *       details.reason에 원인</li>
 *   <li>READ_CONTEXT_CHANGED: 실행 중 행사 계획(Event.revision)이 바뀜. 작업은 STALE_CONTEXT로 끝난다.</li>
 *   <li>CONSENT_WITHDRAWN: 외부 AI 종류의 요청 계정이 동의를 철회함. AI를 부르지 않거나 결과를 반영하지 않는다.</li>
 * </ul>
 */
public record AiJobError(String code, String message, boolean retryable, Map<String, Object> details) {

    public static final String AI_TIMEOUT = "AI_TIMEOUT";
    public static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";
    public static final String READ_CONTEXT_CHANGED = "READ_CONTEXT_CHANGED";
    /** 외부 AI를 쓰는 작업의 요청 계정이 외부 AI 동의를 철회함. 다시 시도하지 않는다(V9-D43). */
    public static final String CONSENT_WITHDRAWN = "CONSENT_WITHDRAWN";

    public AiJobError {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static AiJobError timeout() {
        return new AiJobError(AI_TIMEOUT, "마감 시각까지 AI 작업을 끝내지 못했습니다.", false, Map.of());
    }

    public static AiJobError unavailable(String reason, String message, boolean retryable) {
        return new AiJobError(AI_UNAVAILABLE, message, retryable, Map.of("reason", reason));
    }

    public static AiJobError contextChanged(long expectedRevision, long currentRevision) {
        return new AiJobError(READ_CONTEXT_CHANGED, "작업 중 행사 계획이 바뀌어 결과를 반영하지 않았습니다.", false,
                Map.of("expectedRevision", expectedRevision, "currentRevision", currentRevision));
    }

    /** 외부 AI 동의 철회로 호출하지 않았거나 결과를 반영하지 않았다. 공개 조회에서는 다시 시도 불가 AI_UNAVAILABLE이다. */
    public static AiJobError consentWithdrawn() {
        return new AiJobError(CONSENT_WITHDRAWN, "요청한 사용자가 외부 AI 처리 동의를 철회했습니다.", false, Map.of());
    }

    /** AI 응답이 계약과 다름. 같은 요청을 다시 보내도 같은 결과일 수 있어 다시 시도하지 않는다. */
    public static AiJobError invalidResult(String message) {
        return unavailable("INVALID_RESULT", message, false);
    }
}

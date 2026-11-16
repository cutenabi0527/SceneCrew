package com.ssafy.scenecrew.aijob.kind;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.domain.AiJob;

/**
 * AI 작업 종류 하나(전사, 보고 분석, 계획 생성 등)의 규칙. 종류마다 빈으로 등록하면 공통 처리에서 찾아 쓴다.
 *
 * <p>공통 처리가 맡는 것: 접수·실행기 전달·시도 횟수·마감·재시도·응답 없음/인증 실패·행사 계획 변경 확인·복구.
 * 종류가 맡는 것: AI 서버 경로, 받은 응답을 요청과 대조해 성공·실패로 나누기, 업무 반영, 끝난 뒤 알림 같은 후속 처리.
 */
public interface AiJobKind {

    /** ai_job.kind 값. 예: ANALYZE_REPORT */
    String kind();

    /** AI 서버 내부 API 경로. 예: /internal/analyze-report */
    String path();

    /**
     * 이 종류가 쓰는 AI 서버 자원. 예: GMS, STT, REPORT
     * 같은 자원을 쓰는 작업끼리만 scenecrew.ai.job.slots의 동시 호출 수를 나눠 쓴다.
     * 자원이 다르면 서로 기다리지 않는다(계획 생성이 GMS를 다 써도 보고 분석은 바로 실행된다).
     */
    String resource();

    /**
     * AI 응답을 읽는다. 응답이 JSON 객체일 때만 불린다(응답 없음·인증 실패·JSON이 아닌 응답은 공통 처리가 판정한다).
     * 식별 필드(jobId/attempt/requestSchemaVersion/inputHash/contextRevision)가 요청과 다르거나 계약을 어기면
     * AiJobError.invalidResult로 실패를 돌려준다. 예외를 던지면 계약 위반 응답으로 본다.
     */
    AiJobOutcome read(AiJobEnvelope request, AiResponse response);

    /**
     * 외부 생성 AI(GMS)에 데이터를 보내는 종류인지. true면 공통 관문(AiJobGate)이 요청 계정의 외부 AI 동의를
     * 접수·AI 호출 직전·결과 반영 직전에 확인하고, 철회됐으면 CONSENT_WITHDRAWN으로 끝낸다(V9-D43, V9-D40).
     * 로컬 모델만 쓰는 종류(전사·보고 분석)는 기본값 false로 둔다.
     */
    default boolean external() {
        return false;
    }

    /**
     * 접수된 작업 하나가 외부 생성 AI(GMS)로 나갈 수 있는지. 공통 관문은 AI 호출 직전·결과 반영 직전 동의 재확인에 이 값을 쓴다
     * (접수 때는 external()만 본다). 기본은 external()이다. 동의가 없어도 접수하고 입력의 허용 여부로 외부 AI 사용을 정하는
     * 종류(ASSIGN_INITIAL의 allowExternalAi)는 재정의해, 외부 AI를 허용한 작업만 동의 철회 시 CONSENT_WITHDRAWN으로 끝낸다.
     */
    default boolean external(AiJob job) {
        return external();
    }

    /** 진행 중 작업 수 한도(V9-D43). 넘으면 접수가 429 RATE_LIMITED·Retry-After로 거절된다. 기본은 한도 없음. */
    default ActiveLimit activeLimit() {
        return ActiveLimit.NONE;
    }

    /**
     * 요청4의 scope(서버가 검증한 최소 범위)를 만든다. 매 시도마다 저장된 작업 값으로 만들어 같은 값이 나가야 한다.
     * 기본은 null이며 이때 scope 필드를 보내지 않는다.
     */
    default JsonNode scope(AiJob job) {
        return null;
    }

    /**
     * 성공 결과를 반영하기 전에 행사 계획(Event.revision)이 입력을 만든 때와 같은지 공통 처리가 확인할지.
     * 다르면 반영하지 않고 STALE_CONTEXT로 끝낸다. 계획에 기대지 않는 종류(전사 등)는 false로 바꾼다.
     */
    default boolean requiresCurrentContext() {
        return true;
    }

    /**
     * 검사를 통과한 성공 결과를 업무에 반영한다(Report.analysis 투영 등). 결과 기록과 같은 트랜잭션, 같은 행 잠금 안에서
     * 작업마다 한 번만 불린다. 현재 권한·동의 같은 반영 직전 검사도 여기서 하고, 반영할 수 없으면 실패 결과를 돌려준다.
     * 예외를 던지면 반영과 기록이 함께 취소되고 작업은 FAILED(APPLY_FAILED)로 끝난다. 기본은 반영 없이 그대로 돌려준다.
     */
    default AiJobOutcome apply(AiJob job, AiJobOutcome outcome) {
        return outcome;
    }

    /**
     * 작업이 끝난 뒤(성공·실패·마감 모두) 이어서 할 일(알림 등). AI_JOB_FINISHED outbox 작업으로 최소 한 번 불린다.
     * 같은 작업으로 두 번 불릴 수 있으므로 업무 반영은 apply에서 하고, 여기서는 두 번 불려도 괜찮은 일만 한다.
     * job은 트랜잭션 밖에서 다시 조회한 값이다. 지연 로딩이 필요한 값을 읽거나 job을 고쳐 저장하지 않는다.
     */
    default void afterFinished(AiJob job) {
    }
}

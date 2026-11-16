package com.ssafy.scenecrew.aijob.client;

import java.time.Duration;

/** AI 서버 내부 API 호출. 테스트는 이 인터페이스를 가짜로 바꿔 끼운다. */
public interface AiClient {

    /**
     * path(예: /internal/analyze-report)로 JSON 본문을 보내고 응답을 그대로 돌려준다.
     * 4xx·5xx도 예외가 아니라 응답으로 돌려준다. timeout 안에 답이 없거나 연결하지 못하면 AiTransportException이다.
     * 기다리는 중 스레드가 중단되면(서버 종료) InterruptedException을 그대로 던진다. 연결 실패와 구별해야
     * 이미 도착했을 수 있는 요청을 다시 보내지 않는다.
     */
    AiResponse post(String path, String jsonBody, Duration timeout) throws AiTransportException, InterruptedException;
}

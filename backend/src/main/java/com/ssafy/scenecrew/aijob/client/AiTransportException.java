package com.ssafy.scenecrew.aijob.client;

/**
 * AI 서버에서 응답을 받지 못함. timeout이면 정한 시간 안에 답이 없었고, 아니면 연결 자체가 실패했다.
 * 요청이 AI 서버에 도착했는지는 알 수 없다.
 */
public class AiTransportException extends Exception {
    private final boolean timeout;

    public AiTransportException(String message, boolean timeout, Throwable cause) {
        super(message, cause);
        this.timeout = timeout;
    }

    public boolean isTimeout() {
        return timeout;
    }
}

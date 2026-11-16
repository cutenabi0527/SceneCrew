package com.ssafy.scenecrew.global.outbox;

import com.ssafy.scenecrew.global.outbox.domain.OutboxEvent;

/**
 * 한 종류(type)의 outbox 작업을 처리하는 쪽. 기능마다 구현체를 빈으로 등록하면 OutboxRelay가 찾아 호출한다.
 *
 * <p>정상 반환하면 처리 완료, 예외를 던지면 실패로 보고 나중에 다시 호출한다.
 * 같은 작업이 두 번 올 수 있으므로(최소 한 번 전달) 두 번 처리해도 결과가 한 번만 반영되게 만든다.
 * 정상 반환은 작업이 끝났거나, 서버가 꺼져도 남는 곳(DB 행 등)에 넘겨졌다는 뜻이어야 한다.
 * 메모리 큐나 스레드 풀에만 넣고 반환하면 outbox는 완료로 기록되고, 그 뒤 서버가 꺼지면 작업이 사라진다.
 * 오래 걸리는 일은 DB에 대기 상태로 남긴 뒤 반환하고, 그 상태를 다시 꺼내는 복구 경로를 함께 만든다.
 * 처리 중인 작업은 scenecrew.outbox.relay.lease-seconds 동안만 붙잡혀 있다. 그 안에 끝내지 못하면
 * 다른 작업자가 다시 꺼낼 수 있고, 늦게 끝난 쪽의 결과는 기록되지 않는다.
 */
public interface OutboxHandler {

    /** 처리할 작업 종류. 예: AI_JOB_REQUESTED */
    String type();

    void handle(OutboxEvent event);
}

package com.ssafy.scenecrew.aijob.pipeline;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.ssafy.scenecrew.aijob.kind.AiJobKind;

/**
 * AI 서버 자원별 동시 호출 수(격벽). 자원마다 세마포어 하나를 두고, 호출 전에 슬롯을 얻고 끝나면 돌려준다.
 *
 * <p>BE의 병목은 스레드가 아니라 AI 서버 자원이다(AI10: STT 동시 1, 보고 모델 동시 1, GMS 동시 2).
 * 그래서 스레드 수로 줄을 세우지 않고 자원 단위로 막는다. 자원이 다르면 서로 기다리지 않고,
 * 같은 자원은 먼저 온 작업부터(공정 모드) 슬롯을 얻는다. 기다리는 동안은 가상 스레드라 OS 스레드를 차지하지 않는다.
 *
 * <p>슬롯은 이 서버 안에서만 센다. BE 서버가 여러 대가 되면 서버 수만큼 늘어나므로, 그때는 DB에서 RUNNING 수를 세는
 * 방식으로 바꾼다(backend/docs/ai-job.md).
 */
@Component
public class AiJobSlots {
    private final Map<String, Semaphore> slots;

    public AiJobSlots(AiJobProperties properties, AiJobKinds kinds) {
        this.slots = properties.slots().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> new Semaphore(e.getValue(), true)));
        for (AiJobKind kind : kinds.all()) {
            if (!slots.containsKey(kind.resource())) {
                throw new IllegalStateException("AI 작업 종류 " + kind.kind() + "의 자원 " + kind.resource()
                        + "에 scenecrew.ai.job.slots가 없습니다.");
            }
        }
    }

    /** timeout 안에 슬롯을 얻으면 true. 얻었으면 끝난 뒤 반드시 release를 부른다. */
    public boolean acquire(String resource, Duration timeout) throws InterruptedException {
        return slot(resource).tryAcquire(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
    }

    public void release(String resource) {
        slot(resource).release();
    }

    /** 지금 남은 슬롯 수. 테스트와 로그용 */
    public int available(String resource) {
        return slot(resource).availablePermits();
    }

    private Semaphore slot(String resource) {
        Semaphore slot = slots.get(resource);
        if (slot == null) {
            throw new IllegalStateException("scenecrew.ai.job.slots에 없는 자원입니다: " + resource);
        }
        return slot;
    }
}

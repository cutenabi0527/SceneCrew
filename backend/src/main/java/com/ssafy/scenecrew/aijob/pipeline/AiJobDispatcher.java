package com.ssafy.scenecrew.aijob.pipeline;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.ssafy.scenecrew.aijob.repository.AiJobRepository;

/**
 * 대기 중인 작업을 실행할 가상 스레드에 넘긴다. 작업마다 가상 스레드 하나를 쓰고 스레드 수로 제한하지 않는다.
 * 동시 호출 수는 AI 서버 자원별 슬롯(AiJobSlots)이 정한다.
 *
 * <p>넘기기 전에 ai_job.lease_until에 담당 표시(dispatchLease)를 적어 두 작업자가 같은 작업을 동시에 넘기지 않게 한다.
 * 가상 스레드는 바로 시작해 작업을 RUNNING으로 바꾸므로, 메모리 줄에서 오래 기다리다 복구가 같은 작업을 또 넣는 일이 없다.
 * 넘긴 직후 서버가 꺼지면 작업은 QUEUED로 남고, 담당 표시가 지나면 AiJobRecovery가 다시 넘긴다.
 */
@Slf4j
@Component
public class AiJobDispatcher implements DisposableBean {
    private final AiJobRepository repository;
    private final TransactionTemplate transaction;
    private final AiJobProperties properties;
    private final AiJobRunner runner;
    private final ExecutorService executor;

    public AiJobDispatcher(AiJobRepository repository, TransactionTemplate transaction, AiJobProperties properties,
            AiJobRunner runner) {
        this.repository = repository;
        this.transaction = transaction;
        this.properties = properties;
        this.runner = runner;
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ai-job-", 0).factory());
    }

    /**
     * 작업을 실행 스레드에 넘긴다. 넘겼으면 true.
     * 대기 상태가 아니거나, 다른 작업자가 이미 맡았거나, 재시도 대기 중이거나, 마감이 지났으면 false다.
     */
    public boolean dispatch(UUID jobId) {
        Instant now = Instant.now();
        // DB(timestamptz)는 마이크로초까지 저장한다. 담당 표시로 비교하려면 같은 정밀도로 맞춘다.
        Instant lease = now.plus(properties.dispatchLease()).truncatedTo(ChronoUnit.MICROS);
        Boolean reserved = transaction.execute(status -> repository.findForUpdate(jobId)
                .map(job -> job.reserveDispatch(now, lease))
                .orElse(false));
        if (!Boolean.TRUE.equals(reserved)) {
            return false;
        }
        try {
            executor.execute(() -> runner.run(jobId, lease));
            return true;
        } catch (RejectedExecutionException e) {
            // 서버가 종료 중이다. 담당 표시를 되돌려 다음 서버의 복구가 기다리지 않고 바로 가져가게 한다.
            log.warn("서버 종료 중이라 AI 작업을 넘기지 못함: jobId={}", jobId);
            transaction.executeWithoutResult(status -> repository.findForUpdate(jobId)
                    .ifPresent(job -> job.releaseDispatch(lease)));
            return false;
        }
    }

    /**
     * 실행 중인 가상 스레드를 중단한다. AI 응답을 기다리던 작업은 아무것도 기록하지 않고 RUNNING으로 남으며,
     * 호출이 AI에 도착했는지 알 수 없으므로 다시 보내지 않고 복구가 마감 뒤 AI_TIMEOUT으로 끝낸다.
     */
    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}

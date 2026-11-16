package com.ssafy.scenecrew.aijob.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.ssafy.scenecrew.aijob.domain.AiJobState;
import com.ssafy.scenecrew.aijob.kind.AiJobError;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import com.ssafy.scenecrew.aijob.repository.AiJobRepository;

/**
 * 메모리에서 사라진 작업을 DB 기준으로 되살린다.
 *
 * <ol>
 *   <li>마감이 지난 대기 작업, 실행 담당 증표가 만료된 실행 작업을 AI_TIMEOUT으로 끝낸다.
 *       실행 중 서버가 꺼졌으면 호출이 AI에 도착했는지 알 수 없어 다시 보내지 않는다.</li>
 *   <li>마감 전인데 아무도 붙잡고 있지 않은 대기 작업을 실행기에 다시 넘긴다.
 *       실행 스레드에 넘긴 직후 실행이 시작되기 전에 서버가 꺼진 경우다.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiJobRecovery {
    private final AiJobRepository repository;
    private final TransactionTemplate transaction;
    private final AiJobProperties properties;
    private final AiJobDispatcher dispatcher;
    private final AiJobFinisher finisher;

    /** 한 번 확인하고, 끝내거나 다시 넘긴 작업 수를 돌려준다. */
    public int recoverOnce() {
        return expire() + redispatch();
    }

    private int expire() {
        List<UUID> ids = repository.findExpired(AiJobState.QUEUED, AiJobState.RUNNING, Instant.now(), page());
        int expired = 0;
        for (UUID id : ids) {
            Boolean done = transaction.execute(status -> repository.findForUpdate(id)
                    .filter(job -> job.isExpired(Instant.now()))
                    .map(job -> {
                        AiJobState before = job.getState();
                        finisher.finish(job, AiJobOutcome.failed(AiJobError.timeout()), Instant.now());
                        log.warn("AI 작업 마감 처리: jobId={} state={} attempt={}", id, before, job.getAttempt());
                        return true;
                    })
                    .orElse(false));
            if (Boolean.TRUE.equals(done)) {
                expired++;
            }
        }
        return expired;
    }

    private int redispatch() {
        Instant now = Instant.now();
        List<UUID> ids = repository.findDispatchable(AiJobState.QUEUED, now, now.minus(properties.dispatchLease()),
                page());
        int dispatched = 0;
        for (UUID id : ids) {
            if (dispatcher.dispatch(id)) {
                log.info("실행되지 않은 AI 작업을 다시 넘김: jobId={}", id);
                dispatched++;
            }
        }
        return dispatched;
    }

    private PageRequest page() {
        return PageRequest.of(0, properties.recovery().batchSize());
    }
}

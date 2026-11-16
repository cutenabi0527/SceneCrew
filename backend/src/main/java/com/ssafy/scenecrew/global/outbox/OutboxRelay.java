package com.ssafy.scenecrew.global.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.ssafy.scenecrew.global.outbox.domain.OutboxEvent;
import com.ssafy.scenecrew.global.outbox.repository.OutboxEventRepository;

/**
 * outbox_event에서 처리할 작업을 꺼내 종류(type)에 맞는 OutboxHandler에 넘긴다.
 *
 * <ol>
 *   <li>짧은 트랜잭션으로 작업을 골라 잠그고 lease를 적는다. 여러 서버가 같은 작업을 동시에 잡지 않는다.</li>
 *   <li>트랜잭션 밖에서 handler를 호출한다.</li>
 *   <li>성공하면 processedAt, 실패하면 시도 횟수와 다음 시도 시각을 기록한다.</li>
 * </ol>
 *
 * 처리 중 서버가 죽어 기록하지 못한 작업은 lease가 끝난 뒤 다시 꺼낸다(최소 한 번 전달).
 * 꺼낼 때 적은 lease 값을 담당 증표로 쓴다. 결과를 기록할 때 그 값이 그대로인지 확인해,
 * lease가 만료된 뒤 늦게 끝난 이전 작업자가 새 작업자의 담당 표시를 지우지 못하게 한다.
 * 순서는 오래 기다린 작업부터 꺼내지만 실패한 작업이 뒤로 밀리므로 엄격한 순서는 보장하지 않는다.
 */
@Slf4j
@Component
public class OutboxRelay {
    private final OutboxEventRepository repository;
    private final TransactionTemplate transaction;
    private final OutboxRelayProperties properties;
    private final Map<String, OutboxHandler> handlers;

    public OutboxRelay(OutboxEventRepository repository, TransactionTemplate transaction,
            OutboxRelayProperties properties, List<OutboxHandler> handlers) {
        this.repository = repository;
        this.transaction = transaction;
        this.properties = properties;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(OutboxHandler::type, Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("같은 outbox type의 handler가 둘 이상입니다: " + a.type());
                }));
    }

    /** 한 묶음을 꺼내 처리하고, 꺼낸 작업 수를 돌려준다. */
    public int relayOnce() {
        List<OutboxEvent> claimed = claim();
        claimed.forEach(this::dispatch);
        return claimed.size();
    }

    private List<OutboxEvent> claim() {
        return transaction.execute(status -> {
            Instant now = Instant.now();
            List<UUID> ids = repository.lockReady(now, properties.maxAttempts(), properties.batchSize());
            if (ids.isEmpty()) {
                return List.of();
            }
            // DB(timestamptz)는 마이크로초까지 저장한다. 담당 증표로 비교하려면 같은 정밀도로 맞춘다.
            Instant leaseUntil = now.plusSeconds(properties.leaseSeconds()).truncatedTo(ChronoUnit.MICROS);
            repository.lease(ids, leaseUntil);
            return repository.findAllById(ids);
        });
    }

    private void dispatch(OutboxEvent event) {
        OutboxHandler handler = handlers.get(event.getType());
        if (handler == null) {
            // 코드 실수라 다시 시도해도 소용없다. 바로 시도 한도로 올려 더 꺼내지 않는다.
            log.error("outbox handler 없음, 처리 중단: id={} type={}", event.getId(), event.getType());
            record(event, () -> repository.markFailed(event.getId(), properties.maxAttempts(), Instant.now(),
                    event.getLeaseUntil()));
            return;
        }
        try {
            handler.handle(event);
        } catch (RuntimeException e) {
            fail(event, e);
            return;
        }
        record(event, () -> repository.markProcessed(event.getId(), Instant.now(), event.getLeaseUntil()));
    }

    private void fail(OutboxEvent event, RuntimeException cause) {
        long attempts = event.getAttempts() + 1;
        Instant next = Instant.now().plus(properties.backoffAfter(attempts));
        if (!record(event, () -> repository.markFailed(event.getId(), attempts, next, event.getLeaseUntil()))) {
            return;
        }
        if (attempts >= properties.maxAttempts()) {
            log.error("outbox 작업 처리 포기: id={} type={} attempts={}", event.getId(), event.getType(), attempts, cause);
        } else {
            log.warn("outbox 작업 처리 실패, {} 뒤 다시 시도: id={} type={} attempts={} cause={}",
                    properties.backoffAfter(attempts), event.getId(), event.getType(), attempts, cause.toString());
        }
    }

    /** 결과를 기록한다. 그 사이 다른 작업자가 작업을 이어받았으면 아무것도 바꾸지 않고 false를 돌려준다. */
    private boolean record(OutboxEvent event, IntSupplier update) {
        Integer updated = transaction.execute(status -> update.getAsInt());
        if (updated == null || updated == 0) {
            log.warn("outbox 작업 담당권을 잃어 결과를 기록하지 않음: id={} type={} lease={}",
                    event.getId(), event.getType(), event.getLeaseUntil());
            return false;
        }
        return true;
    }
}

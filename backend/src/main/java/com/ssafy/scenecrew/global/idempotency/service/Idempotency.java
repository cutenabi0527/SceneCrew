package com.ssafy.scenecrew.global.idempotency.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import lombok.RequiredArgsConstructor;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.domain.IdempotencyRecord;
import com.ssafy.scenecrew.global.idempotency.repository.IdempotencyRecordRepository;

/**
 * 요청 키(Idempotency-Key) 처리. 같은 계정·API·키 재요청에 업무를 다시 하지 않고 최초 HTTP 상태·본문을 돌려준다
 * (V9-D12/D15, 사전 B IdempotencyRecord, backend/docs/idempotency.md). S15P31A202-341.
 *
 * <p>업무 서비스의 트랜잭션 안에서 부른다. 현재 접근 확인(CurrentMembers)은 부르기 전에 끝낸다.
 * <ol>
 *   <li>같은 계정·API·키를 트랜잭션 잠금(pg_try_advisory_xact_lock)으로 줄 세운다. 이미 처리 중이면 inProgressWait(3초)까지
 *       다시 시도하고, 그 안에 앞 요청이 끝나면 그 결과를 돌려준다. 넘기면 409 REQUEST_IN_PROGRESS(be/11 "제한 대기 뒤").</li>
 *   <li>완료 기록이 있으면: 다른 참여 회차 403, 다른 대상·본문 409 IDEMPOTENCY_MISMATCH,
 *       24시간이 지났으면 409 IDEMPOTENCY_RESULT_EXPIRED, 아니면 기록의 결과를 돌려준다.</li>
 *   <li>처음이면 업무를 하고 결과 기록을 같은 트랜잭션에 저장한다. 업무가 실패하면 기록도 남지 않아 같은 키로 다시 시도할 수 있다.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class Idempotency {
    /** 최초 결과를 다시 돌려주는 기간(서버 최초 접수부터) */
    public static final Duration REPLAY_WINDOW = Duration.ofHours(24);

    private final IdempotencyRecordRepository records;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final IdempotencyProperties properties;

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> IdempotentResult<T> run(IdempotencyRequest request, Class<T> bodyType,
            Supplier<IdempotentResult<T>> work) {
        lock(request);
        var existing = records.findByActorAccountIdAndOperationAndIdempotencyKey(request.actorAccountId(),
                request.operation(), request.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), request, bodyType);
        }
        Instant accepted = now();
        IdempotentResult<T> result = Objects.requireNonNull(work.get(), "업무 결과가 없습니다.");
        Instant completed = now();
        records.save(IdempotencyRecord.completed(request.actorAccountId(), request.operation(),
                request.idempotencyKey(), request.requestHash(), request.eventId(), request.actorMemberId(),
                request.participationRevision(), result.resultType(), jsonMapper.writeValueAsString(result.resultRefs()),
                result.httpStatus(), jsonMapper.writeValueAsString(result.body()), accepted, completed,
                accepted.plus(REPLAY_WINDOW)));
        return result;
    }

    /**
     * 잠금 없이 완료 기록만 확인한다. 업무 전에 트랜잭션 밖의 비싼 작업(파일 업로드 등)이 있는 API가 흔한 재요청을 먼저 거를 때 쓴다.
     * 기록이 있으면 run과 같은 규칙(회차 403·불일치 409·만료 409·최초 결과)으로 판단하고, 없으면 비어 있다.
     * 동시에 온 같은 요청은 여기서 거르지 못하므로, 업무를 저장하는 트랜잭션에서 반드시 run을 다시 부른다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> java.util.Optional<IdempotentResult<T>> find(IdempotencyRequest request, Class<T> bodyType) {
        return records.findByActorAccountIdAndOperationAndIdempotencyKey(request.actorAccountId(), request.operation(),
                request.idempotencyKey()).map(record -> replay(record, request, bodyType));
    }

    /**
     * 같은 키 요청끼리만 줄 세운다. 잠금은 트랜잭션이 끝나면 풀린다. 키가 다른데 값이 겹치면 드물게 기다리거나 409가 난다.
     * 기다리는 동안 이 트랜잭션의 DB 연결을 쥐고 있으므로 대기는 짧게(기본 3초) 둔다.
     */
    private void lock(IdempotencyRequest request) {
        String name = request.actorAccountId() + "|" + request.operation() + "|" + request.idempotencyKey();
        long until = System.nanoTime() + properties.inProgressWait().toNanos();
        while (!tryLock(name)) {
            if (System.nanoTime() - until >= 0) {
                throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
            }
            try {
                Thread.sleep(properties.lockRetry());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
            }
        }
    }

    private boolean tryLock(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                Boolean.class, name));
    }

    private <T> IdempotentResult<T> replay(IdempotencyRecord record, IdempotencyRequest request, Class<T> bodyType) {
        if (record.getParticipationRevision() != null
                && !record.getParticipationRevision().equals(request.participationRevision())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!record.getRequestHash().equals(request.requestHash())
                || !Objects.equals(record.getEventId(), request.eventId())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_MISMATCH);
        }
        if (!now().isBefore(record.getReplayExpiresAt())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_RESULT_EXPIRED);
        }
        T body = jsonMapper.readValue(record.getReceipt(), bodyType);
        var refs = jsonMapper.readValue(record.getResultRefs(), new TypeReference<java.util.Map<String, Object>>() { });
        return new IdempotentResult<>(record.getHttpStatus(), body, record.getResultType(), refs, true);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}

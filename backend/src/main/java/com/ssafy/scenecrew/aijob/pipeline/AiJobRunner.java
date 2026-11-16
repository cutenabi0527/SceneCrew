package com.ssafy.scenecrew.aijob.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.aijob.client.AiClient;
import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.client.AiTransportException;
import com.ssafy.scenecrew.aijob.domain.AiJob;
import com.ssafy.scenecrew.aijob.kind.AiJobEnvelope;
import com.ssafy.scenecrew.aijob.kind.AiJobError;
import com.ssafy.scenecrew.aijob.kind.AiJobKind;
import com.ssafy.scenecrew.aijob.kind.AiJobOutcome;
import com.ssafy.scenecrew.aijob.repository.AiJobRepository;
import com.ssafy.scenecrew.event.repository.EventRepository;

/**
 * AI 작업 한 번의 호출(attempt). 작업마다 가상 스레드 하나에서 실행한다.
 *
 * <ol>
 *   <li>짧은 트랜잭션으로 QUEUED → RUNNING. 시도 횟수를 1 올리고 실행 담당 증표(lease)를 적는다.</li>
 *   <li>그 종류가 쓰는 AI 자원의 슬롯을 남은 마감까지 기다린다. 못 얻으면 AI_TIMEOUT이다.</li>
 *   <li>트랜잭션 밖에서 AI 서버를 호출한다. 응답 대기는 남은 마감까지다.</li>
 *   <li>짧은 트랜잭션으로 결과를 기록한다. 증표가 그대로일 때만 기록하고, 다시 시도할 실패면 대기 상태로 돌린다.
 *       성공이면 행사를 공유 잠금으로 읽어 revision이 그대로인지 확인한 뒤 종류별 반영(AiJobKind.apply)을 같은 트랜잭션에서 한다.</li>
 * </ol>
 *
 * 다시 보내는 것은 요청이 AI에 도착하지 않았다고 확신할 때(연결 실패, 앞단의 502·503)와 AI가 retryable=true로 답했을 때뿐이다.
 * 도착 여부를 알 수 없으면(서버 종료로 중단, 앞단의 504·SERVICE_TIMEOUT) 다시 보내지 않는다. GMS 호출이 두 번 쓰이는 것을 막는다.
 * 서버 종료로 중단되면 아무것도 기록하지 않고 RUNNING으로 남겨, 증표가 지난 뒤 AiJobRecovery가 마감 처리한다.
 */
@Slf4j
@Component
public class AiJobRunner {
    /** JSON이 아닌 응답 중 요청이 AI 앱에 도착하지 않았다고 볼 수 있는 상태 코드(nginx 앞단) */
    private static final Set<Integer> NOT_DELIVERED = Set.of(502, 503);
    /** AI 게이트웨이가 하위 앱 응답을 기다리다 포기함. 요청은 하위 앱에 도착해 처리 중일 수 있다. */
    private static final String GATEWAY_SERVICE_TIMEOUT = "SERVICE_TIMEOUT";

    private final AiJobRepository repository;
    private final TransactionTemplate transaction;
    private final AiJobProperties properties;
    private final AiJobKinds kinds;
    private final AiJobSlots slots;
    private final AiJobFinisher finisher;
    private final AiClient client;
    private final JsonMapper jsonMapper;
    private final EventRepository eventRepository;
    private final AiJobGate gate;

    public AiJobRunner(AiJobRepository repository, TransactionTemplate transaction, AiJobProperties properties,
            AiJobKinds kinds, AiJobSlots slots, AiJobFinisher finisher, AiClient client, JsonMapper jsonMapper,
            EventRepository eventRepository, AiJobGate gate) {
        this.repository = repository;
        this.transaction = transaction;
        this.properties = properties;
        this.kinds = kinds;
        this.slots = slots;
        this.finisher = finisher;
        this.client = client;
        this.jsonMapper = jsonMapper;
        this.eventRepository = eventRepository;
        this.gate = gate;
    }

    /** dispatchLease는 AiJobDispatcher가 적은 담당 표시다. 그 사이 다른 작업자가 맡았으면 아무것도 하지 않는다. */
    public void run(UUID jobId, Instant dispatchLease) {
        Started started = transaction.execute(status -> start(jobId, dispatchLease));
        if (started == null) {
            return;
        }
        AiJobOutcome outcome;
        try {
            outcome = callWithSlot(started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("서버 종료로 AI 작업 중단, 기록하지 않고 RUNNING으로 남김: jobId={} attempt={}",
                    started.jobId(), started.attempt());
            return;
        }
        recordWithRetry(started, outcome);
    }

    private Started start(UUID jobId, Instant dispatchLease) {
        AiJob job = repository.findForUpdate(jobId).orElse(null);
        if (job == null) {
            return null;
        }
        Instant now = Instant.now();
        Duration runDeadline = properties.runDeadline(job.getKind()).orElse(null);
        if (!job.start(dispatchLease, now, runDeadline, properties.runGrace())) {
            return null;
        }
        Instant runLease = job.getLeaseUntil();
        AiJobKind kind = kinds.find(job.getKind()).orElse(null);
        if (kind == null) {
            // 접수 때 확인하므로 배포 사이에 종류가 빠진 경우다. 다시 시도해도 소용없다.
            log.error("등록되지 않은 AI 작업 종류, 실패 처리: jobId={} kind={}", jobId, job.getKind());
            finisher.finish(job, AiJobOutcome.failed(
                    AiJobError.unavailable("UNKNOWN_KIND", "처리할 수 없는 AI 작업 종류입니다.", false)), now);
            return null;
        }
        AiJobEnvelope envelope = new AiJobEnvelope(job.getId(), Math.toIntExact(job.getAttempt()),
                job.getRequestSchemaVersion(), job.getKind(), job.getInputHash(), job.getContextRevision(),
                job.getDeadlineAt(), kind.scope(job), jsonMapper.readTree(job.getInput()));
        return new Started(jobId, job.getAttempt(), runLease, job.getDeadlineAt(), kind, kind.external(job), envelope);
    }

    /** 자원 슬롯을 남은 마감까지 기다린 뒤 호출한다. 슬롯은 호출이 끝나면 돌려준다. */
    private AiJobOutcome callWithSlot(Started started) throws InterruptedException {
        String resource = started.kind().resource();
        if (!slots.acquire(resource, remaining(started))) {
            log.warn("AI 자원 슬롯을 마감까지 얻지 못함: jobId={} resource={}", started.jobId(), resource);
            return AiJobOutcome.failed(AiJobError.timeout());
        }
        try {
            // 외부 AI 종류는 보내기 직전에 동의를 다시 본다. 철회됐으면 부르지 않는다(V9-D43).
            if (!consentedNow(started)) {
                log.warn("외부 AI 동의 철회로 호출하지 않음: jobId={} attempt={}", started.jobId(), started.attempt());
                return AiJobOutcome.failed(AiJobError.consentWithdrawn());
            }
            return call(started);
        } finally {
            slots.release(resource);
        }
    }

    private AiJobOutcome call(Started started) throws InterruptedException {
        Duration remaining = remaining(started);
        if (remaining.isNegative() || remaining.isZero()) {
            return AiJobOutcome.failed(AiJobError.timeout());
        }
        try {
            AiResponse response = client.post(started.kind().path(), jsonMapper.writeValueAsString(started.envelope()),
                    remaining);
            return interpret(started, response);
        } catch (AiTransportException e) {
            if (e.isTimeout() || pastDeadline(started)) {
                return AiJobOutcome.failed(AiJobError.timeout());
            }
            log.warn("AI 서버 연결 실패: jobId={} attempt={} cause={}", started.jobId(), started.attempt(), e.toString());
            return AiJobOutcome.failed(
                    AiJobError.unavailable("CONNECTION_FAILED", "AI 서버에 연결하지 못했습니다.", true));
        } catch (RuntimeException e) {
            log.error("AI 서버 호출 준비 실패: jobId={} attempt={}", started.jobId(), started.attempt(), e);
            return AiJobOutcome.failed(
                    AiJobError.unavailable("CLIENT_ERROR", "AI 서버를 호출하지 못했습니다.", false));
        }
    }

    /** 공통으로 판정할 수 있는 응답(인증 실패, 앞단 오류)을 먼저 거르고 나머지는 종류별 규칙에 맡긴다. */
    private AiJobOutcome interpret(Started started, AiResponse response) {
        if (response.status() == 401 || response.status() == 403) {
            log.error("AI 서버가 서비스 인증을 거절함. AI_SERVICE_TOKEN 확인 필요: jobId={} status={}",
                    started.jobId(), response.status());
            return AiJobOutcome.failed(
                    AiJobError.unavailable("UNAUTHENTICATED", "AI 서버가 서비스 인증을 거절했습니다.", false));
        }
        JsonNode body = readObject(response.body());
        if (body == null) {
            return frontError(started, response.status());
        }
        if (body.has("error") && !hasJobId(body)) {
            return gatewayError(body);
        }
        try {
            return started.kind().read(started.envelope(), response);
        } catch (RuntimeException e) {
            log.warn("AI 응답을 읽지 못함: jobId={} attempt={}", started.jobId(), started.attempt(), e);
            return AiJobOutcome.failed(AiJobError.invalidResult("AI 응답을 읽지 못했습니다."));
        }
    }

    /** JSON이 아닌 응답. AI 앱이 아니라 nginx 같은 앞단이 답한 경우다. */
    private AiJobOutcome frontError(Started started, int status) {
        if (pastDeadline(started)) {
            return AiJobOutcome.failed(AiJobError.timeout());
        }
        if (NOT_DELIVERED.contains(status)) {
            return AiJobOutcome.failed(AiJobError.unavailable("HTTP_" + status,
                    "AI 서버에 요청이 전달되지 않았습니다.", true));
        }
        if (status == 504) {
            return AiJobOutcome.failed(AiJobError.unavailable("GATEWAY_TIMEOUT",
                    "AI 서버 앞단이 응답을 기다리다 끊었습니다. 요청이 도착했을 수 있어 다시 보내지 않습니다.", false));
        }
        return AiJobOutcome.failed(AiJobError.invalidResult("AI 응답이 JSON 객체가 아닙니다. HTTP " + status));
    }

    /**
     * jobId 없이 error만 있는 응답. AI 게이트웨이(ai/runtime/gateway.py)가 하위 앱 대신 답했거나, AI 앱이 요청을 해석하지 못한
     * 접수 전 실패다. SERVICE_TIMEOUT은 하위 앱에 도착해 처리 중일 수 있어 게이트웨이가 retryable=true로 보내도 다시 보내지 않는다.
     */
    private static AiJobOutcome gatewayError(JsonNode body) {
        JsonNode error = body.get("error");
        JsonNode reasonNode = error.path("details").path("reason");
        JsonNode codeNode = error.path("code");
        String reason = reasonNode.isValueNode() ? reasonNode.asString()
                : codeNode.isValueNode() ? codeNode.asString() : "UNKNOWN";
        boolean retryable = error.path("retryable").asBoolean(false) && !GATEWAY_SERVICE_TIMEOUT.equals(reason);
        return AiJobOutcome.failed(AiJobError.unavailable(reason, "AI 게이트웨이가 요청을 처리하지 못했습니다.", retryable));
    }

    /**
     * 결과를 기록한다. 반영(apply) 중 예외는 같은 결과로 다시 해도 같을 수 있어 APPLY_FAILED로 끝낸다.
     * 그 밖의 기록 실패(DB 잠금 대기 초과 등)는 한 번 더 기록해 보고, 그래도 실패하면 RUNNING으로 두어 복구에 맡긴다.
     */
    private void recordWithRetry(Started started, AiJobOutcome outcome) {
        for (int tries = 1; ; tries++) {
            try {
                transaction.executeWithoutResult(status -> record(started, outcome));
                return;
            } catch (ApplyFailedException e) {
                log.error("AI 결과 반영 실패: jobId={} attempt={}", started.jobId(), started.attempt(), e.getCause());
                transaction.executeWithoutResult(status -> finishIfOwned(started, AiJobOutcome.failed(
                        AiJobError.unavailable("APPLY_FAILED", "AI 결과를 반영하지 못했습니다.", false))));
                return;
            } catch (RuntimeException e) {
                if (tries >= 2) {
                    log.error("AI 결과 기록 실패, RUNNING으로 두고 복구에 맡김: jobId={} attempt={}",
                            started.jobId(), started.attempt(), e);
                    return;
                }
                log.warn("AI 결과 기록 실패, 한 번 더 시도: jobId={} attempt={} cause={}",
                        started.jobId(), started.attempt(), e.toString());
            }
        }
    }

    private void record(Started started, AiJobOutcome outcome) {
        AiJob job = repository.findForUpdate(started.jobId()).orElse(null);
        if (job == null || !job.isRunBy(started.attempt(), started.runLease())) {
            log.warn("AI 작업 담당권을 잃어 결과를 기록하지 않음: jobId={} attempt={} outcome={}",
                    started.jobId(), started.attempt(), outcome.state());
            return;
        }
        Instant now = Instant.now();
        if (outcome.retryable() && started.attempt() < properties.maxAttempts()) {
            Instant next = now.plus(properties.retryDelayAfter(started.attempt())).truncatedTo(ChronoUnit.MICROS);
            if (next.isBefore(job.getDeadlineAt())) {
                log.warn("AI 작업 실패, {}에 다시 시도: jobId={} attempt={} error={}",
                        next, started.jobId(), started.attempt(), outcome.error().code());
                finisher.retryLater(job, outcome.error(), next);
                return;
            }
        }
        AiJobOutcome result = outcome.hasResult() ? accept(job, started.kind(), outcome) : outcome;
        finisher.finish(job, result, now);
        if (result.error() != null) {
            log.warn("AI 작업 종료: jobId={} attempt={} state={} error={}", started.jobId(), started.attempt(),
                    result.state(), result.error().code());
        }
    }

    /**
     * 성공 결과를 반영한다. 행사를 공유 잠금으로 읽어 이 트랜잭션이 끝날 때까지 계획이 바뀌지 못하게 한 뒤
     * revision을 확인한다. 실행 중 계획이 바뀌었으면 반영하지 않고 STALE_CONTEXT로 끝낸다.
     */
    private AiJobOutcome accept(AiJob job, AiJobKind kind, AiJobOutcome outcome) {
        // 결과가 돌아오는 사이 동의를 철회했으면 반영하지 않는다(V9-D43). 행사 revision과 무관하게 본다.
        if (!gate.stillConsented(job, kind)) {
            return AiJobOutcome.failed(AiJobError.consentWithdrawn());
        }
        if (kind.requiresCurrentContext()) {
            long current = eventRepository.findForShare(job.getEventId()).orElseThrow().getRevision();
            if (current != job.getContextRevision()) {
                return AiJobOutcome.staleContext(AiJobError.contextChanged(job.getContextRevision(), current));
            }
        }
        try {
            return Objects.requireNonNull(kind.apply(job, outcome), "AiJobKind.apply가 null을 돌려줬습니다.");
        } catch (RuntimeException e) {
            throw new ApplyFailedException(e);
        }
    }

    private boolean consentedNow(Started started) {
        if (!started.external()) return true;
        return Boolean.TRUE.equals(transaction.execute(status -> repository.findById(started.jobId())
                .map(job -> gate.stillConsented(job, started.kind())).orElse(false)));
    }

    private void finishIfOwned(Started started, AiJobOutcome outcome) {
        repository.findForUpdate(started.jobId())
                .filter(job -> job.isRunBy(started.attempt(), started.runLease()))
                .ifPresent(job -> finisher.finish(job, outcome, Instant.now()));
    }

    private JsonNode readObject(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = jsonMapper.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static boolean hasJobId(JsonNode body) {
        JsonNode jobId = body.get("jobId");
        return jobId != null && !jobId.isNull();
    }

    private static Duration remaining(Started started) {
        return Duration.between(Instant.now(), started.deadlineAt());
    }

    private static boolean pastDeadline(Started started) {
        return !Instant.now().isBefore(started.deadlineAt());
    }

    /** external: 이 작업이 외부 AI로 나갈 수 있는지(AiJobKind.external(job)). 호출 직전 동의 재확인 여부를 정한다. */
    private record Started(UUID jobId, long attempt, Instant runLease, Instant deadlineAt, AiJobKind kind,
            boolean external, AiJobEnvelope envelope) {
    }

    /** AiJobKind.apply에서 난 예외. 기록 트랜잭션은 함께 취소된다. */
    private static final class ApplyFailedException extends RuntimeException {
        ApplyFailedException(RuntimeException cause) {
            super(cause);
        }
    }
}

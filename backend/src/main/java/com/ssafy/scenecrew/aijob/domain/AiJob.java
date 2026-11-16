package com.ssafy.scenecrew.aijob.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.ColumnTransformer;

/**
 * AI 작업 한 건. 상태 변경은 아래 메서드로만 하고, 호출하는 쪽은 행 잠금(AiJobRepository.findForUpdate) 안에서 부른다.
 *
 * <p>leaseUntil은 상태마다 뜻이 다르다.
 * <ul>
 *   <li>QUEUED: 실행기에 넘긴 작업자의 담당 표시 또는 재시도 대기 시각. 이 시각이 지나야 다시 넘길 수 있다.</li>
 *   <li>RUNNING: 실행 중인 작업자의 담당 증표(자원 슬롯을 기다리는 동안 포함). 결과는 증표가 그대로일 때만 기록한다.</li>
 *   <li>종료 상태: 비운다.</li>
 * </ul>
 */
@Entity
@Table(name = "ai_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiJob {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    private UUID requestedByMemberId;

    @Column(nullable = false, columnDefinition = "text")
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AiJobState state;

    @Column(nullable = false, columnDefinition = "text")
    private String inputHash;

    @Column(nullable = false)
    private Long contextRevision;

    @Column(nullable = false, columnDefinition = "text")
    private String requestSchemaVersion;

    @Column(columnDefinition = "text")
    private String resultSchemaVersion;

    @Column(columnDefinition = "text")
    private String modelVersion;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String input;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String result;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String error;

    @Column(nullable = false)
    private Long attempt;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String executionMeta;

    @Column(columnDefinition = "text")
    private String triggerKey;

    @Column(columnDefinition = "text")
    private String analysisFlowId;

    @Column(nullable = false)
    private Long autoReanalysisCount;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant firstStartedAt;

    @Column(nullable = false)
    private Instant deadlineAt;

    private Instant leaseUntil;

    private Instant finishedAt;

    /** 접수한 작업. attempt는 0이고 첫 실행에서 1이 된다. */
    public static AiJob queued(UUID eventId, UUID requestedByMemberId, String kind, String requestSchemaVersion,
            String inputJson, String inputHash, long contextRevision, Instant now, Instant deadlineAt) {
        AiJob job = new AiJob();
        job.id = UUID.randomUUID();
        job.eventId = eventId;
        job.requestedByMemberId = requestedByMemberId;
        job.kind = kind;
        job.state = AiJobState.QUEUED;
        job.inputHash = inputHash;
        job.contextRevision = contextRevision;
        job.requestSchemaVersion = requestSchemaVersion;
        job.input = inputJson;
        job.attempt = 0L;
        job.executionMeta = "{}";
        job.autoReanalysisCount = 0L;
        job.createdAt = now;
        job.deadlineAt = deadlineAt;
        return job;
    }

    /** 대기 중이고, 다른 작업자가 넘겨받지 않았고, 마감 전이면 실행기에 넘길 담당을 잡는다. */
    public boolean reserveDispatch(Instant now, Instant until) {
        if (state != AiJobState.QUEUED || isHeld(now) || !now.isBefore(deadlineAt)) {
            return false;
        }
        leaseUntil = until;
        return true;
    }

    /** 실행기에 넘기지 못했을 때 잡아 둔 담당 표시를 되돌린다. 그사이 다른 작업자가 맡았으면 그대로 둔다. */
    public void releaseDispatch(Instant dispatchLease) {
        if (state == AiJobState.QUEUED && Objects.equals(leaseUntil, dispatchLease)) {
            leaseUntil = null;
        }
    }

    /**
     * 담당을 잡은 작업자만 실행을 시작한다. 시도 횟수를 1 올리고 실행 담당 증표(마감 + runGrace)를 적는다.
     *
     * <p>runDeadline이 있는 종류(문서 추출)는 첫 실행이 접수 때 정한 대기 마감 안에 시작되면 마감을 now + runDeadline으로
     * 한 번만 다시 정한다. 재시도·재시작에서는 다시 정하지 않고, 대기 마감이 지난 뒤 시작되면 늘리지 않아 시간 초과로 끝난다.
     */
    public boolean start(Instant dispatchLease, Instant now, Duration runDeadline, Duration runGrace) {
        if (state != AiJobState.QUEUED || !Objects.equals(leaseUntil, dispatchLease)) {
            return false;
        }
        state = AiJobState.RUNNING;
        attempt = attempt + 1;
        if (firstStartedAt == null) {
            firstStartedAt = now;
            if (runDeadline != null && now.isBefore(deadlineAt)) {
                deadlineAt = now.plus(runDeadline).truncatedTo(ChronoUnit.MICROS);
            }
        }
        leaseUntil = deadlineAt.plus(runGrace).truncatedTo(ChronoUnit.MICROS);
        return true;
    }

    /** 이 시도의 실행 담당자가 아직 그대로인지. 마감 처리나 다른 실행이 끼어들었으면 false다. */
    public boolean isRunBy(long expectedAttempt, Instant runLease) {
        return state == AiJobState.RUNNING && attempt == expectedAttempt && Objects.equals(leaseUntil, runLease);
    }

    /** 실행을 끝낸다. state는 종료 상태(SUCCEEDED/PARTIAL/FAILED/STALE_CONTEXT)다. executionMetaJson이 null이면 그대로 둔다. */
    public void finish(AiJobState finalState, String resultSchemaVersion, String modelVersion, String resultJson,
            String errorJson, String executionMetaJson, Instant now) {
        if (finalState == AiJobState.QUEUED || finalState == AiJobState.RUNNING) {
            throw new IllegalArgumentException("종료 상태가 아닙니다: " + finalState);
        }
        state = finalState;
        this.resultSchemaVersion = resultSchemaVersion;
        this.modelVersion = modelVersion;
        result = resultJson;
        error = errorJson;
        if (executionMetaJson != null) {
            executionMeta = executionMetaJson;
        }
        finishedAt = now;
        leaseUntil = null;
    }

    /** 다시 시도할 수 있는 실패. 대기 상태로 돌리고 notBefore 전에는 실행기에 넘기지 않는다. 마지막 오류는 남긴다. */
    public void retryLater(String errorJson, Instant notBefore) {
        state = AiJobState.QUEUED;
        error = errorJson;
        leaseUntil = notBefore;
    }

    /** 마감이 지나 더 진행할 수 없는지. 대기 중이면 마감, 실행 중이면 담당 증표 만료로 판단한다. */
    public boolean isExpired(Instant now) {
        return switch (state) {
            case QUEUED -> !now.isBefore(deadlineAt);
            case RUNNING -> leaseUntil == null || leaseUntil.isBefore(now);
            default -> false;
        };
    }

    public boolean isFinished() {
        return state != AiJobState.QUEUED && state != AiJobState.RUNNING;
    }

    private boolean isHeld(Instant now) {
        return leaseUntil != null && !leaseUntil.isBefore(now);
    }
}

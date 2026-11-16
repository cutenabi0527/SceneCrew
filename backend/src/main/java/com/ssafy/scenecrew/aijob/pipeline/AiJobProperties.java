package com.ssafy.scenecrew.aijob.pipeline;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 작업 공통 처리 규칙(scenecrew.ai.job). backend/docs/ai-job.md
 *
 * @param maxAttempts   한 작업의 최대 호출 횟수(첫 호출 포함)
 * @param retryDelays   n번째 호출이 다시 시도 가능한 실패로 끝난 뒤 기다리는 시간
 * @param dispatchLease 실행기에 넘긴 작업을 붙잡아 두는 시간. 그 안에 실행이 시작되지 않으면(넘긴 직후 서버 종료) 복구가 다시 넘긴다.
 * @param runGrace      실행 담당 증표를 마감보다 얼마나 더 유지할지. 응답 기록에 쓰는 여유 시간이다.
 * @param recovery      사라진 작업을 다시 찾고 마감 지난 작업을 끝내는 주기 실행
 * @param deadlines     종류별 마감(접수부터). 여기 없는 종류는 접수하지 않는다.
 * @param runDeadlines  첫 실행부터의 마감(AI10). 여기 있는 종류는 deadlines가 첫 실행까지의 대기 마감이 되고,
 *                      첫 실행이 그 안에 시작되면 그때부터 이 값으로 마감을 한 번 다시 정한다. 예: 문서 추출 300초 대기 + 600초 실행
 * @param slots         AI 서버 자원별 동시 호출 수(AI10). AiJobKind.resource()가 여기 없으면 서버가 뜨지 않는다.
 */
@ConfigurationProperties("scenecrew.ai.job")
public record AiJobProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue({"5s", "30s"}) List<Duration> retryDelays,
        @DefaultValue("30s") Duration dispatchLease,
        @DefaultValue("10s") Duration runGrace,
        @DefaultValue Recovery recovery,
        Map<String, Duration> deadlines,
        Map<String, Duration> runDeadlines,
        Map<String, Integer> slots) {

    public AiJobProperties {
        if (maxAttempts < 1 || retryDelays.size() < maxAttempts - 1) {
            throw new IllegalArgumentException("scenecrew.ai.job 값이 올바르지 않습니다.");
        }
        retryDelays = List.copyOf(retryDelays);
        deadlines = deadlines == null ? Map.of() : Map.copyOf(deadlines);
        runDeadlines = runDeadlines == null ? Map.of() : Map.copyOf(runDeadlines);
        for (Map.Entry<String, Duration> run : runDeadlines.entrySet()) {
            if (!deadlines.containsKey(run.getKey()) || run.getValue() == null
                    || run.getValue().isNegative() || run.getValue().isZero()) {
                throw new IllegalArgumentException(
                        "scenecrew.ai.job.run-deadlines는 deadlines에 있는 종류만, 0보다 큰 값으로 적어야 합니다: " + run.getKey());
            }
        }
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        if (slots.values().stream().anyMatch(n -> n == null || n < 1)) {
            throw new IllegalArgumentException("scenecrew.ai.job.slots 값은 1 이상이어야 합니다.");
        }
    }

    /**
     * @param enabled   주기 실행을 켤지. 테스트는 끄고 AiJobRecovery.recoverOnce()를 직접 부른다.
     * @param delayMs   한 번 확인한 뒤 다음 확인까지 기다리는 시간
     * @param batchSize 한 번에 확인하는 최대 작업 수
     */
    public record Recovery(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30000") long delayMs,
            @DefaultValue("20") int batchSize) {
    }

    public Optional<Duration> deadline(String kind) {
        return Optional.ofNullable(deadlines.get(kind));
    }

    /** 첫 실행부터의 마감. 없으면 접수 때 정한 마감을 그대로 쓴다. */
    public Optional<Duration> runDeadline(String kind) {
        return Optional.ofNullable(runDeadlines.get(kind));
    }

    /** attempt번째 호출이 실패한 뒤의 대기 시간 */
    public Duration retryDelayAfter(long attempt) {
        return retryDelays.get((int) attempt - 1);
    }
}

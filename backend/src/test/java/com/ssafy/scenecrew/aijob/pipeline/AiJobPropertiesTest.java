package com.ssafy.scenecrew.aijob.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** S15P31A202-339: 두 단계 마감 설정(run-deadlines) 검사 */
class AiJobPropertiesTest {
    private static final Duration WAIT = Duration.ofSeconds(300);
    private static final Duration RUN = Duration.ofSeconds(600);

    @Test
    void runDeadlineIsReadOnlyForConfiguredKind() {
        AiJobProperties properties = properties(Map.of("EXTRACT_EVENT_INPUT", WAIT, "TRANSCRIBE", Duration.ofSeconds(90)),
                Map.of("EXTRACT_EVENT_INPUT", RUN));

        assertThat(properties.runDeadline("EXTRACT_EVENT_INPUT")).contains(RUN);
        assertThat(properties.runDeadline("TRANSCRIBE")).isEmpty();
    }

    @Test
    void missingRunDeadlinesMeansNone() {
        assertThat(properties(Map.of("TRANSCRIBE", Duration.ofSeconds(90)), null).runDeadline("TRANSCRIBE")).isEmpty();
    }

    @Test
    void runDeadlineNeedsWaitDeadlineOfSameKind() {
        assertThatThrownBy(() -> properties(Map.of(), Map.of("EXTRACT_EVENT_INPUT", RUN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXTRACT_EVENT_INPUT");
    }

    @Test
    void runDeadlineMustBePositive() {
        assertThatThrownBy(() -> properties(Map.of("EXTRACT_EVENT_INPUT", WAIT), Map.of("EXTRACT_EVENT_INPUT", Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AiJobProperties properties(Map<String, Duration> deadlines, Map<String, Duration> runDeadlines) {
        return new AiJobProperties(3, List.of(Duration.ofSeconds(5), Duration.ofSeconds(30)), Duration.ofSeconds(30),
                Duration.ofSeconds(10), new AiJobProperties.Recovery(true, 30000, 20), deadlines, runDeadlines, Map.of());
    }
}

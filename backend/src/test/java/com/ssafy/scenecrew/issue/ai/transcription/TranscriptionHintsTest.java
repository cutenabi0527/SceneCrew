package com.ssafy.scenecrew.issue.ai.transcription;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class TranscriptionHintsTest {

    @Test
    void dropsBlankAndLongNamesDedupesAndSorts() {
        List<String> raw = Arrays.asList("메인 무대", " A구역 ", "A구역", "", null, "가".repeat(31), "가".repeat(30));

        assertThat(TranscriptionHints.pick(raw)).containsExactly("A구역", "가".repeat(30), "메인 무대");
    }

    @Test
    void keepsAtMostFiftyPerKind() {
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            raw.add("구역%02d".formatted(i));
        }

        List<String> picked = TranscriptionHints.pick(raw);

        assertThat(picked).hasSize(50).startsWith("구역00").endsWith("구역49");
    }
}

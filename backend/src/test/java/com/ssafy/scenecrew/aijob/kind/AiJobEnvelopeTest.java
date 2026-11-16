package com.ssafy.scenecrew.aijob.kind;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** scope는 종류가 정할 때만 요청에 들어간다. 정하지 않은 종류(계획·보고 분석 등)의 요청에는 키 자체가 없어야 한다. */
class AiJobEnvelopeTest {
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void scopeIsOmittedWhenKindDoesNotDefineIt() {
        JsonNode sent = MAPPER.readTree(MAPPER.writeValueAsString(envelope(null)));

        assertThat(sent.has("scope")).isFalse();
        assertThat(sent.has("payload")).isTrue();
    }

    @Test
    void scopeIsSentWhenKindDefinesIt() {
        JsonNode scope = MAPPER.readTree("{\"eventId\":\"e\",\"uploaderMemberId\":\"m\"}");

        JsonNode sent = MAPPER.readTree(MAPPER.writeValueAsString(envelope(scope)));

        assertThat(sent.get("scope")).isEqualTo(scope);
    }

    private static AiJobEnvelope envelope(JsonNode scope) {
        return new AiJobEnvelope(UUID.randomUUID(), 1, "4", "ANALYZE_REPORT", "a".repeat(64), 3,
                Instant.parse("2026-11-05T12:00:00Z"), scope, MAPPER.readTree("{\"text\":\"무대 뒤\"}"));
    }
}

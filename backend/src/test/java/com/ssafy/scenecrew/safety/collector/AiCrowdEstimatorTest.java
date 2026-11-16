package com.ssafy.scenecrew.safety.collector;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.aijob.client.AiResponse;
import com.ssafy.scenecrew.aijob.client.AiTransportException;
import com.ssafy.scenecrew.safety.collector.CrowdEstimator.EstimateException;
import com.ssafy.scenecrew.safety.collector.CrowdEstimator.Request;
import com.ssafy.scenecrew.safety.collector.CrowdEstimator.Zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AI 서버 밀집도 경로의 요청 모양과 응답 검사(#349 ↔ #360). 응답이 하나라도 어긋나면 이번 결과 전체를 버린다. */
class AiCrowdEstimatorTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID STAGE = UUID.randomUUID(), LOBBY = UUID.randomUUID();
    static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 9, 9 };

    final Request request = new Request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7, Instant.parse("2026-11-18T10:00:00Z"),
            "a".repeat(64), JPEG, List.of(new Zone(STAGE, "FULL", JSON.readTree("[{\"x\":0.1,\"y\":0.2}]")),
                    new Zone(LOBBY, "PARTIAL", JSON.readTree("[{\"x\":0.5,\"y\":0.6}]"))));

    @Test
    void sendsTheFrameAndOnlyTheCoveredZones() throws Exception {
        String[] seen = new String[2];
        var estimator = new AiCrowdEstimator((path, body, timeout) -> {
            seen[0] = path;
            seen[1] = body;
            return new AiResponse(200, ok());
        }, Duration.ofSeconds(8));
        var result = estimator.estimate(request);

        assertThat(seen[0]).isEqualTo("/internal/estimate-crowd");
        JsonNode body = JSON.readTree(seen[1]);
        assertThat(body.propertyNames()).containsExactly("schemaVersion", "eventId", "cameraId", "streamSessionId", "sequence",
                "capturedAt", "inputHash", "image", "zones");
        assertThat(body.get("sequence").asString()).isEqualTo("7");
        assertThat(body.get("capturedAt").asString()).isEqualTo("2026-11-18T10:00:00Z");
        assertThat(body.get("image").get("contentType").asString()).isEqualTo("image/jpeg");
        assertThat(java.util.Base64.getDecoder().decode(body.get("image").get("base64").asString())).isEqualTo(JPEG);
        assertThat(body.get("zones")).hasSize(2);
        assertThat(body.get("zones").get(1).get("scope").asString()).isEqualTo("PARTIAL");
        assertThat(body.get("zones").get(0).get("roi").get(0).get("x").asDouble()).isEqualTo(0.1);

        assertThat(result.modelVersion()).isEqualTo("crowd-v1");
        assertThat(result.zones()).hasSize(2);
        assertThat(result.zones().get(0).estimatedCount()).isEqualTo(12.5);
        assertThat(result.zones().get(1).estimatedCount()).isNull();
        assertThat(result.zones().get(1).qualityReasons()).containsExactly("LOW_LIGHT");
    }

    @Test
    void rejectsAnswersOutsideTheContract() {
        rejected("{\"modelVersion\":\"m\",\"zones\":[{\"zoneId\":\"" + UUID.randomUUID() + "\",\"estimatedCount\":1,\"qualityReasons\":[]}]}");
        rejected("{\"modelVersion\":\"m\",\"zones\":[" + zone(STAGE, "1") + "," + zone(STAGE, "2") + "]}");
        rejected("{\"modelVersion\":\"m\",\"zones\":[" + zone(STAGE, "-1") + "]}");
        rejected("{\"modelVersion\":\"m\",\"zones\":[" + zone(STAGE, "\"3\"") + "]}");
        rejected("{\"modelVersion\":\"m\",\"zones\":[{\"zoneId\":\"" + STAGE + "\",\"qualityReasons\":[]}]}");
        rejected("{\"modelVersion\":\"m\",\"zones\":[{\"zoneId\":\"" + STAGE + "\",\"estimatedCount\":1,\"qualityReasons\":[\"low light\"]}]}");
        rejected("{\"modelVersion\":\"\",\"zones\":[]}");
        rejected("{\"zones\":[]}");
        rejected("[]");
        rejected("not json");
    }

    @Test
    void transportAndHttpFailuresAreEstimateFailures() {
        assertThatThrownBy(() -> new AiCrowdEstimator((p, b, t) -> new AiResponse(503, "{}"), Duration.ofSeconds(1)).estimate(request))
                .isInstanceOf(EstimateException.class).hasMessageContaining("503");
        assertThatThrownBy(() -> new AiCrowdEstimator((p, b, t) -> {
            throw new AiTransportException("시간 초과", true, null);
        }, Duration.ofSeconds(1)).estimate(request)).isInstanceOf(EstimateException.class).hasMessageContaining("시간 초과");
    }

    @Test
    void stubMarksTestValuesForEveryCoveredZone() {
        var result = new StubCrowdEstimator().estimate(request);
        assertThat(result.modelVersion()).isEqualTo("test-stub");
        assertThat(result.zones()).extracting(CrowdEstimator.ZoneCount::zoneId).containsExactly(STAGE, LOBBY);
        assertThat(result.zones()).allSatisfy(z -> {
            assertThat(z.estimatedCount()).isBetween(0.0, 40.0);
            assertThat(z.qualityReasons()).containsExactly("TEST_VALUE");
        });
        // 같은 프레임이면 같은 값
        assertThat(new StubCrowdEstimator().estimate(request)).isEqualTo(result);
    }

    void rejected(String answer) {
        assertThatThrownBy(() -> new AiCrowdEstimator((p, b, t) -> new AiResponse(200, answer), Duration.ofSeconds(1)).estimate(request))
                .as(answer).isInstanceOf(EstimateException.class);
    }

    static String zone(UUID id, String count) {
        return "{\"zoneId\":\"" + id + "\",\"estimatedCount\":" + count + ",\"qualityReasons\":[]}";
    }

    static String ok() {
        return "{\"modelVersion\":\"crowd-v1\",\"zones\":[" + zone(STAGE, "12.5") + ",{\"zoneId\":\"" + LOBBY
                + "\",\"estimatedCount\":null,\"qualityReasons\":[\"LOW_LIGHT\"]}]}";
    }
}

package com.ssafy.scenecrew.equipment.collector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 소켓 + 제품 Security 설정 + PostgreSQL. 수집기 등록은 실제 운영과 같은 JSON 설정으로 넣는다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CollectorIngestHttpTest {
    static final String TOKEN_A = "token-a-" + UUID.randomUUID();
    static final String TOKEN_B = "token-b-" + UUID.randomUUID();
    static final String TOKEN_C = "token-c-" + UUID.randomUUID();
    static final UUID EVENT_A = UUID.randomUUID(), EVENT_B = UUID.randomUUID();
    static final UUID ASSET_A = UUID.randomUUID(), ASSET_B = UUID.randomUUID();
    static final UUID OLD_RUN = UUID.randomUUID(), RUN = UUID.randomUUID(), RUN_B = UUID.randomUUID(),
            RUN_C = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("scenecrew.equipment.collector.enabled", () -> "true");
        r.add("scenecrew.equipment.collector.cleanup.enabled", () -> "false");
        r.add("scenecrew.equipment.collector.registrations", () -> """
                [{"collectorId":"collector-a","eventId":"%s","tokenSha256":"%s","sources":{"qlxd4-main":"%s"},
                  "runs":[{"runId":"%s","active":false},{"runId":"%s","active":true}]},
                 {"collectorId":"collector-b","eventId":"%s","tokenSha256":"%s","sources":{"qlxd4-main":"%s"},
                  "runs":[{"runId":"%s","active":true}]},
                 {"collectorId":"collector-c","eventId":"%s","tokenSha256":"%s","sources":{"other-event":"%s"},
                  "runs":[{"runId":"%s","active":true}]}]
                """.formatted(EVENT_A, CollectorRegistry.sha256(TOKEN_A), ASSET_A, OLD_RUN, RUN,
                EVENT_B, CollectorRegistry.sha256(TOKEN_B), ASSET_B, RUN_B,
                EVENT_A, CollectorRegistry.sha256(TOKEN_C), ASSET_B, RUN_C));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired EquipmentObservationReader reader;
    @Autowired ObservationRetention retention;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void rows() {
        for (UUID event : List.of(EVENT_A, EVENT_B)) {
            jdbc.update("insert into event (id, name, state, revision, last_input_modified_at) values (?, '수집 시험', 'LIVE', 1, now())"
                    + " on conflict (id) do update set state = 'LIVE'", event);
        }
        insertAsset(ASSET_A, EVENT_A);
        insertAsset(ASSET_B, EVENT_B);
        jdbc.update("delete from equipment_observation where equipment_asset_id in (?, ?)", ASSET_A, ASSET_B);
        jdbc.update("delete from equipment_observation_latest where equipment_asset_id in (?, ?)", ASSET_A, ASSET_B);
    }

    void insertAsset(UUID id, UUID event) {
        jdbc.update("""
                insert into equipment_asset (id, event_id, name, model, technical_state, state_evidence, component_asset_ids,
                    ports, verified_compatibility, revision)
                values (?, ?, 'QLXD4 메인', 'QLXD4', 'UNKNOWN', '{"sourceKind":"MANUAL_CONFIRMATION"}'::jsonb, '[]'::jsonb,
                    '[]'::jsonb, '[]'::jsonb, 3)
                on conflict (id) do nothing
                """, id, event);
    }

    // --- 기록 만들기 (수집 프로그램 equipment/collector/observation.py와 같은 형식) ---

    /** 기록의 수집기 시각. 보관 기간이 지난 기록은 거절하므로 지금 시각을 기준으로 둔다. */
    final Instant start = Instant.now().minusSeconds(60);

    Map<String, Object> base(String type, UUID run, long seq) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schema", ObservationBatch.SCHEMA);
        r.put("recordType", type);
        r.put("collectorId", "collector-a");
        r.put("runId", run.toString());
        r.put("sessionId", "9d145a8e-8b8e-4c47-9f0e-6a4b2f0d1c11");
        r.put("sequence", seq);
        r.put("adapter", "shure-qlxd4");
        r.put("sourceRef", "qlxd4-main");
        r.put("mode", "SIMULATOR");
        r.put("channel", 1);
        r.put("receivedAt", start.plusMillis(seq * 500).toString());
        return r;
    }

    Map<String, Object> reading(UUID run, long seq, String param, Object value, String raw) {
        Map<String, Object> r = base("READING", run, seq);
        r.put("origin", "REPORT");
        r.put("param", param);
        r.put("raw", raw);
        r.put("value", value);
        r.put("valueStatus", "OK");
        r.put("unit", "bars");
        r.put("frame", "REP 1 " + param + " " + raw);
        r.put("observedAt", null);
        r.put("timeBasis", "RECEIVED");
        return r;
    }

    Map<String, Object> sample(UUID run, long seq, String param, int value) {
        Map<String, Object> r = reading(run, seq, param, value, String.format("%03d", value));
        r.put("origin", "SAMPLE");
        r.put("unit", null);
        return r;
    }

    Map<String, Object> link(UUID run, long seq, String state) {
        Map<String, Object> r = base("LINK", run, seq);
        r.put("linkState", state);
        r.put("reason", null);
        return r;
    }

    Map<String, Object> status(UUID run, long seq, String metering) {
        Map<String, Object> r = base("STATUS", run, seq);
        r.put("linkState", "CONNECTED");
        r.put("metering", metering);
        r.put("staleAfterMs", 2000);
        r.put("lastSampleAt", "2026-11-05T01:00:00Z");
        r.put("sampleAgeMs", 120);
        r.put("lastValues", Map.of("RF_ANTENNA", Map.of("value", 40, "valueStatus", "OK", "raw", "040",
                "receivedAt", "2026-11-05T01:00:00Z", "sessionId", "9d145a8e-8b8e-4c47-9f0e-6a4b2f0d1c11")));
        return r;
    }

    String batch(String collectorId, List<Map<String, Object>> records) {
        return json.writeValueAsString(Map.of("schema", ObservationBatch.SCHEMA, "collectorId", collectorId,
                "sentAt", "2026-11-05T01:00:05Z", "droppedSinceLast", 0, "records", records));
    }

    record Reply(int status, JsonNode body) {
        String code() {
            return body.path("code").asString();
        }
    }

    Reply post(String collectorId, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/equipment-collectors/"
                + collectorId + "/observations")).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), json.readTree(response.body().isEmpty() ? "{}" : response.body()));
    }

    Reply postA(List<Map<String, Object>> records) throws Exception {
        return post("collector-a", TOKEN_A, batch("collector-a", records));
    }

    long ttlHours(long sequence) {
        return jdbc.queryForObject("select extract(epoch from expires_at - server_received_at)::bigint / 3600"
                + " from equipment_observation where run_id = ? and sequence = ?", Long.class, RUN, sequence);
    }

    long count() {
        return jdbc.queryForObject("select count(*) from equipment_observation where equipment_asset_id = ?", Long.class, ASSET_A);
    }

    // --- 시험 ---

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsWhenCollectorReportsDroppedRecords(CapturedOutput output) throws Exception {
        String body = json.writeValueAsString(Map.of("schema", ObservationBatch.SCHEMA, "collectorId", "collector-a",
                "sentAt", "2026-11-05T01:00:05Z", "droppedSinceLast", 7,
                "records", List.of(link(RUN, 1, "CONNECTED"))));
        var reply = post("collector-a", TOKEN_A, body);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(output).contains("장비 수집기 collector-a가 미전송 한도 초과로 버린 기록 7건을 알렸습니다");
        // 버린 수가 없으면 남기지 않는다
        postA(List.of(status(RUN, 2, "FRESH")));
        assertThat(output.toString().split("버린 기록", -1)).hasSize(2);
    }

    @Test
    void storesBatchAndAcksLastSequenceWithoutChangingAssetOrCreatingIssue() throws Exception {
        var asset = jdbc.queryForMap("select technical_state, state_evidence::text as evidence, revision from equipment_asset where id = ?", ASSET_A);
        var reply = postA(List.of(link(RUN, 1, "CONNECTED"), reading(RUN, 2, "BATT_BARS", 4, "004"), status(RUN, 3, "FRESH")));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("collectorId").asString()).isEqualTo("collector-a");
        assertThat(reply.body().get("accepted").asInt()).isEqualTo(3);
        assertThat(reply.body().get("duplicates").asInt()).isZero();
        assertThat(reply.body().get("runs").get(0).get("runId").asString()).isEqualTo(RUN.toString());
        assertThat(reply.body().get("runs").get(0).get("lastSequence").asLong()).isEqualTo(3);
        assertThat(count()).isEqualTo(3);
        // 수집 값은 근거로만 남는다: 장비 상태·근거·revision 그대로, 이슈 없음
        assertThat(jdbc.queryForMap("select technical_state, state_evidence::text as evidence, revision from equipment_asset where id = ?", ASSET_A))
                .isEqualTo(asset);
        assertThat(jdbc.queryForObject("select count(*) from issue where event_id = ?", Long.class, EVENT_A)).isZero();
        assertThat(reader.latest(ASSET_A)).extracting(EquipmentObservationReader.Latest::recordType)
                .containsExactlyInAnyOrder("LINK", "READING", "STATUS");
    }

    @Test
    void resendingSameBatchIsDuplicateSuccess() throws Exception {
        var records = List.of(reading(RUN, 10, "BATT_BARS", 4, "004"), reading(RUN, 11, "BATT_BARS", 3, "003"));
        assertThat(postA(records).status()).isEqualTo(200);
        var again = postA(records);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("accepted").asInt()).isZero();
        assertThat(again.body().get("duplicates").asInt()).isEqualTo(2);
        assertThat(again.body().get("runs").get(0).get("lastSequence").asLong()).isEqualTo(11);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void sameKeyDifferentContentRejectsWholeBatch() throws Exception {
        assertThat(postA(List.of(reading(RUN, 20, "BATT_BARS", 4, "004"))).status()).isEqualTo(200);
        var reply = postA(List.of(reading(RUN, 21, "BATT_BARS", 4, "004"), reading(RUN, 20, "BATT_BARS", 1, "001")));
        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("COLLECTOR_SEQUENCE_CONFLICT");
        assertThat(reply.body().get("requestId").asString()).isNotBlank();
        // 묶음 전체가 저장되지 않는다(21번도 없음)
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void lateArrivingOlderRecordDoesNotReplaceLatest() throws Exception {
        assertThat(postA(List.of(reading(RUN, 31, "BATT_BARS", 2, "002"))).status()).isEqualTo(200);
        assertThat(postA(List.of(reading(RUN, 30, "BATT_BARS", 5, "005"))).status()).isEqualTo(200);
        var battery = reader.latest(ASSET_A).stream().filter(l -> "BATT_BARS".equals(l.item())).findFirst().orElseThrow();
        assertThat(battery.sequence()).isEqualTo(31);
        assertThat(battery.payload().get("raw").asString()).isEqualTo("002");
        // 더 이른 기록은 변화가 아니므로 내용 보관 24시간, 최신 값을 바꾼 기록은 30일
        assertThat(ttlHours(30)).isEqualTo(24);
        assertThat(ttlHours(31)).isEqualTo(30 * 24);
    }

    @Test
    void laterRunWinsOverOldRunAndClosedRunAcceptsOnlyDuplicates() throws Exception {
        // 닫힌 run의 옛 기록은 이미 저장돼 있었다고 둔다(run이 닫히기 전에 받은 것)
        var old = reading(OLD_RUN, 900, "BATT_BARS", 1, "001");
        jdbc.update("""
                insert into equipment_observation (collector_id, run_id, sequence, event_id, equipment_asset_id, record_type,
                    channel, item, session_id, collector_received_at, server_received_at, content_hash, payload, expires_at)
                values ('collector-a', ?, 900, ?, ?, 'READING', 1, 'BATT_BARS', null, now(), now(), ?, ?::jsonb, now() + interval '30 days')
                """, OLD_RUN, EVENT_A, ASSET_A, ObservationBatch.hash(json.valueToTree(old)), json.writeValueAsString(old));
        jdbc.update("""
                insert into equipment_observation_latest (equipment_asset_id, channel, record_type, item, collector_id, run_id,
                    run_order, sequence, session_id, collector_received_at, server_received_at, payload)
                values (?, 1, 'READING', 'BATT_BARS', 'collector-a', ?, 0, 900, null, now(), now(), ?::jsonb)
                """, ASSET_A, OLD_RUN, json.writeValueAsString(old));
        assertThat(postA(List.of(reading(RUN, 1, "BATT_BARS", 4, "004"))).status()).isEqualTo(200);
        // 새 run은 sequence가 작아도 옛 run보다 나중이다
        assertThat(reader.latest(ASSET_A).get(0).runId()).isEqualTo(RUN);

        var dup = postA(List.of(old));
        assertThat(dup.status()).isEqualTo(200);
        assertThat(dup.body().get("duplicates").asInt()).isEqualTo(1);

        var fresh = postA(List.of(reading(OLD_RUN, 901, "BATT_BARS", 1, "001")));
        assertThat(fresh.status()).isEqualTo(409);
        assertThat(fresh.code()).isEqualTo("COLLECTOR_RUN_NOT_ACTIVE");
        var mixed = postA(List.of(old, reading(OLD_RUN, 902, "BATT_BARS", 1, "001")));
        assertThat(mixed.status()).isEqualTo(409);

        var unknownRun = postA(List.of(reading(UUID.randomUUID(), 1, "BATT_BARS", 1, "001")));
        assertThat(unknownRun.code()).isEqualTo("COLLECTOR_RUN_NOT_ACTIVE");
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void otherEventTokenAndMissingTokenAreUnauthenticated() throws Exception {
        var body = batch("collector-a", List.of(reading(RUN, 40, "BATT_BARS", 4, "004")));
        assertThat(post("collector-a", TOKEN_B, body).code()).isEqualTo("COLLECTOR_UNAUTHENTICATED");
        assertThat(post("collector-a", null, body).status()).isEqualTo(401);
        assertThat(post("no-such-collector", TOKEN_A, body).code()).isEqualTo("COLLECTOR_UNAUTHENTICATED");
        // B의 토큰으로 B 경로에 A 기록을 넣을 수 없다
        assertThat(post("collector-b", TOKEN_B, body).code()).isEqualTo("COLLECTOR_SCOPE_FORBIDDEN");
        assertThat(count()).isZero();
    }

    @Test
    void scopeAndSourceMappingAreChecked() throws Exception {
        var unmapped = reading(RUN, 50, "BATT_BARS", 4, "004");
        unmapped.put("sourceRef", "qlxd4-spare");
        var reply = postA(List.of(unmapped));
        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("COLLECTOR_SOURCE_UNMAPPED");

        var otherCollector = reading(RUN, 51, "BATT_BARS", 4, "004");
        otherCollector.put("collectorId", "collector-b");
        assertThat(postA(List.of(otherCollector)).code()).isEqualTo("COLLECTOR_SCOPE_FORBIDDEN");

        // 설정이 다른 행사의 장비를 가리키면 저장하지 않는다
        var cross = reading(RUN_C, 1, "BATT_BARS", 4, "004");
        cross.put("collectorId", "collector-c");
        cross.put("sourceRef", "other-event");
        assertThat(post("collector-c", TOKEN_C, batch("collector-c", List.of(cross))).code())
                .isEqualTo("COLLECTOR_SCOPE_FORBIDDEN");
        assertThat(count()).isZero();
    }

    @Test
    void malformedBatchesAreRejected() throws Exception {
        assertThat(post("collector-a", TOKEN_A, "not json").code()).isEqualTo("INVALID_COLLECTOR_REQUEST");
        assertThat(post("collector-a", TOKEN_A, "{\"schema\":\"x\"}").code()).isEqualTo("INVALID_COLLECTOR_REQUEST");

        var extra = reading(RUN, 60, "BATT_BARS", 4, "004");
        extra.put("unexpected", true);
        var reply = postA(List.of(extra));
        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("COLLECTOR_RECORD_INVALID");
        var noObserved = reading(RUN, 61, "BATT_BARS", 4, "004");
        noObserved.put("observedAt", "2026-11-05T01:00:00Z");
        assertThat(postA(List.of(noObserved)).code()).isEqualTo("COLLECTOR_RECORD_INVALID");

        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 1; i <= 101; i++) many.add(reading(RUN, 100 + i, "BATT_BARS", 4, "004"));
        var tooMany = postA(many);
        assertThat(tooMany.status()).isEqualTo(413);
        assertThat(tooMany.code()).isEqualTo("COLLECTOR_BATCH_TOO_LARGE");
        assertThat(count()).isZero();
    }

    @Test
    void endedEventRejectsCollection() throws Exception {
        jdbc.update("update event set state = 'ENDED' where id = ?", EVENT_A);
        var reply = postA(List.of(reading(RUN, 70, "BATT_BARS", 4, "004")));
        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("COLLECTOR_EVENT_CLOSED");
        assertThat(count()).isZero();
    }

    @Test
    void nullEnumFieldsAreRecordErrors() throws Exception {
        for (String field : List.of("mode", "origin", "valueStatus")) {
            var record = reading(RUN, 75, "BATT_BARS", 4, "004");
            record.put(field, null);
            var reply = postA(List.of(record));
            assertThat(reply.status()).as(field).isEqualTo(422);
            assertThat(reply.code()).isEqualTo("COLLECTOR_RECORD_INVALID");
        }
        var record = status(RUN, 76, "FRESH");
        record.put("metering", null);
        assertThat(postA(List.of(record)).status()).isEqualTo(422);
        record = link(RUN, 77, "CONNECTED");
        record.put("linkState", null);
        assertThat(postA(List.of(record)).status()).isEqualTo(422);
    }

    @Test
    void retentionDeletesMeteringAfterDayAndChangesAfterMonthButKeepsLatest() throws Exception {
        // 주기 계측 2건(24시간), 보고 값 변화 1건(30일), 같은 값 보고 1건(24시간)
        assertThat(postA(List.of(sample(RUN, 80, "RF_LEVEL", 93), sample(RUN, 81, "RF_LEVEL", 91),
                reading(RUN, 82, "BATT_BARS", 4, "004"), reading(RUN, 83, "BATT_BARS", 4, "004"))).status()).isEqualTo(200);
        assertThat(ttlHours(80)).isEqualTo(24);
        assertThat(ttlHours(82)).isEqualTo(30 * 24);
        assertThat(ttlHours(83)).isEqualTo(24);

        var day = retention.cleanup(Instant.now().plusSeconds(25 * 3600));
        assertThat(day.observationsDeleted()).isEqualTo(3);
        assertThat(jdbc.queryForList("select sequence from equipment_observation where run_id = ?", Long.class, RUN))
                .containsExactly(82L);
        // 기록을 지워도 최신 값은 남는다
        assertThat(reader.latest(ASSET_A)).extracting(EquipmentObservationReader.Latest::sequence)
                .containsExactlyInAnyOrder(81L, 83L);

        retention.cleanup(Instant.now().plusSeconds(31L * 24 * 3600));
        assertThat(count()).isZero();
        assertThat(reader.latest(ASSET_A)).hasSize(2);

        // 종료된 행사의 최신 값은 마지막 수신 후 30일이 지나면 지운다
        jdbc.update("update event set state = 'ENDED' where id = ?", EVENT_A);
        assertThat(retention.cleanup(Instant.now().plusSeconds(31L * 24 * 3600)).latestDeleted()).isEqualTo(2);
        assertThat(reader.latest(ASSET_A)).isEmpty();
    }

    @Test
    void retentionDeletesInBatches() throws Exception {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 1; i <= 25; i++) many.add(sample(RUN, 200 + i, "RF_LEVEL", 50 + i));
        assertThat(postA(many).status()).isEqualTo(200);
        var small = new ObservationRetention(jdbc, 10, 2);
        assertThat(small.cleanup(Instant.now().plusSeconds(25 * 3600)).observationsDeleted()).isEqualTo(20);
        assertThat(small.cleanup(Instant.now().plusSeconds(25 * 3600)).observationsDeleted()).isEqualTo(5);
    }

    @Test
    void recordOlderThanRetentionIsRejectedInsteadOfStoredAgain() throws Exception {
        var old = sample(RUN, 300, "RF_LEVEL", 90);
        old.put("receivedAt", Instant.now().minusSeconds(25 * 3600).toString());
        var reply = postA(List.of(old));
        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("COLLECTOR_SEQUENCE_CONFLICT");
        assertThat(reply.body().get("details").get("reason").asString()).isEqualTo("EVIDENCE_EXPIRED");
        assertThat(count()).isZero();
    }

    @Test
    void concurrentSameBatchStoresOnceAndResendIsDuplicate() throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 1; i <= 50; i++) records.add(sample(RUN, 400 + i, "RF_LEVEL", 40 + i % 10));
        String body = batch("collector-a", records);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Reply>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> post("collector-a", TOKEN_A, body)));
            int accepted = 0;
            for (var f : futures) {
                Reply r = f.get();
                // 같은 장비의 묶음은 차례로 판정되므로 하나만 저장하고 나머지는 중복 성공이다(드물게 503이면 다시 보내면 된다)
                assertThat(r.status()).isIn(200, 503);
                if (r.status() == 200) accepted += r.body().get("accepted").asInt();
            }
            assertThat(accepted).isEqualTo(50);
        } finally {
            pool.shutdownNow();
        }
        var again = post("collector-a", TOKEN_A, body);
        assertThat(again.body().get("duplicates").asInt()).isEqualTo(50);
        assertThat(count()).isEqualTo(50);
    }
}

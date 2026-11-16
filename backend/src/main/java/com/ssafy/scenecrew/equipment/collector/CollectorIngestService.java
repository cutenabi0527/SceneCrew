package com.ssafy.scenecrew.equipment.collector;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.equipment.collector.CollectorRegistry.Collector;
import com.ssafy.scenecrew.equipment.collector.ObservationBatch.Record;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 장비 수집기의 관측 묶음을 받아 저장한다(#330).
 *
 * 처리 순서는 계약 후보(internal-collector-proposed.yaml x-batch-processing)를 따른다.
 * 인증 → 형식 → 범위(수집기·행사·sourceRef) → 기존 기록 확인 → 새 기록의 run 확인 → 묶음 전체를 한 트랜잭션에 저장 → ACK.
 * 하나라도 실패하면 아무것도 저장하지 않는다.
 *
 * 수집 값은 equipment_observation(기록)과 equipment_observation_latest(항목별 최신 값)에만 남는다.
 * equipment_asset은 읽기만 하고 잠그지도 않는다. technicalState를 바꾸거나 이슈를 만들지 않는다(BE13).
 */
public class CollectorIngestService {
    /** 주기 계측과 값이 그대로인 기록의 보관 기간. 중복 판정도 이 기간 동안만 한다. */
    static final Duration METERING_TTL = Duration.ofHours(24);
    /** 상태 변화 기록의 보관 기간 */
    static final Duration CHANGE_TTL = Duration.ofDays(30);
    /** 기록을 지운 뒤 다시 온 기록을 새 기록으로 저장하지 않도록, 보관 기간보다 이만큼 일찍 거절을 시작한다(시계 차이 여유). */
    static final Duration EXPIRY_MARGIN = Duration.ofHours(1);
    private static final Set<String> OPEN_EVENT_STATES = Set.of("RECRUITING", "LIVE");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(CollectorIngestService.class);

    private final CollectorRegistry registry;
    private final WebCollectors webCollectors;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CollectorIngestService(CollectorRegistry registry, JdbcTemplate jdbc, PlatformTransactionManager manager,
            Clock clock) {
        this(registry, new WebCollectors(jdbc), jdbc, manager, clock);
    }

    /** 설정 등록을 먼저 찾고, 없으면 연결 코드로 DB에 등록한 수집기(#394)를 찾는다. */
    public CollectorIngestService(CollectorRegistry registry, WebCollectors webCollectors, JdbcTemplate jdbc,
            PlatformTransactionManager manager, Clock clock) {
        this.registry = registry;
        this.webCollectors = webCollectors;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
    }

    public record Ack(String collectorId, Instant receivedAt, int accepted, int duplicates, List<RunAck> runs) {
    }

    public record RunAck(UUID runId, long lastSequence) {
    }

    public Ack ingest(String pathCollectorId, String authorization, String body) {
        Collector collector = authenticate(pathCollectorId, authorization);
        ObservationBatch batch = ObservationBatch.parse(body);
        checkScope(collector, batch);
        Ack ack = tx.execute(status -> store(collector, batch));
        if (batch.droppedSinceLast() > 0) {
            // 수집기가 서버에 보내지 못해 한도를 넘어 버린 기록이다. 그 구간은 저장되지 않으므로 운영에서 알 수 있게 남긴다.
            log.warn("장비 수집기 {}가 미전송 한도 초과로 버린 기록 {}건을 알렸습니다. 해당 번호 구간은 저장되지 않습니다.",
                    collector.id(), batch.droppedSinceLast());
        }
        return ack;
    }

    private Collector authenticate(String collectorId, String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()) : null;
        // 없는 수집기와 틀린 토큰을 구별해 알려 주지 않는다
        return registry.find(collectorId)
                .filter(c -> token != null && !token.isEmpty() && c.accepts(token))
                .or(() -> webCollectors.authenticate(collectorId, token))
                .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTOR_UNAUTHENTICATED));
    }

    private void checkScope(Collector collector, ObservationBatch batch) {
        if (!collector.id().equals(batch.collectorId())) throw new BusinessException(ErrorCode.COLLECTOR_SCOPE_FORBIDDEN);
        for (int i = 0; i < batch.records().size(); i++) {
            Record r = batch.records().get(i);
            if (!collector.id().equals(r.node().get("collectorId").asString()))
                throw new BusinessException(ErrorCode.COLLECTOR_SCOPE_FORBIDDEN);
            if (!collector.sources().containsKey(r.sourceRef()))
                throw new BusinessException(ErrorCode.COLLECTOR_SOURCE_UNMAPPED, ErrorCode.COLLECTOR_SOURCE_UNMAPPED.getMessage(),
                        Map.of("index", i, "sourceRef", r.sourceRef()));
        }
    }

    private Ack store(Collector collector, ObservationBatch batch) {
        Instant now = clock.instant();
        // 행사·장비 행은 잠그지 않는다. 총괄의 장비 변경·예약 승인을 수집 요청이 막지 않게 하기 위해서다.
        String eventState = jdbc.query("select state from event where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, collector.eventId());
        if (eventState == null) throw new BusinessException(ErrorCode.COLLECTOR_SCOPE_FORBIDDEN);
        if (!OPEN_EVENT_STATES.contains(eventState)) throw new BusinessException(ErrorCode.COLLECTOR_EVENT_CLOSED);

        Set<UUID> assetIds = new TreeSet<>();
        for (Record r : batch.records()) assetIds.add(collector.sources().get(r.sourceRef()));
        for (UUID assetId : assetIds) {
            UUID eventId = jdbc.query("select event_id from equipment_asset where id = ?",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null, assetId);
            if (!collector.eventId().equals(eventId)) throw new BusinessException(ErrorCode.COLLECTOR_SCOPE_FORBIDDEN);
            // 같은 장비의 기록은 한 번에 하나씩 판정한다. 업무 행 대신 트랜잭션 범위의 advisory lock을 쓴다(정해진 순서로).
            jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, lockKey(assetId));
        }
        if (collector.web()) {
            // 웹 연결 수집기: 묶음마다 연결한 사람의 유지 자격을 다시 본다(V9-D59).
            webCollectors.requireQualified(collector, assetIds);
            webCollectors.touchBatch(collector.id(), now);
        } else {
            // 설정 등록 경로: 웹으로 연결된 장비의 기록은 받지 않는다(한 장비는 두 등록 중 하나만, V9-D60).
            Boolean linked = jdbc.queryForObject("select exists (select 1 from equipment_asset where id = any(?)"
                    + " and link_collector_id is not null)", Boolean.class, (Object) assetIds.toArray(new UUID[0]));
            if (Boolean.TRUE.equals(linked)) throw new BusinessException(ErrorCode.INPUT_LINKED_ELSEWHERE);
        }

        // 기존 기록 확인: 같은 키에 같은 내용이면 중복 성공, 다른 내용이면 묶음 전체 거절
        Map<String, String> seen = new HashMap<>();
        Map<String, String> stored = storedHashes(collector, batch);
        List<Record> fresh = new ArrayList<>();
        int duplicates = 0;
        for (Record r : batch.records()) {
            String key = r.runId() + ":" + r.sequence();
            String earlier = seen.putIfAbsent(key, r.contentHash());
            String existing = stored.get(key);
            String known = existing != null ? existing : earlier;
            if (known != null) {
                if (!known.equals(r.contentHash())) throw new BusinessException(ErrorCode.COLLECTOR_SEQUENCE_CONFLICT,
                        ErrorCode.COLLECTOR_SEQUENCE_CONFLICT.getMessage(), Map.of("runId", r.runId().toString(),
                                "sequence", r.sequence()));
                duplicates++;
                continue;
            }
            fresh.add(r);
        }
        // 새 기록은 등록된 진행 중 run에서만 받는다. 닫힌 run의 중복과 새 기록이 섞이면 묶음 전체를 거절한다.
        for (Record r : fresh) {
            if (collector.run(r.runId()).map(run -> !run.active()).orElse(true))
                throw new BusinessException(ErrorCode.COLLECTOR_RUN_NOT_ACTIVE, ErrorCode.COLLECTOR_RUN_NOT_ACTIVE.getMessage(),
                        Map.of("runId", r.runId().toString()));
        }

        Map<String, Latest> latest = latest(assetIds);
        Map<String, Latest> changedLatest = new LinkedHashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (Record r : fresh) {
            UUID assetId = collector.sources().get(r.sourceRef());
            int order = collector.run(r.runId()).orElseThrow().order();
            Duration ttl = classify(latest, changedLatest, collector, assetId, r, order, now);
            // 이미 보관 기간이 지났을 기록이다. 같은 기록을 전에 받아 지웠을 수 있으므로 새 기록으로 저장하지 않는다.
            if (r.receivedAt().plus(ttl).minus(EXPIRY_MARGIN).isBefore(now))
                throw new BusinessException(ErrorCode.COLLECTOR_SEQUENCE_CONFLICT, ErrorCode.COLLECTOR_SEQUENCE_CONFLICT.getMessage(),
                        Map.of("runId", r.runId().toString(), "sequence", r.sequence(), "reason", "EVIDENCE_EXPIRED"));
            rows.add(new Object[] { collector.id(), r.runId(), r.sequence(), collector.eventId(), assetId, r.type(),
                    r.channel(), r.item(), r.sessionId(), Timestamp.from(r.receivedAt()), Timestamp.from(now),
                    r.contentHash(), JSON.writeValueAsString(r.node()), Timestamp.from(now.plus(ttl)) });
        }
        if (!rows.isEmpty()) {
            int[] inserted = jdbc.batchUpdate("""
                    insert into equipment_observation (collector_id, run_id, sequence, event_id, equipment_asset_id,
                        record_type, channel, item, session_id, collector_received_at, server_received_at, content_hash,
                        payload, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    on conflict do nothing
                    """, rows);
            for (int n : inserted) {
                // 다른 요청이 같은 기록을 먼저 저장했다. 이번 묶음은 되돌리고 다시 보내면 중복으로 확인된다.
                if (n == 0) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }
        }
        for (Latest l : changedLatest.values()) {
            jdbc.update("""
                    insert into equipment_observation_latest (equipment_asset_id, channel, record_type, item, collector_id, run_id,
                        run_order, sequence, session_id, collector_received_at, server_received_at, payload)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    on conflict (equipment_asset_id, channel, record_type, item) do update set
                        collector_id = excluded.collector_id, run_id = excluded.run_id, run_order = excluded.run_order,
                        sequence = excluded.sequence, session_id = excluded.session_id,
                        collector_received_at = excluded.collector_received_at, server_received_at = excluded.server_received_at,
                        payload = excluded.payload
                    """, l.assetId(), l.channel(), l.type(), l.item(), l.collectorId(), l.runId(), l.runOrder(), l.sequence(),
                    l.sessionId(), Timestamp.from(l.collectorReceivedAt()), Timestamp.from(now), JSON.writeValueAsString(l.node()));
        }

        List<RunAck> runs = new ArrayList<>();
        for (UUID runId : new LinkedHashSet<>(batch.records().stream().map(Record::runId).toList())) {
            Long last = jdbc.queryForObject("select max(sequence) from equipment_observation where collector_id = ? and run_id = ?",
                    Long.class, collector.id(), runId);
            runs.add(new RunAck(runId, last == null ? 0 : last));
        }
        return new Ack(collector.id(), now, fresh.size(), duplicates, List.copyOf(runs));
    }

    private static long lockKey(UUID assetId) {
        return assetId.getMostSignificantBits() ^ assetId.getLeastSignificantBits();
    }

    private Map<String, String> storedHashes(Collector collector, ObservationBatch batch) {
        Map<UUID, List<Long>> byRun = new LinkedHashMap<>();
        for (Record r : batch.records()) byRun.computeIfAbsent(r.runId(), k -> new ArrayList<>()).add(r.sequence());
        Map<String, String> result = new HashMap<>();
        for (var entry : byRun.entrySet()) {
            jdbc.query("select sequence, content_hash from equipment_observation"
                    + " where collector_id = ? and run_id = ? and sequence = any(?)",
                    rs -> { result.put(entry.getKey() + ":" + rs.getLong(1), rs.getString(2)); },
                    collector.id(), entry.getKey(), entry.getValue().toArray(new Long[0]));
        }
        return result;
    }

    /** 항목별 최신 기록. 같은 수집기의 기록끼리는 (run 순서, sequence)로 나중을 정한다. */
    private record Latest(UUID assetId, int channel, String type, String item, String collectorId, UUID runId, int runOrder,
            long sequence, UUID sessionId, Instant collectorReceivedAt, JsonNode node) {
    }

    private static String key(UUID assetId, int channel, String type, String item) {
        return assetId + "|" + channel + "|" + type + "|" + item;
    }

    private Map<String, Latest> latest(Set<UUID> assetIds) {
        Map<String, Latest> result = new HashMap<>();
        jdbc.query("""
                select equipment_asset_id, channel, record_type, item, collector_id, run_id, run_order, sequence, session_id,
                       collector_received_at, payload::text
                from equipment_observation_latest where equipment_asset_id = any(?)
                """, rs -> {
            var l = new Latest(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getObject(6, UUID.class), rs.getInt(7), rs.getLong(8), rs.getObject(9, UUID.class),
                    rs.getTimestamp(10).toInstant(), JSON.readTree(rs.getString(11)));
            result.put(key(l.assetId(), l.channel(), l.type(), l.item()), l);
        }, (Object) assetIds.toArray(new UUID[0]));
        return result;
    }

    /**
     * 기록의 보관 기간을 정하고, 최신 값이 바뀌면 changedLatest에 남긴다.
     * - 주기 계측(READING origin=SAMPLE): 24시간
     * - 보고 값(READING origin=REPORT): 최신 값의 value·valueStatus·raw가 바뀌면 30일, 아니면 24시간
     * - STATUS: linkState·metering이 바뀌면 30일, 아니면 24시간
     * - LINK·UNPARSED: 30일
     * 더 이른 기록(이전 run이거나 같은 run의 작은 sequence)은 최신 값을 바꾸지 않고 변화로 보지 않는다.
     */
    private Duration classify(Map<String, Latest> latest, Map<String, Latest> changedLatest, Collector collector,
            UUID assetId, Record r, int order, Instant now) {
        if ("UNPARSED".equals(r.type())) return CHANGE_TTL;
        String item = Objects.toString(r.item(), "");
        String k = key(assetId, r.channel(), r.type(), item);
        Latest current = latest.get(k);
        boolean newer = current == null || !current.collectorId().equals(collector.id())
                || current.runOrder() < order || (current.runOrder() == order && current.sequence() < r.sequence());
        if (newer) {
            Latest next = new Latest(assetId, r.channel(), r.type(), item, collector.id(), r.runId(), order, r.sequence(),
                    r.sessionId(), r.receivedAt(), r.node());
            latest.put(k, next);
            changedLatest.put(k, next);
        }
        return switch (r.type()) {
            case "LINK" -> CHANGE_TTL;
            case "READING" -> "SAMPLE".equals(r.node().get("origin").asString()) || !newer
                    || !changed(current, r, List.of("value", "valueStatus", "raw")) ? METERING_TTL : CHANGE_TTL;
            default -> newer && changed(current, r, List.of("linkState", "metering")) ? CHANGE_TTL : METERING_TTL;
        };
    }

    private static boolean changed(Latest current, Record r, List<String> fields) {
        if (current == null) return true;
        for (String f : fields) if (!Objects.equals(current.node().get(f), r.node().get(f))) return true;
        return false;
    }
}

package com.ssafy.scenecrew.equipment.collector;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 노트북 수집 프로그램의 연결 코드 교환과 찾은 수신기 보고(#394, V9-D59). 로그인 없이 부르는 내부 경로다.
 *
 * <ul>
 *   <li>교환(pair): 쓰지 않았고 만료 전인 코드면 수집기(id·토큰·runId)를 새로 만들고 코드를 쓴 것으로 남긴다.
 *       잘못된 코드·만료·이미 씀·코드 주인의 자격 상실은 구별하지 않고 401이다(코드 추측을 돕지 않게). 요청량은 nginx가 제한한다.</li>
 *   <li>교환 본문은 {code, programVersion?, hostLabel?}다. 버전(40자)·노트북 이름(60자)은 화면 표시와 낡은 프로그램 안내용이다.</li>
 *   <li>수신기 보고(receivers): 토큰으로 인증하고, 보낸 목록으로 이 수집기의 수신기 목록을 통째로 바꾼다. IP 칸은 받지 않는다.
 *       응답의 links는 지금 장비 칸에 연결된 수신기·채널이다. 프로그램은 이 요청을 주기적으로(약 10초) 보내 연결 변화를 따라가고,
 *       links에 있는 수신기 기록만 보낸다(연결 안 된 수신기 기록이 섞이면 관측 묶음 전체가 422다).</li>
 * </ul>
 */
public class CollectorPairingService {
    static final int MAX_RECEIVERS = 16;
    static final String RECEIVER_KEY = "[A-Za-z0-9._:-]{1,100}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WebCollectors collectors;
    private final Clock clock;

    public CollectorPairingService(JdbcTemplate jdbc, PlatformTransactionManager manager, WebCollectors collectors, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.collectors = collectors;
        this.clock = clock;
    }

    /** 교환 응답. token 원문은 이 응답에만 있다. */
    public record Paired(String collectorId, String token, UUID runId) {
    }

    public record ReceiversAck(String collectorId, int receivers, List<WebCollectors.Link> links) {
    }

    public Paired pair(String body) {
        JsonNode root = object(body);
        for (String name : root.propertyNames())
            if (!Set.of("code", "programVersion", "hostLabel").contains(name)) throw invalid(name);
        JsonNode code = root.get("code");
        if (code == null || !code.isString()) throw invalid("code");
        String programVersion = text(root.get("programVersion"), 40, "programVersion");
        String hostLabel = text(root.get("hostLabel"), 60, "hostLabel");
        String hash = LinkCodes.hash(code.asString());
        if (hash == null) throw unauthenticated();
        Instant now = clock.instant();
        return tx.execute(status -> {
            List<Object[]> rows = jdbc.query("""
                    SELECT c.event_id, c.member_id FROM equipment_collector_code c
                      JOIN event e ON e.id = c.event_id
                      JOIN member m ON m.id = c.member_id
                     WHERE c.code_sha256 = ? AND c.used_at IS NULL AND c.expires_at > ?
                       AND e.state IN ('RECRUITING', 'LIVE') AND m.active
                     FOR UPDATE OF c""", (r, n) -> new Object[] { r.getObject(1, UUID.class), r.getObject(2, UUID.class) },
                    hash, now.atOffset(ZoneOffset.UTC));
            if (rows.isEmpty()) throw unauthenticated();
            String id = WebCollectors.newId();
            String token = WebCollectors.newToken();
            UUID runId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO equipment_collector (id, event_id, member_id, token_sha256, run_id, program_version, host_label,
                                                     created_at, last_seen_at, last_batch_at, revoked_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)""", id, rows.getFirst()[0], rows.getFirst()[1],
                    CollectorRegistry.sha256(token), runId, programVersion, hostLabel, now.atOffset(ZoneOffset.UTC));
            jdbc.update("UPDATE equipment_collector_code SET used_at = ?, collector_id = ? WHERE code_sha256 = ?",
                    now.atOffset(ZoneOffset.UTC), id, hash);
            return new Paired(id, token, runId);
        });
    }

    public ReceiversAck receivers(String collectorId, String authorization, String body) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        if (collectors.authenticate(collectorId, token).isEmpty()) throw unauthenticated();
        JsonNode root = object(body);
        JsonNode list = root.get("receivers");
        if (list == null || !list.isArray() || root.size() != 1) throw invalid("receivers");
        if (list.size() > MAX_RECEIVERS) throw invalid("receivers");
        List<Object[]> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Instant now = clock.instant();
        for (int i = 0; i < list.size(); i++) {
            JsonNode r = list.get(i);
            String at = "receivers[" + i + "]";
            if (r == null || !r.isObject()) throw invalid(at);
            for (String name : r.propertyNames())
                if (!Set.of("receiverKey", "name", "model", "channels").contains(name)) throw invalid(at + "." + name);
            JsonNode key = r.get("receiverKey");
            if (key == null || !key.isString() || !key.asString().matches(RECEIVER_KEY)) throw invalid(at + ".receiverKey");
            if (!keys.add(key.asString())) throw invalid(at + ".receiverKey");
            JsonNode channels = r.get("channels");
            if (channels == null || !channels.isInt() || channels.asInt() < 1 || channels.asInt() > 4) throw invalid(at + ".channels");
            rows.add(new Object[] { collectorId, key.asString(), text(r.get("name"), 100, at + ".name"),
                    text(r.get("model"), 40, at + ".model"), channels.asInt(), now.atOffset(ZoneOffset.UTC) });
        }
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM equipment_collector_receiver WHERE collector_id = ?", collectorId);
            if (!rows.isEmpty()) jdbc.batchUpdate("""
                    INSERT INTO equipment_collector_receiver (collector_id, receiver_key, name, model, channels, seen_at)
                    VALUES (?, ?, ?, ?, ?, ?)""", rows);
            collectors.touch(collectorId, now);
        });
        return new ReceiversAck(collectorId, rows.size(), collectors.links(collectorId));
    }

    private static JsonNode object(String body) {
        try {
            JsonNode root = body == null ? null : JSON.readTree(body);
            if (root == null || !root.isObject()) throw invalid("body");
            return root;
        } catch (JacksonException e) {
            throw invalid("body");
        }
    }

    private static String text(JsonNode node, int max, String field) {
        if (node == null || node.isNull()) return null;
        if (!node.isString()) throw invalid(field);
        String value = node.asString().strip();
        if (value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > max) throw invalid(field);
        return value;
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(ErrorCode.COLLECTOR_UNAUTHENTICATED);
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(ErrorCode.INVALID_COLLECTOR_REQUEST, ErrorCode.INVALID_COLLECTOR_REQUEST.getMessage(),
                Map.of("field", field));
    }
}

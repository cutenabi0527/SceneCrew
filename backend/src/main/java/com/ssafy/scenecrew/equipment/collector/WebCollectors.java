package com.ssafy.scenecrew.equipment.collector;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.ssafy.scenecrew.equipment.collector.CollectorRegistry.Collector;
import com.ssafy.scenecrew.equipment.collector.CollectorRegistry.Run;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 연결 코드로 DB에 등록한 장비 수집기(#394, V9-D59). 관측 수신(CollectorIngestService)이 설정 등록 다음으로 찾는다.
 *
 * <ul>
 *   <li>인증: 폐기되지 않은 수집기의 토큰 SHA-256이 같아야 한다. 없는 수집기와 틀린 토큰을 구별하지 않는다(401).</li>
 *   <li>sources: 이 수집기에 연결된 장비 칸의 수신기 키(link_source_ref) → 장비 id. run은 짝을 맺을 때 받은 하나다.</li>
 *   <li>유지 자격(묶음마다): 연결한 사람이 참여 중이고 CHIEF이거나 장비 연결 업무의 담당자(완료해도 유지, 취소 아님),
 *       행사가 RECRUITING·LIVE. 아니면 403 COLLECTOR_LINK_FORBIDDEN이다.</li>
 * </ul>
 * 토큰·코드 원문은 저장하지 않는다. 수신기 IP는 받지도 저장하지도 않는다.
 */
public class WebCollectors {
    public static final String ID_PREFIX = "web-";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public WebCollectors(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 토큰이 맞는 폐기되지 않은 수집기. 설정 등록과 같은 Collector 모양으로 돌려준다. */
    public Optional<Collector> authenticate(String collectorId, String token) {
        if (collectorId == null || !collectorId.startsWith(ID_PREFIX) || token == null || token.isEmpty()) return Optional.empty();
        List<Object[]> rows = jdbc.query("""
                SELECT event_id, token_sha256, run_id FROM equipment_collector WHERE id = ? AND revoked_at IS NULL""",
                (r, n) -> new Object[] { r.getObject(1, UUID.class), r.getString(2), r.getObject(3, UUID.class) }, collectorId);
        if (rows.isEmpty()) return Optional.empty();
        Object[] row = rows.getFirst();
        Map<String, UUID> sources = new LinkedHashMap<>();
        jdbc.query("SELECT link_source_ref, id FROM equipment_asset WHERE link_collector_id = ? ORDER BY link_source_ref",
                rs -> { sources.put(rs.getString(1), rs.getObject(2, UUID.class)); }, collectorId);
        Collector collector = new Collector(collectorId, (UUID) row[0], (String) row[1], Map.copyOf(sources),
                List.of(new Run((UUID) row[2], true, 0)), true);
        return collector.accepts(token) ? Optional.of(collector) : Optional.empty();
    }

    /** 묶음에 든 장비마다 연결한 사람의 유지 자격을 다시 본다. 같은 트랜잭션에서 저장 직전에 부른다. */
    public void requireQualified(Collector collector, Iterable<UUID> assetIds) {
        for (UUID assetId : assetIds) {
            Boolean ok = jdbc.query("""
                    SELECT e.state IN ('RECRUITING', 'LIVE') AND m.active
                           AND (m.event_role = 'CHIEF' OR (t.assignee_id = m.id AND t.status <> 'CANCELED'))
                      FROM equipment_asset a
                      JOIN event e ON e.id = a.event_id
                      JOIN member m ON m.id = a.link_member_id
                      LEFT JOIN task t ON t.id = a.link_task_id
                     WHERE a.id = ? AND a.link_collector_id = ?""",
                    rs -> rs.next() ? rs.getBoolean(1) : null, assetId, collector.id());
            if (!Boolean.TRUE.equals(ok)) throw new BusinessException(ErrorCode.COLLECTOR_LINK_FORBIDDEN);
        }
    }

    /** 프로그램의 신호(수신기 보고). 넘겨받기 판단(살아 있는 연결)에 쓴다. */
    public void touch(String collectorId, Instant at) {
        jdbc.update("UPDATE equipment_collector SET last_seen_at = ? WHERE id = ?", at.atOffset(ZoneOffset.UTC), collectorId);
    }

    /** 관측 묶음을 저장했다. 자동 관측 상태(연결됨·프로그램 끊김)는 이 시각으로 판정한다(#394 리뷰). */
    public void touchBatch(String collectorId, Instant at) {
        jdbc.update("UPDATE equipment_collector SET last_seen_at = ?, last_batch_at = ? WHERE id = ?",
                at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC), collectorId);
    }

    /** 이 수집기에서 지금 장비 칸에 연결된 수신기·채널. 프로그램은 이 수신기 기록만 보낸다. */
    public List<Link> links(String collectorId) {
        return jdbc.query("""
                SELECT link_source_ref, link_channel FROM equipment_asset WHERE link_collector_id = ?
                 ORDER BY link_source_ref, link_channel""", (r, n) -> new Link(r.getString(1), r.getInt(2)), collectorId);
    }

    public record Link(String receiverKey, int channel) {
    }

    /** 연결된 장비 칸이 하나도 남지 않은 수집기는 토큰을 폐기한다(다시 쓰려면 새 코드가 필요하다). */
    public void revokeIfUnused(String collectorId, Instant at) {
        if (collectorId == null) return;
        jdbc.update("""
                UPDATE equipment_collector SET revoked_at = ?
                 WHERE id = ? AND revoked_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM equipment_asset WHERE link_collector_id = ?)""",
                at.atOffset(ZoneOffset.UTC), collectorId, collectorId);
    }

    /** 수집기 id(web-+12자)와 토큰(32바이트) 원문 */
    static String newId() {
        return ID_PREFIX + random(9);
    }

    static String newToken() {
        return random(32);
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}

package com.ssafy.scenecrew.change.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/** Event 잠금을 가진 실제 writer만 호출한다. 완료 이력·옛 참여 회차는 갱신하지 않는다. */
@Component
public class PendingAcknowledgments {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public PendingAcknowledgments(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void supersede(UUID eventId, Map<UUID, Set<UUID>> affected) {
        if (affected.isEmpty()) return;
        for (Row row : pending(eventId)) {
            Set<UUID> tasks = affected.get(row.memberId());
            if (tasks == null || tasks.isEmpty()) continue;
            ArrayNode current = (ArrayNode) JSON.readTree(row.targets());
            ArrayNode remaining = JSON.createArrayNode();
            current.forEach(t -> {
                if (!tasks.contains(UUID.fromString(t.get("taskId").asString()))) remaining.add(t);
            });
            if (remaining.size() != current.size()) save(row.id(), remaining);
        }
    }

    /** ChangeSet이 없는 직접 해제는 기존 pending의 현재 대상을 최소 해제 안내로 바꾼다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID eventId, UUID memberId, Set<UUID> releasedTaskIds, Instant now) {
        if (releasedTaskIds.isEmpty()) return;
        for (Row row : pending(eventId)) {
            if (!memberId.equals(row.memberId())) continue;
            ArrayNode targets = (ArrayNode) JSON.readTree(row.targets());
            boolean changed = false;
            for (var target : targets) {
                if (!target.get("removed").asBoolean()
                        && releasedTaskIds.contains(UUID.fromString(target.get("taskId").asString()))) {
                    var notice = (tools.jackson.databind.node.ObjectNode) target;
                    notice.putArray("changeKinds").add("ASSIGNEE");
                    notice.put("removed", true);
                    notice.put("changedAt", now.toString());
                    changed = true;
                }
            }
            if (changed) save(row.id(), targets);
        }
    }

    private List<Row> pending(UUID eventId) {
        return jdbc.query("""
                SELECT a.id,a.member_id,a.targets::text FROM change_acknowledgment a
                JOIN member m ON m.id=a.member_id AND m.event_id=a.event_id
                WHERE a.event_id=? AND a.acknowledged_at IS NULL AND m.active
                  AND a.participation_revision=m.participation_revision
                ORDER BY a.id FOR UPDATE OF a
                """, (r,n) -> new Row(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),eventId);
    }

    private void save(UUID id, ArrayNode targets) {
        String canonical = ChangeSetService.canonical(targets);
        jdbc.update("UPDATE change_acknowledgment SET targets=?::jsonb,targets_hash=? WHERE id=? AND acknowledged_at IS NULL",
                JSON.writeValueAsString(targets),ChangeSetService.sha256(canonical),id);
    }

    private record Row(UUID id, UUID memberId, String targets) { }
}

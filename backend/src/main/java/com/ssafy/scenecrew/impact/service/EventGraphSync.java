package com.ssafy.scenecrew.impact.service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 행사 하나의 업무 관계를 PostgreSQL에서 읽어 Neo4j에 다시 그린다.
 * 행사의 기존 그래프를 지우고 통째로 다시 만든다. 행사당 노드가 수백 개라 충분히 빠르고,
 * 변경분을 맞추는 로직 없이 항상 PostgreSQL과 같은 상태가 된다.
 *
 * 그래프 모델 (backend/docs/impact-analysis.md)
 *   (Task)-[:IN_ZONE]->(Zone), (Task)-[:PART_OF]->(Program)-[:HELD_IN]->(Zone)
 *   (Task)-[:OWNED_BY]->(WorkGroup), (Task)-[:ASSIGNED_TO]->(Member)-[:BELONGS_TO]->(WorkGroup)
 *   (Issue)-[:OCCURRED_IN]->(Zone), (Issue)-[:ABOUT]->(Task)
 */
public class EventGraphSync {
    private static final List<String> LABELS = List.of("Issue", "Task", "Member", "WorkGroup", "Program", "Zone");

    private final NamedParameterJdbcTemplate jdbc;
    private final Driver driver;
    private final AtomicBoolean schemaReady = new AtomicBoolean();

    public EventGraphSync(NamedParameterJdbcTemplate jdbc, Driver driver) {
        this.jdbc = jdbc;
        this.driver = driver;
    }

    public void rebuild(UUID eventId) {
        ensureSchema();
        Map<String, Object> params = read(eventId);
        try (Session session = driver.session()) {
            session.executeWriteWithoutResult(tx -> {
                for (String label : LABELS) {
                    tx.run("MATCH (n:" + label + " {eventId: $eventId}) DETACH DELETE n", params);
                }
                tx.run("UNWIND $zones AS z CREATE (:Zone {id: z.id, eventId: $eventId})", params);
                tx.run("""
                        UNWIND $programs AS p
                        CREATE (:Program {id: p.id, eventId: $eventId, startAt: p.startAt, endAt: p.endAt})
                        """, params);
                tx.run("UNWIND $workGroups AS g CREATE (:WorkGroup {id: g.id, eventId: $eventId})", params);
                tx.run("""
                        UNWIND $members AS m
                        CREATE (:Member {id: m.id, eventId: $eventId, name: m.name, available: m.available,
                                         reserve: m.reserve, active: m.active, role: m.role})
                        """, params);
                tx.run("""
                        UNWIND $tasks AS t
                        CREATE (:Task {id: t.id, eventId: $eventId, title: t.title, status: t.status,
                                       startAt: t.startAt, endAt: t.endAt, workGroupId: t.workGroupId})
                        """, params);
                tx.run("UNWIND $issues AS i CREATE (:Issue {id: i.id, eventId: $eventId})", params);

                tx.run("""
                        UNWIND $programs AS p WITH p WHERE p.zoneId IS NOT NULL
                        MATCH (a:Program {id: p.id}), (z:Zone {id: p.zoneId}) CREATE (a)-[:HELD_IN]->(z)
                        """, params);
                tx.run("""
                        UNWIND $members AS m WITH m WHERE m.workGroupId IS NOT NULL
                        MATCH (a:Member {id: m.id}), (g:WorkGroup {id: m.workGroupId}) CREATE (a)-[:BELONGS_TO]->(g)
                        """, params);
                tx.run("""
                        UNWIND $tasks AS t
                        MATCH (a:Task {id: t.id}), (z:Zone {id: t.zoneId}), (g:WorkGroup {id: t.workGroupId})
                        CREATE (a)-[:IN_ZONE]->(z), (a)-[:OWNED_BY]->(g)
                        """, params);
                tx.run("""
                        UNWIND $tasks AS t WITH t WHERE t.programId IS NOT NULL
                        MATCH (a:Task {id: t.id}), (p:Program {id: t.programId}) CREATE (a)-[:PART_OF]->(p)
                        """, params);
                tx.run("""
                        UNWIND $tasks AS t WITH t WHERE t.assigneeId IS NOT NULL
                        MATCH (a:Task {id: t.id}), (m:Member {id: t.assigneeId}) CREATE (a)-[:ASSIGNED_TO]->(m)
                        """, params);
                tx.run("""
                        UNWIND $issues AS i
                        MATCH (a:Issue {id: i.id}), (z:Zone {id: i.zoneId}) CREATE (a)-[:OCCURRED_IN]->(z)
                        """, params);
                tx.run("""
                        UNWIND $issues AS i WITH i WHERE i.taskId IS NOT NULL
                        MATCH (a:Issue {id: i.id}), (t:Task {id: i.taskId}) CREATE (a)-[:ABOUT]->(t)
                        """, params);
            });
        }
    }

    /** 노드 id는 유일, eventId로 행사 단위 삭제가 빠르도록 색인한다. 처음 한 번만 실행한다. */
    private void ensureSchema() {
        if (schemaReady.get()) {
            return;
        }
        try (Session session = driver.session()) {
            for (String label : LABELS) {
                String name = label.toLowerCase();
                session.run("CREATE CONSTRAINT " + name + "_id IF NOT EXISTS FOR (n:" + label + ") REQUIRE n.id IS UNIQUE")
                        .consume();
                session.run("CREATE INDEX " + name + "_event IF NOT EXISTS FOR (n:" + label + ") ON (n.eventId)")
                        .consume();
            }
        }
        schemaReady.set(true);
    }

    /** 그래프에 필요한 열만 읽는다. id는 문자열, 시각은 마이크로초 정수로 바꾼다. */
    private Map<String, Object> read(UUID eventId) {
        Map<String, Object> p = Map.of("eventId", eventId);
        Map<String, Object> params = new HashMap<>();
        params.put("eventId", eventId.toString());
        params.put("zones", jdbc.queryForList("SELECT id::text AS id FROM zone WHERE event_id = :eventId", p));
        params.put("workGroups", jdbc.queryForList("SELECT id::text AS id FROM work_group WHERE event_id = :eventId", p));
        params.put("programs", jdbc.query("""
                SELECT id::text AS id, zone_id::text AS zone_id, start_at, end_at FROM program WHERE event_id = :eventId
                """, p, (rs, n) -> row(
                "id", rs.getString("id"), "zoneId", rs.getString("zone_id"),
                "startAt", micros(rs.getObject("start_at", OffsetDateTime.class)),
                "endAt", micros(rs.getObject("end_at", OffsetDateTime.class)))));
        params.put("members", jdbc.query("""
                SELECT id::text AS id, work_group_id::text AS work_group_id, display_name, available, is_reserve,
                       active, event_role
                FROM member WHERE event_id = :eventId
                """, p, (rs, n) -> row(
                "id", rs.getString("id"), "workGroupId", rs.getString("work_group_id"),
                "name", rs.getString("display_name"), "available", rs.getBoolean("available"),
                "reserve", rs.getBoolean("is_reserve"), "active", rs.getBoolean("active"),
                "role", rs.getString("event_role"))));
        params.put("tasks", jdbc.query("""
                SELECT id::text AS id, title, status, start_at, end_at, zone_id::text AS zone_id,
                       program_id::text AS program_id, work_group_id::text AS work_group_id,
                       assignee_id::text AS assignee_id
                FROM task WHERE event_id = :eventId
                """, p, (rs, n) -> row(
                "id", rs.getString("id"), "title", rs.getString("title"), "status", rs.getString("status"),
                "startAt", micros(rs.getObject("start_at", OffsetDateTime.class)),
                "endAt", micros(rs.getObject("end_at", OffsetDateTime.class)),
                "zoneId", rs.getString("zone_id"), "programId", rs.getString("program_id"),
                "workGroupId", rs.getString("work_group_id"), "assigneeId", rs.getString("assignee_id"))));
        params.put("issues", jdbc.query("""
                SELECT id::text AS id, zone_id::text AS zone_id, task_id::text AS task_id
                FROM issue WHERE event_id = :eventId
                """, p, (rs, n) -> row(
                "id", rs.getString("id"), "zoneId", rs.getString("zone_id"), "taskId", rs.getString("task_id"))));
        return params;
    }

    private static long micros(OffsetDateTime time) {
        return GraphTime.toMicros(time.toInstant());
    }

    /** null 값을 담을 수 있는 Map (Map.of는 null을 받지 않는다) */
    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }
}

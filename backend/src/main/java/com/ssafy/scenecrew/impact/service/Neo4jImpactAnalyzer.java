package com.ssafy.scenecrew.impact.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.impact.service.ImpactResult.AffectedTask;
import com.ssafy.scenecrew.impact.service.ImpactResult.MemberRef;

/**
 * 그래프(Neo4j)로 구한 영향 분석. SqlImpactAnalyzer와 같은 규칙이며, 이슈가 있는 행사의 그래프가
 * EventGraphSync로 미리 적재되어 있어야 한다.
 */
public class Neo4jImpactAnalyzer implements ImpactAnalyzer {

    private static final String AFFECTED = """
            MATCH (i:Issue {id: $issueId})
            MATCH (t:Task {eventId: i.eventId})
            WHERE t.status IN ['WAITING', 'IN_PROGRESS']
              AND (EXISTS { (i)-[:OCCURRED_IN]->(:Zone)<-[:IN_ZONE]-(t) } OR EXISTS { (i)-[:ABOUT]->(t) })
            OPTIONAL MATCH (t)-[:ASSIGNED_TO]->(a:Member)
            OPTIONAL MATCH (t)-[:OWNED_BY]->(:WorkGroup)<-[:BELONGS_TO]-(c:Member)
              WHERE c.active AND c.available AND c.role = 'WORKER' AND (a IS NULL OR c <> a)
                AND NOT EXISTS {
                  MATCH (c)<-[:ASSIGNED_TO]-(o:Task)
                  WHERE o <> t AND o.status IN ['WAITING', 'IN_PROGRESS']
                    AND o.startAt < t.endAt AND t.startAt < o.endAt
                }
            RETURN t.id AS taskId, t.title AS title, t.startAt AS startAt, t.endAt AS endAt,
                   t.workGroupId AS workGroupId,
                   a {.id, .name, .reserve} AS assignee,
                   collect(c {.id, .name, .reserve}) AS candidates
            """;

    private final Driver driver;

    public Neo4jImpactAnalyzer(Driver driver) {
        this.driver = driver;
    }

    @Override
    public ImpactResult analyze(UUID issueId) {
        Map<String, Object> params = Map.of("issueId", issueId.toString());
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                if (!tx.run("MATCH (i:Issue {id: $issueId}) RETURN i.id", params).hasNext()) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "이슈를 찾을 수 없습니다.");
                }
                List<AffectedTask> tasks = tx.run(AFFECTED, params).list(Neo4jImpactAnalyzer::task);
                return new ImpactResult(issueId, tasks);
            });
        }
    }

    private static AffectedTask task(Record r) {
        return new AffectedTask(
                UUID.fromString(r.get("taskId").asString()),
                r.get("title").asString(),
                GraphTime.fromMicros(r.get("startAt").asLong()),
                GraphTime.fromMicros(r.get("endAt").asLong()),
                UUID.fromString(r.get("workGroupId").asString()),
                r.get("assignee").isNull() ? null : member(r.get("assignee")),
                r.get("candidates").asList(Neo4jImpactAnalyzer::member));
    }

    private static MemberRef member(Value v) {
        return new MemberRef(UUID.fromString(v.get("id").asString()), v.get("name").asString(),
                v.get("reserve").asBoolean());
    }
}

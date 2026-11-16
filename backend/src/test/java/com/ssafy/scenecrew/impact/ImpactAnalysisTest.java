package com.ssafy.scenecrew.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.impact.service.EventGraphSync;
import com.ssafy.scenecrew.impact.service.ImpactResult.AffectedTask;
import com.ssafy.scenecrew.impact.service.ImpactResult.MemberRef;
import com.ssafy.scenecrew.impact.service.ImpactResult;
import com.ssafy.scenecrew.impact.service.Neo4jImpactAnalyzer;
import com.ssafy.scenecrew.impact.service.SqlImpactAnalyzer;

/**
 * 같은 행사 데이터로 SQL 구현과 Neo4j 구현의 영향 분석 결과가 같은지 확인한다.
 * PostgreSQL(Testcontainers)과 Neo4j(서버와 같은 5.26.32 이미지)를 띄우므로 Docker가 필요하다.
 *
 * 행사 시나리오 (구역 A·B, 직무 그룹 무대·접수, 시각은 UTC)
 * - t1 A 무대 10~12시 김담당, t2 A 무대 13~14시 미배정, t4 A 무대 완료(10~12시, 이수동)
 * - t3 B 무대 11~12:30 박예비, t6 B 무대 15~16시 정예비, t5 B 접수 10~12시 한접수
 * - 무대 그룹 인원: 김담당, 이수동, 박예비(예비), 정예비(예비), 최불가(투입 불가), 윤비활성(비활성)
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
class ImpactAnalysisTest {
    private static final String NEO4J_IMAGE = "neo4j:5.26.32-community";
    private static final String NEO4J_PASSWORD = "test-password-1234";

    private static GenericContainer<?> neo4j;
    private static Driver driver;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private SqlImpactAnalyzer sql;
    private Neo4jImpactAnalyzer graph;
    private EventGraphSync sync;
    private Scenario s;

    @BeforeAll
    static void startNeo4j() {
        neo4j = new GenericContainer<>(DockerImageName.parse(NEO4J_IMAGE))
                .withEnv("NEO4J_AUTH", "neo4j/" + NEO4J_PASSWORD)
                .withExposedPorts(7687)
                .waitingFor(Wait.forLogMessage("(?s).*Started\\..*", 1));
        neo4j.start();
        driver = GraphDatabase.driver("bolt://" + neo4j.getHost() + ":" + neo4j.getMappedPort(7687),
                AuthTokens.basic("neo4j", NEO4J_PASSWORD));
    }

    @AfterAll
    static void stopNeo4j() {
        if (driver != null) {
            driver.close();
        }
        if (neo4j != null) {
            neo4j.stop();
        }
    }

    @BeforeEach
    void setUp() {
        sql = new SqlImpactAnalyzer(jdbc);
        graph = new Neo4jImpactAnalyzer(driver);
        sync = new EventGraphSync(jdbc, driver);
        s = new Scenario(jdbc);
        sync.rebuild(s.event);
    }

    @Test
    void zoneIssueFindsTasksAssigneesAndFreeCandidates() {
        ImpactResult result = sql.analyze(s.issueInZoneA);

        assertThat(result.tasks()).extracting(AffectedTask::taskId).containsExactly(s.t1, s.t2);

        AffectedTask t1 = result.tasks().get(0);
        assertThat(t1.assignee().memberId()).isEqualTo(s.kim);
        // 박예비는 t3(11~12:30)와 겹쳐 제외, 이수동의 완료 업무는 겹쳐도 막지 않음, 예비 인원이 먼저
        assertThat(t1.candidates()).extracting(MemberRef::memberId).containsExactly(s.jung, s.lee);

        AffectedTask t2 = result.tasks().get(1);
        assertThat(t2.assignee()).isNull();
        assertThat(t2.candidates()).extracting(MemberRef::memberId).containsExactly(s.park, s.jung, s.kim, s.lee);
    }

    @Test
    void sqlAndGraphGiveSameResult() {
        assertThat(graph.analyze(s.issueInZoneA)).isEqualTo(sql.analyze(s.issueInZoneA));
        assertThat(graph.analyze(s.issueAboutTask)).isEqualTo(sql.analyze(s.issueAboutTask));
    }

    @Test
    void issueAboutTaskAlsoIncludesThatTask() {
        // 구역 B 이슈인데 t1(구역 A)을 가리킨다. 구역 B의 업무와 t1을 함께 본다.
        ImpactResult result = graph.analyze(s.issueAboutTask);

        assertThat(result.tasks()).extracting(AffectedTask::taskId)
                .containsExactlyInAnyOrder(s.t1, s.t3, s.t5, s.t6);
    }

    @Test
    void rebuildReflectsChangesAndDoesNotDuplicate() {
        jdbc.update("UPDATE member SET available = false WHERE id = :id", Map.of("id", s.lee));
        sync.rebuild(s.event);
        sync.rebuild(s.event);

        ImpactResult result = graph.analyze(s.issueInZoneA);
        assertThat(result).isEqualTo(sql.analyze(s.issueInZoneA));
        assertThat(result.tasks().get(0).candidates()).extracting(MemberRef::memberId).containsExactly(s.jung);
        try (Session session = driver.session()) {
            long tasks = session.executeRead(tx -> tx.run(
                    "MATCH (t:Task {eventId: $e}) RETURN count(t) AS n", Map.of("e", s.event.toString()))
                    .single().get("n").asLong());
            assertThat(tasks).isEqualTo(6);
        }
    }

    @Test
    void unknownIssueIsNotFound() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> sql.analyze(unknown)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> graph.analyze(unknown)).isInstanceOf(BusinessException.class);
    }

    /** 테스트마다 새 행사를 만든다. 다른 테스트의 데이터와 섞이지 않는다. */
    private static final class Scenario {
        final UUID event = UUID.randomUUID();
        final UUID zoneA = UUID.randomUUID();
        final UUID zoneB = UUID.randomUUID();
        final UUID stage = UUID.randomUUID();
        final UUID reception = UUID.randomUUID();
        final UUID chief = UUID.randomUUID();
        final UUID kim = UUID.randomUUID();
        final UUID lee = UUID.randomUUID();
        final UUID park = UUID.randomUUID();
        final UUID jung = UUID.randomUUID();
        final UUID choi = UUID.randomUUID();
        final UUID yoon = UUID.randomUUID();
        final UUID han = UUID.randomUUID();
        final UUID t1 = UUID.randomUUID();
        final UUID t2 = UUID.randomUUID();
        final UUID t3 = UUID.randomUUID();
        final UUID t4 = UUID.randomUUID();
        final UUID t5 = UUID.randomUUID();
        final UUID t6 = UUID.randomUUID();
        final UUID issueInZoneA = UUID.randomUUID();
        final UUID issueAboutTask = UUID.randomUUID();

        private final NamedParameterJdbcTemplate jdbc;
        private int crewNo;

        Scenario(NamedParameterJdbcTemplate jdbc) {
            this.jdbc = jdbc;
            jdbc.update("""
                    INSERT INTO event (id, name, state, revision, last_input_modified_at)
                    VALUES (:id, '테스트 행사', 'LIVE', 0, now())
                    """, Map.of("id", event));
            zone(zoneA, "A구역");
            zone(zoneB, "B구역");
            group(stage, "무대");
            group(reception, "접수");

            member(chief, null, "총괄", "CHIEF", true, false, true);
            member(kim, stage, "김담당", "WORKER", true, false, true);
            member(lee, stage, "이수동", "WORKER", true, false, true);
            member(park, stage, "박예비", "WORKER", true, true, true);
            member(jung, stage, "정예비", "WORKER", true, true, true);
            member(choi, stage, "최불가", "WORKER", false, false, true);
            member(yoon, stage, "윤비활성", "WORKER", true, false, false);
            member(han, reception, "한접수", "WORKER", true, false, true);

            task(t1, zoneA, stage, kim, "프로젝터 점검", "10:00", "12:00", "WAITING");
            task(t2, zoneA, stage, null, "객석 안내", "13:00", "14:00", "IN_PROGRESS");
            task(t3, zoneB, stage, park, "음향 대기", "11:00", "12:30", "WAITING");
            task(t4, zoneA, stage, lee, "리허설 지원", "10:00", "12:00", "DONE");
            task(t5, zoneB, reception, han, "입구 접수", "10:00", "12:00", "WAITING");
            task(t6, zoneB, stage, jung, "무대 정리", "15:00", "16:00", "WAITING");

            issue(issueInZoneA, zoneA, null);
            issue(issueAboutTask, zoneB, t1);
        }

        private void zone(UUID id, String name) {
            jdbc.update("INSERT INTO zone (id, event_id, name, aliases) VALUES (:id, :e, :name, '[]'::jsonb)",
                    Map.of("id", id, "e", event, "name", name));
        }

        private void group(UUID id, String name) {
            jdbc.update("""
                    INSERT INTO work_group (id, event_id, name, instructions, active) VALUES (:id, :e, :name, '', true)
                    """, Map.of("id", id, "e", event, "name", name));
        }

        private void member(UUID id, UUID groupId, String name, String role, boolean available, boolean reserve,
                boolean active) {
            UUID account = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO account (id, display_name, status, signup_completed, token_version, consent, created_at)
                    VALUES (:id, :name, 'ACTIVE', true, 0, '{}'::jsonb, now())
                    """, Map.of("id", account, "name", name));
            HashMap<String, Object> p = new HashMap<>();
            p.put("id", id);
            p.put("e", event);
            p.put("account", account);
            p.put("group", groupId);
            p.put("crew", ++crewNo);
            p.put("role", role);
            p.put("name", name);
            p.put("available", available);
            p.put("reserve", reserve);
            p.put("active", active);
            jdbc.update("""
                    INSERT INTO member (id, event_id, account_id, work_group_id, crew_no, event_role, display_name,
                                        available, is_reserve, active, participation_revision)
                    VALUES (:id, :e, :account, :group, :crew, :role, :name, :available, :reserve, :active, 0)
                    """, p);
        }

        private void task(UUID id, UUID zoneId, UUID groupId, UUID assignee, String title, String start, String end,
                String status) {
            HashMap<String, Object> p = new HashMap<>();
            p.put("id", id);
            p.put("e", event);
            p.put("group", groupId);
            p.put("assignee", assignee);
            p.put("zone", zoneId);
            p.put("title", title);
            p.put("start", at(start));
            p.put("end", at(end));
            p.put("status", status);
            jdbc.update("""
                    INSERT INTO task (id, event_id, work_group_id, bundle_key, assignee_id, zone_id, title,
                                      start_at, end_at, steps, checklist, status)
                    VALUES (:id, :e, :group, 'bundle', :assignee, :zone, :title, :start, :end,
                            '[]'::jsonb, '[]'::jsonb, :status)
                    """, p);
        }

        private void issue(UUID id, UUID zoneId, UUID taskId) {
            HashMap<String, Object> p = new HashMap<>();
            p.put("id", id);
            p.put("e", event);
            p.put("group", stage);
            p.put("task", taskId);
            p.put("zone", zoneId);
            p.put("reporter", kim);
            jdbc.update("""
                    INSERT INTO issue (id, event_id, work_group_id, task_id, zone_id, title, reporter_member_id,
                                       status, created_at)
                    VALUES (:id, :e, :group, :task, :zone, '프로젝터 고장', :reporter, 'RECEIVED', now())
                    """, p);
        }

        private static OffsetDateTime at(String hhmm) {
            return Instant.parse("2026-11-14T" + hhmm + ":00Z").atOffset(ZoneOffset.UTC);
        }
    }

}

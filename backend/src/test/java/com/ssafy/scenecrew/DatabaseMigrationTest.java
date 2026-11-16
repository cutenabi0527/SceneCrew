package com.ssafy.scenecrew;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
class DatabaseMigrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allMigrationsAreApplied() {
        var applied = jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(applied).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18");
    }

    @Test
    void inviteCiphertextRequiresHashButLegacyHashOnlyIsPreserved() {
        UUID id=UUID.randomUUID();
        jdbcTemplate.update("insert into event (id,name,state,revision,last_input_modified_at) values (?,'legacy','DRAFT',1,now())",id);
        try {
            assertThatThrownBy(()->jdbcTemplate.update("update event set invite_token_ciphertext='fixture' where id=?",id))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbcTemplate.update("update event set invite_token_hash='legacy-hash' where id=?",id);
            assertThat(jdbcTemplate.queryForObject("select invite_token_ciphertext from event where id=?",String.class,id)).isNull();
            jdbcTemplate.update("update event set invite_token_ciphertext='fixture' where id=?",id);
            assertThatThrownBy(()->jdbcTemplate.update("update event set invite_token_hash=NULL where id=?",id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {jdbcTemplate.update("delete from event where id=?",id);}
    }

    @Test
    void reportKeepsAuthorParticipationRevision() {
        var column = jdbcTemplate.queryForMap("select data_type, is_nullable from information_schema.columns"
                + " where table_name = 'report' and column_name = 'participation_revision'");
        assertThat(column).containsEntry("data_type", "bigint").containsEntry("is_nullable", "NO");
    }

    @Test
    void schemaMatchesErdCounts() {
        // V11 설치 테이블과 등록 회차, V12의 푸시 세션 연결을 포함하고 V13에서 issue.urgency를 지웠다.
        // V14는 평면도 배경 표(venue_layout_background, 열 8·외래 키 2)를, V15는 그 표에 평면도 영역 열 4개를 더했다.
        // V16(#393)은 safety_camera에 웹 연결 열 5개(input_kind·link_id·link_member_id·linked_at·link_task_id)와 외래 키 2개를 더했다.
        // V17(#395)은 equipment_asset에 자동 관측 종류·연결 담당 업무 열 2개와 외래 키 1개를 더했다.
        // V18(#394)은 수집기·연결 코드·찾은 수신기 표 3개(열 11·7·6, 외래 키 2·3·1)와 equipment_asset 연결 열 5개·외래 키 2개를 더했다.
        assertThat(count("select count(*) from information_schema.tables"
                + " where table_schema = 'public' and table_name <> 'flyway_schema_history'")).isEqualTo(44);
        assertThat(count("select count(*) from information_schema.columns"
                + " where table_schema = 'public' and table_name <> 'flyway_schema_history'")).isEqualTo(470);
        assertThat(count("select count(*) from information_schema.table_constraints"
                + " where table_schema = 'public' and constraint_type = 'FOREIGN KEY'")).isEqualTo(107);
    }

    @Test
    void newWorkGroupIsNotAllowedToViewEquipment() {
        var column = jdbcTemplate.queryForMap("select data_type, is_nullable, column_default from information_schema.columns"
                + " where table_name = 'work_group' and column_name = 'equipment_view_allowed'");
        assertThat(column).containsEntry("data_type", "boolean").containsEntry("is_nullable", "NO")
                .containsEntry("column_default", "false");
    }

    @Test
    void changeSetRefsAreArraysOrUnknown() {
        var checks = jdbcTemplate.queryForList("select conname from pg_constraint where contype = 'c'"
                + " and conrelid = 'change_set'::regclass", String.class);
        assertThat(checks).contains("chk_change_set_evidence_refs_array", "chk_change_set_simulation_refs_array");
        var nullable = jdbcTemplate.queryForList("select is_nullable from information_schema.columns"
                + " where table_name = 'change_set' and column_name in ('evidence_refs', 'simulation_refs')", String.class);
        assertThat(nullable).containsExactly("YES", "YES");
    }

    /** 근거 칼럼은 SQL NULL(기존 행, 근거 미상)과 배열만 받고, 배열이 아닌 JSON은 거절한다. */
    @Test
    void changeSetRefsAcceptOnlyArraysOrSqlNull() {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("insert into event (id, name, state, revision, last_input_modified_at)"
                + " values (?, '근거 칼럼 시험', 'DRAFT', 0, now())", eventId);
        try {
            for (String column : List.of("evidence_refs", "simulation_refs")) {
                for (String accepted : new String[] {null, "[]", "[{\"id\":\"a\"},{\"id\":\"b\"}]"}) {
                    assertThat(insertChangeSet(eventId, column, accepted)).as(column + " = " + accepted).isEqualTo(1);
                }
                for (String rejected : List.of("{}", "\"ref\"", "null")) {
                    assertThatThrownBy(() -> insertChangeSet(eventId, column, rejected))
                            .as(column + " = " + rejected)
                            .isInstanceOf(DataIntegrityViolationException.class);
                }
            }
        } finally {
            jdbcTemplate.update("delete from change_set where event_id = ?", eventId);
            jdbcTemplate.update("delete from event where id = ?", eventId);
        }
    }

    /** 변경안 근거 이력은 revision마다 한 행이고, 저장한 뒤에는 고칠 수 없으며, 원 변경안을 지워도 함께 지워지지 않는다. */
    @Test
    void changeSetEvidenceRevisionIsAppendOnly() {
        assertThat(keyColumns("change_set_evidence_revision", "i.indisprimary"))
                .containsExactlyInAnyOrder("change_set_id", "revision");
        UUID eventId = UUID.randomUUID();
        UUID changeSetId = UUID.randomUUID();
        jdbcTemplate.update("insert into event (id, name, state, revision, last_input_modified_at)"
                + " values (?, '근거 이력 시험', 'DRAFT', 0, now())", eventId);
        jdbcTemplate.update("insert into change_set (id, event_id, mode, status, revision, base_revision, operations, rationale)"
                + " values (?, ?, 'MANUAL', 'DRAFT', 0, 0, '[]'::jsonb, '시험')", changeSetId, eventId);
        try {
            assertThat(insertEvidence(changeSetId, 0, "{\"selections\":[]}")).isEqualTo(1);
            assertThat(insertEvidence(changeSetId, 1, "{\"selections\":[]}")).isEqualTo(1);
            assertThatThrownBy(() -> insertEvidence(changeSetId, 1, "{}")).as("같은 revision 두 번")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertEvidence(changeSetId, 2, "[]")).as("객체가 아닌 근거")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbcTemplate.update("update change_set_evidence_revision set snapshot = '{}'::jsonb"
                    + " where change_set_id = ? and revision = 0", changeSetId)).as("저장한 근거 수정")
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbcTemplate.update("delete from change_set where id = ?", changeSetId))
                    .as("근거가 남은 변경안 삭제").isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.update("delete from change_set_evidence_revision where change_set_id = ?", changeSetId);
            jdbcTemplate.update("delete from change_set where id = ?", changeSetId);
            jdbcTemplate.update("delete from event where id = ?", eventId);
        }
    }

    /** 미확정 계획 초안은 행사당 한 행이고, 내용은 JSON 객체다. */
    @Test
    void eventUnconfirmedDraftIsOnePerEvent() {
        assertThat(keyColumns("event_unconfirmed_draft", "i.indisunique and not i.indisprimary"))
                .containsExactly("event_id");
        var payloadCheck = jdbcTemplate.queryForList("select conname from pg_constraint where contype = 'c'"
                + " and conrelid = 'event_unconfirmed_draft'::regclass", String.class);
        assertThat(payloadCheck).contains("chk_event_unconfirmed_draft_payload", "chk_event_unconfirmed_draft_revision");
    }

    private int insertEvidence(UUID changeSetId, long revision, String snapshot) {
        return jdbcTemplate.update("insert into change_set_evidence_revision (change_set_id, revision, snapshot, captured_at)"
                + " values (?, ?, ?::jsonb, now())", changeSetId, revision, snapshot);
    }

    private int insertChangeSet(UUID eventId, String column, String json) {
        return jdbcTemplate.update("insert into change_set (id, event_id, mode, status, revision, base_revision,"
                + " operations, rationale, " + column + ") values (?, ?, 'MANUAL', 'DRAFT', 0, 0, '[]'::jsonb, '시험', ?::jsonb)",
                UUID.randomUUID(), eventId, json);
    }

    @Test
    void concertTablesKeepRangeAndUniquenessRules() {
        var checks = jdbcTemplate.queryForList("select conname from pg_constraint where contype = 'c'"
                + " and conrelid::regclass::text in ('equipment_reservation', 'equipment_connection', 'venue_layout',"
                + " 'operational_dependency', 'safety_zone_profile')", String.class);
        assertThat(checks).contains("chk_equipment_reservation_time", "chk_equipment_connection_not_self",
                "chk_venue_layout_size", "chk_operational_dependency_lag", "chk_safety_zone_profile_area");

        var partialUniques = jdbcTemplate.queryForList("select indexname from pg_indexes"
                + " where indexdef like 'CREATE UNIQUE INDEX%WHERE%'", String.class);
        assertThat(partialUniques).contains("uq_operational_situation_issue");
    }

    @Test
    void reservationAssetsUseCompositeKey() {
        assertThat(keyColumns("equipment_reservation_asset", "i.indisprimary"))
                .containsExactlyInAnyOrder("reservation_id", "asset_id");
    }

    @Test
    void connectionPortIsUniqueRegardlessOfDirection() {
        assertThat(keyColumns("equipment_connection_port", "i.indisprimary"))
                .containsExactlyInAnyOrder("connection_id", "asset_id", "port");
        // 출발·도착을 따로 보지 않고 (장비, 포트)만으로 막아야 반대 방향 재사용도 거부된다.
        assertThat(keyColumns("equipment_connection_port", "i.indisunique and not i.indisprimary"))
                .containsExactlyInAnyOrder("asset_id", "port");
    }

    private List<String> keyColumns(String table, String condition) {
        return jdbcTemplate.queryForList("select a.attname from pg_index i"
                + " join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)"
                + " where i.indrelid = '" + table + "'::regclass and " + condition, String.class);
    }

    private Integer count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }
}

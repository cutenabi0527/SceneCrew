package com.ssafy.scenecrew;

import java.util.Map;
import java.util.UUID;

import com.ssafy.scenecrew.support.TestRows;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
class PushMigrationUpgradeTest {
    @Test
    void upgradesV11WithoutBindingLegacyInstallationsAndPreservesAppliedChecksums() {
        try (var postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).target("11").load().migrate();
            var checksums = jdbc.queryForList("""
                    SELECT version, checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank
                    """);
            assertThat(checksums).hasSize(11);
            var rows = new TestRows(jdbc);
            UUID account = rows.account();
            UUID member = rows.member(rows.event("LIVE"), account, "WORKER", true);
            UUID token = UUID.randomUUID();
            UUID installation = UUID.randomUUID();
            jdbc.update("INSERT INTO device_installation(device_id,proof_hash,account_id) VALUES (?,?,?)",
                    installation, new byte[32], account);
            jdbc.update("""
                    INSERT INTO device_token(id,member_id,device_id,fcm_token,active,updated_at)
                    VALUES (?,?,?,'legacy-fixture',true,now())
                    """, token, member, installation.toString());

            var upgrade = Flyway.configure().dataSource(dataSource).load();
            // V12 위에 V13(#381 긴급도 삭제)·V14·V15(#372 평면도 배경 표·영역)·V16(#393 카메라 웹 연결)·V17(#395 장비 자동 관측 종류)·V18(#394 장비 웹 연결)까지 함께 올라간다.
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(7);
            upgrade.validate();
            var upgradedChecksums = jdbc.queryForList("""
                    SELECT version, checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank
                    """);
            assertThat(upgradedChecksums.subList(0, 11)).isEqualTo(checksums);
            assertThat(upgradedChecksums.get(11)).containsEntry("version", "12");
            Map<String,Object> row = jdbc.queryForMap(
                    "SELECT active, participation_revision FROM device_token WHERE id=?", token);
            assertThat(row).containsEntry("active", true).containsEntry("participation_revision", null);
            assertThat(jdbc.queryForMap("SELECT account_id,push_session_id FROM device_installation WHERE device_id=?",
                    installation)).containsEntry("account_id", account).containsEntry("push_session_id", null);
        }
    }
}

package com.ssafy.scenecrew;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨텍스트가 뜨는 시점에 ddl-auto=validate가 엔티티와 Flyway 스키마를 대조한다.
 * 불일치가 있으면 컨텍스트 로딩 단계에서 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
class EntityMappingTest {
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void allErdTablesAreMappedAsEntities() {
        // JPA 엔티티 37개: 기존 22개 + v9 장비·안전·상황 13개 + V5 근거 이력·미확정 초안 2개 (docs/planning/be/12-erd-contract.md)
        // V6 장비 관측 2테이블(equipment_observation·equipment_observation_latest)은 엔티티 없이 JdbcTemplate로만 쓴다.
        assertThat(entityManagerFactory.getMetamodel().getEntities()).hasSize(37);
    }
}

package com.ssafy.scenecrew.impact.config;

import org.neo4j.driver.Driver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import com.ssafy.scenecrew.impact.service.EventGraphSync;
import com.ssafy.scenecrew.impact.service.ImpactAnalyzer;
import com.ssafy.scenecrew.impact.service.Neo4jImpactAnalyzer;
import com.ssafy.scenecrew.impact.service.SqlImpactAnalyzer;

/**
 * 영향 분석 구현 선택. scenecrew.impact.engine이 sql(기본)이면 SQL, neo4j면 그래프 구현을 쓴다.
 * 그래프 적재(EventGraphSync)는 Neo4j 접속 정보가 있을 때만 만든다.
 */
@Configuration
public class ImpactConfig {

    @Bean
    @ConditionalOnProperty(prefix = "scenecrew.impact", name = "engine", havingValue = "sql", matchIfMissing = true)
    ImpactAnalyzer sqlImpactAnalyzer(NamedParameterJdbcTemplate jdbc) {
        return new SqlImpactAnalyzer(jdbc);
    }

    @Bean
    @ConditionalOnProperty(prefix = "scenecrew.impact", name = "engine", havingValue = "neo4j")
    ImpactAnalyzer neo4jImpactAnalyzer(Driver driver) {
        return new Neo4jImpactAnalyzer(driver);
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.neo4j", name = "uri")
    EventGraphSync eventGraphSync(NamedParameterJdbcTemplate jdbc, Driver driver) {
        return new EventGraphSync(jdbc, driver);
    }
}

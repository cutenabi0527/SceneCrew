package com.ssafy.scenecrew.global.graph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j 드라이버. 접속 주소(spring.neo4j.uri)가 있을 때만 만든다.
 * 없으면 Neo4j 기능 없이 앱이 기동되고, 영향 분석은 SQL 구현을 쓴다.
 * 원본은 PostgreSQL이고 Neo4j는 읽기 전용 사본이라 JPA 트랜잭션과 묶지 않는다.
 */
@Configuration
@EnableConfigurationProperties(Neo4jProperties.class)
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "spring.neo4j", name = "uri")
    Driver neo4jDriver(Neo4jProperties properties) {
        Neo4jProperties.Authentication auth = properties.authentication();
        return auth == null
                ? GraphDatabase.driver(properties.uri())
                : GraphDatabase.driver(properties.uri(), AuthTokens.basic(auth.username(), auth.password()));
    }
}

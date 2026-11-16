package com.ssafy.scenecrew.global.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Neo4j 접속 정보. 서버에서는 compose가 SPRING_NEO4J_URI, SPRING_NEO4J_AUTHENTICATION_USERNAME/PASSWORD로 넣는다.
 * Spring Data Neo4j를 쓰지 않으므로 같은 이름의 설정을 직접 읽는다.
 */
@ConfigurationProperties("spring.neo4j")
public record Neo4jProperties(String uri, Authentication authentication) {

    public record Authentication(String username, String password) {
    }
}

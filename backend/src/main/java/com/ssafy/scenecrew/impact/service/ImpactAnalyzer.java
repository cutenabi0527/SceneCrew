package com.ssafy.scenecrew.impact.service;

import java.util.UUID;

/**
 * 현장 이슈의 영향 분석. 같은 기능을 SQL(SqlImpactAnalyzer)과 그래프(Neo4jImpactAnalyzer)로 구현해
 * 설정(scenecrew.impact.engine)으로 바꿔 쓰고 결과·성능을 비교한다.
 * 결과는 변경안 후보를 만들기 위한 정보이며, 업무를 바꾸지 않는다(자동 배치 없음).
 */
public interface ImpactAnalyzer {

    /** 없는 이슈면 BusinessException(NOT_FOUND) */
    ImpactResult analyze(UUID issueId);
}

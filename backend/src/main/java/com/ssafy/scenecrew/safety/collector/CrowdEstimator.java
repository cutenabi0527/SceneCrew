package com.ssafy.scenecrew.safety.collector;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * 프레임 1장의 구역별 인원 추정(#349 → #360 밀집도 AI).
 *
 * <p>카메라에 등록된 관측 범위(coverage)의 구역만 묻는다. 카메라가 비추지 않는 구역은 묻지도, 0명으로 채우지도 않는다.
 * 모델이 준비되기 전에는 StubCrowdEstimator(시험값)를 쓴다.
 */
public interface CrowdEstimator {
    Result estimate(Request request) throws EstimateException, InterruptedException;

    /** @param roi 정규화 이미지 좌표 [{x,y}…]. 저장한 coverage 그대로다. */
    record Zone(UUID zoneId, String scope, JsonNode roi) {
    }

    record Request(UUID eventId, UUID cameraId, UUID streamSessionId, long sequence, Instant capturedAt, String inputHash,
            byte[] jpeg, List<Zone> zones) {
    }

    /** @param estimatedCount 추정 인원. 믿을 수 없으면 null(0명이 아니다). */
    record ZoneCount(UUID zoneId, Double estimatedCount, List<String> qualityReasons) {
    }

    record Result(String modelVersion, List<ZoneCount> zones) {
    }

    /** 추정 결과를 받지 못함(연결 실패·시간 초과·형식 오류). 이번 프레임은 관측을 남기지 않는다. */
    class EstimateException extends Exception {
        public EstimateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

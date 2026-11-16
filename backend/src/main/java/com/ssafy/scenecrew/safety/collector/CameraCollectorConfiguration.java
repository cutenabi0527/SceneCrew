package com.ssafy.scenecrew.safety.collector;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import lombok.extern.slf4j.Slf4j;

import com.ssafy.scenecrew.aijob.client.AiClient;

/**
 * 카메라 프레임 수신·분석(#349). 기본은 꺼져 있고 scenecrew.safety.camera.enabled=true일 때만 경로를 연다.
 * 추정은 scenecrew.safety.camera.estimator가 ai면 AI 서버, 그 밖(기본 stub)이면 시험값이다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scenecrew.safety.camera.enabled", havingValue = "true")
public class CameraCollectorConfiguration {
    @Bean
    CameraCollectors cameraCollectors(@Value("${scenecrew.safety.camera.registrations:}") String json) {
        return CameraCollectors.parse(json);
    }

    @Bean
    LatestFrames latestFrames() {
        return new LatestFrames();
    }

    @Bean
    CameraFrameService cameraFrameService(CameraCollectors collectors, JdbcTemplate jdbc, PlatformTransactionManager manager,
            LatestFrames frames) {
        return new CameraFrameService(collectors, jdbc, manager, frames, Clock.systemUTC());
    }

    @Bean
    CrowdEstimator crowdEstimator(@Value("${scenecrew.safety.camera.estimator:stub}") String kind, AiClient client,
            @Value("${scenecrew.safety.camera.estimate-timeout:8s}") Duration timeout) {
        if ("ai".equals(kind)) return new AiCrowdEstimator(client, timeout);
        log.info("카메라 인원 추정은 시험값(test-stub)입니다. AI 서버를 쓰려면 CAMERA_ESTIMATOR=ai로 정합니다.");
        return new StubCrowdEstimator();
    }

    @Bean
    CameraAnalyzer cameraAnalyzer(LatestFrames frames, CrowdEstimator estimator, JdbcTemplate jdbc,
            PlatformTransactionManager manager, @Value("${scenecrew.safety.camera.analysis-interval:10s}") Duration interval) {
        return new CameraAnalyzer(frames, estimator, jdbc, manager, Clock.systemUTC(), interval);
    }

    @Bean
    @ConditionalOnProperty(name = "scenecrew.safety.camera.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    CameraScheduler cameraScheduler(CameraAnalyzer analyzer,
            @Value("${scenecrew.safety.camera.cleanup.batch-size:1000}") int batchSize) {
        return new CameraScheduler(analyzer, batchSize);
    }

    static class CameraScheduler {
        private final CameraAnalyzer analyzer;
        private final int batchSize;

        CameraScheduler(CameraAnalyzer analyzer, int batchSize) {
            this.analyzer = analyzer;
            this.batchSize = batchSize;
        }

        /** 1초마다: 분석할 때가 된 카메라의 분석 시작, 연결 상태·최신성 갱신 */
        @Scheduled(initialDelay = 1000, fixedDelay = 1000)
        void tick() {
            analyzer.tick();
            analyzer.sweep();
        }

        @Scheduled(initialDelayString = "${scenecrew.safety.camera.cleanup.interval:10m}",
                fixedDelayString = "${scenecrew.safety.camera.cleanup.interval:10m}")
        void cleanup() {
            int deleted = analyzer.cleanup(batchSize);
            if (deleted > 0) log.info("안전 관측 정리: 보관 기간이 지난 관측 {}건 삭제", deleted);
        }
    }
}

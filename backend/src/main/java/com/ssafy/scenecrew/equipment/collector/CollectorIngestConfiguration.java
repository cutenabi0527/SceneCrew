package com.ssafy.scenecrew.equipment.collector;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.scenecrew.equipment.service.AutoTechnicalStates;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;

import lombok.extern.slf4j.Slf4j;

/**
 * 장비 수집 수신(#330). 기본은 꺼져 있고 scenecrew.equipment.collector.enabled=true일 때만 경로를 연다.
 * 수집기 등록 정보는 scenecrew.equipment.collector.registrations(JSON)에서 읽는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scenecrew.equipment.collector.enabled", havingValue = "true")
public class CollectorIngestConfiguration {
    @Bean
    CollectorRegistry collectorRegistry(@Value("${scenecrew.equipment.collector.registrations:}") String json) {
        return CollectorRegistry.parse(json);
    }

    @Bean
    WebCollectors webCollectors(JdbcTemplate jdbc) {
        return new WebCollectors(jdbc);
    }

    @Bean
    CollectorIngestService collectorIngestService(CollectorRegistry registry, WebCollectors webCollectors, JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        return new CollectorIngestService(registry, webCollectors, jdbc, manager, Clock.systemUTC());
    }

    @Bean
    CollectorPairingService collectorPairingService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            WebCollectors webCollectors) {
        return new CollectorPairingService(jdbc, manager, webCollectors, Clock.systemUTC());
    }

    @Bean
    EquipmentObservationReader equipmentObservationReader(JdbcTemplate jdbc) {
        return new EquipmentObservationReader(jdbc);
    }

    @Bean
    ObservationRetention observationRetention(JdbcTemplate jdbc,
            @Value("${scenecrew.equipment.collector.cleanup.batch-size:1000}") int batchSize,
            @Value("${scenecrew.equipment.collector.cleanup.max-batches:100}") int maxBatches) {
        return new ObservationRetention(jdbc, batchSize, maxBatches);
    }

    @Bean
    @ConditionalOnProperty(name = "scenecrew.equipment.collector.cleanup.enabled", havingValue = "true", matchIfMissing = true)
    ObservationRetentionScheduler observationRetentionScheduler(ObservationRetention retention) {
        return new ObservationRetentionScheduler(retention);
    }

    /** 자동 관측으로 장비 기술 상태를 맞춘다(AutoTechnicalStates). 프로그램 끊김(15초)을 따라가려고 5초마다 본다. */
    @Bean
    @ConditionalOnProperty(name = "scenecrew.equipment.auto-technical-state.enabled", havingValue = "true",
            matchIfMissing = true)
    AutoTechnicalStateScheduler autoTechnicalStateScheduler(JdbcTemplate jdbc, EventWriteSupport events,
            PlatformTransactionManager manager) {
        return new AutoTechnicalStateScheduler(jdbc, events, new TransactionTemplate(manager));
    }

    @Slf4j
    static class AutoTechnicalStateScheduler {
        private final JdbcTemplate jdbc;
        private final EventWriteSupport events;
        private final TransactionTemplate tx;

        AutoTechnicalStateScheduler(JdbcTemplate jdbc, EventWriteSupport events, TransactionTemplate tx) {
            this.jdbc = jdbc;
            this.events = events;
            this.tx = tx;
        }

        @Scheduled(initialDelayString = "${scenecrew.equipment.auto-technical-state.interval:5s}",
                fixedDelayString = "${scenecrew.equipment.auto-technical-state.interval:5s}")
        void run() {
            try {
                int changed = AutoTechnicalStates.reconcile(jdbc, events, tx, EventWriteSupport.now());
                if (changed > 0) log.info("자동 관측으로 장비 기술 상태 {}건을 바꿨습니다", changed);
            } catch (RuntimeException e) {
                // 다음 주기에 다시 본다. 수집 수신은 막지 않는다
                log.warn("자동 기술 상태 맞추기 실패: {}", e.toString());
            }
        }
    }

    @Slf4j
    static class ObservationRetentionScheduler {
        private final ObservationRetention retention;

        ObservationRetentionScheduler(ObservationRetention retention) {
            this.retention = retention;
        }

        @Scheduled(initialDelayString = "${scenecrew.equipment.collector.cleanup.interval:10m}",
                fixedDelayString = "${scenecrew.equipment.collector.cleanup.interval:10m}")
        void run() {
            var result = retention.cleanup(Instant.now());
            if (result.observationsDeleted() > 0 || result.latestDeleted() > 0)
                log.info("장비 관측 정리: 기록 {}건, 종료 행사 최신 값 {}건 삭제", result.observationsDeleted(), result.latestDeleted());
        }
    }
}

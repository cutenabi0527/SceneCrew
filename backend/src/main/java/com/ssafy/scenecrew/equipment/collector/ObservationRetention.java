package com.ssafy.scenecrew.equipment.collector;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 관측 기록 보관 기간 정리(#330, V9-D17).
 *
 * - 기록(equipment_observation): expires_at이 지난 행을 지운다. 주기 계측과 값이 그대로인 기록은 24시간,
 *   상태 변화는 30일이다. 지운 뒤 다시 온 같은 기록은 수신 단계에서 거절한다(EVIDENCE_EXPIRED).
 * - 최신 값(equipment_observation_latest): 종료된 행사의 값은 마지막 수신 후 30일 뒤 지운다.
 *
 * 한 번에 batchSize행씩 나눠 지운다. 한 문장이 오래 잠그거나 테이블을 크게 부풀리지 않게 하기 위해서다.
 * 한 번 실행에서 maxBatches번까지만 돌고 남은 것은 다음 실행에서 이어서 지운다.
 */
public class ObservationRetention {
    static final Duration LATEST_AFTER_END = Duration.ofDays(30);
    private final JdbcTemplate jdbc;
    private final int batchSize;
    private final int maxBatches;

    public ObservationRetention(JdbcTemplate jdbc, int batchSize, int maxBatches) {
        this.jdbc = jdbc;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    public record Result(int observationsDeleted, int latestDeleted) {
    }

    public Result cleanup(Instant now) {
        Timestamp at = Timestamp.from(now);
        int observations = repeat("""
                delete from equipment_observation where ctid in (
                    select ctid from equipment_observation where expires_at <= ? limit ?)
                """, at);
        int latest = repeat("""
                delete from equipment_observation_latest where ctid in (
                    select l.ctid from equipment_observation_latest l
                    join equipment_asset a on a.id = l.equipment_asset_id
                    join event e on e.id = a.event_id
                    where e.state = 'ENDED' and l.server_received_at <= ? limit ?)
                """, Timestamp.from(now.minus(LATEST_AFTER_END)));
        return new Result(observations, latest);
    }

    private int repeat(String sql, Timestamp at) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int n = jdbc.update(sql, at, batchSize);
            total += n;
            if (n < batchSize) break;
        }
        return total;
    }
}

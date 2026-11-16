package com.ssafy.scenecrew.impact.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 그래프에 시각을 마이크로초(epoch) 정수로 저장한다. PostgreSQL timestamptz와 같은 정밀도라
 * SQL 구현과 결과가 같고, 시간이 겹치는지 정수 비교로 판단할 수 있다.
 */
final class GraphTime {

    private GraphTime() {
    }

    static long toMicros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    static Instant fromMicros(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }
}

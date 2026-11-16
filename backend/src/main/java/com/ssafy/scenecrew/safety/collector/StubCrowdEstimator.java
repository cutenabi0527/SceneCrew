package com.ssafy.scenecrew.safety.collector;

import java.util.List;

/**
 * 밀집도 모델(#360) 전의 시험값. 모델 버전 test-stub과 품질 사유 TEST_VALUE로 시험값임을 관측에 남긴다.
 *
 * <p>값은 프레임 바이트 해시와 구역 id로 정한다. 같은 프레임·구역은 늘 같은 값이고, 화면이 바뀌면 값도 바뀐다
 * (사람이 손대지 않아도 지도 값이 갱신되는지 확인하는 용도). 실제 인원과는 관계없다.
 */
public class StubCrowdEstimator implements CrowdEstimator {
    public static final String MODEL_VERSION = "test-stub";
    public static final String TEST_VALUE = "TEST_VALUE";
    static final int MAX_COUNT = 40;

    @Override
    public Result estimate(Request request) {
        int seed = (int) Long.parseLong(request.inputHash().substring(0, 8), 16);
        return new Result(MODEL_VERSION, request.zones().stream()
                .map(z -> new ZoneCount(z.zoneId(), (double) Math.floorMod(seed ^ z.zoneId().hashCode(), MAX_COUNT + 1),
                        List.of(TEST_VALUE)))
                .toList());
    }
}

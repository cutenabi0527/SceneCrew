package com.ssafy.scenecrew.support;

import org.testcontainers.DockerClientFactory;

/**
 * DB 테스트는 Docker가 있어야 실행된다.
 * 이미지 빌드처럼 Docker가 없는 환경에서는 건너뛰지만, REQUIRE_DB_TESTS=true면 건너뛰지 않고 실패한다.
 * CI처럼 DB 테스트가 반드시 실행돼야 하는 곳에서 이 값을 켠다.
 */
public final class DockerAvailability {
    static final String REQUIRE_DB_TESTS = "REQUIRE_DB_TESTS";

    private DockerAvailability() {
    }

    public static boolean isAvailable() {
        if (dockerAvailable()) {
            return true;
        }
        if (Boolean.parseBoolean(System.getenv(REQUIRE_DB_TESTS))) {
            throw new IllegalStateException(REQUIRE_DB_TESTS + "=true지만 Docker를 사용할 수 없어 DB 테스트를 실행할 수 없습니다.");
        }
        return false;
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }
}

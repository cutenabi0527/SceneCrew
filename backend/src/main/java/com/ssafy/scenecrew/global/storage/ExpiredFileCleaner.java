package com.ssafy.scenecrew.global.storage;

import java.time.Instant;

/** 보관 기간이 끝난 원본을 지우는 쪽(음성·문서). FileCleanup이 주기적으로 부른다. */
public interface ExpiredFileCleaner {

    /**
     * 만료된 원본을 최대 limit개 지우고 지운 개수를 돌려준다.
     * S3를 먼저 지우고 DB storage_key를 비운다. S3 삭제가 실패한 것은 그대로 두어 다음 회차에 다시 시도한다.
     */
    int cleanupExpired(Instant now, int limit);
}

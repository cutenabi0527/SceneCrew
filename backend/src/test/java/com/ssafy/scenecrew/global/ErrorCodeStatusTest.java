package com.ssafy.scenecrew.global;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeStatusTest {
    @Test void generic403IsForbiddenAndExplicitChannelMismatchStaysDistinct() {
        assertThat(ErrorCode.fromStatus(HttpStatus.FORBIDDEN)).isEqualTo(ErrorCode.FORBIDDEN);
        var explicit = new BusinessException(ErrorCode.AUTH_CHANNEL_MISMATCH);
        assertThat(explicit.getErrorCode()).isEqualTo(ErrorCode.AUTH_CHANNEL_MISMATCH);
        assertThat(explicit.getErrorCode().getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void otherExistingGenericStatusesArePreserved() {
        assertThat(ErrorCode.fromStatus(HttpStatus.UNAUTHORIZED)).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(ErrorCode.fromStatus(HttpStatus.BAD_REQUEST)).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(ErrorCode.fromStatus(HttpStatus.NOT_FOUND)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.fromStatus(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }
}

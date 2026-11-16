package com.ssafy.scenecrew.global.idempotency.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/** S15P31A202-341: 요청 키 형식과 요청 비교 값 */
class IdempotencyRequestTest {
    private static final UUID ACCOUNT = UUID.randomUUID();

    @Test
    void keyMustBeUuid() {
        for (String key : new String[] {null, "", "abc", "20000000-0000-4000-8000-00000000000g",
                "20000000-0000-4000-8000-0000000000011"}) {
            assertThatThrownBy(() -> request(key)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
    }

    @Test
    void upperCaseKeyIsSameKey() {
        assertThat(request("2000000A-0000-4000-8000-00000000000B").idempotencyKey())
                .isEqualTo("2000000a-0000-4000-8000-00000000000b");
    }

    @Test
    void hashSeparatesParts() {
        assertThat(IdempotencyRequest.hash("ab", "c")).isNotEqualTo(IdempotencyRequest.hash("a", "bc"));
        assertThat(IdempotencyRequest.hash((Object) null)).isNotEqualTo(IdempotencyRequest.hash(""));
        assertThat(IdempotencyRequest.hash("a", new byte[] {1, 2})).isEqualTo(IdempotencyRequest.hash("a", new byte[] {1, 2}))
                .matches("[0-9a-f]{64}");
        assertThat(IdempotencyRequest.hash("a", new byte[] {1, 2})).isNotEqualTo(IdempotencyRequest.hash("a", new byte[] {1, 3}));
    }

    private static IdempotencyRequest request(String key) {
        return new IdempotencyRequest(ACCOUNT, "TEST", key, "hash", null, null, null);
    }
}

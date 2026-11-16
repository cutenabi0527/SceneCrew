package com.ssafy.scenecrew.event.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import static org.assertj.core.api.Assertions.*;

class MemberAvailabilityRequestTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void acceptsBooleanAndBigintBoundaries() {
        assertThat(MemberAvailabilityRequest.parse(json.readTree("{\"available\":false,\"expectedEventRevision\":\"0\"}")))
                .isEqualTo(new MemberAvailabilityRequest(false, 0));
        assertThat(MemberAvailabilityRequest.parse(json.readTree("{\"available\":true,\"expectedEventRevision\":\"9223372036854775807\"}")))
                .isEqualTo(new MemberAvailabilityRequest(true, Long.MAX_VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "{\"available\":true}", "{\"expectedEventRevision\":\"1\"}",
            "{\"available\":null,\"expectedEventRevision\":\"1\"}", "{\"available\":\"true\",\"expectedEventRevision\":\"1\"}",
            "{\"available\":1,\"expectedEventRevision\":\"1\"}", "{\"available\":true,\"expectedEventRevision\":null}",
            "{\"available\":true,\"expectedEventRevision\":1}", "{\"available\":true,\"expectedEventRevision\":\"-1\"}",
            "{\"available\":true,\"expectedEventRevision\":\"01\"}", "{\"available\":true,\"expectedEventRevision\":\"+1\"}",
            "{\"available\":true,\"expectedEventRevision\":\"1.0\"}", "{\"available\":true,\"expectedEventRevision\":\" 1\"}",
            "{\"available\":true,\"expectedEventRevision\":\"1\\n\"}", "{\"available\":true,\"expectedEventRevision\":\"\"}",
            "{\"available\":true,\"expectedEventRevision\":\"9223372036854775808\"}",
            "{\"available\":true,\"expectedEventRevision\":\"1\",\"isReserve\":true}"})
    void rejectsInvalidShapeTypesAndRevision(String body) {
        assertThatThrownBy(() -> MemberAvailabilityRequest.parse(json.readTree(body)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @ParameterizedTest @ValueSource(strings = {"invalid", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000z"})
    void rejectsNonCanonicalMemberId(String id) {
        assertThatThrownBy(() -> MemberAvailabilityRequest.memberId(id)).isInstanceOf(BusinessException.class);
    }
}

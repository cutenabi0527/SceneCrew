package com.ssafy.scenecrew.invite;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.invite.dto.JoinRequest;
import static org.assertj.core.api.Assertions.*;

class JoinRequestTest {
    final JsonMapper json=JsonMapper.builder().build();
    final String valid="{\"workGroupId\":\"10000000-0000-4000-8000-000000000001\",\"available\":false,\"isReserve\":true,\"expectedEventRevision\":\"1\"}";
    @Test void exactWireTypesAndFieldsAreRequired() {
        assertThat(JoinRequest.parse(json.readTree(valid)).isReserve()).isTrue();
        for (String body : new String[]{valid.replace("false","\"false\""),valid.replace("\"1\"","1"),
                valid.replace("\"1\"","\"01\""),valid.replace("\"1\"","\"-1\""),
                valid.replace("\"1\"","\"9223372036854775808\""),valid.replace("true","null"),
                valid.replace("}",",\"extra\":1}"),valid.replace(",\"isReserve\":true", ""),"null","[]"})
            assertThatThrownBy(() -> JoinRequest.parse(json.readTree(body))).isInstanceOf(BusinessException.class);
    }
    @Test void requestKeyMustBeCanonicalUuid() {
        assertThat(JoinRequest.uuid("20000000-0000-4000-8000-000000000001")).isNotNull();
        for (String key : new String[]{"1-1-1-1-1","", "not-a-uuid"})
            assertThatThrownBy(() -> JoinRequest.uuid(key)).isInstanceOf(BusinessException.class);
    }
}

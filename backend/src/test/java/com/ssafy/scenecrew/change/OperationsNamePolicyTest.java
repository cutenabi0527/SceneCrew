package com.ssafy.scenecrew.change;

import com.ssafy.scenecrew.change.service.Operations;
import com.ssafy.scenecrew.global.error.BusinessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import static org.assertj.core.api.Assertions.*;

/** V9-D35: 표시 이름은 UTF-16 길이가 아닌 유니코드 코드포인트 기준 최대 20자다. */
class OperationsNamePolicyTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"가", "🎵"})
    void acceptsTwentyCodePointsAndRejectsTwentyOne(String character) {
        for (String type : new String[]{"UPDATE_PROGRAM", "UPSERT_WORK_GROUP"}) {
            ArrayNode twenty = operation(type, character.repeat(20));
            assertThat(Operations.check(twenty)).isEqualTo(twenty);
            assertThatThrownBy(() -> Operations.check(operation(type, character.repeat(21))))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE_PROGRAM", "UPSERT_WORK_GROUP"})
    void countsDisplayWhitespaceBeforeDuplicateNormalization(String type) {
        assertThat(Operations.check(operation(type, "가".repeat(19) + " "))).hasSize(1);
        assertThatThrownBy(() -> Operations.check(operation(type, "가".repeat(20) + " ")))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0x00a0, 0x202f, 0x0085})
    void rejectsUnicodeWhitespaceOnlyNames(int codePoint) {
        for (String type : new String[]{"UPDATE_PROGRAM", "UPSERT_WORK_GROUP"}) {
            assertThatThrownBy(() -> Operations.check(operation(type, Character.toString(codePoint))))
                    .isInstanceOf(BusinessException.class);
        }
    }

    private ArrayNode operation(String type, String name) {
        var operations = json.createArrayNode();
        var op = operations.addObject().put("type", type);
        var payload = op.putObject("payload").put("name", name);
        if (type.equals("UPDATE_PROGRAM")) {
            payload.put("programId", "00000000-0000-4000-8000-000000000001");
        } else {
            payload.put("tempId", "tmp-name-policy").put("instructions", "");
        }
        return operations;
    }
}

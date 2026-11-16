package com.ssafy.scenecrew.issue;

import com.ssafy.scenecrew.issue.service.IssueService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class IssueTextNormalizationTest {
    @ParameterizedTest
    @ValueSource(ints = {0x001c, 0x001d, 0x001e, 0x001f})
    void normalizesPythonWhitespaceSeparatorsAndNfc(int codePoint) {
        assertThat(IssueService.normalize("가" + Character.toString(codePoint) + "나다"))
                .isEqualTo("가 나다");
    }
}

package com.ssafy.scenecrew.account;

import java.util.stream.Stream;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import com.ssafy.scenecrew.account.dto.RegisterRequest;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;

class RegisterRequestNameTest {
    private static jakarta.validation.ValidatorFactory factory;
    @BeforeAll static void setup() { factory = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void close() { factory.close(); }

    static RegisterRequest input(String name) {
        return new RegisterRequest("synthetic@example.invalid", "SyntheticPass8!", name,
                new RegisterRequest.Consent(true, false, false, false));
    }
    static void rejected(String name) {
        assertThatThrownBy(() -> input(name).validate(factory.getValidator()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
    @TestFactory Stream<DynamicTest> rawControlsAreRejectedBeforeTrim() {
        return Stream.of(0, 9, 10, 13, 31, 127, 133, 0x2028, 0x2029).flatMap(cp -> {
            String c = Character.toString(cp);
            return Stream.of(
                    DynamicTest.dynamicTest("leading U+" + Integer.toHexString(cp), () -> rejected(c + "name")),
                    DynamicTest.dynamicTest("trailing U+" + Integer.toHexString(cp), () -> rejected("name" + c)),
                    DynamicTest.dynamicTest("internal U+" + Integer.toHexString(cp), () -> rejected("na" + c + "me")));
        });
    }
    @Test void trimsOrdinarySpacesAndCountsUnicodeCodePoints() {
        for (String name : new String[]{"a", "a".repeat(20), "😀".repeat(20)}) {
            var request = input("  " + name + "  ");
            request.validate(factory.getValidator());
            assertThat(request.displayName()).isEqualTo(name);
        }
        rejected("a".repeat(21));
        rejected("😀".repeat(21));
        rejected("");
        rejected("   ");
        rejected(null);
    }
}

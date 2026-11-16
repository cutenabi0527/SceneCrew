package com.ssafy.scenecrew.account;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.dto.*;
import jakarta.validation.Validation;
import static org.assertj.core.api.Assertions.assertThat;

class LoginDtoTest {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final AccountSummary account = new AccountSummary(UUID.randomUUID(), "합성계정",
            "fixture@example.invalid", true, "ACTIVE");

    @Test void requestDoesNotSerializeOrPrintPassword() throws Exception {
        var request = new LoginRequest(" FIXTURE@EXAMPLE.INVALID ", "SyntheticPass8!");
        assertThat(request.email()).isEqualTo("fixture@example.invalid");
        assertThat(request.toString()).doesNotContain("SyntheticPass8!", "fixture@");
        assertThat(mapper.writeValueAsString(request)).doesNotContain("password", "SyntheticPass8!");
    }

    @Test void webResponseHasExactlyFourTopLevelFieldsAndNoTokens() throws Exception {
        var result = new WebLoginResult(account, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
        var json = mapper.readTree(mapper.writeValueAsString(result));
        assertThat(json.size()).isEqualTo(4);
        assertThat(json.get("channel").asText()).isEqualTo("WEB");
        assertThat(json.get("account").size()).isEqualTo(5);
        assertThat(json.has("accessToken")).isFalse();
        assertThat(json.has("refreshToken")).isFalse();
    }

    @Test void androidResponseCarriesTokensButToStringRedactsThem() throws Exception {
        var result = new AndroidLoginResult(account, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600),
                "synthetic-access", "synthetic-refresh");
        var json = mapper.readTree(mapper.writeValueAsString(result));
        assertThat(json.get("accessToken").asText()).isEqualTo("synthetic-access");
        assertThat(json.get("refreshToken").asText()).isEqualTo("synthetic-refresh");
        assertThat(result.toString()).doesNotContain("synthetic-access", "synthetic-refresh");
    }

    @Test void validatesNullInvalidEmailAndPasswordBoundaries() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new LoginRequest("fixture@example.invalid", "a".repeat(8)))).isEmpty();
            assertThat(validator.validate(new LoginRequest("fixture@example.invalid", "a".repeat(64)))).isEmpty();
            assertThat(validator.validate(new LoginRequest(null, null))).isNotEmpty();
            assertThat(validator.validate(new LoginRequest("not-an-email", "a".repeat(8)))).isNotEmpty();
            for (var password : new String[]{"a".repeat(7), "a".repeat(65), "Space pass8", "한글Password8!"}) {
                assertThat(validator.validate(new LoginRequest("fixture@example.invalid", password))).isNotEmpty();
            }
        }
    }
}

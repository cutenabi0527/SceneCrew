package com.ssafy.scenecrew.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ssafy.scenecrew.account.service.LocalCredentialVerifier;
import com.ssafy.scenecrew.global.error.*;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;

/** candidate.3 input. Normalization never changes the password. */
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotNull @Pattern(regexp = "[!-~]{8,64}")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
        @NotNull String displayName,
        @NotNull @Valid Consent consent) {
    public RegisterRequest {
        email = LocalCredentialVerifier.normalizeEmail(email);
        // Reject raw controls before trim can erase them.
        if (displayName != null && displayName.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.LINE_SEPARATOR
                || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        displayName = displayName == null ? null : displayName.trim();
    }

    public void validate(Validator validator) {
        if (!validator.validate(this).isEmpty() || displayName.codePointCount(0, displayName.length()) < 1
                || displayName.codePointCount(0, displayName.length()) > 20) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    public record Consent(@NotNull Boolean service, @NotNull Boolean voice,
            @NotNull Boolean push, @NotNull Boolean externalAi) {}

    @Override public String toString() { return "RegisterRequest[redacted]"; }
}

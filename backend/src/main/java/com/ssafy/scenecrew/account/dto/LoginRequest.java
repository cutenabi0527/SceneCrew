package com.ssafy.scenecrew.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = "[!-~]{8,64}")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
    public LoginRequest {
        email = com.ssafy.scenecrew.account.service.LocalCredentialVerifier.normalizeEmail(email);
    }

    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}

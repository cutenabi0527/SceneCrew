package com.ssafy.scenecrew.account.service;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ssafy.scenecrew.global.error.*;

public interface PasswordResetDelivery {
    void requireAvailable();
    RegistrationDelivery.Outcome accept(Message message);
    record Message(String email, @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String recoveryProof,
            Instant expiresAt) {
        @Override public String toString() { return "PasswordResetMail[redacted]"; }
    }
    static PasswordResetDelivery unavailable() {
        return new PasswordResetDelivery() {
            public void requireAvailable() { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
            public RegistrationDelivery.Outcome accept(Message message) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }
        };
    }
}

package com.ssafy.scenecrew.account.service;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ssafy.scenecrew.global.error.*;

/** No production fake. An adapter acknowledges actual provider acceptance, not mailbox arrival. */
public interface RegistrationDelivery {
    void requireAvailable();
    Outcome accept(Message message);
    enum Outcome { ACCEPTED, DEFINITELY_NOT_SENT, UNKNOWN }
    record Message(UUID requestId, String email,
            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String code, Instant expiresAt) {
        @Override public String toString() { return "VerificationMail[redacted]"; }
    }
    static RegistrationDelivery unavailable() {
        return new RegistrationDelivery() {
            public void requireAvailable() { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
            public Outcome accept(Message message) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
        };
    }
}

package com.ssafy.scenecrew.account.service;

import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.ssafy.scenecrew.account.dto.RegisterRequest;
import com.ssafy.scenecrew.account.infra.RedisPendingRegistrations;
import com.ssafy.scenecrew.global.error.*;

/** #223 intake only. #224 must recheck duplicates at the PostgreSQL commit boundary. */
public final class EmailRegistrationService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final Validator validator;
    private final RedisPendingRegistrations pending;
    private final RegistrationDelivery delivery;

    public EmailRegistrationService(JdbcTemplate jdbc, PasswordEncoder passwords, Validator validator,
            RedisPendingRegistrations pending, RegistrationDelivery delivery) {
        this.jdbc = jdbc; this.passwords = passwords; this.validator = validator;
        this.pending = pending; this.delivery = delivery;
    }

    public RedisPendingRegistrations.Accepted register(RegisterRequest input) {
        input.validate(validator);
        try {
            // Provider reference emails are intentionally excluded. Never reactivate or merge an Account.
            var existing = jdbc.queryForList("""
                    SELECT a.signup_completed FROM auth_identity i JOIN account a ON a.id=i.account_id
                    WHERE i.provider='LOCAL' AND lower(btrim(i.email))=?
                    """, Boolean.class, input.email());
            if (!existing.isEmpty()) throw new BusinessException(Boolean.TRUE.equals(existing.getFirst())
                    ? ErrorCode.EMAIL_ALREADY_REGISTERED : ErrorCode.INVALID_STATE);
            // Missing production delivery blocks before password hashing, storage or rate reservation.
            delivery.requireAvailable();
            var accepted = pending.save(input, passwords.encode(input.password()));
            var outcome = delivery.accept(pending.message(accepted.requestId()));
            // Both definite non-send and uncertainty retain the reservation; no unapproved refund/retry.
            if (outcome != RegistrationDelivery.Outcome.ACCEPTED)
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            return accepted;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // Includes DB/Redis and delivery failures. Do not log credential-bearing causes.
            // Pending state/send reservation may survive an uncertain delivery for its original 10min.
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}

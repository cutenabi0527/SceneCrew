package com.ssafy.scenecrew.account.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.ssafy.scenecrew.account.dto.RegisterRequest;
import com.ssafy.scenecrew.global.error.*;

/** PostgreSQL-only commit boundary. Caller must first validate the pending request and code. */
public final class RegistrationAccountWriter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public RegistrationAccountWriter(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        // Returning from create means commit completed, even if a caller accidentally has a transaction.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public LocalCredentialVerifier.VerifiedAccount create(String email, String displayName,
            String passwordHash, RegisterRequest.Consent consent) {
        UUID accountId = UUID.randomUUID(), identityId = UUID.randomUUID();
        String normalized = LocalCredentialVerifier.normalizeEmail(email);
        String consentJson = ConsentRecords.initial(consent.service(), consent.voice(), consent.push(), consent.externalAi());
        var callbackFailure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        try {
            return transaction.execute(status -> {
                try {
                    var existing = jdbc.queryForList("""
                            SELECT a.signup_completed FROM auth_identity i JOIN account a ON a.id=i.account_id
                            WHERE i.provider='LOCAL' AND lower(btrim(i.email))=?
                            """, Boolean.class, normalized);
                    if (!existing.isEmpty()) throw new BusinessException(Boolean.TRUE.equals(existing.getFirst())
                            ? ErrorCode.EMAIL_ALREADY_REGISTERED : ErrorCode.INVALID_STATE);
                    jdbc.update("""
                            INSERT INTO account (id,display_name,status,signup_completed,token_version,consent,created_at)
                            VALUES (?,?,'ACTIVE',true,0,?::jsonb,now())
                            """, accountId, displayName, consentJson);
                    jdbc.update("""
                            INSERT INTO auth_identity (id,account_id,provider,subject,password_hash,email,email_verified)
                            VALUES (?,?,'LOCAL',?,?,?,true)
                            """, identityId, accountId, accountId.toString(), passwordHash, normalized);
                    return new LocalCredentialVerifier.VerifiedAccount(accountId, displayName, normalized, 0, identityId);
                } catch (RuntimeException e) { callbackFailure.set(e); throw e; }
            });
        } catch (RuntimeException e) {
            // TransactionTemplate rethrows the same callback exception only after rollback succeeds.
            // A commit/rollback exception (including a lost commit acknowledgement) remains uncertain.
            boolean rolledBack = e == callbackFailure.get();
            ErrorCode code = e instanceof BusinessException business ? business.getErrorCode()
                    : ErrorCode.SERVICE_UNAVAILABLE;
            if (rolledBack && e instanceof org.springframework.dao.DuplicateKeyException duplicate
                    && duplicate.getMostSpecificCause().getMessage() != null
                    && duplicate.getMostSpecificCause().getMessage().contains("uq_local_email_normalized"))
                code = ErrorCode.EMAIL_ALREADY_REGISTERED;
            throw new CreationFailure(rolledBack, code, accountId, identityId);
        }
    }

    /** Fresh PostgreSQL observation of this exact attempt, never an email-only success inference. */
    public boolean committed(CreationFailure failure) {
        return Boolean.TRUE.equals(transaction.execute(status -> jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM account a JOIN auth_identity i ON i.account_id=a.id
                WHERE a.id=? AND i.id=? AND i.provider='LOCAL' AND i.subject=?
                  AND a.signup_completed AND i.email_verified)
                """, Boolean.class, failure.accountId, failure.identityId, failure.accountId.toString())));
    }

    public static final class CreationFailure extends RuntimeException {
        private final boolean rolledBack;
        private final ErrorCode code;
        private final UUID accountId;
        private final UUID identityId;
        private CreationFailure(boolean rolledBack,ErrorCode code,UUID accountId,UUID identityId) {
            super("Registration database operation failed"); this.rolledBack=rolledBack; this.code=code;
            this.accountId=accountId; this.identityId=identityId;
        }
        public boolean rolledBack() { return rolledBack; }
        public ErrorCode code() { return code; }
    }
}

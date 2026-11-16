package com.ssafy.scenecrew.account.service;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ssafy.scenecrew.global.error.*;

/** Password and tokenVersion commit together. The conditional account UPDATE serializes competing proofs. */
public class PasswordResetAccountWriter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public PasswordResetAccountWriter(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Optional<Target> find(String email) {
        return jdbc.query("""
                SELECT a.id,i.id,a.token_version FROM account a JOIN auth_identity i ON i.account_id=a.id
                WHERE i.provider='LOCAL' AND lower(btrim(i.email))=? AND i.email_verified
                  AND a.status='ACTIVE' AND a.signup_completed
                """,(rs,n)->new Target(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3)),email)
                .stream().findFirst();
    }
    public void change(Target target, Instant expiresAt, String passwordHash) {
        transaction.executeWithoutResult(status -> {
            int changed=jdbc.update("""
                    UPDATE account SET token_version=token_version+1
                    WHERE id=? AND token_version=? AND status='ACTIVE' AND signup_completed
                      AND clock_timestamp() < ?
                    """,target.accountId(),target.tokenVersion(),Timestamp.from(expiresAt));
            if(changed!=1) throw new BusinessException(ErrorCode.INVALID_STATE);
            int identity=jdbc.update("""
                    UPDATE auth_identity SET password_hash=?
                    WHERE id=? AND account_id=? AND provider='LOCAL' AND email_verified
                    """,passwordHash,target.identityId(),target.accountId());
            if(identity!=1) throw new BusinessException(ErrorCode.INVALID_STATE);
        });
        // No Redis write after commit: a lost commit/HTTP acknowledgement cannot enable a second update.
    }
    public record Target(UUID accountId, UUID identityId, long tokenVersion) {
        @Override public String toString() { return "ResetTarget[redacted]"; }
    }
}

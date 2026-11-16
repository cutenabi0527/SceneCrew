package com.ssafy.scenecrew.account.service;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ssafy.scenecrew.global.error.*;

/** Account -> Identity shared locks span credential creation AND the Redis acknowledgement.
 * Redis is not enlisted in this DB transaction. A failed/unknown commit never returns credentials,
 * retries issuance, or restores a consumed proof. No Event/Member locks are acquired here.
 */
public final class AccountIssuanceGuard {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public AccountIssuanceGuard(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // FOR SHARE cannot run in a PostgreSQL read-only transaction.
        transaction.setReadOnly(false);
    }

    public <T> T issue(UUID account, UUID identity, long version, String provider,
            ErrorCode denied, Supplier<T> issuance) {
        try {
            return transaction.execute(status -> {
                var accounts = jdbc.queryForList("""
                        SELECT id FROM account
                        WHERE id=? AND status='ACTIVE' AND signup_completed=true AND token_version=?
                        FOR SHARE
                        """, UUID.class, account, version);
                if (accounts.size() != 1) throw new BusinessException(denied);
                var identities = jdbc.queryForList("""
                        SELECT id FROM auth_identity
                        WHERE id=? AND account_id=? AND provider=?
                          AND ((provider='LOCAL' AND email_verified=true)
                            OR (provider IN ('GOOGLE','KAKAO','NAVER') AND length(subject)>0))
                        FOR SHARE
                        """, UUID.class, identity, account, provider);
                if (identities.size() != 1) throw new BusinessException(denied);
                return issuance.get();
            });
        } catch (DataAccessException | TransactionException failure) {
            // Redis may already have committed. Do not compensate or retry blindly.
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}

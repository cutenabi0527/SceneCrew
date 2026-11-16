package com.ssafy.scenecrew.account.service;

import com.ssafy.scenecrew.account.dto.AccountSummary;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Updates only the authenticated account; existing Member names remain historical facts. */
public final class AccountProfileService {
    private final JdbcTemplate jdbc;
    private final AccountSummaryReader accounts;
    private final TransactionTemplate transaction;

    public AccountProfileService(JdbcTemplate jdbc, AccountSummaryReader accounts, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public AccountSummary update(SessionAuthenticator.Principal principal, String displayName) {
        String name = normalizedName(displayName);
        try {
            return transaction.execute(status -> {
                // PostgreSQL holds the row lock until commit. Rechecks eligibility after a competing writer.
                int changed = jdbc.update("""
                        UPDATE account SET display_name=?
                        WHERE id=? AND status='ACTIVE' AND signup_completed=true AND token_version=?
                        """, name, principal.accountId(), principal.tokenVersion());
                if (changed != 1) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
                return accounts.read(principal);
            });
        } catch (DataAccessException | TransactionException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static String normalizedName(String value) {
        if (value == null || value.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                || cp == 0x2028 || cp == 0x2029)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        String name = value.trim();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > 20) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        // A lone UTF-16 surrogate is not a Unicode scalar and cannot be persisted as PostgreSQL UTF-8.
        if (name.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return name;
    }
}

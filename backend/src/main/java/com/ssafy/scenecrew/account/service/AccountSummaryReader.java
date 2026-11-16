package com.ssafy.scenecrew.account.service;

import org.springframework.jdbc.core.JdbcTemplate;
import com.ssafy.scenecrew.account.dto.AccountSummary;
import com.ssafy.scenecrew.global.error.*;

/** Fresh DTO projection; never reuses the login-time name/email snapshot. */
public final class AccountSummaryReader {
    private final JdbcTemplate jdbc;
    public AccountSummaryReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public AccountSummary read(SessionAuthenticator.Principal principal) {
        try {
            var rows = jdbc.query("""
                    SELECT a.id, a.display_name, i.email, i.email_verified, a.status
                    FROM account a LEFT JOIN auth_identity i ON i.account_id=a.id AND i.provider='LOCAL'
                    WHERE a.id=? AND a.status='ACTIVE' AND a.signup_completed=true AND a.token_version=?
                    """, (r, n) -> new AccountSummary(r.getObject(1, java.util.UUID.class), r.getString(2),
                            r.getString(3), r.getString(3) != null && r.getBoolean(4), r.getString(5)),
                    principal.accountId(), principal.tokenVersion());
            if (rows.size() != 1) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            return rows.getFirst();
        } catch (org.springframework.dao.DataAccessException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}

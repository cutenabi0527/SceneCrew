package com.ssafy.scenecrew.account.oauth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ssafy.scenecrew.account.service.LocalCredentialVerifier.VerifiedAccount;
import com.ssafy.scenecrew.global.error.*;

/** Never searches by provider email and never creates/links identities. Fresh single JOIN at exchange. */
public final class OAuthAccountReader {
    private final JdbcTemplate jdbc;
    private final com.ssafy.scenecrew.account.service.AccountIssuanceGuard issuance;
    private final TransactionTemplate transaction;
    public OAuthAccountReader(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc; issuance=new com.ssafy.scenecrew.account.service.AccountIssuanceGuard(jdbc,manager); transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transaction.setReadOnly(true);
    }
    public <T> T withIssuanceLock(VerifiedAccount account, SocialProvider provider, java.util.function.Supplier<T> action) {
        return issuance.issue(account.accountId(), account.identityId(), account.tokenVersion(), provider.name(),
                ErrorCode.UNAUTHENTICATED, action);
    }
    /** Includes disabled/incomplete identities: they must not enter signup as an unlinked subject. */
    public boolean hasIdentity(SocialProvider provider,String subject) {
        try {
            return Boolean.TRUE.equals(transaction.execute(status->jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM auth_identity WHERE provider=? AND subject=?)",
                    Boolean.class,provider.name(),subject)));
        } catch (org.springframework.dao.DataAccessException | TransactionException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
    public VerifiedAccount linked(SocialProvider provider,String subject) {
        try {
            return transaction.execute(status->{
                var rows=jdbc.query("""
                        SELECT a.id,a.display_name,l.email,a.token_version,i.id
                        FROM auth_identity i JOIN account a ON a.id=i.account_id
                        LEFT JOIN auth_identity l ON l.account_id=a.id AND l.provider='LOCAL' AND l.email_verified=true
                        WHERE i.provider=? AND i.subject=? AND a.status='ACTIVE' AND a.signup_completed=true
                        """,(r,n)->new VerifiedAccount(r.getObject(1,java.util.UUID.class),r.getString(2),r.getString(3),
                                r.getLong(4),r.getObject(5,java.util.UUID.class)),provider.name(),subject);
                if (rows.size()!=1) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
                return rows.getFirst();
            });
        } catch (org.springframework.dao.DataAccessException | TransactionException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}

package com.ssafy.scenecrew.account.oauth;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ssafy.scenecrew.account.service.LocalCredentialVerifier.VerifiedAccount;
import com.ssafy.scenecrew.global.error.*;

/** Account creation commits independently; subsequent credential issuance has a separate shared-lock boundary. */
public final class SocialSignupAccounts {
    private final JdbcTemplate jdbc;
    private final com.ssafy.scenecrew.account.service.AccountIssuanceGuard issuance;
    private final TransactionTemplate transaction;
    public SocialSignupAccounts(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc; issuance=new com.ssafy.scenecrew.account.service.AccountIssuanceGuard(jdbc,manager);
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public <T> T withIssuanceLock(VerifiedAccount account, SocialProvider provider, java.util.function.Supplier<T> action) {
        return issuance.issue(account.accountId(), account.identityId(), account.tokenVersion(), provider.name(),
                ErrorCode.UNAUTHENTICATED, action);
    }
    public VerifiedAccount create(RedisOAuthAttempts.Attempt proof,String displayName,SocialSignupService.Consent consent) {
        try {
            return transaction.execute(status->{
                // The UNIQUE constraint arbitrates races after this current-state check.
                if (Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM auth_identity WHERE provider=? AND subject=?)",
                        Boolean.class,proof.provider().name(),proof.subject())))
                    throw new BusinessException(ErrorCode.AUTH_CONTEXT_CONFLICT);
                var account=UUID.randomUUID(); var identity=UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO account(id,display_name,status,signup_completed,token_version,consent,created_at)
                        VALUES (?,?,'ACTIVE',true,0,?::jsonb,now())
                        """,account,displayName,com.ssafy.scenecrew.account.service.ConsentRecords.initial(
                        consent.service(),consent.voice(),consent.push(),consent.externalAi()));
                jdbc.update("""
                        INSERT INTO auth_identity(id,account_id,provider,subject,password_hash,email,email_verified)
                        VALUES (?,?,?,?,NULL,NULL,false)
                        """,identity,account,proof.provider().name(),proof.subject());
                return new VerifiedAccount(account,displayName,null,0,identity);
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.AUTH_CONTEXT_CONFLICT);
        } catch (org.springframework.dao.DataAccessException | TransactionException e) {
            // Includes unknown commit outcome. Never retry or infer rollback from an exception.
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}

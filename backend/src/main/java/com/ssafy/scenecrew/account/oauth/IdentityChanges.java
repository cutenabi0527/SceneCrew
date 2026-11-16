package com.ssafy.scenecrew.account.oauth;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ssafy.scenecrew.account.service.SessionAuthenticator.Principal;
import com.ssafy.scenecrew.global.error.*;
import static com.ssafy.scenecrew.account.oauth.IdentityChangeException.Reason.*;

/** Not a bean. All #227 writers lock Account first, then re-read identities at READ_COMMITTED.
 * Commit is the change boundary. Existing global UNIQUE handles cross-account subject races.
 * Deleting the identity invalidates its v2 sessions through CurrentAccountReader, even after relinking
 * the same subject (new UUID). No other provider's sessions/tokenVersion are touched.
 */
public final class IdentityChanges {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IdentityReauthentication reauthentication;
    public IdentityChanges(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc,manager,IdentityReauthentication.unavailable());
    }
    public IdentityChanges(JdbcTemplate jdbc, PlatformTransactionManager manager, IdentityReauthentication reauthentication) {
        this.jdbc=jdbc; this.reauthentication=Objects.requireNonNull(reauthentication);
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    // Only server OAuth completion in this package may supply a verified subject. Never map a request DTO here.
    UUID link(Principal principal, SocialProvider provider, String subject, String proof) {
        if (subject==null || subject.isBlank() || subject.length()>512 || subject.chars().anyMatch(Character::isISOControl))
            throw new IdentityChangeException(INVALID_SUBJECT);
        Objects.requireNonNull(provider);
        try {
            return transaction.execute(status -> {
                current(principal);
                reauthentication.require(principal,IdentityReauthentication.Operation.LINK_IDENTITY,provider,proof);
                if (count("SELECT count(*) FROM auth_identity WHERE account_id=? AND provider=?",principal.accountId(),provider.name())>0)
                    throw new IdentityChangeException(PROVIDER_ALREADY_LINKED);
                if (count("SELECT count(*) FROM auth_identity WHERE provider=? AND subject=?",provider.name(),subject)>0)
                    throw new IdentityChangeException(IDENTITY_ALREADY_LINKED);
                UUID identity=UUID.randomUUID();
                jdbc.update("INSERT INTO auth_identity VALUES (?, ?, ?, ?, NULL, NULL, false)",identity,principal.accountId(),provider.name(),subject);
                reauthentication.consume(principal,IdentityReauthentication.Operation.LINK_IDENTITY,provider,proof);
                return identity;
            });
        } catch (DuplicateKeyException conflict) {
            // Known UNIQUE constraints remain the arbiter when different accounts race for one subject.
            throw new IdentityChangeException(IDENTITY_ALREADY_LINKED);
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }
    public void unlink(Principal principal, SocialProvider provider, String proof) {
        Objects.requireNonNull(provider); // enum excludes LOCAL
        try {
            transaction.executeWithoutResult(status -> {
                current(principal);
                reauthentication.require(principal,IdentityReauthentication.Operation.UNLINK_IDENTITY,provider,proof);
                if (count("SELECT count(*) FROM auth_identity WHERE account_id=? AND provider=?",principal.accountId(),provider.name())!=1)
                    throw new IdentityChangeException(TARGET_NOT_FOUND);
                if (count("""
                        SELECT count(*) FROM auth_identity WHERE account_id=? AND provider<>? AND
                        ((provider='LOCAL' AND email_verified=true AND length(btrim(email))>0 AND length(password_hash)>0)
                        OR (provider IN ('GOOGLE','KAKAO','NAVER') AND length(btrim(subject))>0))
                        """,principal.accountId(),provider.name())<1) throw new IdentityChangeException(LAST_IDENTITY);
                jdbc.update("DELETE FROM auth_identity WHERE account_id=? AND provider=?",principal.accountId(),provider.name());
                reauthentication.consume(principal,IdentityReauthentication.Operation.UNLINK_IDENTITY,provider,proof);
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }
    private void current(Principal principal) {
        if (principal==null) throw denied();
        var accounts=jdbc.query("SELECT token_version FROM account WHERE id=? AND status='ACTIVE' AND signup_completed=true FOR UPDATE",
                (r,n)->r.getLong(1),principal.accountId());
        if (accounts.size()!=1 || accounts.getFirst()!=principal.tokenVersion()) throw denied();
        if (count("""
                SELECT count(*) FROM auth_identity WHERE id=? AND account_id=? AND
                ((provider='LOCAL' AND email_verified=true) OR (provider IN ('GOOGLE','KAKAO','NAVER') AND length(subject)>0))
                """,principal.identityId(),principal.accountId())!=1) throw denied();
    }
    private int count(String sql,Object... args) { return jdbc.queryForObject(sql,Integer.class,args); }
    private static BusinessException denied() { return new BusinessException(ErrorCode.UNAUTHENTICATED); }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
}

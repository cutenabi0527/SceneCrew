package com.ssafy.scenecrew.account.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 새 READ_COMMITTED 읽기 트랜잭션에서 단일 JOIN으로 현재 상태를 확인한다. 제품 bean 아님. */
public final class CurrentAccountReader {
    private final JdbcTemplate jdbc;
    private final AccountIssuanceGuard issuance;
    private final TransactionTemplate transaction;
    public CurrentAccountReader(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        issuance = new AccountIssuanceGuard(jdbc, manager);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(true);
    }
    public <T> T withIssuanceLock(StoredLoginSession session, java.util.function.Supplier<T> action) {
        return issuance.issue(session.accountId(), session.identityId(), session.tokenVersion(), session.provider(),
                com.ssafy.scenecrew.global.error.ErrorCode.UNAUTHENTICATED, action);
    }
    public boolean isActive(StoredLoginSession session) {
        return Boolean.TRUE.equals(transaction.execute(status -> jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM account a JOIN auth_identity i ON i.account_id = a.id
                WHERE a.id = ? AND i.id = ? AND a.status = 'ACTIVE' AND a.signup_completed = true
                  AND a.token_version = ? AND i.provider = ? AND ((i.provider = 'LOCAL' AND i.email_verified = true)
                    OR (i.provider IN ('GOOGLE','KAKAO','NAVER') AND length(i.subject) > 0)))
                """, Boolean.class, session.accountId(), session.identityId(), session.tokenVersion(), session.provider())));
    }
}

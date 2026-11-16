package com.ssafy.scenecrew.account.service;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.repository.AccountRepository;
import com.ssafy.scenecrew.account.repository.AuthIdentityRepository;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 자격 검증·발급 핵심. 공개 연결 후보는 별도 설정으로 격리하고 실패 제한·채널 검사를 선행한다.
 * 반환 전 Redis 저장 성공을 확인한다. 이후 HTTP 전송 실패는 세션 삭제 사유가 아니다.
 */
public final class EmailLoginService {
    private final LocalCredentialVerifier verifier;
    private final AccountIssuanceGuard issuance;
    private final AccountRepository accounts;
    private final AuthIdentityRepository identities;
    private final LoginTokenIssuer tokens;
    private final RedisLoginSessions sessions;
    public EmailLoginService(LocalCredentialVerifier verifier, AccountRepository accounts,
            AuthIdentityRepository identities, LoginTokenIssuer tokens, RedisLoginSessions sessions, AccountIssuanceGuard issuance) {
        this.issuance = issuance;
        this.verifier = verifier; this.accounts = accounts; this.identities = identities;
        this.tokens = tokens; this.sessions = sessions;
    }
    public Result login(String email, String password, String channel) {
        return login(email, password, channel, sessions::register);
    }
    public Result login(String email, String password, String channel, Registration registration) {
        try {
            return performLogin(email, password, channel, registration);
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
    private Result performLogin(String email, String password, String channel, Registration registration) {
        if (!"WEB".equals(channel) && !"ANDROID".equals(channel)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        var account = verifier.verify(email, password)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        return issueVerified(account,channel,registration);
    }
    // #224 calls only after code validation and the Account/Identity commit. No public proof bypass.
    Result issueVerified(LocalCredentialVerifier.VerifiedAccount account,String channel,Registration registration) {
        if (!"WEB".equals(channel) && !"ANDROID".equals(channel))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return issuance.issue(account.accountId(), account.identityId(), account.tokenVersion(), "LOCAL",
                ErrorCode.INVALID_CREDENTIALS, () -> {
            // JDBC guard above is authoritative even when a caller holds stale JPA entities.
            // #191에서도 현재 상태를 다시 확인해야 한다. DB·Redis 전체 원자성을 주장하지 않는다.
            var current = accounts.findById(account.accountId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
            var identity = identities.findById(account.identityId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
            if (!"ACTIVE".equals(current.getStatus()) || !Boolean.TRUE.equals(current.getSignupCompleted())
                    || current.getTokenVersion() != account.tokenVersion()
                    || !account.accountId().equals(identity.getAccountId())
                    || !"LOCAL".equals(identity.getProvider())
                    || !Boolean.TRUE.equals(identity.getEmailVerified())) {
                throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
            }
            var issued = tokens.issue(account);
            registration.register(account, issued, channel);
            return new Result(account, issued);
        });
    }
    public record Result(LocalCredentialVerifier.VerifiedAccount account, LoginTokens tokens) {
        @Override public String toString() { return "LoginResult[redacted]"; }
    }
    @FunctionalInterface
    public interface Registration {
        void register(LocalCredentialVerifier.VerifiedAccount account, LoginTokens tokens, String channel);
    }
}

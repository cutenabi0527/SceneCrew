package com.ssafy.scenecrew.account.service;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.ssafy.scenecrew.account.repository.AccountRepository;
import com.ssafy.scenecrew.account.repository.AuthIdentityRepository;

/**
 * 자격 검증만 수행한다. 세션 생성·실패 집계·HTTP 성공 판정은 호출자의 책임이다.
 * 해시와 버전은 한 SQL snapshot이며 공개 발급 직전에는 현재 상태를 다시 검사한다.
 */
public final class LocalCredentialVerifier {
    private final AuthIdentityRepository identities;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public LocalCredentialVerifier(AuthIdentityRepository identities, AccountRepository accounts,
            PasswordEncoder passwords) {
        this.identities = identities;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public Optional<VerifiedAccount> verify(String email, String password) {
        if (password == null || !password.matches("[!-~]{8,64}")) {
            return Optional.empty();
        }
        var identity = identities.findLocalCredentials(normalizeEmail(email));
        boolean matches = passwords.matches(password,
                identity.map(AuthIdentityRepository.LocalCredentials::getPasswordHash).orElse(dummyHash));
        if (identity.isEmpty() || !matches) return Optional.empty();
        var local=identity.get();
        if (!Boolean.TRUE.equals(local.getEmailVerified()) || !"ACTIVE".equals(local.getStatus())
                || !Boolean.TRUE.equals(local.getSignupCompleted())) return Optional.empty();
        return Optional.of(new VerifiedAccount(local.getAccountId(),local.getDisplayName(),
                normalizeEmail(local.getEmail()),local.getTokenVersion(),local.getIdentityId()));
    }

    // 자격 검증 시점의 값이다. 발급 직전 및 #191의 매 요청 현재 상태 재검사가 필요하다.
    public record VerifiedAccount(UUID accountId, String displayName, String email,
            long tokenVersion, UUID identityId) {
        @Override
        public String toString() {
            return "VerifiedAccount[redacted]";
        }
    }
}

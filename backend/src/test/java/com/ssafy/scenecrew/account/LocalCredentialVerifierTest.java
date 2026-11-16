package com.ssafy.scenecrew.account;

import java.util.Optional;
import java.util.UUID;
import com.ssafy.scenecrew.account.domain.Account;
import com.ssafy.scenecrew.account.domain.AuthIdentity;
import com.ssafy.scenecrew.account.repository.AccountRepository;
import com.ssafy.scenecrew.account.repository.AuthIdentityRepository;
import com.ssafy.scenecrew.account.service.LocalCredentialVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LocalCredentialVerifierTest {
    private final AuthIdentityRepository identities = mock(AuthIdentityRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private LocalCredentialVerifier verifier;
    private Account account;
    private AuthIdentity identity;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        account = construct(Account.class);
        identity = construct(AuthIdentity.class);
        ReflectionTestUtils.setField(account, "id", accountId);
        ReflectionTestUtils.setField(account, "displayName", "합성계정");
        ReflectionTestUtils.setField(account, "status", "ACTIVE");
        ReflectionTestUtils.setField(account, "signupCompleted", true);
        ReflectionTestUtils.setField(account, "tokenVersion", 7L);
        ReflectionTestUtils.setField(identity, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(identity, "accountId", accountId);
        ReflectionTestUtils.setField(identity, "provider", "LOCAL");
        ReflectionTestUtils.setField(identity, "email", "fixture@example.invalid");
        ReflectionTestUtils.setField(identity, "emailVerified", true);
        ReflectionTestUtils.setField(identity, "passwordHash", encoder.encode("SyntheticPass8!"));
        var snapshot=mock(AuthIdentityRepository.LocalCredentials.class);
        when(snapshot.getAccountId()).thenAnswer(c->account.getId());
        when(snapshot.getIdentityId()).thenAnswer(c->identity.getId());
        when(snapshot.getDisplayName()).thenAnswer(c->account.getDisplayName());
        when(snapshot.getStatus()).thenAnswer(c->account.getStatus());
        when(snapshot.getSignupCompleted()).thenAnswer(c->account.getSignupCompleted());
        when(snapshot.getTokenVersion()).thenAnswer(c->account.getTokenVersion());
        when(snapshot.getEmail()).thenAnswer(c->identity.getEmail());
        when(snapshot.getEmailVerified()).thenAnswer(c->identity.getEmailVerified());
        when(snapshot.getPasswordHash()).thenAnswer(c->identity.getPasswordHash());
        when(identities.findLocalCredentials("fixture@example.invalid")).thenReturn(Optional.of(snapshot));
        verifier = new LocalCredentialVerifier(identities, accounts, encoder);
    }

    @Test void normalizesEmailAndReturnsVerifiedIdentityWithoutWriting() {
        var result = verifier.verify("  FIXTURE@EXAMPLE.INVALID  ", "SyntheticPass8!").orElseThrow();
        assertThat(result.accountId()).isEqualTo(accountId);
        assertThat(result.tokenVersion()).isEqualTo(7L);
        assertThat(result.email()).isEqualTo("fixture@example.invalid");
        verify(identities, never()).save(any());
        verify(accounts, never()).save(any());
    }

    @Test void rejectsWrongPasswordAndMissingEmailIdentically() {
        assertThat(verifier.verify("fixture@example.invalid", "WrongPassword8!")).isEmpty();
        assertThat(verifier.verify("missing@example.invalid", "WrongPassword8!")).isEmpty();
        verifyNoInteractions(accounts);
    }

    @Test void rejectsIncompleteSignup() {
        ReflectionTestUtils.setField(account, "signupCompleted", false);
        assertThat(verifier.verify("fixture@example.invalid", "SyntheticPass8!")).isEmpty();
    }

    @Test void rejectsUnverifiedEmail() {
        ReflectionTestUtils.setField(identity, "emailVerified", false);
        assertThat(verifier.verify("fixture@example.invalid", "SyntheticPass8!")).isEmpty();
    }

    @Test void rejectsDisabledAndUnknownStatus() {
        for (var status : new String[]{"DISABLED", "PENDING", "active"}) {
            ReflectionTestUtils.setField(account, "status", status);
            assertThat(verifier.verify("fixture@example.invalid", "SyntheticPass8!")).isEmpty();
        }
    }

    @Test void doesNotTrimOrTruncatePassword() {
        for (var password : new String[]{" SyntheticPass8!", "SyntheticPass8! ", "a".repeat(65), "한글Password8!"}) {
            assertThat(verifier.verify("fixture@example.invalid", password)).isEmpty();
        }
        verifyNoInteractions(identities, accounts);
    }

    private static <T> T construct(Class<T> type) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}

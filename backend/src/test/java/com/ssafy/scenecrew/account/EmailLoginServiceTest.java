package com.ssafy.scenecrew.account;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.ssafy.scenecrew.account.domain.*;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.repository.*;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailLoginServiceTest {
    final LocalCredentialVerifier verifier = mock(LocalCredentialVerifier.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final AuthIdentityRepository identities = mock(AuthIdentityRepository.class);
    final LoginTokenIssuer issuer = mock(LoginTokenIssuer.class);
    final RedisLoginSessions sessions = mock(RedisLoginSessions.class);
    final AccountIssuanceGuard issuance = mock(AccountIssuanceGuard.class, call -> {
        if (call.getMethod().getName().equals("issue")) return ((java.util.function.Supplier<?>)call.getArgument(5)).get();
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
    });
    final EmailLoginService service = new EmailLoginService(verifier, accounts, identities, issuer, sessions, issuance);
    final LocalCredentialVerifier.VerifiedAccount verified = new LocalCredentialVerifier.VerifiedAccount(
            UUID.randomUUID(), "합성계정", "fixture@example.invalid", 3, UUID.randomUUID());
    final LoginTokens tokens = new LoginTokens(UUID.randomUUID(), "synthetic-access", "synthetic-refresh",
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(1209600));

    void valid() {
        when(verifier.verify("fixture@example.invalid", "SyntheticPass8!")).thenReturn(Optional.of(verified));
        when(issuer.issue(verified)).thenReturn(tokens);
        var account = mock(Account.class);
        when(account.getStatus()).thenReturn("ACTIVE");
        when(account.getSignupCompleted()).thenReturn(true);
        when(account.getTokenVersion()).thenReturn(3L);
        when(accounts.findById(verified.accountId())).thenReturn(Optional.of(account));
        var identity = mock(AuthIdentity.class);
        when(identity.getAccountId()).thenReturn(verified.accountId());
        when(identity.getProvider()).thenReturn("LOCAL");
        when(identity.getEmailVerified()).thenReturn(true);
        when(identities.findById(verified.identityId())).thenReturn(Optional.of(identity));
    }
    @Test void returnsOnlyAfterConfirmedRegistration() {
        valid();
        var result = service.login("fixture@example.invalid", "SyntheticPass8!", "WEB");
        assertThat(result.tokens()).isSameAs(tokens);
        var order = inOrder(verifier, issuance, issuer, accounts, identities, sessions);
        order.verify(verifier).verify(anyString(), anyString());
        order.verify(issuance).issue(eq(verified.accountId()), eq(verified.identityId()), eq(verified.tokenVersion()),
                eq("LOCAL"), eq(ErrorCode.INVALID_CREDENTIALS), any());
        order.verify(accounts).findById(verified.accountId());
        order.verify(identities).findById(verified.identityId());
        order.verify(issuer).issue(verified);
        order.verify(sessions).register(verified, tokens, "WEB");
    }
    @Test void storageFailureNeverReturnsTokens() {
        valid();
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE))
                .when(sessions).register(any(), any(), anyString());
        assertThatThrownBy(() -> service.login("fixture@example.invalid", "SyntheticPass8!", "WEB"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasMessageNotContaining(tokens.refreshToken());
    }
    @Test void rechecksAccountBeforeRegistration() {
        valid();
        when(accounts.findById(verified.accountId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login("fixture@example.invalid", "SyntheticPass8!", "WEB"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(sessions);
    }
    @Test void credentialRejectionDoesNotIssueOrRegister() {
        when(verifier.verify(anyString(), anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login("fixture@example.invalid", "WrongPassword8!", "WEB"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        verifyNoInteractions(issuer, sessions);
    }
    @Test void databaseFailureIsUnavailableAndDoesNotIssueTokens() {
        when(verifier.verify(anyString(), anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic db failure"));
        assertThatThrownBy(() -> service.login("fixture@example.invalid", "SyntheticPass8!", "WEB"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verifyNoInteractions(issuer, sessions);
    }
    @Test void generic401MappingStaysUnauthenticated() {
        assertThat(ErrorCode.fromStatus(org.springframework.http.HttpStatus.UNAUTHORIZED))
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }
    @Test void databaseTransactionAcquisitionFailureIsUnavailableWithoutIssuanceOrSensitiveCause() {
        when(verifier.verify(anyString(), anyString()))
                .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("synthetic-sensitive-connection"));
        assertThatThrownBy(() -> service.login("fixture@example.invalid", "SyntheticPass8!", "WEB"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasNoCause().hasMessageNotContaining("synthetic-sensitive-connection");
        verifyNoInteractions(issuer, sessions);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"web", "IOS"})
    void rejectsInvalidChannelBeforeAnyCredentialOrStorageWork(String channel) {
        assertThatThrownBy(() -> service.login("fixture@example.invalid", "SyntheticPass8!", channel))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        verifyNoInteractions(verifier, issuer, accounts, identities, sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "signup", "version", "missingIdentity", "owner", "provider", "emailVerified"})
    void rejectsStateChangesBetweenVerificationAndRegistration(String change) {
        valid();
        var current = accounts.findById(verified.accountId()).orElseThrow();
        var identity = identities.findById(verified.identityId()).orElseThrow();
        switch (change) {
            case "status" -> when(current.getStatus()).thenReturn("DISABLED");
            case "signup" -> when(current.getSignupCompleted()).thenReturn(false);
            case "version" -> when(current.getTokenVersion()).thenReturn(4L);
            case "missingIdentity" -> when(identities.findById(verified.identityId())).thenReturn(Optional.empty());
            case "owner" -> when(identity.getAccountId()).thenReturn(UUID.randomUUID());
            case "provider" -> when(identity.getProvider()).thenReturn("GOOGLE");
            case "emailVerified" -> when(identity.getEmailVerified()).thenReturn(false);
            default -> throw new AssertionError(change);
        }
        assertThatThrownBy(() -> service.login("fixture@example.invalid", "SyntheticPass8!", "WEB"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        verifyNoInteractions(sessions);
    }

    @Test void databaseFailureDuringRecheckDoesNotRegisterOrExposeCause() {
        valid();
        when(identities.findById(verified.identityId())).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("synthetic-sensitive-cause"));
        assertThatThrownBy(() -> service.login("fixture@example.invalid", "SyntheticPass8!", "WEB"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasMessageNotContaining("synthetic-sensitive-cause").hasNoCause();
        verifyNoInteractions(sessions);
    }
}

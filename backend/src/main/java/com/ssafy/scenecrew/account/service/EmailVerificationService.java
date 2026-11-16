package com.ssafy.scenecrew.account.service;

import java.util.UUID;
import com.ssafy.scenecrew.account.dto.Credentials;
import com.ssafy.scenecrew.account.infra.RedisPendingRegistrations;
import com.ssafy.scenecrew.account.infra.RedisVerificationRequests;
import com.ssafy.scenecrew.global.error.*;

/** No outer transaction. Redis claim, PostgreSQL commit, Redis completion and session are separate boundaries. */
public final class EmailVerificationService {
    private final RedisVerificationRequests requests;
    private final RegistrationAccountWriter accounts;
    private final RegistrationSessionIssuer sessions;
    private final RedisPendingRegistrations pending;
    private final RegistrationDelivery delivery;
    public EmailVerificationService(RedisVerificationRequests requests,RegistrationAccountWriter accounts,
            RegistrationSessionIssuer sessions,RedisPendingRegistrations pending,RegistrationDelivery delivery) {
        this.requests=requests; this.accounts=accounts; this.sessions=sessions; this.pending=pending; this.delivery=delivery;
    }
    public EmailLoginService.Result verify(UUID requestId,String code,Credentials credentials) {
        if (requestId==null || credentials==null || !java.util.Set.of("WEB","ANDROID").contains(credentials.channel()))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        RegistrationSessionIssuer.Prepared prepared;
        try { prepared=sessions.prepare(credentials); }
        catch (BusinessException failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(); }
        var owner=UUID.randomUUID();
        // Lost claim acknowledgement is not retried or released by another owner.
        requests.claim(requestId,code,owner);
        RedisVerificationRequests.Pending input;
        try { input=requests.read(requestId); }
        catch (RuntimeException failure) { finishQuietly(requestId,owner,"PENDING"); throw unavailable(); }
        LocalCredentialVerifier.VerifiedAccount account;
        try {
            account=accounts.create(input.email(),input.displayName(),input.passwordHash(),input.consent());
        } catch (RegistrationAccountWriter.CreationFailure failure) {
            if (failure.rolledBack()) {
                // Do not reset attempts, budget or expiry. Duplicates/legacy identities are terminal.
                requests.finish(requestId,owner,failure.code()==ErrorCode.SERVICE_UNAVAILABLE?"PENDING":"TERMINATED");
                throw new BusinessException(failure.code());
            }
            try {
                // Even confirmed commit after a lost acknowledgement never mints a recovery session.
                if (accounts.committed(failure)) finishQuietly(requestId,owner,"COMPLETED");
            } catch (RuntimeException observationUnavailable) { /* Remain VERIFYING; never infer rollback. */ }
            throw unavailable();
        } catch (RuntimeException failure) {
            // Unknown failures are not proof of rollback. Keep the claim and never automatically INSERT again.
            throw unavailable();
        }
        try {
            requests.finish(requestId,owner,"COMPLETED");
            return sessions.issue(account,prepared);
        } catch (BusinessException failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(); }
        // HTTP response loss after return preserves both the committed account and any issued session.
    }
    public RedisPendingRegistrations.Accepted resend(UUID requestId) {
        if (requestId==null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        try {
            delivery.requireAvailable();
            var accepted=requests.reserveResend(requestId);
            var outcome=delivery.accept(pending.message(requestId));
            if (outcome!=RegistrationDelivery.Outcome.ACCEPTED) throw unavailable();
            return accepted;
        } catch (BusinessException failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(); }
    }
    private void finishQuietly(UUID id,UUID owner,String state) {
        try { requests.finish(id,owner,state); } catch (RuntimeException ignored) { /* Original TTL remains. */ }
    }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
}

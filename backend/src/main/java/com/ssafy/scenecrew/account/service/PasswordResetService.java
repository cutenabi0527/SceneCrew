package com.ssafy.scenecrew.account.service;

import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.ssafy.scenecrew.account.infra.RedisPasswordResets;
import com.ssafy.scenecrew.global.error.*;

public final class PasswordResetService {
    public static final String ACCEPTED="해당 이메일로 비밀번호 재설정이 가능한 경우 안내를 발송합니다.";
    private final RedisPasswordResets resets;
    private final PasswordResetAccountWriter accounts;
    private final PasswordEncoder passwords;
    private final PasswordResetDelivery delivery;
    private final Validator validator;
    public PasswordResetService(RedisPasswordResets resets,PasswordResetAccountWriter accounts,
            PasswordEncoder passwords,PasswordResetDelivery delivery,Validator validator) {
        this.resets=resets; this.accounts=accounts; this.passwords=passwords; this.delivery=delivery; this.validator=validator;
    }
    public void request(String email) {
        String normalized=LocalCredentialVerifier.normalizeEmail(email);
        if(!validator.validate(new Address(normalized)).isEmpty()) invalid();
        try {
            delivery.requireAvailable(); // Global configuration failure is independent of account existence.
            var target=accounts.find(normalized);
            var message=resets.reserve(normalized,target); // Missing accounts use the same budget and response.
            if(target.isPresent()) {
                try { delivery.accept(message); }
                catch(RuntimeException ignored) { /* Recipient-specific/uncertain delivery cannot reveal existence. */ }
            }
            // Never retry automatically, refund budget, or discard a potentially delivered proof.
        } catch(BusinessException e) { throw e; }
        catch(RuntimeException e) { throw unavailable(); }
    }
    public void confirm(String proof,String password) {
        if(password==null || !password.matches("[!-~]{8,64}")) invalid();
        try {
            var verified=resets.read(proof); // Read does not consume the proof.
            accounts.change(verified.target(),verified.expiresAt(),passwords.encode(password));
        } catch(BusinessException e) { throw e; }
        catch(RuntimeException e) { throw unavailable(); }
    }
    private record Address(@NotBlank @Email String email) {
        @Override public String toString() { return "ResetAddress[redacted]"; }
    }
    private static void invalid() { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
}

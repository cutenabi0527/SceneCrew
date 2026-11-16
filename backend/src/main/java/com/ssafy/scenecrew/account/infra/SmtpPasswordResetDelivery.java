package com.ssafy.scenecrew.account.infra;

import org.springframework.core.env.Environment;
import com.ssafy.scenecrew.account.service.PasswordResetDelivery;
import com.ssafy.scenecrew.account.service.RegistrationDelivery;

public final class SmtpPasswordResetDelivery implements PasswordResetDelivery {
    private final SmtpMailSender sender;
    public SmtpPasswordResetDelivery(Environment env) { sender = new SmtpMailSender(env); }
    SmtpPasswordResetDelivery(SmtpMailSender.Settings settings) { sender = new SmtpMailSender(settings); }
    public void requireAvailable() { sender.requireAvailable(); }
    public RegistrationDelivery.Outcome accept(Message message) {
        requireAvailable();
        if (message == null || message.recoveryProof() == null || message.expiresAt() == null)
            return RegistrationDelivery.Outcome.DEFINITELY_NOT_SENT;
        return sender.send(message.email(), "SCENE CREW 비밀번호 재설정",
                "비밀번호 재설정 증명: " + message.recoveryProof() + "\n만료 시각(UTC): " + message.expiresAt()
                + "\n재설정 화면에서 증명과 새 비밀번호를 입력해 주세요. 열람만으로 사용되지 않습니다."
                + "\n요청하지 않았다면 이 메일을 무시해 주세요.");
    }
    @Override public String toString() { return "SmtpPasswordResetDelivery[redacted]"; }
}

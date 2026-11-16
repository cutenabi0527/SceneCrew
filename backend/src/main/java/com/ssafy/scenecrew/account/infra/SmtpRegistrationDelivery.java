package com.ssafy.scenecrew.account.infra;

import java.util.Properties;
import org.springframework.core.env.Environment;
import com.ssafy.scenecrew.account.service.RegistrationDelivery;

/** Signup-specific message; SMTP transport is shared with password recovery. */
public final class SmtpRegistrationDelivery implements RegistrationDelivery {
    private final SmtpMailSender sender;
    public SmtpRegistrationDelivery(Environment env) { sender = new SmtpMailSender(env); }
    SmtpRegistrationDelivery(Settings settings) { sender = new SmtpMailSender(settings); }
    public void requireAvailable() { sender.requireAvailable(); }
    Properties properties() { return sender.properties(); }
    public Outcome accept(Message message) {
        requireAvailable();
        if (message == null || message.code() == null || !message.code().matches("[0-9]{6}")
                || message.expiresAt() == null) return Outcome.DEFINITELY_NOT_SENT;
        return sender.send(message.email(), "SCENE CREW 이메일 인증번호",
                "인증번호: " + message.code() + "\n만료 시각(UTC): " + message.expiresAt()
                        + "\n만료 후에는 사용할 수 없습니다.");
    }
    @Override public String toString() { return "SmtpRegistrationDelivery[redacted]"; }
    static final class Settings extends SmtpMailSender.Settings {
        Settings(boolean enabled, String host, int port, String username, String password, String from, int timeoutMs) {
            super(enabled,host,port,username,password,from,timeoutMs);
        }
    }
}

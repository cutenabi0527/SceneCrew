package com.ssafy.scenecrew.account.infra;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.core.env.Environment;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.account.service.RegistrationDelivery.Outcome;

/** One connection and one send attempt per message. No retry or delivery-budget mutation. */
final class SmtpMailSender {
    private final Settings settings;

    public SmtpMailSender(Environment env) {
        this(new Settings(
                env.getProperty("scenecrew.auth.mail.smtp.enabled", Boolean.class, false),
                env.getProperty("scenecrew.auth.mail.smtp.host", "smtp.gmail.com"),
                env.getProperty("scenecrew.auth.mail.smtp.port", Integer.class, 587),
                env.getProperty("scenecrew.auth.mail.smtp.username", ""),
                env.getProperty("scenecrew.auth.mail.smtp.password", ""),
                env.getProperty("scenecrew.auth.mail.smtp.from", ""),
                env.getProperty("scenecrew.auth.mail.smtp.timeout-ms", Integer.class, 5000)));
    }

    SmtpMailSender(Settings settings) {
        this.settings = settings;
        // Angus can trace protocol/body through JUL independently of Session.debug.
        for (String name : new String[]{"org.eclipse.angus.mail.smtp",
                "org.eclipse.angus.mail.smtp.protocol"}) {
            Logger.getLogger(name).setLevel(Level.OFF);
        }
    }

    public void requireAvailable() {
        if (!settings.enabled || settings.host.isBlank() || settings.port < 1 || settings.port > 65535
                || settings.username.isBlank() || settings.password.isBlank()
                || !validAddress(settings.from) || settings.timeoutMs < 1 || settings.timeoutMs > 60000) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static boolean validAddress(String value) {
        try {
            if (value == null || value.contains("\r") || value.contains("\n")) return false;
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            return address.getPersonal() == null && value.equals(address.getAddress());
        } catch (AddressException e) { return false; }
    }

    Properties properties() {
        Properties p = new Properties();
        p.setProperty("mail.smtp.auth", "true");
        p.setProperty("mail.smtp.starttls.enable", "true");
        p.setProperty("mail.smtp.starttls.required", "true");
        p.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        p.setProperty("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        p.setProperty("mail.smtp.sendpartial", "false");
        p.setProperty("mail.smtp.reportsuccess", "false");
        p.setProperty("mail.smtp.quitwait", "false");
        p.setProperty("mail.smtp.connectiontimeout", Integer.toString(settings.timeoutMs));
        p.setProperty("mail.smtp.timeout", Integer.toString(settings.timeoutMs));
        p.setProperty("mail.smtp.writetimeout", Integer.toString(settings.timeoutMs));
        p.setProperty("mail.debug", "false");
        p.setProperty("mail.debug.auth", "false");
        return p;
    }

    public Outcome send(String email, String subject, String text) {
        requireAvailable();
        if (!validAddress(email) || subject == null || text == null) return Outcome.DEFINITELY_NOT_SENT;
        Session session = Session.getInstance(properties());
        session.setDebug(false);
        Transport transport = null;
        boolean sending = false;
        try {
            MimeMessage mail = new MimeMessage(session);
            mail.setFrom(new InternetAddress(settings.from));
            mail.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(email));
            mail.setSubject(subject, "UTF-8");
            mail.setText(text, "UTF-8");
            mail.saveChanges();
            transport = session.getTransport("smtp");
            transport.connect(settings.host, settings.port, settings.username, settings.password);
            sending = true;
            transport.sendMessage(mail, mail.getAllRecipients());
            // sendMessage returned after final positive DATA response. QUIT is not acceptance.
            return Outcome.ACCEPTED;
        } catch (AuthenticationFailedException e) {
            return sending ? Outcome.UNKNOWN : Outcome.DEFINITELY_NOT_SENT;
        } catch (MessagingException e) {
            return sending && explicitRejection(e) ? Outcome.DEFINITELY_NOT_SENT : Outcome.UNKNOWN;
        } catch (RuntimeException e) {
            return Outcome.UNKNOWN;
        } finally {
            if (transport != null) {
                try { transport.close(); } catch (MessagingException | RuntimeException ignored) { }
            }
        }
    }

    private static boolean explicitRejection(MessagingException failure) {
        if (failure instanceof SendFailedException sent && sent.getValidSentAddresses() != null
                && sent.getValidSentAddresses().length > 0) return false;
        Exception current = failure;
        for (int i = 0; i < 10 && current instanceof MessagingException next; i++) {
            int code = current instanceof SMTPSendFailedException smtp ? smtp.getReturnCode()
                    : current instanceof SMTPAddressFailedException address ? address.getReturnCode() : 0;
            if (code >= 400 && code <= 599) return true;
            current = next.getNextException();
        }
        return false;
    }

    @Override public String toString() { return "SmtpMailSender[redacted]"; }

    static class Settings {
        final boolean enabled;
        final String host, username, password, from;
        final int port, timeoutMs;
        Settings(boolean enabled, String host, int port, String username, String password, String from, int timeoutMs) {
            this.enabled = enabled; this.host = host; this.port = port; this.username = username;
            this.password = password; this.from = from; this.timeoutMs = timeoutMs;
        }
        @Override public String toString() { return "SmtpSettings[redacted]"; }
    }
}

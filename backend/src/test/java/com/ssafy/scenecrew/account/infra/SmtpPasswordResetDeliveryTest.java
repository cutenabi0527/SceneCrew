package com.ssafy.scenecrew.account.infra;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.*;
import com.ssafy.scenecrew.account.service.PasswordResetDelivery;
import com.ssafy.scenecrew.account.service.RegistrationDelivery;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class SmtpPasswordResetDeliveryTest {
    @BeforeAll static void tls() throws Exception {
        SmtpRegistrationDeliveryTest.temporary=java.nio.file.Files.createTempDirectory("reset-smtp-");
        SmtpRegistrationDeliveryTest.certificates();
    }
    @AfterAll static void restore() { SmtpRegistrationDeliveryTest.restoreTls(); }
    @ParameterizedTest @EnumSource(SmtpRegistrationDeliveryTest.Mode.class)
    void resetMailUsesSameTlsAndOutcomeClassificationWithoutSignupCodeLogic(
            SmtpRegistrationDeliveryTest.Mode mode,CapturedOutput output) throws Exception {
        String proof=java.util.UUID.randomUUID()+"."+"A".repeat(43);
        var expiry=Instant.now().plusSeconds(600);
        try(var server=new SmtpRegistrationDeliveryTest.FakeSmtp(mode)) {
            var adapter=new SmtpPasswordResetDelivery(new SmtpMailSender.Settings(true,"localhost",server.port(),
                    "sender@example.invalid","synthetic-app-password","sender@example.invalid",1000));
            var expected=switch(mode) {
                case ACCEPT -> RegistrationDelivery.Outcome.ACCEPTED;
                case RCPT_REJECT,DATA_REJECT,AUTH_REJECT -> RegistrationDelivery.Outcome.DEFINITELY_NOT_SENT;
                default -> RegistrationDelivery.Outcome.UNKNOWN;
            };
            assertThat(adapter.accept(new PasswordResetDelivery.Message("recipient@example.invalid",proof,expiry))).isEqualTo(expected);
            assertThat(server.connections).isEqualTo(1); assertThat(server.dataCount).isLessThanOrEqualTo(1);
            if(mode==SmtpRegistrationDeliveryTest.Mode.ACCEPT) {
                var message=new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()),
                        new ByteArrayInputStream(server.body.getBytes(StandardCharsets.UTF_8)));
                assertThat(message.getSubject()).isEqualTo("SCENE CREW 비밀번호 재설정");
                assertThat(message.getContent().toString()).contains(proof,expiry.toString()).doesNotContain("이메일 인증번호");
            }
            assertThat(output.getAll()).doesNotContain(proof,"synthetic-app-password");
        }
    }
}

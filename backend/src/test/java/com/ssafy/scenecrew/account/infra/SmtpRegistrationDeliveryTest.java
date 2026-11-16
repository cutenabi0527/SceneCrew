package com.ssafy.scenecrew.account.infra;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import com.ssafy.scenecrew.account.config.RegistrationMailConfiguration;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.account.service.RegistrationDelivery;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class SmtpRegistrationDeliveryTest {
    @TempDir static Path temporary;
    static SSLContext previous, tls;
    static final String PASSWORD = "synthetic-app-password";
    static final String CODE = "012345";
    static final Instant EXPIRY = Instant.parse("2026-11-02T12:10:00Z");

    @BeforeAll static void certificates() throws Exception {
        Path store = temporary.resolve("fake-smtp.p12");
        String tool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var process = new ProcessBuilder(tool, "-genkeypair", "-alias", "fake-smtp", "-keyalg", "RSA",
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "synthetic-store",
                "-keypass", "synthetic-store", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-validity", "1", "-noprompt").redirectErrorStream(true).start();
        process.getInputStream().transferTo(OutputStream.nullOutputStream());
        assertThat(process.waitFor()).isZero();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "synthetic-store".toCharArray()); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "synthetic-store".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        previous = SSLContext.getDefault();
        SSLContext.setDefault(tls);
    }
    @AfterAll static void restoreTls() { if (previous != null) SSLContext.setDefault(previous); }

    RegistrationDelivery.Message message() {
        return new RegistrationDelivery.Message(UUID.randomUUID(), "recipient@example.invalid", CODE, EXPIRY);
    }
    SmtpRegistrationDelivery adapter(int port) {
        return new SmtpRegistrationDelivery(new SmtpRegistrationDelivery.Settings(
                true, "localhost", port, "sender@example.invalid", PASSWORD, "sender@example.invalid", 1000));
    }

    enum Mode { ACCEPT, RCPT_REJECT, DATA_REJECT, AUTH_REJECT, TIMEOUT, DISCONNECT, NO_TLS }

    @ParameterizedTest @EnumSource(Mode.class)
    void actualSmtpProtocolClassifiesOneAttemptWithoutLeaking(Mode mode, CapturedOutput output) throws Exception {
        StringBuilder logs = new StringBuilder();
        Logger protocol = Logger.getLogger("org.eclipse.angus.mail.smtp.protocol");
        protocol.setLevel(Level.ALL);
        Handler capture = new Handler() {
            public void publish(LogRecord r) { logs.append(r.getMessage()); }
            public void flush() { }
            public void close() { }
        };
        protocol.addHandler(capture);
        try (FakeSmtp server = new FakeSmtp(mode)) {
            SmtpRegistrationDelivery delivery = adapter(server.port());
            var expected = switch (mode) {
                case ACCEPT -> RegistrationDelivery.Outcome.ACCEPTED;
                case RCPT_REJECT, DATA_REJECT, AUTH_REJECT -> RegistrationDelivery.Outcome.DEFINITELY_NOT_SENT;
                default -> RegistrationDelivery.Outcome.UNKNOWN;
            };
            assertThat(delivery.accept(message())).isEqualTo(expected);
            assertThat(server.connections).isEqualTo(1);
            assertThat(server.dataCount).isLessThanOrEqualTo(1);
            if (mode == Mode.ACCEPT) {
                // Final DATA succeeds, then fake disconnects without QUIT reply.
                assertThat(server.dataCount).isEqualTo(1);
                assertThat(server.authenticated).isTrue();
                var mail = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()),
                        new ByteArrayInputStream(server.body.getBytes(StandardCharsets.UTF_8)));
                assertThat(mail.getAllRecipients()).hasSize(1);
                assertThat(mail.getContent().toString()).contains(CODE, EXPIRY.toString());
                assertThat(mail.getContent().toString()).doesNotContain(PASSWORD, message().requestId().toString());
            }
            if (mode == Mode.NO_TLS) {
                assertThat(server.authenticated).isFalse();
                assertThat(server.dataCount).isZero();
            }
            assertThat(output.getAll() + logs).doesNotContain(CODE, PASSWORD,
                    Base64.getEncoder().encodeToString(PASSWORD.getBytes(StandardCharsets.UTF_8)));
            assertThat(delivery.toString()).doesNotContain(PASSWORD);
            assertThat(message().toString()).doesNotContain(CODE);
        } finally { protocol.removeHandler(capture); }
    }

    @Test void missingOrDisabledSettingsFailClosedBeforeConnecting() {
        for (String missing : List.of("enabled", "username", "password", "from")) {
            var env = new MockEnvironment().withProperty("scenecrew.auth.mail.smtp.enabled", "true")
                    .withProperty("scenecrew.auth.mail.smtp.username", "sender@example.invalid")
                    .withProperty("scenecrew.auth.mail.smtp.password", PASSWORD)
                    .withProperty("scenecrew.auth.mail.smtp.from", "sender@example.invalid");
            env.setProperty("scenecrew.auth.mail.smtp." + missing, missing.equals("enabled") ? "false" : "");
            var delivery = new SmtpRegistrationDelivery(env);
            assertThatThrownBy(delivery::requireAvailable).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                    .hasMessageNotContaining(PASSWORD);
            assertThatThrownBy(() -> delivery.accept(message())).isInstanceOf(BusinessException.class);
        }
    }

    @Test void configurationRequiresBothExplicitSwitchesAndDoesNotReplaceExistingPort() {
        var runner = new ApplicationContextRunner().withUserConfiguration(RegistrationMailConfiguration.class);
        runner.run(c -> assertThat(c).doesNotHaveBean(RegistrationDelivery.class));
        runner.withPropertyValues("scenecrew.auth.public-enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(RegistrationDelivery.class));
        runner.withPropertyValues("scenecrew.auth.mail.smtp.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(RegistrationDelivery.class));
        var enabled = runner.withPropertyValues("scenecrew.auth.public-enabled=true",
                "scenecrew.auth.mail.smtp.enabled=true");
        enabled.run(c -> {
            assertThat(c).hasSingleBean(RegistrationDelivery.class);
            assertThatThrownBy(c.getBean(RegistrationDelivery.class)::requireAvailable)
                    .isInstanceOf(BusinessException.class);
        });
        RegistrationDelivery fake = RegistrationDelivery.unavailable();
        enabled.withBean("existingDelivery", RegistrationDelivery.class, () -> fake)
                .run(c -> assertThat(c.getBean(RegistrationDelivery.class)).isSameAs(fake));
    }

    /** Loopback SMTP fixture with real STARTTLS and synthetic credentials, never Gmail. */
    static final class FakeSmtp implements AutoCloseable {
        final ServerSocket listener;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CountDownLatch release = new CountDownLatch(1);
        final Future<?> worker;
        volatile Socket socket;
        volatile int connections, dataCount;
        volatile boolean authenticated;
        volatile String body = "";
        BufferedReader reader;
        BufferedWriter writer;
        FakeSmtp(Mode mode) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            worker = executor.submit(() -> {
                try {
                    socket = listener.accept(); connections++;
                    socket.setSoTimeout(5000);
                    streams(); reply("220 fake SMTP");
                    boolean encrypted = false;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("EHLO")) {
                            reply("250-localhost");
                            if (!encrypted && mode != Mode.NO_TLS) reply("250-STARTTLS");
                            reply(encrypted ? "250 AUTH LOGIN" : "250 OK");
                        } else if (line.equals("STARTTLS")) {
                            reply("220 Ready");
                            SSLSocket secure = (SSLSocket) tls.getSocketFactory()
                                    .createSocket(socket, "localhost", socket.getPort(), true);
                            secure.setUseClientMode(false); secure.startHandshake();
                            socket = secure; streams(); encrypted = true;
                        } else if (line.startsWith("AUTH LOGIN")) {
                            reply("334 VXNlcm5hbWU6"); reader.readLine();
                            reply("334 UGFzc3dvcmQ6"); reader.readLine();
                            authenticated = true;
                            reply(mode == Mode.AUTH_REJECT ? "535 rejected " + PASSWORD : "235 authenticated");
                        } else if (line.startsWith("MAIL FROM")) reply("250 OK");
                        else if (line.startsWith("RCPT TO")) reply(mode == Mode.RCPT_REJECT ? "550 rejected" : "250 OK");
                        else if (line.equals("DATA")) {
                            reply("354 Continue");
                            StringBuilder received = new StringBuilder();
                            while ((line = reader.readLine()) != null && !line.equals(".")) received.append(line).append("\r\n");
                            body = received.toString(); dataCount++;
                            if (mode == Mode.TIMEOUT) { release.await(5, TimeUnit.SECONDS); break; }
                            if (mode == Mode.DISCONNECT) break;
                            reply(mode == Mode.DATA_REJECT ? "554 rejected " + CODE : "250 accepted");
                            if (mode == Mode.ACCEPT) break;
                        } else if (line.equals("RSET")) reply("250 OK");
                        else if (line.equals("QUIT")) break;
                        else reply("500 Unsupported");
                    }
                } catch (Exception e) { throw new RuntimeException("Fake SMTP protocol failed", e); }
                finally { try { if (socket != null) socket.close(); } catch (IOException ignored) { } }
            });
        }
        int port() { return listener.getLocalPort(); }
        void streams() throws IOException {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }
        void reply(String text) throws IOException { writer.write(text + "\r\n"); writer.flush(); }
        @Override public void close() throws Exception {
            release.countDown();
            try { worker.get(6, TimeUnit.SECONDS); }
            finally {
                listener.close(); if (socket != null) socket.close();
                executor.shutdownNow();
            }
        }
    }
}

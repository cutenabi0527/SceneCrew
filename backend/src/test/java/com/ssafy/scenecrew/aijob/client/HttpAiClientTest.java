package com.ssafy.scenecrew.aijob.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** JDK 내장 HTTP 서버를 가짜 AI 서버로 띄워 요청 형식·응답 전달·시간 초과를 확인한다. */
class HttpAiClientTest {
    private HttpServer server;
    private final Map<String, String> received = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/echo", exchange -> {
            received.put("method", exchange.getRequestMethod());
            received.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
            received.put("contentType", exchange.getRequestHeaders().getFirst("Content-Type"));
            received.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 503, "{\"error\":{\"code\":\"AI_UNAVAILABLE\"}}");
        });
        server.createContext("/internal/slow", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}");
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsBearerTokenAndJsonAndReturnsErrorStatusAsResponse() throws Exception {
        AiResponse response = client(baseUrl() + "/").post("/internal/echo", "{\"text\":\"한글\"}", Duration.ofSeconds(5));

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body()).contains("AI_UNAVAILABLE");
        assertThat(received).containsEntry("method", "POST")
                .containsEntry("authorization", "Bearer test-token")
                .containsEntry("contentType", "application/json")
                .containsEntry("body", "{\"text\":\"한글\"}");
    }

    @Test
    void noAnswerWithinTimeoutIsTimeout() {
        assertThatThrownBy(() -> client(baseUrl()).post("/internal/slow", "{}", Duration.ofMillis(200)))
                .isInstanceOfSatisfying(AiTransportException.class, e -> assertThat(e.isTimeout()).isTrue());
    }

    @Test
    void refusedConnectionIsNotTimeout() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> client("http://127.0.0.1:" + closedPort).post("/internal/echo", "{}", Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(AiTransportException.class, e -> assertThat(e.isTimeout()).isFalse());
    }

    @Test
    void missingSettingsAreRejectedBeforeCalling() {
        HttpAiClient client = new HttpAiClient(new AiServerProperties("", "", Duration.ofSeconds(1)));

        assertThatThrownBy(() -> client.post("/internal/echo", "{}", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(received).isEmpty();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpAiClient client(String baseUrl) {
        return new HttpAiClient(new AiServerProperties(baseUrl, "test-token", Duration.ofSeconds(1)));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}

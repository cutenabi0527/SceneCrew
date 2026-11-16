package com.ssafy.scenecrew.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import com.ssafy.scenecrew.account.service.AccountAnonymizer;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.outbox.OutboxRelay;
import com.ssafy.scenecrew.notification.service.ChangeAcknowledgmentNotificationWriter;
import com.ssafy.scenecrew.notification.service.DeviceRegistration;
import com.ssafy.scenecrew.notification.service.PushSender;
import com.ssafy.scenecrew.notification.service.PushSessionEligibility;
import com.ssafy.scenecrew.support.TestRows;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeviceRegistrationHttpTest {
    static final String ORIGIN = "https://android.example.invalid";
    static final String PASSWORD = "SyntheticPass8!";
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("scenecrew.auth.public-enabled", () -> "true");
        registry.add("scenecrew.auth.signing-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("scenecrew.auth.failure-hmac-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("scenecrew.auth.issuer", () -> "device-test");
        registry.add("scenecrew.auth.audience", () -> "device-test");
        registry.add("scenecrew.auth.web-origins", () -> "https://web.example.invalid");
        registry.add("scenecrew.auth.android-origins", () -> ORIGIN);
        registry.add("scenecrew.cors.allowed-origins", () -> ORIGIN);
    }

    @AfterAll static void stop() { REDIS.stop(); }

    @LocalServerPort int port;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired AccountAnonymizer anonymizer;
    @Autowired SessionAuthenticator authenticator;
    @Autowired DeviceRegistration registration;
    @Autowired ChangeAcknowledgmentNotificationWriter notificationWriter;
    @Autowired TransactionTemplate transaction;
    @Autowired OutboxRelay relay;
    @MockitoBean PushSender push;
    @MockitoSpyBean PushSessionEligibility pushSessions;
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient http = HttpClient.newHttpClient();
    UUID first, second, event, anotherEvent, firstMember, secondMember, anotherMember, device;
    Session firstSession, secondSession;

    @BeforeEach void fixture() throws Exception {
        var rows = new TestRows(jdbc);
        first = rows.account();
        second = rows.account();
        event = rows.event("LIVE");
        anotherEvent = rows.event("RECRUITING");
        firstMember = rows.member(event, first, "WORKER", true);
        anotherMember = rows.member(anotherEvent, first, "WORKER", true);
        secondMember = rows.member(event, second, "WORKER", true);
        device = UUID.randomUUID();
        firstSession = login(first);
        secondSession = login(second);
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM outbox_event WHERE event_id=?", event);
        jdbc.update("DELETE FROM notification_delivery WHERE notification_id IN (SELECT id FROM notification WHERE event_id=?)", event);
        jdbc.update("DELETE FROM notification WHERE event_id=?", event);
        jdbc.update("DELETE FROM change_acknowledgment WHERE event_id=?", event);
        jdbc.update("DELETE FROM change_set WHERE event_id=?", event);
        jdbc.update("DELETE FROM device_installation WHERE account_id IN (?,?)", first, second);
        jdbc.update("DELETE FROM device_token WHERE member_id IN (?,?,?)", firstMember, secondMember, anotherMember);
        jdbc.update("DELETE FROM member WHERE event_id IN (?,?)", event, anotherEvent);
        jdbc.update("DELETE FROM event WHERE id IN (?,?)", event, anotherEvent);
        jdbc.update("DELETE FROM auth_identity WHERE account_id IN (?,?)", first, second);
        jdbc.update("DELETE FROM account WHERE id IN (?,?)", first, second);
    }

    @Test void registersAcrossActiveEventsAndRefreshesTokenAndParticipation() throws Exception {
        var registered = send(put(firstSession, device, "token-a", proof(1)));
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(200);
        assertThat(json.readTree(registered.body()).propertyNames()).containsExactlyInAnyOrder("deviceId", "registered");
        assertThat(json.readTree(registered.body()).path("deviceId").asString()).isEqualTo(device.toString());
        assertThat(activeMembers()).containsExactlyInAnyOrder(firstMember, anotherMember);
        assertThat(jdbc.queryForObject("SELECT octet_length(proof_hash) FROM device_installation WHERE device_id=?",
                Integer.class, device)).isEqualTo(32);

        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE member SET participation_revision=2 WHERE id=?", firstMember);
        assertThat(jdbc.queryForObject("SELECT participation_revision FROM device_token WHERE member_id=?",
                Long.class, firstMember)).isEqualTo(1L);
        assertThat(send(put(firstSession, device, "token-b", proof(1))).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT participation_revision FROM device_token WHERE member_id=?",
                Long.class, firstMember)).isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT fcm_token FROM device_token WHERE device_id=?", String.class,
                device.toString())).containsExactly("token-b", "token-b");
    }

    @Test void rejectsTakeoverAndUnregisterWithoutCorrectInstallationAndAccount() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        var wrong = send(put(secondSession, device, "token-b", proof(2)));
        assertThat(wrong.statusCode()).isEqualTo(403);
        assertThat(wrong.body()).doesNotContain("token-b", proof(2));
        assertThat(send(put(secondSession, UUID.randomUUID(), "token-a", proof(2))).statusCode()).isEqualTo(403);
        assertThat(send(request("/api/v1/me/devices/" + device).DELETE()).statusCode()).isEqualTo(401);
        assertThat(send(delete(firstSession, proof(2))).statusCode()).isEqualTo(403);
        assertThat(send(delete(secondSession, proof(1))).statusCode()).isEqualTo(403);
        assertThat(activeMembers()).containsExactlyInAnyOrder(firstMember, anotherMember);
        assertThat(send(delete(firstSession, proof(1))).statusCode()).isEqualTo(204);
        assertThat(send(delete(firstSession, proof(1))).statusCode()).isEqualTo(204);
        assertThat(activeMembers()).isEmpty();
    }

    @Test void sameInstallationCanSwitchAccountsButOnlyOneAccountRemainsActive() throws Exception {
        var one = http.sendAsync(put(firstSession, device, "same-token", proof(1)).build(),
                HttpResponse.BodyHandlers.ofString());
        var two = http.sendAsync(put(secondSession, device, "same-token", proof(1)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(one.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(two.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        UUID winner = jdbc.queryForObject("SELECT account_id FROM device_installation WHERE device_id=?", UUID.class, device);
        if (first.equals(winner)) assertThat(activeMembers()).containsExactlyInAnyOrder(firstMember, anotherMember);
        else {
            assertThat(winner).isEqualTo(second);
            assertThat(activeMembers()).containsExactly(secondMember);
        }
    }

    @Test void unregisterBeforeLogoutAndRejectTheRevokedAccessToken() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        assertThat(send(delete(firstSession, proof(1))).statusCode()).isEqualTo(204);
        assertThat(send(request("/api/v1/auth/logout").header("Content-Type", "application/json")
                .header("X-SceneCrew-Logout-Attempt", "AAAAAAAAAAAAAAAAAAAAAA")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        java.util.Map.of("refreshToken", firstSession.refresh()))))).statusCode()).isEqualTo(204);
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(401);
        assertThat(send(put(secondSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        assertThat(activeMembers()).containsExactly(secondMember);
    }

    @Test void consentOnDoesNotReactivateARegistrationUntilExplicitPut() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        assertThat(send(consent(firstSession, false)).statusCode()).isEqualTo(200);
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        assertThat(activeMembers()).isEmpty();
        assertThat(send(consent(firstSession, true)).statusCode()).isEqualTo(200);
        assertThat(activeMembers()).isEmpty();
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        assertThat(activeMembers()).containsExactlyInAnyOrder(firstMember, anotherMember);
    }

    @Test void switchingToAnAccountWithoutConsentStillRevokesPreviousAccountLinks() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        assertThat(send(consent(secondSession, false)).statusCode()).isEqualTo(200);

        assertThat(send(put(secondSession, device, "not-consented-token", proof(1))).statusCode()).isEqualTo(200);

        assertThat(activeMembers()).isEmpty();
        assertThat(jdbc.queryForList("SELECT fcm_token FROM device_token WHERE member_id=?", String.class, secondMember))
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT account_id FROM device_installation WHERE device_id=?", UUID.class, device))
                .isEqualTo(second);
    }

    @Test void anonymizationRemovesOwnedInstallationsAndPreservesTransferredInstallations() throws Exception {
        UUID another = UUID.randomUUID();
        assertThat(send(put(firstSession, another, "owned-token", proof(2))).statusCode()).isEqualTo(200);
        assertThat(send(put(firstSession, device, "switch-token", proof(1))).statusCode()).isEqualTo(200);
        assertThat(send(put(secondSession, device, "switch-token", proof(1))).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE account SET status='DISABLED',deactivated_at=now() WHERE id=?", first);

        anonymizer.processPending();

        assertThat(jdbc.queryForList("SELECT device_id FROM device_installation WHERE account_id=?", UUID.class, first))
                .isEmpty();
        assertThat(activeMembers()).containsExactly(secondMember);
        assertThat(jdbc.queryForList("SELECT id FROM device_token WHERE member_id IN (?,?)", UUID.class,
                firstMember, anotherMember)).isEmpty();
    }

    @Test void androidCorsAllowsDeviceProofOnUnregister() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/api/v1/me/devices/" + device))
                .header("Origin", ORIGIN).header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "authorization,x-scenecrew-client,x-device-proof")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse(""))
                .containsIgnoringCase("x-device-proof");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void logoutBlocksNewPushWhenDeleteIsSkippedOrRejected(boolean rejectedDelete) throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        if (rejectedDelete) {
            assertThat(send(delete(firstSession, proof(2))).statusCode()).isEqualTo(403);
        }
        assertThat(logout(firstSession).statusCode()).isEqualTo(204);
        assertThat(send(delete(firstSession, proof(1))).statusCode()).isEqualTo(401);

        UUID notification = approveFor(firstMember);
        relay.relayOnce();

        org.mockito.Mockito.verifyNoInteractions(push);
        assertThat(jdbc.queryForObject("SELECT state FROM notification_delivery WHERE notification_id=?",
                String.class, notification)).isEqualTo("CANCELED");
    }

    @Test void logoutLeavesOtherAndroidInstallationAndWebSessionUsable() throws Exception {
        Session independent = startAndroidSession(first);
        String webCookie = loginWeb(first);
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        assertThat(send(put(independent, UUID.randomUUID(), "token-independent", proof(2))).statusCode()).isEqualTo(200);
        assertThat(logout(firstSession).statusCode()).isEqualTo(204);
        UUID notification = approveFor(firstMember);
        stubSend("token-independent", notification);

        relay.relayOnce();

        org.mockito.Mockito.verify(push).send("token-independent", notification, event);
        org.mockito.Mockito.verifyNoMoreInteractions(push);
        assertThat(send(request("/api/v1/me").header("Authorization", "Bearer " + independent.access()).GET())
                .statusCode()).isEqualTo(200);
        assertThat(send(HttpRequest.newBuilder(uri("/api/v1/me")).header("Cookie", webCookie).GET())
                .statusCode()).isEqualTo(200);
    }

    @Test void lateLogoutDoesNotRevokeTheTransferredInstallation() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        assertThat(send(put(secondSession, device, "token-b", proof(1))).statusCode()).isEqualTo(200);
        assertThat(logout(firstSession).statusCode()).isEqualTo(204);
        UUID notification = approveFor(secondMember);
        stubSend("token-b", notification);

        relay.relayOnce();

        org.mockito.Mockito.verify(push).send("token-b", notification, event);
        org.mockito.Mockito.verifyNoMoreInteractions(push);
        assertThat(activeMembers()).containsExactly(secondMember);
    }

    @Test void refreshPreservesTheBindingAndLogoutWithRotatedProofRevokesIt() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        var response = send(request("/api/v1/auth/refresh").header("Content-Type", "application/json")
                .header("X-SceneCrew-Refresh-Attempt", "BBBBBBBBBBBBBBBBBBBBBB")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        java.util.Map.of("refreshToken", firstSession.refresh())))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Session rotated = sessionFrom(response);
        assertThat(rotated.refresh()).isNotEqualTo(firstSession.refresh());
        UUID beforeLogout = approveFor(firstMember);
        stubSend("token-a", beforeLogout);
        relay.relayOnce();
        org.mockito.Mockito.verify(push).send("token-a", beforeLogout, event);

        assertThat(logout(rotated).statusCode()).isEqualTo(204);
        UUID afterLogout = approveFor(firstMember);
        relay.relayOnce();

        org.mockito.Mockito.verifyNoMoreInteractions(push);
        assertThat(jdbc.queryForObject("SELECT state FROM notification_delivery WHERE notification_id=?",
                String.class, afterLogout)).isEqualTo("CANCELED");
    }

    @Test void queuedPushDoesNotSendAfterConsentWithdrawalCommits() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        UUID notification = approveFor(firstMember);
        stubSend("token-a", notification);
        UUID sid = UUID.fromString(firstSession.refresh().substring(0, 36));
        var redisChecked = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            boolean current = (boolean) invocation.callRealMethod();
            assertThat(current).isTrue();
            redisChecked.countDown();
            assertThat(resume.await(15, TimeUnit.SECONDS)).isTrue();
            return current;
        }).when(pushSessions).isCurrent(sid, first, 0L);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var dispatch = executor.submit(relay::relayOnce);
            try {
                assertThat(redisChecked.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(send(consent(firstSession, false)).statusCode()).isEqualTo(200);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM device_token WHERE member_id=? AND active", Long.class, firstMember)).isZero();
            } finally {
                resume.countDown();
            }
            dispatch.get(15, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject("SELECT state FROM notification_delivery WHERE notification_id=?",
                String.class, notification)).isEqualTo("CANCELED");
        org.mockito.Mockito.verifyNoInteractions(push);
    }

    @Test void claimedPushDoesNotUseOldRegistrationAfterConsentIsRestored() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        UUID notification = approveFor(firstMember);
        stubSend("token-a", notification);
        UUID sid = UUID.fromString(firstSession.refresh().substring(0, 36));
        var firstCheck = new AtomicBoolean(true);
        var redisChecked = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            boolean current = (boolean) invocation.callRealMethod();
            if (firstCheck.getAndSet(false)) {
                assertThat(current).isTrue();
                redisChecked.countDown();
                assertThat(resume.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return current;
        }).when(pushSessions).isCurrent(sid, first, 0L);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var dispatch = executor.submit(relay::relayOnce);
            try {
                assertThat(redisChecked.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(send(consent(firstSession, false)).statusCode()).isEqualTo(200);
                assertThat(send(consent(firstSession, true)).statusCode()).isEqualTo(200);
                assertThat(send(put(firstSession, device, "token-new", proof(1))).statusCode()).isEqualTo(200);
            } finally {
                resume.countDown();
            }
            dispatch.get(15, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject("SELECT state FROM notification_delivery WHERE notification_id=?",
                String.class, notification)).isEqualTo("CANCELED");
        org.mockito.Mockito.verifyNoInteractions(push);

        UUID newNotification = approveFor(firstMember);
        stubSend("token-new", newNotification);
        relay.relayOnce();
        org.mockito.Mockito.verify(push).send("token-new", newNotification, event);
    }

    @Test void finalDatabaseCheckFailureDoesNotSendAndUsesRetryLimit() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        UUID notification = approveFor(firstMember);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("fixture outage"))
                .when(jdbc).queryForList(org.mockito.ArgumentMatchers.startsWith("SELECT EXISTS (SELECT 1 "),
                        org.mockito.ArgumentMatchers.eq(Boolean.class),
                        org.mockito.ArgumentMatchers.any(Object[].class));

        for (int attempt = 1; attempt <= 3; attempt++) {
            relay.relayOnce();
            assertThat(jdbc.queryForMap("SELECT state,attempt FROM notification_delivery WHERE notification_id=?",
                    notification)).containsEntry("state", attempt < 3 ? "PENDING" : "FAILED")
                    .containsEntry("attempt", (long) attempt);
            jdbc.update("UPDATE outbox_event SET available_at=? WHERE event_id=?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(60)), event);
        }

        relay.relayOnce();
        org.mockito.Mockito.verifyNoInteractions(push);
    }

    @Test void queuedPushChecksTheSessionAfterLogoutBeforeCallingTheSender() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        UUID notification = approveFor(firstMember);
        UUID sid = UUID.fromString(firstSession.refresh().substring(0, 36));
        var claimed = new CountDownLatch(1);
        var checkRedis = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            claimed.countDown();
            assertThat(checkRedis.await(15, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(pushSessions).isCurrent(sid, first, 0L);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var dispatch = executor.submit(relay::relayOnce);
            try {
                assertThat(claimed.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(logout(firstSession).statusCode()).isEqualTo(204);
            } finally {
                checkRedis.countDown();
            }
            dispatch.get(15, TimeUnit.SECONDS);
        }

        org.mockito.Mockito.verifyNoInteractions(push);
        assertThat(jdbc.queryForObject("SELECT state FROM notification_delivery WHERE notification_id=?",
                String.class, notification)).isEqualTo("CANCELED");
    }

    @Test void redisReadFailureDoesNotSendAndRetryRechecksRevocation() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        UUID notification = approveFor(firstMember);
        UUID sid = UUID.fromString(firstSession.refresh().substring(0, 36));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("fixture outage"))
                .when(pushSessions).isCurrent(sid, first, 0L);

        relay.relayOnce();

        org.mockito.Mockito.verifyNoInteractions(push);
        assertThat(jdbc.queryForObject("SELECT state FROM notification_delivery WHERE notification_id=?",
                String.class, notification)).isEqualTo("PENDING");
        org.mockito.Mockito.doCallRealMethod().when(pushSessions).isCurrent(sid, first, 0L);
        assertThat(logout(firstSession).statusCode()).isEqualTo(204);
        jdbc.update("UPDATE outbox_event SET available_at=? WHERE event_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), event);
        relay.relayOnce();
        org.mockito.Mockito.verifyNoInteractions(push);
        assertThat(jdbc.queryForObject("SELECT state FROM notification_delivery WHERE notification_id=?",
                String.class, notification)).isEqualTo("CANCELED");
    }

    @Test void legacyInstallationIsExcludedUntilExplicitRegistration() throws Exception {
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE device_installation SET push_session_id=NULL WHERE device_id=?", device);
        UUID legacyNotification = approveFor(firstMember);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery WHERE notification_id=?",
                Long.class, legacyNotification)).isZero();
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        UUID currentNotification = approveFor(firstMember);
        stubSend("token-a", currentNotification);
        relay.relayOnce();
        org.mockito.Mockito.verify(push).send("token-a", currentNotification, event);
        org.mockito.Mockito.verifyNoMoreInteractions(push);
    }

    @Test void redisReadFailureDuringRegistrationRollsBackInstallationWithoutRevokingLogin() throws Exception {
        UUID sid = UUID.fromString(firstSession.refresh().substring(0, 36));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("fixture outage"))
                .when(pushSessions).isCurrent(sid, first, 0L);

        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_installation WHERE device_id=?", Long.class, device))
                .isZero();
        assertThat(send(request("/api/v1/me").header("Authorization", "Bearer " + firstSession.access()).GET())
                .statusCode()).isEqualTo(200);

        org.mockito.Mockito.doCallRealMethod().when(pushSessions).isCurrent(sid, first, 0L);
        assertThat(send(put(firstSession, device, "token-a", proof(1))).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT push_session_id FROM device_installation WHERE device_id=?",
                UUID.class, device)).isEqualTo(sid);
    }

    @Test void registrationAuthenticatedBeforeLogoutCannotReplaceAnInstallationAfterLogout() throws Exception {
        assertThat(send(put(secondSession, device, "token-b", proof(1))).statusCode()).isEqualTo(200);
        var authenticated = new CountDownLatch(1);
        var continueRegistration = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var registrationRequest = executor.submit(() -> {
                var principal = authenticator.authenticate(firstSession.access());
                authenticated.countDown();
                assertThat(continueRegistration.await(15, TimeUnit.SECONDS)).isTrue();
                registration.register(principal, device, proof(1), "token-a");
                return null;
            });
            try {
                assertThat(authenticated.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(logout(firstSession).statusCode()).isEqualTo(204);
            } finally {
                continueRegistration.countDown();
            }
            assertThatThrownBy(() -> registrationRequest.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class)
                    .cause().extracting(cause -> ((BusinessException) cause).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHENTICATED);
        }
        assertThat(activeMembers()).containsExactly(secondMember);
    }

    private void stubSend(String token, UUID notification) {
        org.mockito.Mockito.when(push.send(token, notification, event))
                .thenReturn(new PushSender.Outcome(PushSender.Status.SENT, "synthetic-message"));
    }

    private UUID approveFor(UUID member) {
        UUID change = UUID.randomUUID();
        UUID ack = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO change_set (id,event_id,mode,status,revision,base_revision,operations,rationale,applied_at)
                VALUES (?,?,'MANUAL','APPLIED',1,1,'[]','fixture',now())
                """, change, event);
        jdbc.update("""
                INSERT INTO change_acknowledgment (id,event_id,change_set_id,member_id,participation_revision,targets,targets_hash)
                VALUES (?,?,?,?,1,'[]','fixture-hash')
                """, ack, event, change, member);
        return transaction.execute(status -> notificationWriter.write(
                new ChangeAcknowledgmentNotificationWriter.Input(event, change, ack, member, 1, Instant.now())))
                .notificationId();
    }

    private HttpResponse<String> logout(Session session) throws Exception {
        return send(request("/api/v1/auth/logout").header("Content-Type", "application/json")
                .header("X-SceneCrew-Logout-Attempt", "AAAAAAAAAAAAAAAAAAAAAA")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        java.util.Map.of("refreshToken", session.refresh())))));
    }

    private List<UUID> activeMembers() {
        return jdbc.queryForList("SELECT member_id FROM device_token WHERE device_id=? AND active",
                UUID.class, device.toString());
    }

    private Session login(UUID account) throws Exception {
        String email = account + "@example.invalid";
        jdbc.update("UPDATE account SET consent='{\"push\":true}'::jsonb WHERE id=?", account);
        jdbc.update("""
                INSERT INTO auth_identity(id,account_id,provider,subject,password_hash,email,email_verified)
                VALUES (?,?,'LOCAL',?,?,?,true)
                """, UUID.randomUUID(), account, account.toString(), new BCryptPasswordEncoder(4).encode(PASSWORD), email);
        return startAndroidSession(account);
    }

    private Session startAndroidSession(UUID account) throws Exception {
        var response = send(request("/api/v1/auth/login").header("Content-Type", "application/json")
                .POST(loginBody(account)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return sessionFrom(response);
    }

    private String loginWeb(UUID account) throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Origin", "https://web.example.invalid").header("X-SceneCrew-Client", "WEB")
                .header("X-SceneCrew-CSRF", "1").header("Content-Type", "application/json").POST(loginBody(account)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return String.join("; ", response.headers().allValues("Set-Cookie").stream()
                .map(cookie -> cookie.split(";", 2)[0]).toList());
    }

    private HttpRequest.BodyPublisher loginBody(UUID account) {
        return HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                java.util.Map.of("email", account + "@example.invalid", "password", PASSWORD)));
    }

    private Session sessionFrom(HttpResponse<String> response) {
        var body = json.readTree(response.body());
        return new Session(body.path("accessToken").asString(), body.path("refreshToken").asString());
    }

    private HttpRequest.Builder put(Session session, UUID id, String token, String proof) {
        return request("/api/v1/me/devices/" + id).header("Authorization", "Bearer " + session.access())
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(java.util.Map.of("token", token, "deviceProof", proof))));
    }

    private HttpRequest.Builder delete(Session session, String proof) {
        return request("/api/v1/me/devices/" + device).header("Authorization", "Bearer " + session.access())
                .header("X-Device-Proof", proof).DELETE();
    }

    private HttpRequest.Builder consent(Session session, boolean value) {
        return request("/api/v1/me/consents").header("Authorization", "Bearer " + session.access())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"push\":" + value + "}"));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(uri(path)).header("Origin", ORIGIN).header("X-SceneCrew-Client", "ANDROID");
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String proof(int seed) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record Session(String access, String refresh) { }
}

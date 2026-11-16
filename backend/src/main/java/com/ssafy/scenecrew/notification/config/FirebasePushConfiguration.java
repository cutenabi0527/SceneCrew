package com.ssafy.scenecrew.notification.config;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ssafy.scenecrew.notification.service.PushSender;

@Configuration(proxyBeanMethods = false)
class FirebasePushConfiguration {
    // 휴대폰 알림 영역에 보일 고정 문구(#307). 변경 확인·보고 알림 모두 같은 문구다. 잠금 화면에 보일 수 있어
    // 업무명·이름·보고 내용 같은 개인정보는 넣지 않는다. 앱은 data의 notificationId로 서버 목록을 다시 읽어 연다.
    static final String TITLE = "SceneCrew";
    static final String BODY = "새 알림이 있습니다. 눌러서 확인해 주세요.";

    /** data만 보내면 앱이 백그라운드·종료 상태일 때 휴대폰에 알림이 뜨지 않는다. 표시용 notification을 함께 보낸다. */
    static Message message(String token, UUID notificationId, UUID eventId) {
        return Message.builder().setToken(token)
                .setNotification(Notification.builder().setTitle(TITLE).setBody(BODY).build())
                .putAllData(Map.of("notificationId", notificationId.toString(), "eventId", eventId.toString()))
                .build();
    }

    @Bean(destroyMethod = "delete")
    @ConditionalOnProperty(name = "scenecrew.push.enabled", havingValue = "true")
    FirebaseApp pushFirebaseApp() {
        try {
            var options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .setProjectId("scenecrew-dc000")
                    .setConnectTimeout(4000)
                    .setReadTimeout(5000)
                    .setWriteTimeout(4000)
                    .build();
            return FirebaseApp.initializeApp(options, "scenecrew-push");
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("FCM 인증을 초기화할 수 없습니다. 서버 ADC 설정을 확인하세요.");
        }
    }

    @Bean
    @ConditionalOnProperty(name = "scenecrew.push.enabled", havingValue = "true")
    PushSender firebasePushSender(FirebaseApp pushFirebaseApp) {
        var messaging = FirebaseMessaging.getInstance(pushFirebaseApp);
        return (token, notificationId, eventId) -> {
            try {
                return new PushSender.Outcome(PushSender.Status.SENT,
                        messaging.send(message(token, notificationId, eventId)));
            } catch (FirebaseMessagingException failure) {
                var code = failure.getMessagingErrorCode();
                if (code == MessagingErrorCode.UNREGISTERED) {
                    return PushSender.Outcome.of(PushSender.Status.UNREGISTERED);
                }
                if (code == MessagingErrorCode.UNAVAILABLE || code == MessagingErrorCode.INTERNAL
                        || code == MessagingErrorCode.QUOTA_EXCEEDED || isTransient(failure)) {
                    return PushSender.Outcome.of(PushSender.Status.RETRY);
                }
                LoggerFactory.getLogger(FirebasePushConfiguration.class)
                        .warn("FCM 요청을 처리할 수 없습니다. code={}", failure.getErrorCode());
                return PushSender.Outcome.of(PushSender.Status.FAILED);
            }
        };
    }

    private static boolean isTransient(FirebaseMessagingException failure) {
        return switch (failure.getErrorCode()) {
            case UNAVAILABLE, INTERNAL, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED -> true;
            default -> false;
        };
    }
}

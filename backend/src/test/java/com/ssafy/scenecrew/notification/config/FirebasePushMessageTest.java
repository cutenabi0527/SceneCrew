package com.ssafy.scenecrew.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.firebase.messaging.Message;

/** FCM 메시지 모양(#307). SDK가 읽기 메서드를 두지 않아 직렬화되는 필드를 리플렉션으로 확인한다. */
class FirebasePushMessageTest {
    @Test
    void showsFixedNotificationAndKeepsTargetData() throws ReflectiveOperationException {
        UUID notificationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Message message = FirebasePushConfiguration.message("token-1", notificationId, eventId);

        // 백그라운드·종료 상태에서도 휴대폰에 보이도록 표시용 블록이 있다. 개인정보 없는 고정 문구다.
        Object notification = field(message, "notification");
        assertThat(notification).isNotNull();
        assertThat(field(notification, "title")).isEqualTo("SceneCrew");
        assertThat(field(notification, "body")).isEqualTo("새 알림이 있습니다. 눌러서 확인해 주세요.");
        // 앱이 누른 알림을 찾는 데 쓰는 data는 그대로다
        assertThat(field(message, "data")).isEqualTo(Map.of(
                "notificationId", notificationId.toString(), "eventId", eventId.toString()));
        assertThat(field(message, "token")).isEqualTo("token-1");
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}

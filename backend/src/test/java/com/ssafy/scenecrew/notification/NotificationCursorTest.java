package com.ssafy.scenecrew.notification;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.notification.dto.NotificationView;
import com.ssafy.scenecrew.notification.service.NotificationCursor;

class NotificationCursorTest {
    final UUID account = UUID.randomUUID();
    final NotificationCursor codec = new NotificationCursor(new byte[32]);
    final NotificationView row = new NotificationView(UUID.randomUUID(), UUID.randomUUID(), "1", "알림", "",
            Instant.parse("2026-11-05T01:02:03.123456Z"), null, new NotificationView.Target("INITIAL_ASSIGNMENT", UUID.randomUUID()));

    @Test void preservesFullPostgresTimestampAndUuidAcrossInstances() {
        var position = new NotificationCursor(new byte[32]).decode(account, codec.encode(account, row));
        assertThat(position.createdAt()).isEqualTo(row.createdAt());
        assertThat(position.id()).isEqualTo(row.id());
        assertThat(codec.decode(account, null)).isNull();
    }
    @Test void rejectsAnotherAccount() { invalid(UUID.randomUUID(), codec.encode(account, row)); }
    @Test void rejectsMalformedEmptyAndNonCanonicalValues() {
        for (String cursor : List.of("", "null", "abc", "a.b", ".", " ", "a".repeat(513), codec.encode(account, row) + "="))
            invalid(account, cursor);
    }
    @Test void rejectsPayloadAndSignatureTampering() {
        String cursor = codec.encode(account, row);
        for (int i : new int[]{0, cursor.indexOf('.') + 1, cursor.length() - 1}) {
            char changed = cursor.charAt(i) == 'A' ? 'B' : 'A';
            invalid(account, cursor.substring(0, i) + changed + cursor.substring(i + 1));
        }
    }
    @Test void rejectsAnotherSigningKey() {
        byte[] other = new byte[32]; other[0] = 1;
        assertThatThrownBy(() -> new NotificationCursor(other).decode(account, codec.encode(account, row)))
                .isInstanceOf(BusinessException.class);
    }
    private void invalid(UUID owner, String cursor) {
        assertThatThrownBy(() -> codec.decode(owner, cursor)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}

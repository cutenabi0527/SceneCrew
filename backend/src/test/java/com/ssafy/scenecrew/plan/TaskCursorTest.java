package com.ssafy.scenecrew.plan;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ssafy.scenecrew.plan.service.TaskCursor;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;

class TaskCursorTest {
    final TaskCursor codec = new TaskCursor(new byte[32]);
    final UUID event = UUID.randomUUID(), account = UUID.randomUUID(), member = UUID.randomUUID();
    final TaskCursor.Scope scope = new TaskCursor.Scope(event, account, member, 2, "WORKER");

    @Test void roundTripsPlannedMicrosecondAndNullBoundaries() {
        for (Instant time : Arrays.asList(Instant.parse("2026-11-06T00:01:02.123456Z"), null)) {
            var position = new TaskCursor.Position(time, UUID.randomUUID());
            assertThat(codec.decode(scope, codec.encode(scope, position))).isEqualTo(position);
        }
        assertThat(codec.decode(scope, null)).isNull();
    }

    @Test void bindsEveryVisibilityDimensionAndTheSigningKey() {
        var encoded = codec.encode(scope, new TaskCursor.Position(Instant.EPOCH, UUID.randomUUID()));
        for (var other : List.of(new TaskCursor.Scope(UUID.randomUUID(), account, member, 2, "WORKER"),
                new TaskCursor.Scope(event, UUID.randomUUID(), member, 2, "WORKER"),
                new TaskCursor.Scope(event, account, UUID.randomUUID(), 2, "WORKER"),
                new TaskCursor.Scope(event, account, member, 3, "WORKER"),
                new TaskCursor.Scope(event, account, member, 2, "CHIEF"))) invalid(() -> codec.decode(other, encoded));
        byte[] otherKey = new byte[32]; otherKey[0] = 1;
        invalid(() -> new TaskCursor(otherKey).decode(scope, encoded));
    }

    @Test void rejectsMalformedTamperedAndNoncanonicalInput() {
        String encoded = codec.encode(scope, new TaskCursor.Position(Instant.EPOCH, UUID.randomUUID()));
        for (String value : List.of("", "null", ".", "a.b.c", "a.b", "x".repeat(513), encoded + "=",
                "X" + encoded.substring(1))) invalid(() -> codec.decode(scope, value));
        assertThatThrownBy(() -> new TaskCursor(new byte[31])).isInstanceOf(IllegalArgumentException.class);
    }
    static void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}

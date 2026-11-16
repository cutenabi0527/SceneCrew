package com.ssafy.scenecrew.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class StorageKeysTest {
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-8333-333333333301");
    private static final UUID FILE = UUID.fromString("77777777-7777-4777-8777-777777777701");

    @Test
    void keysStartWithKindThenEvent() {
        assertThat(StorageKeys.audio(EVENT, FILE)).isEqualTo("audio/" + EVENT + "/" + FILE);
        assertThat(StorageKeys.document(EVENT, FILE)).isEqualTo("documents/" + EVENT + "/" + FILE);
    }
}

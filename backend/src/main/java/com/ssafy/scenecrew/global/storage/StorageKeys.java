package com.ssafy.scenecrew.global.storage;

import java.util.UUID;

/**
 * 객체 키 규칙. 종류를 앞에 두어 종류별 자동 삭제 규칙을 걸 수 있게 하고, 원래 파일 이름은 넣지 않는다.
 * DB storage_key에는 이 문자열을 그대로 저장한다.
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    public static String audio(UUID eventId, UUID audioAssetId) {
        return "audio/" + eventId + "/" + audioAssetId;
    }

    public static String document(UUID eventId, UUID intakeDocumentId) {
        return "documents/" + eventId + "/" + intakeDocumentId;
    }
}

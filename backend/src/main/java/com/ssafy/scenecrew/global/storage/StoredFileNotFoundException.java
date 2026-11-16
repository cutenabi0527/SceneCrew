package com.ssafy.scenecrew.global.storage;

/** 키에 해당하는 파일이 없음. 만료로 이미 지워졌거나 저장되지 않은 경우다. */
public class StoredFileNotFoundException extends FileStorageException {

    public StoredFileNotFoundException(String key, Throwable cause) {
        super("저장된 파일이 없습니다: " + key, cause);
    }
}

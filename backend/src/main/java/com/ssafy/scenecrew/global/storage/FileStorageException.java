package com.ssafy.scenecrew.global.storage;

/** 저장소 호출 실패. 사용자 입력 문제가 아니라 서버 쪽 장애로 다룬다. */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public FileStorageException(String message) {
        super(message);
    }
}

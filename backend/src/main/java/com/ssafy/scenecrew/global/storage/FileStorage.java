package com.ssafy.scenecrew.global.storage;

/**
 * 음성·문서 원본 파일 저장소. 서비스 코드는 S3 SDK를 직접 쓰지 않고 이 인터페이스만 쓴다.
 * 키 규칙은 StorageKeys, 저장·삭제 순서와 보관 기간은 backend/docs/file-storage.md를 따른다.
 * 외부 저장소 호출이므로 DB 트랜잭션 안에서 부르지 않는다.
 */
public interface FileStorage {

    /**
     * 파일을 저장하고 내용의 SHA-256(16진수 소문자)을 돌려준다.
     * 저장소가 받은 내용이 보낸 내용과 같은지 체크섬으로 확인한다.
     */
    String put(String key, byte[] content, String contentType);

    /** 파일을 연다. 호출한 쪽이 StoredFile을 닫아야 한다. 없으면 StoredFileNotFoundException. */
    StoredFile get(String key);

    /** 파일을 지운다. 이미 없어도 성공으로 본다. */
    void delete(String key);
}

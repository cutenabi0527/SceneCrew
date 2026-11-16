package com.ssafy.scenecrew.global.storage;

/** 음성으로 열 수 없는 파일(깨짐, 음성 트랙 없음). 사용자에게는 형식 오류로 알린다. */
public class InvalidAudioException extends RuntimeException {

    public InvalidAudioException(String message) {
        super(message);
    }
}

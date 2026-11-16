package com.ssafy.scenecrew.global.storage;

/** 음성 길이를 잴 도구를 쓸 수 없을 때(ffprobe 없음·시간 초과). 파일 문제가 아니라 서버 문제다. */
public class AudioProbeException extends RuntimeException {

    public AudioProbeException(String message) {
        super(message);
    }

    public AudioProbeException(String message, Throwable cause) {
        super(message, cause);
    }
}

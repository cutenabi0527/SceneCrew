package com.ssafy.scenecrew.global.storage;

/**
 * 음성 파일을 실제로 열어 형식과 길이를 잰다. 클라이언트가 보낸 길이나 Content-Type은 믿지 않는다.
 * 운영 구현은 FfprobeAudioProbe(이미지에 설치한 ffprobe)이고, 테스트는 가짜 구현을 쓴다.
 */
public interface AudioProbe {

    /**
     * 열 수 없는 파일이면 InvalidAudioException, 잴 도구를 쓸 수 없으면 AudioProbeException.
     */
    AudioProbeResult probe(byte[] content);
}

package com.ssafy.scenecrew.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 파일 서비스 시험에서 S3와 ffprobe 대신 쓰는 가짜 */
@TestConfiguration
public class FileTestBeans {

    @Bean
    @Primary
    public FakeFileStorage fakeFileStorage() {
        return new FakeFileStorage();
    }

    @Bean
    @Primary
    public FakeAudioProbe fakeAudioProbe() {
        return new FakeAudioProbe();
    }
}

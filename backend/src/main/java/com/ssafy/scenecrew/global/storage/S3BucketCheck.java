package com.ssafy.scenecrew.global.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 기동이 끝나면 버킷에 접근되는지 한 번 확인하고 로그로 남긴다(HeadBucket).
 * 실패해도 기동을 막지 않는다. /actuator/health에 넣으면 S3 장애나 설정 누락만으로 배포 확인(deploy.sh)이
 * 실패해 되돌려지므로, 상태 확인은 로그로만 한다. 버킷이 설정되지 않은 환경(로컬·테스트)은 확인하지 않는다.
 */
@Component
class S3BucketCheck {
    private static final Logger log = LoggerFactory.getLogger(S3BucketCheck.class);
    private final S3Client s3;
    private final S3StorageProperties properties;

    S3BucketCheck(S3Client s3, S3StorageProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void checkOnStartup() {
        if (!StringUtils.hasText(properties.bucket())) {
            log.info("S3_BUCKET이 비어 있어 파일 저장소 확인을 건너뜁니다.");
            return;
        }
        // 자격 증명 조회가 느릴 수 있어 기동 스레드를 붙잡지 않는다.
        Thread.ofVirtual().name("s3-bucket-check").start(this::check);
    }

    /** 접근되면 true. 실패 사유는 로그에 남긴다(버킷 이름 외의 설정 값은 남기지 않는다). */
    boolean check() {
        try {
            s3.headBucket(request -> request.bucket(properties.bucket()));
            log.info("S3 버킷 {}에 접근했습니다.", properties.bucket());
            return true;
        } catch (SdkException e) {
            log.warn("S3 버킷 {}에 접근하지 못했습니다. 버킷 이름·리전·접근 키를 확인해 주세요: {}",
                    properties.bucket(), e.getMessage());
            return false;
        }
    }
}

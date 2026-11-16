package com.ssafy.scenecrew.global.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 설정(scenecrew.storage.s3). 운영 접근 키는 AWS SDK 기본 방식(AWS_ACCESS_KEY_ID 등 환경변수)으로 읽는다.
 * accessKey/secretKey는 MinIO처럼 로컬·테스트 저장소를 쓸 때만 넣는다.
 */
@ConfigurationProperties("scenecrew.storage.s3")
public record S3StorageProperties(String bucket, String region, String endpoint, String accessKey, String secretKey) {
}

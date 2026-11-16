package com.ssafy.scenecrew.global.storage;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@Configuration
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(S3StorageProperties properties) {
        return createClient(properties);
    }

    @Bean
    FileStorage fileStorage(S3Client s3Client, S3StorageProperties properties) {
        return new S3FileStorage(s3Client, properties.bucket());
    }

    /** 접근 키는 첫 호출 때 읽으므로, 버킷·키가 없는 환경에서도 앱은 기동된다. */
    static S3Client createClient(S3StorageProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                // 체크섬은 저장 때 SHA-256을 직접 보낸다. SDK의 자동 체크섬은 S3 호환 저장소(MinIO)와 어긋날 수 있어 끈다.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint())).forcePathStyle(true);
        }
        if (StringUtils.hasText(properties.accessKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }
}

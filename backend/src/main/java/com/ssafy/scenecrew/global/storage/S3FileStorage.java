package com.ssafy.scenecrew.global.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.util.StringUtils;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** FileStorage의 S3 구현. 버킷 하나에 StorageKeys 규칙으로 저장한다. */
public class S3FileStorage implements FileStorage {
    private final S3Client s3;
    private final String bucket;

    public S3FileStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public String put(String key, byte[] content, String contentType) {
        byte[] digest = sha256(content);
        try {
            s3.putObject(request -> request
                            .bucket(bucket())
                            .key(key)
                            .contentType(contentType)
                            .contentLength((long) content.length)
                            .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                            .checksumSHA256(Base64.getEncoder().encodeToString(digest)),
                    RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new FileStorageException("파일을 저장하지 못했습니다: " + key, e);
        }
        return HexFormat.of().formatHex(digest);
    }

    @Override
    public StoredFile get(String key) {
        try {
            ResponseInputStream<GetObjectResponse> in = s3.getObject(request -> request.bucket(bucket()).key(key));
            GetObjectResponse response = in.response();
            return new StoredFile(in, response.contentType(), response.contentLength());
        } catch (NoSuchKeyException e) {
            throw new StoredFileNotFoundException(key, e);
        } catch (SdkException e) {
            throw new FileStorageException("파일을 읽지 못했습니다: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(request -> request.bucket(bucket()).key(key));
        } catch (SdkException e) {
            throw new FileStorageException("파일을 지우지 못했습니다: " + key, e);
        }
    }

    private String bucket() {
        if (!StringUtils.hasText(bucket)) {
            throw new FileStorageException("S3_BUCKET이 설정되지 않았습니다.");
        }
        return bucket;
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

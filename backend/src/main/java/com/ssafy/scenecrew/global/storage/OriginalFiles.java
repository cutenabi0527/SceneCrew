package com.ssafy.scenecrew.global.storage;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/**
 * 음성·문서 서비스가 함께 쓰는 원본 저장·삭제. 저장소 오류를 사용자 오류 코드와 로그로 바꾼다.
 * 언제 지워도 되는지(만료 재확인·잠금)는 각 서비스가 정한다.
 */
@Component
public class OriginalFiles {
    private static final Logger log = LoggerFactory.getLogger(OriginalFiles.class);
    private final FileStorage storage;

    public OriginalFiles(FileStorage storage) {
        this.storage = storage;
    }

    /** 저장하고 SHA-256(16진수)을 돌려준다. 실패하면 503. fileName이 있으면 오류 details에 넣는다. */
    public String put(String key, byte[] content, String mime, String fileName) {
        try {
            return storage.put(key, content, mime);
        } catch (FileStorageException e) {
            log.warn("원본을 저장하지 못했습니다: {}", key, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "파일을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                    fileName == null ? null : Map.of("fileName", fileName));
        }
    }

    public StoredFile get(String key) {
        return storage.get(key);
    }

    /** 지우고 성공 여부를 돌려준다. 이미 없어도 성공이다. 실패는 로그로 남긴다. */
    public boolean delete(String key) {
        try {
            storage.delete(key);
            return true;
        } catch (FileStorageException e) {
            log.warn("원본을 지우지 못했습니다: {}", key, e);
            return false;
        }
    }

    /**
     * 등록에 실패한 업로드의 원본을 치운다. DB 커밋 결과가 불확실하면(커밋 단계 오류) 부르지 않는다.
     * 커밋이 실제로 반영됐는데 원본만 지우면 행은 남고 파일만 사라지기 때문이다. 남은 원본은 만료 정리가 지운다.
     */
    public void discardUnregistered(String key) {
        if (!delete(key)) {
            log.warn("등록에 실패한 원본이 저장소에 남았습니다: {}", key);
        }
    }
}

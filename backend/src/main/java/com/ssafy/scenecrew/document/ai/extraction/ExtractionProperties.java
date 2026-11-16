package com.ssafy.scenecrew.document.ai.extraction;

import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 문서 추출 설정(scenecrew.ai.extraction).
 *
 * @param documentBaseUrl AI 서버가 문서 원본을 내려받을 업무 서버 주소. 예: https://service.p.example.test
 *                        문서 주소는 이 값 + /internal/intake-documents/{documentId}다. 음성 전사와 같이 업무 서버 nginx의
 *                        /internal/(AI 서버 IP만 허용)을 거친다. 비어 있으면 추출을 접수하지 않는다(503).
 */
@ConfigurationProperties("scenecrew.ai.extraction")
public record ExtractionProperties(@DefaultValue("") String documentBaseUrl) {

    public static final String DOCUMENT_PATH = "/internal/intake-documents/";

    public String documentUrl(UUID documentId) {
        return documentBaseUrl.replaceAll("/+$", "") + DOCUMENT_PATH + documentId;
    }

    public boolean configured() {
        return !documentBaseUrl.isBlank();
    }
}

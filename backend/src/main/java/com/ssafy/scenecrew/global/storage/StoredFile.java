package com.ssafy.scenecrew.global.storage;

import java.io.IOException;
import java.io.InputStream;

/** 저장소에서 연 파일. 응답으로 흘려보낸 뒤 반드시 닫는다. */
public record StoredFile(InputStream content, String contentType, long contentLength) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        content.close();
    }
}

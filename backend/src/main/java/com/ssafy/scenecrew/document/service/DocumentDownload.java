package com.ssafy.scenecrew.document.service;

import java.io.IOException;
import java.io.InputStream;

import com.ssafy.scenecrew.global.storage.StoredFile;

/** 내려줄 문서. 컨트롤러가 응답으로 흘려보낸 뒤 닫는다. */
public record DocumentDownload(String originalName, String mime, StoredFile file) implements AutoCloseable {

    public InputStream content() {
        return file.content();
    }

    public long contentLength() {
        return file.contentLength();
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}

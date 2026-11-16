package com.ssafy.scenecrew.document.service;

/** 올릴 문서 하나. originalName은 클라이언트가 보낸 파일 이름이며 형식 판단에는 보조로만 쓴다. */
public record DocumentUpload(String originalName, byte[] content) {
}

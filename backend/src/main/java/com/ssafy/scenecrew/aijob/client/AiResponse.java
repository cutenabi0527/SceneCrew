package com.ssafy.scenecrew.aijob.client;

/** AI 서버가 돌려준 HTTP 상태 코드와 본문(JSON 원문) */
public record AiResponse(int status, String body) {
}

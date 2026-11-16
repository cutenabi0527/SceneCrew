package com.ssafy.scenecrew.account.dto;

/** 요청에서 읽은 인증 전달값(채널, Access Token, Refresh Token). 로그에 값이 남지 않게 toString을 가린다. */
public record Credentials(String channel, String access, String refresh) {
    public Credentials(String channel, String access) { this(channel, access, null); }
    @Override public String toString() { return "Credentials[redacted]"; }
}

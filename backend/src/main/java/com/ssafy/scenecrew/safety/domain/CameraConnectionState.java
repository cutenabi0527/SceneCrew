package com.ssafy.scenecrew.safety.domain;

public enum CameraConnectionState {
    /** 웹 연결 카메라의 연결 전(등록 안됨, V9-D58) */
    UNLINKED,
    DISABLED,
    CONNECTING,
    CONNECTED,
    OFFLINE,
    ERROR
}

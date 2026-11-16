package com.ssafy.scenecrew.aijob.domain;

public enum AiJobState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    STALE_CONTEXT
}

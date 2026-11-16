package com.ssafy.scenecrew.safety.collector;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 카메라마다 가장 최근에 받은 프레임 1장만 메모리에 둔다(#349, V9-D09).
 *
 * <p>디스크·DB에 쓰지 않는다. 새 프레임이 오면 이전 프레임을 바꿔 끼우고, 오래된 프레임은 분석 주기 작업이 지운다.
 * 서버를 다시 시작하면 모두 사라지며, 그 사이 미리보기는 503(NO_FRAME)이다. 업무 서버가 한 대라는 전제다.
 */
public class LatestFrames {
    private final ConcurrentHashMap<UUID, Frame> frames = new ConcurrentHashMap<>();

    /** 같은 카메라의 더 늦은 프레임이 이미 있으면(동시 요청) 바꾸지 않는다. */
    public void put(Frame frame) {
        frames.merge(frame.cameraId(), frame, (old, next) -> next.receivedAt().isBefore(old.receivedAt()) ? old : next);
    }

    public Frame get(UUID cameraId) {
        return frames.get(cameraId);
    }

    public List<Frame> all() {
        return List.copyOf(frames.values());
    }

    /** 그 사이 새 프레임이 들어왔으면 지우지 않는다. */
    public void discard(Frame frame) {
        frames.remove(frame.cameraId(), frame);
    }

    public void discardCamera(UUID cameraId) {
        frames.remove(cameraId);
    }

    /**
     * 받은 프레임 1장. jpeg는 받은 바이트 그대로다(바꾸지 않는다).
     *
     * @param inputHash jpeg 바이트의 SHA-256(소문자 16진수). 관측의 inputHash가 된다.
     */
    public record Frame(UUID eventId, UUID cameraId, UUID streamSessionId, long sequence, Instant capturedAt,
            String captureTimeBasis, Instant receivedAt, byte[] jpeg, String inputHash) {
    }
}

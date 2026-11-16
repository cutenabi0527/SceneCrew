package com.ssafy.scenecrew.safety.collector;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.scenecrew.safety.collector.CameraFrameService.Ack;

/**
 * 카메라 수집기 전용 내부 경로(#349). 공개 /api/v1과 분리하며 사용자 로그인·Cookie를 쓰지 않는다.
 * 본문은 JPEG 바이트 그대로이고, 카메라·순서 정보는 헤더로 받는다(camera/README.md).
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.safety.camera.enabled", havingValue = "true")
public class CameraFrameController {
    public static final String PATH = "/internal/camera-collectors/{collectorId}/frames";

    private final CameraFrameService service;

    public CameraFrameController(CameraFrameService service) {
        this.service = service;
    }

    @PostMapping(path = PATH, consumes = MediaType.IMAGE_JPEG_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Ack ingest(@PathVariable String collectorId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(name = "X-SceneCrew-Source", required = false) String source,
            @RequestHeader(name = "X-SceneCrew-Stream-Session", required = false) String session,
            @RequestHeader(name = "X-SceneCrew-Sequence", required = false) String sequence,
            @RequestHeader(name = "X-SceneCrew-Captured-At", required = false) String capturedAt,
            @RequestHeader(name = "X-SceneCrew-Capture-Time-Basis", required = false) String basis,
            HttpServletRequest request) {
        return service.ingest(collectorId, new CameraFrameService.Headers(authorization, source, session, sequence, capturedAt, basis),
                limit -> request.getInputStream().readNBytes(limit));
    }
}

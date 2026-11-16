package com.ssafy.scenecrew.safety.controller;

import java.time.Clock;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.global.web.StrictJson;
import com.ssafy.scenecrew.safety.collector.LatestFrames;
import com.ssafy.scenecrew.safety.service.CameraLinkService;

/**
 * 카메라 웹 연결(#393, V9-D58·D60). 로그인 세션으로 인증하고 쓰기 검사(WEB 허용 Origin)는 PublicAuthFilter가 한다.
 * 카메라 수신(CAMERA_INGEST_ENABLED)이 켜져 있을 때만 연다. 규칙은 backend/docs/safety-camera.md.
 */
@RestController
@ConditionalOnProperty(name = { "scenecrew.auth.public-enabled", "scenecrew.safety.camera.enabled" }, havingValue = "true")
public class CameraLinkController {
    private final CameraLinkService links;

    public CameraLinkController(CameraLinkService links) {
        this.links = links;
    }

    /** 연결 시작·넘겨받기 {expectedCameraRevision, takeover?} → {linkId, cameraRevision} */
    @PostMapping(value = "/api/v1/safety-cameras/{c}/browser-link", consumes = "application/json")
    public ResponseEntity<CameraLinkService.Link> link(@PathVariable("c") String c, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        StrictJson.object(body, "", Set.of("expectedCameraRevision", "takeover"), Set.of("expectedCameraRevision"));
        var command = new CameraLinkService.LinkCommand(
                StrictJson.revision(body.get("expectedCameraRevision"), "expectedCameraRevision"),
                body.has("takeover") && StrictJson.bool(body.get("takeover"), "takeover"));
        return noStore(links.link(StrictJson.pathId(c, "cameraId"), StrictJson.accountId(authentication), command));
    }

    /** 연결 해제 → {cameraRevision} */
    @DeleteMapping("/api/v1/safety-cameras/{c}/browser-link")
    public ResponseEntity<CameraLinkService.Unlink> unlink(@PathVariable("c") String c, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        return noStore(links.unlink(StrictJson.pathId(c, "cameraId"), StrictJson.accountId(authentication)));
    }

    /** 프레임 한 장(JPEG 2MB 이하). 202 {receivedAt, sequence} */
    @PostMapping(value = "/api/v1/safety-cameras/{c}/frames", consumes = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<CameraLinkService.Ack> frame(@PathVariable("c") String c,
            @RequestHeader(name = "X-SceneCrew-Link", required = false) String link,
            @RequestHeader(name = "X-SceneCrew-Stream-Session", required = false) String session,
            @RequestHeader(name = "X-SceneCrew-Sequence", required = false) String sequence,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        var ack = links.frame(StrictJson.pathId(c, "cameraId"), StrictJson.accountId(authentication),
                new CameraLinkService.FrameHeaders(link, session, sequence), limit -> request.getInputStream().readNBytes(limit));
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(ack);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = { "scenecrew.auth.public-enabled", "scenecrew.safety.camera.enabled" }, havingValue = "true")
    static class Beans {
        @Bean CameraLinkService cameraLinkService(JdbcTemplate jdbc, PlatformTransactionManager manager, EventWriteSupport events,
                LatestFrames frames) {
            return new CameraLinkService(jdbc, manager, events, frames, Clock.systemUTC());
        }
    }
}

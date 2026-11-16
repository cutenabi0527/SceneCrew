package com.ssafy.scenecrew.safety.controller;

import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.global.web.StrictJson;
import com.ssafy.scenecrew.safety.collector.CameraAnalyzer;
import com.ssafy.scenecrew.safety.collector.LatestFrames;
import com.ssafy.scenecrew.safety.dto.SafetyRequests;
import com.ssafy.scenecrew.safety.dto.SafetySupportRequests;
import com.ssafy.scenecrew.safety.dto.SafetySupportRequests.CandidatePage;
import com.ssafy.scenecrew.safety.dto.SafetySupportRequests.CandidateView;
import com.ssafy.scenecrew.safety.service.SafetyCameraService;
import com.ssafy.scenecrew.safety.service.MyLinkService;
import com.ssafy.scenecrew.safety.service.SafetyMapService;
import com.ssafy.scenecrew.safety.service.SafetyObservationReadService;
import com.ssafy.scenecrew.safety.service.SafetySnapshotService;
import com.ssafy.scenecrew.safety.service.SafetySupportPreparation;
import com.ssafy.scenecrew.safety.service.SafetyViews.Receipt;
import com.ssafy.scenecrew.safety.service.SafetyViews;

/**
 * 안전 지도 EXT10·11, 관측 EXT13·14, 카메라 EXT21~24, 카메라 현재 프레임 Q2는 CHIEF 전용이다.
 * 내 연결 목록 L01(#393)은 팀원도 부른다. 카메라 웹 연결·프레임은 CameraLinkController다.
 * 미리보기 세션(EXT25·26)·관측 보고 연결(EXT17·18)은 이 컨트롤러 밖이다.
 * 인증·허용 Origin·JSON 확인은 PublicAuthFilter(eventRead·businessWrite)가 끝낸 뒤 들어온다.
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class SafetyController {
    static final int DEFAULT_LIMIT = 30;

    private final SafetyMapService maps;
    private final SafetyCameraService cameras;
    private final SafetyObservationReadService observations;
    private final SafetySnapshotService snapshots;
    private final SafetySupportPreparation support;
    private final MyLinkService myLinks;

    public SafetyController(SafetyMapService maps, SafetyCameraService cameras, SafetyObservationReadService observations,
            SafetySnapshotService snapshots, SafetySupportPreparation support, MyLinkService myLinks) {
        this.maps = maps;
        this.cameras = cameras;
        this.observations = observations;
        this.snapshots = snapshots;
        this.support = support;
        this.myLinks = myLinks;
    }

    /** 내 연결 목록 L01(#393). 팀원도 부른다. 내가 연결할 카메라 칸만이다. */
    @GetMapping("/api/v1/events/{e}/my-links")
    public ResponseEntity<MyLinkService.MyLinks> myLinks(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        return noStore(myLinks.list(StrictJson.pathId(e, "eventId"), StrictJson.accountId(authentication)));
    }

    @GetMapping("/api/v1/events/{e}/safety-support-candidates")
    public ResponseEntity<CandidatePage> supportCandidates(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        var scope = SafetySupportRequests.query(StrictJson.pathId(e, "eventId"), request);
        var candidates = support.candidates(StrictJson.accountId(authentication), scope).stream()
                .map(CandidateView::from).toList();
        return noStore(new CandidatePage(Long.toString(scope.expectedEventRevision()), candidates));
    }

    /** EXT10 */
    @GetMapping("/api/v1/events/{e}/safety-map")
    public ResponseEntity<SafetyViews.SafetyMap> map(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        return noStore(maps.read(StrictJson.pathId(e, "eventId"), StrictJson.accountId(authentication)));
    }

    /** V9-D62. 발행 때 저장한 평면도 배경 그림(PNG). 없으면 404 */
    @GetMapping(value = "/api/v1/events/{e}/safety-map/background")
    public ResponseEntity<byte[]> mapBackground(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        var background = maps.background(StrictJson.pathId(e, "eventId"), StrictJson.accountId(authentication));
        var response = ResponseEntity.ok().contentType(org.springframework.http.MediaType.IMAGE_PNG)
                .cacheControl(org.springframework.http.CacheControl.noStore()).header("X-Content-Type-Options", "nosniff");
        // 평면도 그림이 놓인 영역(0~1, 왼쪽 위 기준). 화면은 이 영역만 크게 보인다
        if (background.crop() != null) response.header("X-Floor-Plan-Crop", background.crop().header());
        return response.body(background.png());
    }

    /** EXT11 */
    @PatchMapping(value = "/api/v1/events/{e}/safety-map", consumes = "application/json")
    public ResponseEntity<Receipt> saveMap(@PathVariable("e") String e, @RequestBody JsonNode body, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID eventId = StrictJson.pathId(e, "eventId");
        String key = StrictJson.idempotencyKey(request);
        return respond(maps.save(eventId, StrictJson.accountId(authentication), key, SafetyRequests.map(body)));
    }

    /** EXT13 */
    @GetMapping("/api/v1/events/{e}/safety-observations")
    public ResponseEntity<SafetyViews.ObservationPage> observations(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of("cursor", "limit"));
        return noStore(observations.list(StrictJson.pathId(e, "eventId"), StrictJson.accountId(authentication),
                request.getParameter("cursor"), StrictJson.limit(request, DEFAULT_LIMIT)));
    }

    /** EXT14 */
    @GetMapping("/api/v1/safety-observations/{o}")
    public ResponseEntity<SafetyViews.ObservationView> observation(@PathVariable("o") String o, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        return noStore(observations.get(StrictJson.pathId(o, "observationId"), StrictJson.accountId(authentication)));
    }

    /** EXT21 */
    @GetMapping("/api/v1/events/{e}/safety-cameras")
    public ResponseEntity<SafetyViews.CameraPage> cameras(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of("cursor", "limit"));
        return noStore(cameras.list(StrictJson.pathId(e, "eventId"), StrictJson.accountId(authentication),
                request.getParameter("cursor"), StrictJson.limit(request, DEFAULT_LIMIT)));
    }

    /** EXT22 */
    @PostMapping(value = "/api/v1/events/{e}/safety-cameras", consumes = "application/json")
    public ResponseEntity<Receipt> createCamera(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID eventId = StrictJson.pathId(e, "eventId");
        String key = StrictJson.idempotencyKey(request);
        return respond(cameras.create(eventId, StrictJson.accountId(authentication), key, SafetyRequests.camera(body)));
    }

    /** EXT23 */
    @GetMapping("/api/v1/safety-cameras/{c}")
    public ResponseEntity<SafetyViews.CameraView> camera(@PathVariable("c") String c, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        return noStore(cameras.get(StrictJson.pathId(c, "cameraId"), StrictJson.accountId(authentication)));
    }

    /** EXT24 */
    @PatchMapping(value = "/api/v1/safety-cameras/{c}", consumes = "application/json")
    public ResponseEntity<Receipt> patchCamera(@PathVariable("c") String c, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID cameraId = StrictJson.pathId(c, "cameraId");
        String key = StrictJson.idempotencyKey(request);
        return respond(cameras.patch(cameraId, StrictJson.accountId(authentication), key, SafetyRequests.patch(body)));
    }

    /** Q2 카메라 현재 프레임. 메모리의 최신 1장(JPEG)이며 캐시하지 않는다. */
    @GetMapping("/api/v1/safety-cameras/{c}/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable("c") String c, HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        var frame = snapshots.snapshot(StrictJson.pathId(c, "cameraId"), StrictJson.accountId(authentication));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("Cache-Control", "no-store, private")
                .header("X-Captured-At", frame.capturedAt().toString()).header("X-Received-At", frame.receivedAt().toString())
                .header("X-Frame-Freshness", frame.freshness()).body(frame.jpeg());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    private static <T> ResponseEntity<T> respond(IdempotentResult<T> result) {
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
    static class Beans {
        @Bean SafetyMapService safetyMapService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency,
                EventWriteSupport events, com.ssafy.scenecrew.safety.service.SafetySupportProtection support,
                com.ssafy.scenecrew.safety.service.VenueLayoutBackgrounds backgrounds) {
            return new SafetyMapService(jdbc, members, idempotency, events, support, backgrounds);
        }

        @Bean SafetyCameraService safetyCameraService(JdbcTemplate jdbc, CurrentMembers members, Idempotency idempotency,
                EventWriteSupport events, @Value("${scenecrew.auth.signing-key-base64}") String key) {
            return new SafetyCameraService(jdbc, members, idempotency, events, Base64.getDecoder().decode(key));
        }

        @Bean SafetyObservationReadService safetyObservationReadService(JdbcTemplate jdbc, CurrentMembers members,
                @Value("${scenecrew.auth.signing-key-base64}") String key) {
            return new SafetyObservationReadService(jdbc, members, Base64.getDecoder().decode(key));
        }

        @Bean MyLinkService myLinkService(JdbcTemplate jdbc, CurrentMembers members) {
            return new MyLinkService(jdbc, members);
        }

        @Bean SafetySnapshotService safetySnapshotService(JdbcTemplate jdbc, CurrentMembers members, ObjectProvider<LatestFrames> frames) {
            return new SafetySnapshotService(jdbc, members, frames.getIfAvailable(), Clock.systemUTC(), CameraAnalyzer.FRAME_TTL);
        }
    }
}

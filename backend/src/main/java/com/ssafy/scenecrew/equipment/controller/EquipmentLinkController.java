package com.ssafy.scenecrew.equipment.controller;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.equipment.collector.WebCollectors;
import com.ssafy.scenecrew.equipment.service.EquipmentLinkService;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.global.web.StrictJson;

/**
 * 장비 웹 연결(#394, V9-D59·D60): 연결 코드 발급(W5), 수신기 ↔ 장비 칸 연결(W6)·해제(W7).
 * 로그인 세션으로 인증하고 쓰기 검사(WEB 허용 Origin·JSON)는 PublicAuthFilter가 한다. 장비 수집(COLLECTOR_INGEST_ENABLED)이 켜져 있을 때만 연다.
 */
@RestController
@ConditionalOnProperty(name = { "scenecrew.auth.public-enabled", "scenecrew.equipment.collector.enabled" }, havingValue = "true")
public class EquipmentLinkController {
    private final EquipmentLinkService links;

    public EquipmentLinkController(EquipmentLinkService links) {
        this.links = links;
    }

    /** W5 연결 코드 발급. 본문은 없거나 빈 객체. 201 {code, expiresAt} */
    @PostMapping(value = "/api/v1/events/{e}/equipment-links/codes", consumes = "application/json")
    public ResponseEntity<EquipmentLinkService.Code> code(@PathVariable("e") String e,
            @RequestBody(required = false) JsonNode body, HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        if (body != null) StrictJson.object(body, "", Set.of(), Set.of());
        var code = links.issueCode(StrictJson.pathId(e, "eventId"), StrictJson.accountId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store").body(code);
    }

    /** W6 수신기 ↔ 장비 칸 연결 {collectorId, receiverKey, channel, takeover?} → {id, revision} */
    @PutMapping(value = "/api/v1/equipment/{a}/link", consumes = "application/json")
    public ResponseEntity<EquipmentLinkService.Receipt> link(@PathVariable("a") String a, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        StrictJson.object(body, "", Set.of("collectorId", "receiverKey", "channel", "takeover"),
                Set.of("collectorId", "receiverKey", "channel"));
        var command = new EquipmentLinkService.LinkCommand(StrictJson.text(body.get("collectorId"), "collectorId", 100),
                StrictJson.text(body.get("receiverKey"), "receiverKey", 100), StrictJson.integer(body.get("channel"), "channel", 1, 4),
                body.has("takeover") && StrictJson.bool(body.get("takeover"), "takeover"));
        return noStore(links.link(StrictJson.pathId(a, "assetId"), StrictJson.accountId(authentication), command));
    }

    /** W7 연결 해제 → {id, revision} */
    @DeleteMapping("/api/v1/equipment/{a}/link")
    public ResponseEntity<EquipmentLinkService.Receipt> unlink(@PathVariable("a") String a, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        return noStore(links.unlink(StrictJson.pathId(a, "assetId"), StrictJson.accountId(authentication)));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = { "scenecrew.auth.public-enabled", "scenecrew.equipment.collector.enabled" }, havingValue = "true")
    static class Beans {
        @Bean EquipmentLinkService equipmentLinkService(JdbcTemplate jdbc, CurrentMembers members, EventWriteSupport events,
                WebCollectors collectors) {
            return new EquipmentLinkService(jdbc, members, events, collectors);
        }
    }
}

package com.ssafy.scenecrew.equipment.controller;

import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.equipment.dto.RelationRequests;
import com.ssafy.scenecrew.equipment.service.EquipmentRelationService;
import com.ssafy.scenecrew.equipment.service.EquipmentViews.Context;
import com.ssafy.scenecrew.equipment.service.EquipmentViews.Receipt;
import com.ssafy.scenecrew.equipment.service.EquipmentViews.ReleaseReceipt;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.global.web.StrictJson;

/**
 * 장비 관계 R1: 연결·요구·운영 의존의 추가(POST)·교체(PATCH)·해제(DELETE)와 equipment-context 조회.
 * 추가·교체는 PublicAuthFilter(businessWrite), 해제는 본문이 없어 businessDelete 검사를 거친다.
 * 해제의 기대 revision은 query로 받는다(운영 의존은 expectedEventRevision만).
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class EquipmentRelationController {
    private static final String BASE = "/api/v1/events/{e}/equipment-";

    private final EquipmentRelationService relations;

    public EquipmentRelationController(EquipmentRelationService relations) {
        this.relations = relations;
    }

    @GetMapping("/api/v1/events/{e}/equipment-context")
    public ResponseEntity<Context> context(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        return EquipmentController.noStore(relations.context(StrictJson.pathId(e, "eventId"),
                StrictJson.accountId(authentication)));
    }

    // ---- 연결 ----

    @PostMapping(value = BASE + "connections", consumes = "application/json")
    public ResponseEntity<Receipt> createConnection(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID eventId = StrictJson.pathId(e, "eventId");
        String key = StrictJson.idempotencyKey(request);
        return EquipmentController.respond(relations.createConnection(eventId, StrictJson.accountId(authentication), key,
                RelationRequests.connection(body, false)));
    }

    @PatchMapping(value = BASE + "connections/{r}", consumes = "application/json")
    public ResponseEntity<Receipt> replaceConnection(@PathVariable("e") String e, @PathVariable("r") String r,
            @RequestBody JsonNode body, HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID eventId = StrictJson.pathId(e, "eventId");
        UUID id = StrictJson.pathId(r, "connectionId");
        String key = StrictJson.idempotencyKey(request);
        return EquipmentController.respond(relations.replaceConnection(eventId, id, StrictJson.accountId(authentication), key,
                RelationRequests.connection(body, true)));
    }

    @DeleteMapping(BASE + "connections/{r}")
    public ResponseEntity<ReleaseReceipt> releaseConnection(@PathVariable("e") String e, @PathVariable("r") String r,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of("expectedEventRevision", "expectedRevision"));
        UUID eventId = StrictJson.pathId(e, "eventId");
        UUID id = StrictJson.pathId(r, "connectionId");
        String key = StrictJson.idempotencyKey(request);
        return EquipmentController.respond(relations.releaseConnection(eventId, id, StrictJson.accountId(authentication), key,
                eventRevision(request), revision(request)));
    }

    // ---- 요구 ----

    @PostMapping(value = BASE + "requirements", consumes = "application/json")
    public ResponseEntity<Receipt> createRequirement(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID eventId = StrictJson.pathId(e, "eventId");
        String key = StrictJson.idempotencyKey(request);
        return EquipmentController.respond(relations.createRequirement(eventId, StrictJson.accountId(authentication), key,
                RelationRequests.requirement(body, false)));
    }

    @PatchMapping(value = BASE + "requirements/{r}", consumes = "application/json")
    public ResponseEntity<Receipt> replaceRequirement(@PathVariable("e") String e, @PathVariable("r") String r,
            @RequestBody JsonNode body, HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID eventId = StrictJson.pathId(e, "eventId");
        UUID id = StrictJson.pathId(r, "requirementId");
        String key = StrictJson.idempotencyKey(request);
        return EquipmentController.respond(relations.replaceRequirement(eventId, id, StrictJson.accountId(authentication), key,
                RelationRequests.requirement(body, true)));
    }

    @DeleteMapping(BASE + "requirements/{r}")
    public ResponseEntity<ReleaseReceipt> releaseRequirement(@PathVariable("e") String e, @PathVariable("r") String r,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of("expectedEventRevision", "expectedRevision"));
        UUID eventId = StrictJson.pathId(e, "eventId");
        UUID id = StrictJson.pathId(r, "requirementId");
        String key = StrictJson.idempotencyKey(request);
        return EquipmentController.respond(relations.releaseRequirement(eventId, id, StrictJson.accountId(authentication), key,
                eventRevision(request), revision(request)));
    }

    // ---- 운영 의존 ----

    @PostMapping(value = BASE + "dependencies", consumes = "application/json")
    public ResponseEntity<Receipt> createDependency(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID eventId = StrictJson.pathId(e, "eventId");
        String key = StrictJson.idempotencyKey(request);
        return EquipmentController.respond(relations.createDependency(eventId, StrictJson.accountId(authentication), key,
                RelationRequests.dependency(body)));
    }

    @PatchMapping(value = BASE + "dependencies/{r}", consumes = "application/json")
    public ResponseEntity<Receipt> replaceDependency(@PathVariable("e") String e, @PathVariable("r") String r,
            @RequestBody JsonNode body, HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID eventId = StrictJson.pathId(e, "eventId");
        UUID id = StrictJson.pathId(r, "dependencyId");
        String key = StrictJson.idempotencyKey(request);
        return EquipmentController.respond(relations.replaceDependency(eventId, id, StrictJson.accountId(authentication), key,
                RelationRequests.dependency(body)));
    }

    @DeleteMapping(BASE + "dependencies/{r}")
    public ResponseEntity<ReleaseReceipt> releaseDependency(@PathVariable("e") String e, @PathVariable("r") String r,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of("expectedEventRevision"));
        UUID eventId = StrictJson.pathId(e, "eventId");
        UUID id = StrictJson.pathId(r, "dependencyId");
        String key = StrictJson.idempotencyKey(request);
        return EquipmentController.respond(relations.releaseDependency(eventId, id, StrictJson.accountId(authentication), key,
                eventRevision(request)));
    }

    private static long eventRevision(HttpServletRequest request) {
        String value = request.getParameter("expectedEventRevision");
        if (value == null) throw StrictJson.invalid("expectedEventRevision", "REQUIRED");
        return StrictJson.revision(value, "expectedEventRevision");
    }

    private static long revision(HttpServletRequest request) {
        String value = request.getParameter("expectedRevision");
        if (value == null) throw StrictJson.invalid("expectedRevision", "REQUIRED");
        return StrictJson.revision(value, "expectedRevision");
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
    static class Beans {
        @Bean EquipmentRelationService equipmentRelationService(JdbcTemplate jdbc, CurrentMembers members,
                Idempotency idempotency, EventWriteSupport events) {
            return new EquipmentRelationService(jdbc, members, idempotency, events);
        }
    }
}

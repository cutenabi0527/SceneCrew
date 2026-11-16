package com.ssafy.scenecrew.equipment.controller;

import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.equipment.dto.EquipmentRequests;
import com.ssafy.scenecrew.equipment.service.EquipmentAccess;
import com.ssafy.scenecrew.equipment.service.EquipmentReadService;
import com.ssafy.scenecrew.equipment.service.EquipmentViews.Receipt;
import com.ssafy.scenecrew.equipment.service.EquipmentWriteService;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.global.operation.EventWriteSupport;
import com.ssafy.scenecrew.global.web.StrictJson;

/**
 * 장비 현황 조회 EXT01·03·05와 등록 EXT02·상태 기록 EXT04·업무 장비 점검 EXT09.
 * 인증·허용 Origin·JSON 확인은 PublicAuthFilter(eventRead·businessWrite)가 끝낸 뒤 들어온다.
 * 여기서는 query·본문 형식만 검사하고(400), 권한·상태·revision은 서비스가 트랜잭션 안에서 본다.
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class EquipmentController {
    static final int DEFAULT_LIMIT = 30;

    private final EquipmentReadService reads;
    private final EquipmentWriteService writes;

    public EquipmentController(EquipmentReadService reads, EquipmentWriteService writes) {
        this.reads = reads;
        this.writes = writes;
    }

    /** EXT01 */
    @GetMapping("/api/v1/events/{e}/equipment")
    public ResponseEntity<EquipmentReadService.AssetPage> assets(@PathVariable("e") String e, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of("cursor", "limit"));
        return noStore(reads.assets(StrictJson.pathId(e, "eventId"), accountId(authentication),
                request.getParameter("cursor"), StrictJson.limit(request, DEFAULT_LIMIT)));
    }

    /** EXT03 */
    @GetMapping("/api/v1/equipment/{a}")
    public ResponseEntity<EquipmentReadService.Detail> detail(@PathVariable("a") String a, HttpServletRequest request,
            Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        return noStore(reads.detail(StrictJson.pathId(a, "assetId"), accountId(authentication)));
    }

    /** EXT05 */
    @GetMapping("/api/v1/events/{e}/equipment-reservations")
    public ResponseEntity<EquipmentReadService.ReservationPage> reservations(@PathVariable("e") String e,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of("cursor", "limit"));
        return noStore(reads.reservations(StrictJson.pathId(e, "eventId"), accountId(authentication),
                request.getParameter("cursor"), StrictJson.limit(request, DEFAULT_LIMIT)));
    }

    /** EXT02 */
    @PostMapping(value = "/api/v1/events/{e}/equipment", consumes = "application/json")
    public ResponseEntity<Receipt> register(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID eventId = StrictJson.pathId(e, "eventId");
        String key = StrictJson.idempotencyKey(request);
        return respond(writes.register(eventId, accountId(authentication), key, EquipmentRequests.assetCreate(body)));
    }

    /** EXT04 */
    @PostMapping(value = "/api/v1/equipment/{a}/observations", consumes = "application/json")
    public ResponseEntity<Receipt> recordState(@PathVariable("a") String a, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID assetId = StrictJson.pathId(a, "assetId");
        String key = StrictJson.idempotencyKey(request);
        return respond(writes.recordState(assetId, accountId(authentication), key, EquipmentRequests.stateRecord(body)));
    }

    /** EXT09 */
    @PostMapping(value = "/api/v1/tasks/{t}/equipment-checks", consumes = "application/json")
    public ResponseEntity<Receipt> check(@PathVariable("t") String t, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        StrictJson.onlyParams(request, Set.of());
        UUID taskId = StrictJson.pathId(t, "taskId");
        String key = StrictJson.idempotencyKey(request);
        return respond(writes.check(taskId, accountId(authentication), key, EquipmentRequests.check(body)));
    }

    static UUID accountId(Authentication authentication) {
        return StrictJson.accountId(authentication);
    }

    static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    static <T> ResponseEntity<T> respond(IdempotentResult<T> result) {
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
    static class Beans {
        @Bean EquipmentReadService equipmentReadService(JdbcTemplate jdbc, EquipmentAccess access,
                @Value("${scenecrew.auth.signing-key-base64}") String key) {
            return new EquipmentReadService(jdbc, access, Base64.getDecoder().decode(key));
        }

        @Bean EquipmentWriteService equipmentWriteService(JdbcTemplate jdbc, CurrentMembers members,
                Idempotency idempotency, EventWriteSupport events) {
            return new EquipmentWriteService(jdbc, members, idempotency, events);
        }
    }
}

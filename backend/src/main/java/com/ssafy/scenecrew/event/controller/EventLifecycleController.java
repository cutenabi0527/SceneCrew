package com.ssafy.scenecrew.event.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.event.dto.EventListResponse.EventItem;
import com.ssafy.scenecrew.event.dto.EventRequests;
import com.ssafy.scenecrew.event.service.CurrentMembers;
import com.ssafy.scenecrew.event.service.EventLifecycleService;
import com.ssafy.scenecrew.event.service.PublishLinkSlots;
import com.ssafy.scenecrew.event.service.PublishVenueLayout;
import com.ssafy.scenecrew.event.service.InviteTokens;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.idempotency.service.Idempotency;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.global.text.UnicodeText;

/**
 * SC-API-022 생성·024 수정·029 공개·030 시작·031 종료. 인증·허용 Origin·JSON 확인은 PublicAuthFilter(businessWrite)가 끝낸 뒤 들어온다.
 * 여기서는 본문 형식만 검사하고(형식 오류는 400), 권한·상태·revision은 서비스가 트랜잭션 안에서 검사한다.
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class EventLifecycleController {
    /** 계약 EventCreate·EventPatch name: 1~20 코드포인트(앞뒤 공백 제거 후) */
    static final int NAME_MAX = 20;
    /** 계약 DecimalBigInt의 정규 10진 표기(선행 0·-0·공백 금지). 자릿수가 아니라 signed64 범위로 거른다. */
    private static final String REVISION = "0|[1-9][0-9]{0,18}";
    private static final String SIGNED = "0|-?[1-9][0-9]{0,18}";
    private static final String DATE = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    private static final Set<String> NULLABLE = Set.of("date", "prepStartAt", "openAt", "closeAt", "teardownEndAt",
            "locationName", "address", "totalCapacity", "expectedVisitors", "peakVisitors");
    private static final Set<String> TIMES = Set.of("prepStartAt", "openAt", "closeAt", "teardownEndAt");
    private static final Set<String> NUMBERS = Set.of("totalCapacity", "expectedVisitors", "peakVisitors",
            "desiredReserveCount");

    private final EventLifecycleService events;

    public EventLifecycleController(EventLifecycleService events) { this.events = events; }

    /** 022 */
    @PostMapping(value = "/api/v1/events", consumes = "application/json")
    public ResponseEntity<EventItem> create(@RequestBody JsonNode body, HttpServletRequest request,
            Authentication authentication) {
        EventRequests.onlyParams(request, Set.of());
        object(body, Set.of("name", "description"), Set.of("name"));
        String name = name(body.get("name"));
        String description = body.has("description") ? requiredText(body.get("description")) : "";
        return respond(events.create(EventRequests.accountId(authentication), key(request), name, description));
    }

    /** 024 */
    @PatchMapping(value = "/api/v1/events/{e}", consumes = "application/json")
    public ResponseEntity<EventItem> patch(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        EventRequests.onlyParams(request, Set.of());
        UUID eventId = EventRequests.eventId(e);
        var allowed = new java.util.HashSet<>(EventLifecycleService.PATCH_COLUMNS.keySet());
        allowed.add("expectedEventRevision");
        object(body, allowed, Set.of("expectedEventRevision"));
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String field : EventLifecycleService.PATCH_COLUMNS.keySet()) {
            if (body.has(field)) changes.put(field, patchValue(field, body.get(field)));
        }
        return noStore(events.patch(eventId, EventRequests.accountId(authentication), changes,
                revision(body.get("expectedEventRevision"))));
    }

    /** 029 */
    @PostMapping(value = "/api/v1/events/{e}/publish", consumes = "application/json")
    public ResponseEntity<EventItem> publish(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        EventRequests.onlyParams(request, Set.of());
        UUID eventId = EventRequests.eventId(e);
        object(body, Set.of("expectedEventRevision"), Set.of("expectedEventRevision"));
        return respond(events.publish(eventId, EventRequests.accountId(authentication), key(request),
                revision(body.get("expectedEventRevision"))));
    }

    /** 030 */
    @PostMapping(value = "/api/v1/events/{e}/start", consumes = "application/json")
    public ResponseEntity<EventItem> start(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        EventRequests.onlyParams(request, Set.of());
        UUID eventId = EventRequests.eventId(e);
        object(body, Set.of("expectedEventRevision"), Set.of("expectedEventRevision"));
        return noStore(events.start(eventId, EventRequests.accountId(authentication),
                revision(body.get("expectedEventRevision"))));
    }

    /** 031 */
    @PostMapping(value = "/api/v1/events/{e}/end", consumes = "application/json")
    public ResponseEntity<EventItem> end(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        EventRequests.onlyParams(request, Set.of());
        UUID eventId = EventRequests.eventId(e);
        object(body, Set.of("reason", "expectedEventRevision"), Set.of("expectedEventRevision"));
        String reason = null;
        if (body.has("reason") && !body.get("reason").isNull()) {
            reason = UnicodeText.strip(requiredText(body.get("reason")));
            if (reason.isEmpty()) reason = null;
        }
        return noStore(events.end(eventId, EventRequests.accountId(authentication), reason,
                revision(body.get("expectedEventRevision"))));
    }

    // ---- 입력 검사 -------------------------------------------------------------------------------------------

    private static Object patchValue(String field, JsonNode node) {
        if (node.isNull()) {
            if (!NULLABLE.contains(field)) throw EventRequests.invalid();
            return null;
        }
        if ("name".equals(field)) return name(node);
        if ("date".equals(field)) return date(requiredText(node));
        if (TIMES.contains(field)) return time(requiredText(node));
        if (NUMBERS.contains(field)) return number(requiredText(node));
        return requiredText(node);
    }

    private static String name(JsonNode node) {
        String name = UnicodeText.strip(requiredText(node));
        if (name.isEmpty() || name.codePointCount(0, name.length()) > NAME_MAX) throw EventRequests.invalid();
        return name;
    }

    private static LocalDate date(String value) {
        if (!value.matches(DATE)) throw EventRequests.invalid();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw EventRequests.invalid();
        }
    }

    /** RFC 3339 date-time. 시간대(Z 또는 ±hh:mm)가 반드시 있어야 한다. DB 정밀도(마이크로초)로 자른다. */
    private static Instant time(String value) {
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
                    .truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw EventRequests.invalid();
        }
    }

    /** DecimalBigInt: 정규 10진 문자열. 업무 범위는 공개(029) 때 완결성 검사가 본다. */
    private static long number(String value) {
        if (!value.matches(SIGNED)) throw EventRequests.invalid();
        return signed64(value);
    }

    private static long revision(JsonNode node) {
        String value = text(node);
        if (value == null || !value.matches(REVISION)) throw EventRequests.invalid();
        return signed64(value);
    }

    /** signed64 범위를 넘으면 400 */
    private static long signed64(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw EventRequests.invalid();
        }
    }

    private static void object(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw EventRequests.invalid();
        for (String name : node.propertyNames()) if (!allowed.contains(name)) throw EventRequests.invalid();
        for (String name : required) if (!node.has(name)) throw EventRequests.invalid();
    }

    private static String requiredText(JsonNode node) {
        String value = text(node);
        if (value == null) throw EventRequests.invalid();
        return value;
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    /** Idempotency-Key는 한 번만. UUID 형식 검사는 IdempotencyRequest가 한다. */
    private static String key(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key가 필요합니다.");
        return values.getFirst();
    }

    private static ResponseEntity<EventItem> respond(IdempotentResult<EventItem> result) {
        return ResponseEntity.status(result.httpStatus()).header("Cache-Control", "no-store").body(result.body());
    }

    private static ResponseEntity<EventItem> noStore(EventItem body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
    static class Beans {
        /** Missing invite implementation must fail startup. */
        @Bean EventLifecycleService eventLifecycleService(JdbcTemplate jdbc, CurrentMembers members,
                Idempotency idempotency, InviteTokens inviteTokens, PublishVenueLayout venueLayout,
                PublishLinkSlots linkSlots) {
            return new EventLifecycleService(jdbc, members, idempotency, inviteTokens, venueLayout, linkSlots);
        }
    }

}

package com.ssafy.scenecrew.event.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.event.dto.EventListResponse.EventItem;
import com.ssafy.scenecrew.event.dto.EventRequests;
import com.ssafy.scenecrew.event.service.EventInputService;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.text.UnicodeText;

/**
 * SC-API-026 확인한 행사 입력 반영. 인증·허용 Origin·JSON 확인은 PublicAuthFilter(businessWrite)가 끝낸 뒤 들어온다.
 * 여기서는 본문 형식과 확인한 조건 한도(V9-D42)만 검사하고(400), 권한·상태·revision은 서비스가 트랜잭션 안에서 본다.
 * 행사 수명주기 API(EventLifecycleController)와 분리해 두었다.
 */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class EventInputController {
    /** V9-D42: 확인한 조건 최대 20개, 조건 이름 1~40자, 내용 0~1,000자(코드포인트) */
    static final int MAX_CONDITIONS = 20;
    static final int NAME_MAX = 40;
    static final int VALUE_MAX = 1000;
    private static final String REVISION = "0|[1-9][0-9]{0,18}";

    private final EventInputService inputs;

    public EventInputController(EventInputService inputs) { this.inputs = inputs; }

    @PatchMapping(value = "/api/v1/events/{e}/input", consumes = "application/json")
    public ResponseEntity<EventItem> replace(@PathVariable("e") String e, @RequestBody JsonNode body,
            HttpServletRequest request, Authentication authentication) {
        EventRequests.onlyParams(request, Set.of());
        UUID eventId = EventRequests.eventId(e);
        if (body == null || !body.isObject()) throw invalid("body", "TYPE");
        for (String name : body.propertyNames())
            if (!Set.of("expectedEventRevision", "confirmedInput").contains(name)) throw invalid(name, "UNKNOWN");
        long revision = revision(body.get("expectedEventRevision"));
        LinkedHashMap<String, String> conditions = conditions(body.get("confirmedInput"));
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(inputs.replace(eventId, EventRequests.accountId(authentication), conditions, revision));
    }

    /** 전체 교체할 조건 사전. 생략·null은 400, 빈 객체는 모두 비우기다. */
    private static LinkedHashMap<String, String> conditions(JsonNode node) {
        if (node == null) throw invalid("confirmedInput", "REQUIRED");
        if (!node.isObject()) throw invalid("confirmedInput", "TYPE");
        if (node.size() > MAX_CONDITIONS) throw invalid("confirmedInput", "LIMIT");
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        int i = 0;
        for (var entry : node.properties()) {
            String path = "confirmedInput[" + i++ + "]";
            String name = entry.getKey();
            if (UnicodeText.isBlank(name)) throw invalid(path, "BLANK");
            if (name.codePointCount(0, name.length()) > NAME_MAX) throw invalid(path, "LENGTH");
            JsonNode value = entry.getValue();
            if (!value.isString()) throw invalid(path + ".value", "TYPE");
            String text = value.asString();
            if (text.codePointCount(0, text.length()) > VALUE_MAX) throw invalid(path + ".value", "LENGTH");
            out.put(name, text);
        }
        return out;
    }

    private static long revision(JsonNode node) {
        if (node == null) throw invalid("expectedEventRevision", "REQUIRED");
        if (!node.isString() || !node.asString().matches(REVISION)) throw invalid("expectedEventRevision", "FORMAT");
        try {
            return Long.parseLong(node.asString());
        } catch (NumberFormatException ex) {
            throw invalid("expectedEventRevision", "FORMAT");
        }
    }

    /** details.fields는 @Valid 실패와 같은 모양이다. */
    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.getMessage(),
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}

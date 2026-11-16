package com.ssafy.scenecrew.global.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;

import tools.jackson.databind.JsonNode;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;

import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.text.UnicodeText;

/**
 * 계약 스키마(additionalProperties=false)를 그대로 따르는 요청 본문 검사. 장비·안전 API가 함께 쓴다.
 *
 * <p>오류는 400 INVALID_REQUEST이고 details.fields[0]에 경로(예: ports[1].key)와 이유를 담는다(028·026과 같은 모양).
 * 길이는 코드포인트로 센다(V9-D35). 필수 칸의 null은 따로 허용하지 않는 한 거절한다.
 */
public final class StrictJson {
    private static final String REVISION = "0|[1-9][0-9]{0,18}";
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private StrictJson() { }

    public static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.getMessage(),
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }

    /** 객체이고, 허용한 키만 있고, 필수 키가 모두 있다. */
    public static void object(JsonNode node, String path, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw invalid(path.isEmpty() ? "body" : path, "TYPE");
        for (String name : node.propertyNames()) if (!allowed.contains(name)) throw invalid(join(path, name), "UNKNOWN");
        for (String name : required) if (!node.has(name)) throw invalid(join(path, name), "REQUIRED");
    }

    public static String join(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    /** 공백만이 아니고 maxCodePoints 이하인 문자열. 앞뒤 공백은 그대로 둔다. */
    public static String text(JsonNode node, String path, int maxCodePoints) {
        if (node == null || !node.isString()) throw invalid(path, "TYPE");
        String value = node.asString();
        if (UnicodeText.isBlank(value)) throw invalid(path, "BLANK");
        if (codePoints(value) > maxCodePoints) throw invalid(path, "LENGTH");
        return value;
    }

    /** text와 같지만 null이면 null */
    public static String nullableText(JsonNode node, String path, int maxCodePoints) {
        if (node == null) throw invalid(path, "REQUIRED");
        return node.isNull() ? null : text(node, path, maxCodePoints);
    }

    /** 이름: 앞뒤 공백을 지운 뒤 1~max 코드포인트 */
    public static String name(JsonNode node, String path, int maxCodePoints) {
        if (node == null || !node.isString()) throw invalid(path, "TYPE");
        String value = UnicodeText.strip(node.asString());
        if (value.isEmpty()) throw invalid(path, "BLANK");
        if (codePoints(value) > maxCodePoints) throw invalid(path, "LENGTH");
        return value;
    }

    public static UUID uuid(JsonNode node, String path) {
        if (node == null || !node.isString() || !node.asString().matches(UUID_FORMAT)) throw invalid(path, "FORMAT");
        return UUID.fromString(node.asString());
    }

    public static UUID nullableUuid(JsonNode node, String path) {
        if (node == null) throw invalid(path, "REQUIRED");
        return node.isNull() ? null : uuid(node, path);
    }

    /** 중복 없는 UUID 배열(순서 유지) */
    public static List<UUID> uuids(JsonNode node, String path, int max) {
        List<UUID> out = list(node, path, max, StrictJson::uuid);
        if (new HashSet<>(out).size() != out.size()) throw invalid(path, "DUPLICATE");
        return out;
    }

    /** 중복 없는 문자열 배열(순서 유지) */
    public static List<String> texts(JsonNode node, String path, int max, int maxCodePoints) {
        List<String> out = list(node, path, max, (item, at) -> text(item, at, maxCodePoints));
        if (new HashSet<>(out).size() != out.size()) throw invalid(path, "DUPLICATE");
        return out;
    }

    public static <T> List<T> list(JsonNode node, String path, int max, BiFunction<JsonNode, String, T> item) {
        if (node == null || !node.isArray()) throw invalid(path, "TYPE");
        if (node.size() > max) throw invalid(path, "LIMIT");
        List<T> out = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) out.add(item.apply(node.get(i), path + "[" + i + "]"));
        return List.copyOf(out);
    }

    /** 계약 DecimalBigInt revision: 정규 10진 문자열, signed64 범위 */
    public static long revision(JsonNode node, String path) {
        if (node == null || !node.isString() || !node.asString().matches(REVISION)) throw invalid(path, "FORMAT");
        try {
            return Long.parseLong(node.asString());
        } catch (NumberFormatException e) {
            throw invalid(path, "FORMAT");
        }
    }

    public static Long nullableRevision(JsonNode node, String path) {
        if (node == null) throw invalid(path, "REQUIRED");
        return node.isNull() ? null : revision(node, path);
    }

    /** query 문자열의 revision(DELETE의 expectedRevision 등) */
    public static long revision(String value, String field) {
        if (value == null || !value.matches(REVISION)) throw invalid(field, "FORMAT");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid(field, "FORMAT");
        }
    }

    /** RFC 3339 date-time. 시간대가 반드시 있어야 하고 DB 정밀도(마이크로초)로 자른다. */
    public static Instant time(JsonNode node, String path) {
        if (node == null || !node.isString()) throw invalid(path, "TYPE");
        try {
            return OffsetDateTime.parse(node.asString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
                    .truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw invalid(path, "FORMAT");
        }
    }

    public static Instant nullableTime(JsonNode node, String path) {
        if (node == null) throw invalid(path, "REQUIRED");
        return node.isNull() ? null : time(node, path);
    }

    public static boolean bool(JsonNode node, String path) {
        if (node == null || !node.isBoolean()) throw invalid(path, "TYPE");
        return node.asBoolean();
    }

    public static String enumValue(JsonNode node, String path, Set<String> allowed) {
        if (node == null || !node.isString() || !allowed.contains(node.asString())) throw invalid(path, "ENUM");
        return node.asString();
    }

    /** JSON 정수(소수점·지수 표기 없음) min 이상 */
    public static int integer(JsonNode node, String path, int min, int max) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) throw invalid(path, "TYPE");
        int value = node.asInt();
        if (value < min || value > max) throw invalid(path, "RANGE");
        return value;
    }

    /** JSON 수(정수·실수) */
    public static double number(JsonNode node, String path) {
        if (node == null || !node.isNumber()) throw invalid(path, "TYPE");
        double value = node.asDouble();
        if (!Double.isFinite(value)) throw invalid(path, "RANGE");
        return value;
    }

    /** 요청 query가 허용 목록 안에 있고 각 값이 하나뿐이다. */
    public static void onlyParams(HttpServletRequest request, Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey())) throw invalid(entry.getKey(), "UNKNOWN");
            if (entry.getValue().length != 1) throw invalid(entry.getKey(), "DUPLICATE");
        }
    }

    /** 목록 limit: 없으면 기본값, 1~100 */
    public static int limit(HttpServletRequest request, int defaultLimit) {
        String value = request.getParameter("limit");
        if (value == null) return defaultLimit;
        if (!value.matches("[1-9][0-9]{0,2}") || Integer.parseInt(value) > 100) throw invalid("limit", "RANGE");
        return Integer.parseInt(value);
    }

    /** Idempotency-Key는 한 번만. UUID 형식 검사는 IdempotencyRequest가 한다. */
    public static String idempotencyKey(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key가 필요합니다.");
        return values.getFirst();
    }

    /** 경로의 UUID. 형식이 틀리면 400(다른 행사 API와 같음) */
    public static UUID pathId(String value, String name) {
        if (value == null || !value.matches(UUID_FORMAT)) throw invalid(name, "FORMAT");
        return UUID.fromString(value);
    }

    /** 현재 세션의 계정. PublicAuthFilter를 거친 요청에만 쓴다. */
    public static UUID accountId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return principal.accountId();
    }

    public static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}

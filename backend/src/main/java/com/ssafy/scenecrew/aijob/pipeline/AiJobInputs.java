package com.ssafy.scenecrew.aijob.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.json.JsonMapper;

/**
 * AI 작업 입력(ai_job.input)과 inputHash 계산 규칙. 접수(AiJobService)와 계약 예시 생성 테스트가 같은 규칙을 쓴다.
 */
public final class AiJobInputs {
    private AiJobInputs() {
    }

    /** 키를 재귀적으로 정렬한 JSON. 같은 입력이면 같은 inputHash가 나오게 한다. */
    public static String canonicalJson(JsonMapper jsonMapper, Object payload) {
        Object tree = jsonMapper.convertValue(payload, Object.class);
        if (!(tree instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("AI 작업 입력은 JSON 객체여야 합니다.");
        }
        return jsonMapper.writeValueAsString(sorted(tree));
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), sorted(v)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(v -> result.add(sorted(v)));
            return result;
        }
        return value;
    }
}

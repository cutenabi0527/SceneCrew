package com.ssafy.scenecrew.aijob.contract;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * AI 서버와 주고받는 JSON 전용 설정. FE 요청·응답용 기본 설정과 분리한다.
 *
 * AI 결과는 외부 입력이므로 계약(JSON Schema)과 다르면 읽는 단계에서 바로 거절한다.
 * - 모르는 필드, 같은 키 중복, JSON 뒤에 붙은 값은 거절
 * - 타입을 바꿔 받지 않음: true·"12"·1.5를 정수로, 12를 문자열로, 숫자를 Enum·시각으로 받지 않음
 * - 시각은 오프셋이 있는 ISO 8601 문자열만 받음. 기본 Instant 읽기는 숫자(epoch)를 받고 위 설정이 적용되지 않아 따로 둔다
 * Spring 빈으로 등록하지 않는다. 빈으로 두면 FE용 기본 JsonMapper를 대신할 수 있기 때문이다.
 */
public final class AiContractJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfigDefaults(config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
            .addModule(new SimpleModule().addDeserializer(Instant.class, new StringOnlyInstantDeserializer()))
            .build();

    private AiContractJson() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }

    private static final class StringOnlyInstantDeserializer extends StdDeserializer<Instant> {
        StringOnlyInstantDeserializer() {
            super(Instant.class);
        }

        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctxt) {
            if (!p.hasToken(JsonToken.VALUE_STRING)) {
                return (Instant) ctxt.handleUnexpectedToken(Instant.class, p);
            }
            try {
                return OffsetDateTime.parse(p.getString()).toInstant();
            } catch (DateTimeParseException e) {
                return (Instant) ctxt.handleWeirdStringValue(Instant.class, p.getString(), e.getMessage());
            }
        }
    }
}

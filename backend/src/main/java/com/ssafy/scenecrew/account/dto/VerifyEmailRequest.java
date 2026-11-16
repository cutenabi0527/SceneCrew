package com.ssafy.scenecrew.account.dto;

import java.util.UUID;
import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ssafy.scenecrew.global.error.*;

/** Numeric JSON and coercion are rejected; leading zero is part of the proof. */
public record VerifyEmailRequest(UUID requestId,
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String code) {
    public static VerifyEmailRequest parse(JsonNode body) {
        if (!body.isObject() || body.size()!=2 || !body.has("code") || !body.get("code").isString()
                || !body.get("code").asString().matches("[0-9]{6}")) invalid();
        return new VerifyEmailRequest(requestId(body),body.get("code").asString());
    }
    public static UUID reference(JsonNode body) {
        if (!body.isObject() || body.size()!=1) invalid();
        return requestId(body);
    }
    private static UUID requestId(JsonNode body) {
        if (!body.has("requestId") || !body.get("requestId").isString()) invalid();
        String value=body.get("requestId").asString();
        try {
            UUID id=UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) invalid();
            return id;
        } catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
    }
    private static void invalid() { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
    @Override public String toString() { return "VerificationRequest[redacted]"; }
}

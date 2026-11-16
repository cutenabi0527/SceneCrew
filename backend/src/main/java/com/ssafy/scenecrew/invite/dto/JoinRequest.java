package com.ssafy.scenecrew.invite.dto;

import java.util.UUID;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import tools.jackson.databind.JsonNode;

public record JoinRequest(UUID workGroupId, boolean available, boolean isReserve, long expectedEventRevision) {
    public static JoinRequest parse(JsonNode body) {
        if (!body.isObject() || body.size() != 4 || !body.has("workGroupId")
                || !body.get("workGroupId").isString() || !body.has("available")
                || !body.get("available").isBoolean() || !body.has("isReserve")
                || !body.get("isReserve").isBoolean() || !body.has("expectedEventRevision")
                || !body.get("expectedEventRevision").isString()) throw invalid();
        String revision = body.get("expectedEventRevision").asString();
        if (!revision.matches("0|[1-9][0-9]*")) throw invalid();
        try {
            return new JoinRequest(uuid(body.get("workGroupId").asString()), body.get("available").asBoolean(),
                    body.get("isReserve").asBoolean(), Long.parseLong(revision));
        } catch (IllegalArgumentException e) { throw invalid(); }
    }

    public static UUID uuid(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid();
        return UUID.fromString(value);
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

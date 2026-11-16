package com.ssafy.scenecrew.event.dto;

import java.util.UUID;
import tools.jackson.databind.JsonNode;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

public record MemberAvailabilityRequest(boolean available, long expectedEventRevision) {
    public static MemberAvailabilityRequest parse(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 2
                || !body.has("available") || !body.get("available").isBoolean()
                || !body.has("expectedEventRevision") || !body.get("expectedEventRevision").isString())
            throw invalid();
        String revision = body.get("expectedEventRevision").asString();
        if (!revision.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try {
            return new MemberAvailabilityRequest(body.get("available").asBoolean(), Long.parseLong(revision));
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    public static UUID memberId(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid();
        return UUID.fromString(value);
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

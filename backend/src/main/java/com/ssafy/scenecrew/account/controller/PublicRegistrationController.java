package com.ssafy.scenecrew.account.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.ssafy.scenecrew.account.dto.RegisterRequest;
import com.ssafy.scenecrew.account.infra.RedisPendingRegistrations;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;

@RestController
@ConditionalOnProperty(name="scenecrew.auth.public-enabled", havingValue="true")
public class PublicRegistrationController {
    private final EmailRegistrationService registrations;
    public PublicRegistrationController(EmailRegistrationService registrations) { this.registrations = registrations; }

    @PostMapping(value="/api/v1/auth/register", consumes="application/json")
    public ResponseEntity<RedisPendingRegistrations.Accepted> register(@RequestBody JsonNode body) {
        if (!body.isObject() || body.size() != 4 || !text(body, "email") || !text(body, "password")
                || !text(body, "displayName") || !body.has("consent")) invalid();
        var consent = body.get("consent");
        if (!consent.isObject() || consent.size() != 4 || !bool(consent, "service")
                || !bool(consent, "voice") || !bool(consent, "push") || !bool(consent, "externalAi")) invalid();
        var input = new RegisterRequest(body.get("email").asString(), body.get("password").asString(),
                body.get("displayName").asString(), new RegisterRequest.Consent(consent.get("service").asBoolean(),
                consent.get("voice").asBoolean(), consent.get("push").asBoolean(), consent.get("externalAi").asBoolean()));
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(registrations.register(input));
    }

    private static boolean text(JsonNode body, String name) { return body.has(name) && body.get(name).isString(); }
    private static boolean bool(JsonNode body, String name) { return body.has(name) && body.get(name).isBoolean(); }
    private static void invalid() { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
}

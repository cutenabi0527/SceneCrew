package com.ssafy.scenecrew.account.controller;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.ssafy.scenecrew.account.service.PasswordResetService;
import com.ssafy.scenecrew.global.error.*;

@RestController
@ConditionalOnProperty(name="scenecrew.auth.public-enabled",havingValue="true")
public class PublicPasswordResetController {
    private final PasswordResetService resets;
    public PublicPasswordResetController(PasswordResetService resets) { this.resets=resets; }
    @PostMapping(value="/api/v1/auth/password-reset/request",consumes="application/json")
    public ResponseEntity<Map<String,String>> request(@RequestBody JsonNode body) {
        if(!body.isObject() || body.size()!=1 || !text(body,"email")) invalid();
        resets.request(body.get("email").asString());
        return ResponseEntity.accepted().header("Cache-Control","no-store")
                .body(Map.of("message",PasswordResetService.ACCEPTED));
    }
    @PostMapping(value="/api/v1/auth/password-reset/confirm",consumes="application/json")
    public ResponseEntity<Void> confirm(@RequestBody JsonNode body) {
        if(!body.isObject() || body.size()!=2 || !text(body,"recoveryProof") || !text(body,"password")) invalid();
        resets.confirm(body.get("recoveryProof").asString(),body.get("password").asString());
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
    private static boolean text(JsonNode body,String name) { return body.has(name) && body.get(name).isString(); }
    private static void invalid() { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
}

package com.ssafy.scenecrew.notification.controller;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.TransactionException;

import com.ssafy.scenecrew.account.service.SessionAuthenticator.Principal;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.notification.service.DeviceRegistration;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/me/devices/{deviceId}")
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
class DeviceController {
    private final DeviceRegistration registration;

    DeviceController(DeviceRegistration registration) {
        this.registration = registration;
    }

    @PutMapping(consumes = "application/json")
    ResponseEntity<Registered> register(@PathVariable UUID deviceId, @RequestBody JsonNode body,
            Authentication authentication) {
        if (!body.isObject() || body.size() != 2
                || !body.path("token").isString() || !body.path("deviceProof").isString()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        try {
            registration.register((Principal) authentication.getPrincipal(), deviceId,
                    body.path("deviceProof").asString(), body.path("token").asString());
        } catch (DataAccessException | TransactionException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Registered(deviceId, true));
    }

    @DeleteMapping
    ResponseEntity<Void> unregister(@PathVariable UUID deviceId, @RequestHeader("X-Device-Proof") String proof,
            Authentication authentication) {
        Principal principal = (Principal) authentication.getPrincipal();
        if (!"ANDROID".equals(principal.channel())) throw new BusinessException(ErrorCode.AUTH_CHANNEL_MISMATCH);
        try {
            registration.unregister(principal, deviceId, proof);
        } catch (DataAccessException | TransactionException failure) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    record Registered(UUID deviceId, boolean registered) { }
}

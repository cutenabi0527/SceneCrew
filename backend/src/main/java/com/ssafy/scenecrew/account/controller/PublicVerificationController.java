package com.ssafy.scenecrew.account.controller;

import java.time.Clock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import com.ssafy.scenecrew.account.dto.Credentials;
import com.ssafy.scenecrew.account.dto.*;
import com.ssafy.scenecrew.account.filter.PublicAuthFilter;
import com.ssafy.scenecrew.account.infra.RedisPendingRegistrations;
import com.ssafy.scenecrew.account.service.*;

@RestController
@ConditionalOnProperty(name="scenecrew.auth.public-enabled",havingValue="true")
public class PublicVerificationController {
    private final EmailVerificationService verification;
    private final Clock clock;
    public PublicVerificationController(EmailVerificationService verification,Clock clock) {
        this.verification=verification; this.clock=clock;
    }
    @PostMapping(value="/api/v1/auth/verify-email",consumes="application/json")
    public ResponseEntity<?> verify(@RequestBody JsonNode body,HttpServletRequest request) {
        var input=VerifyEmailRequest.parse(body);
        var credentials=(Credentials)request.getAttribute(PublicAuthFilter.CREDENTIALS);
        var result=verification.verify(input.requestId(),input.code(),credentials);
        var response="WEB".equals(credentials.channel())?LoginResponses.web(result,clock):LoginResponses.android(result);
        return ResponseEntity.status(201).headers(response.getHeaders()).body(response.getBody());
    }
    @PostMapping(value="/api/v1/auth/resend-verification",consumes="application/json")
    public ResponseEntity<RedisPendingRegistrations.Accepted> resend(@RequestBody JsonNode body) {
        return ResponseEntity.accepted().header("Cache-Control","no-store")
                .body(verification.resend(VerifyEmailRequest.reference(body)));
    }
}

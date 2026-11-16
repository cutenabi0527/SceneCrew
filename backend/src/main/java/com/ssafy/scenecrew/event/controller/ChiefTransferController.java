package com.ssafy.scenecrew.event.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.event.service.ChiefTransferService;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

@RestController
public class ChiefTransferController {
    private final ChiefTransferService transfers;

    public ChiefTransferController(ChiefTransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping("/api/v1/events/{e}/chief-transfer")
    public ChiefTransferService.Result transfer(@PathVariable("e") UUID eventId,
            @Valid @RequestBody Request request, Authentication authentication) {
        return transfers.transfer(eventId, accountId(authentication), request.successorMemberId(),
                request.previousChiefWorkGroupId(), revision(request.expectedEventRevision()));
    }

    private UUID accountId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return principal.accountId();
    }

    private long revision(Object value) {
        if (value instanceof String text && text.matches("0|[1-9][0-9]{0,18}")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                // The public revision is bounded by signed BIGINT.
            }
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }

    public record Request(@NotNull UUID successorMemberId, @NotNull UUID previousChiefWorkGroupId,
                          @NotNull Object expectedEventRevision) {}
}

package com.ssafy.scenecrew.event;

import java.util.UUID;

import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.event.service.ChiefTransferService;
import com.ssafy.scenecrew.event.controller.ChiefTransferController;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChiefTransferControllerTest {
    private final ChiefTransferService service = mock(ChiefTransferService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChiefTransferController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private final UUID event = UUID.randomUUID(), account = UUID.randomUUID();
    private final UUID successor = UUID.randomUUID(), group = UUID.randomUUID();
    private final String path = "/api/v1/events/" + event + "/chief-transfer";

    private String request(String revision) {
        return """
                {"successorMemberId":"%s","previousChiefWorkGroupId":"%s","expectedEventRevision":%s}
                """.formatted(successor, group, revision);
    }

    private UsernamePasswordAuthenticationToken principal() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new SessionAuthenticator.Principal(account, UUID.randomUUID(), UUID.randomUUID(), 0, "WEB"),
                null, java.util.List.of());
    }

    @Test
    void unauthenticatedRequestDoesNotReachService() throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(request("\"1\"")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(service);
    }

    @Test
    void numericRevisionIsRejectedBeforeService() throws Exception {
        mvc.perform(post(path).principal(principal()).contentType(MediaType.APPLICATION_JSON).content(request("1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void revisionConflictUsesBusinessCode() throws Exception {
        when(service.transfer(event, account, successor, group, 1L))
                .thenThrow(new BusinessException(ErrorCode.VERSION_CONFLICT));
        mvc.perform(post(path).principal(principal()).contentType(MediaType.APPLICATION_JSON).content(request("\"1\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }
}

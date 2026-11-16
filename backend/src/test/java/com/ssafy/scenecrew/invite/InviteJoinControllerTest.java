package com.ssafy.scenecrew.invite;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.global.filter.RequestIdFilter;
import com.ssafy.scenecrew.global.idempotency.service.IdempotentResult;
import com.ssafy.scenecrew.invite.controller.InviteJoinController;
import com.ssafy.scenecrew.invite.dto.JoinRequest;
import com.ssafy.scenecrew.invite.dto.JoinResult;
import com.ssafy.scenecrew.invite.service.InviteJoinService;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC mapping/DTO/error tests ONLY. No security filters: these do not prove product HTTP integration. */
class InviteJoinControllerTest {
    InviteJoinService service;
    MockMvc mvc;
    UUID group=UUID.randomUUID();
    String key=UUID.randomUUID().toString();
    SessionAuthenticator.Principal principal=new SessionAuthenticator.Principal(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),3,"WEB");
    UsernamePasswordAuthenticationToken authentication=new UsernamePasswordAuthenticationToken(principal,null,List.of());
    String body() { return "{\"workGroupId\":\""+group+"\",\"available\":true,\"isReserve\":false,\"expectedEventRevision\":\"1\"}"; }
    @BeforeEach void setup() {
        service=mock(InviteJoinService.class);
        mvc=MockMvcBuilders.standaloneSetup(new InviteJoinController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @Test void successUsesOfficialPathHeaderAndWireShape() throws Exception {
        UUID member=UUID.randomUUID();
        when(service.join(principal,"fixture",key,new JoinRequest(group,true,false,1)))
                .thenReturn(IdempotentResult.of(200, new JoinResult(new JoinResult.Participation(member,"1",true,"WORKER",group),"2","UNKNOWN"), "INVITE_JOINED", Map.of()));
        mvc.perform(post("/api/v1/invites/fixture/join").principal(authentication)
                        .header("Idempotency-Key",key).contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.participation.memberId").value(member.toString()))
                .andExpect(jsonPath("$.participation.participationRevision").value("1"))
                .andExpect(jsonPath("$.eventRevision").value("2"))
                .andExpect(jsonPath("$.initialAssignment").value("UNKNOWN"));
        verify(service).join(principal,"fixture",key,new JoinRequest(group,true,false,1));
    }
    @Test void absentAuthenticationIsRejectedBeforeBusinessLogic() throws Exception {
        mvc.perform(post("/api/v1/invites/fixture/join").header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content(body()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(service);
    }
    @Test void missingMalformedKeyAndUnknownBodyFieldsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/invites/fixture/join").principal(authentication).contentType("application/json").content(body()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/invites/fixture/join").principal(authentication).header("Idempotency-Key","1-1-1-1-1")
                        .contentType("application/json").content(body()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/invites/fixture/join").principal(authentication).header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content(body().replace("}",",\"extra\":true}")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void joinContractErrorsUseCommonAdviceAndPreserveStatusCodeAndRequestId() throws Exception {
        for (ErrorCode code : List.of(ErrorCode.VERSION_CONFLICT, ErrorCode.INVALID_REFERENCE, ErrorCode.EVENT_FULL)) {
            doThrow(new BusinessException(code)).when(service).join(any(),anyString(),anyString(),any());
            mvc.perform(post("/api/v1/invites/fixture/join").principal(authentication)
                            .header("Idempotency-Key",UUID.randomUUID()).requestAttr(RequestIdFilter.ATTRIBUTE,"test-request")
                            .contentType("application/json").content(body()))
                    .andExpect(status().is(code.getStatus().value())).andExpect(jsonPath("$.code").value(code.name()))
                    .andExpect(jsonPath("$.requestId").value("test-request"));
        }
    }
}

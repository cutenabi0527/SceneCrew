package com.ssafy.scenecrew.account;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.ssafy.scenecrew.account.dto.Credentials;
import com.ssafy.scenecrew.account.infra.RedisLoginSessions;
import com.ssafy.scenecrew.account.service.*;
import com.ssafy.scenecrew.global.error.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicLoginServiceTest {
    final EmailLoginService login=mock(EmailLoginService.class);
    final LoginFailureLimiter limiter=mock(LoginFailureLimiter.class);
    final SessionAuthenticator auth=mock(SessionAuthenticator.class);
    final RedisLoginSessions sessions=mock(RedisLoginSessions.class);
    final PublicLoginService service=new PublicLoginService(login,limiter,auth,sessions);
    final List<String> keys=List.of("synthetic-attempts","synthetic-block");
    @Test void unknownSessionRegistrationNeverResetsOrCountsFailure() {
        when(limiter.keys("fixture@example.invalid")).thenReturn(keys);
        when(login.login(anyString(),anyString(),anyString(),any(EmailLoginService.Registration.class)))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        assertThatThrownBy(()->service.login("fixture@example.invalid","SyntheticPass8!",new Credentials("WEB",null)))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verify(limiter,never()).success(anyList()); verify(limiter,never()).failure(anyList());
    }
    @Test void onlyCredentialRejectionCountsAsFailure() {
        when(limiter.keys("fixture@example.invalid")).thenReturn(keys);
        when(login.login(anyString(),anyString(),anyString(),any(EmailLoginService.Registration.class)))
                .thenThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        assertThatThrownBy(()->service.login("fixture@example.invalid","WrongPassword8!",new Credentials("WEB",null)))
                .isInstanceOf(BusinessException.class);
        verify(limiter).failure(keys);verify(limiter,never()).success(anyList());
    }
    @Test void publicLoginCommitsRegistrationAndResetTogetherWithoutASecondResetCall() {
        when(limiter.keys("fixture@example.invalid")).thenReturn(keys);
        var result=mock(EmailLoginService.Result.class);
        var account=mock(LocalCredentialVerifier.VerifiedAccount.class);
        var tokens=mock(LoginTokens.class);
        when(login.login(anyString(),anyString(),anyString(),any(EmailLoginService.Registration.class)))
                .thenAnswer(call->{
                    EmailLoginService.Registration registration=call.getArgument(3);
                    registration.register(account,tokens,"WEB");return result;
                });
        assertThat(service.login("fixture@example.invalid","SyntheticPass8!",new Credentials("WEB",null))).isSameAs(result);
        var order=inOrder(limiter,sessions);order.verify(limiter).check(keys);
        order.verify(sessions).registerReplacingAndReset(account,tokens,"WEB",null,null,keys);
        verify(limiter,never()).success(anyList());
    }
}

package com.ssafy.scenecrew.global.error;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Security 필터의 401/403은 컨트롤러 밖에서 나서 @RestControllerAdvice에 닿지 않는다.
 * 예외를 MVC 예외 처리로 넘겨 GlobalExceptionHandler와 같은 본문을 쓰게 한다.
 */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final HandlerExceptionResolver resolver;

    public SecurityErrorHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) {
        resolver.resolveException(request, response, null, authException);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) {
        resolver.resolveException(request, response, null, accessDeniedException);
    }
}

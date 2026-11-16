package com.ssafy.scenecrew.global.filter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청마다 requestId를 정해 응답 헤더(X-Request-Id), 오류 본문, 로그(MDC)에 같은 값을 남긴다.
 * Security보다 먼저 실행해 401/403 응답에도 requestId가 들어가게 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    public static final String MDC_KEY = "requestId";

    // 앞단(nginx 등)이 넘긴 값은 형식이 안전할 때만 이어 쓴다. 로그에 임의 문자열이 섞이지 않게 한다.
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request);
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    // 오류 처리(/error)로 다시 들어온 요청도 같은 requestId를 쓰도록 거르지 않는다.
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private String resolve(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String existing) {
            return existing;
        }
        String incoming = request.getHeader(HEADER);
        if (incoming != null && ALLOWED.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    public static String current(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String id ? id : null;
    }
}

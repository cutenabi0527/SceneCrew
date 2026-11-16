package com.ssafy.scenecrew.notification.filter;

import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import com.ssafy.scenecrew.account.dto.Credentials;
import com.ssafy.scenecrew.account.controller.AuthTransport;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.*;
import com.ssafy.scenecrew.notification.service.NotificationCursor;

/** Only the two #239 routes. Reuses current transport/session policy without editing shared auth code. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="scenecrew.auth.public-enabled", havingValue="true")
public class NotificationSecurity {
    static boolean matches(HttpServletRequest r) {
        return "GET".equals(r.getMethod()) && "/api/v1/me/notifications".equals(r.getServletPath())
                || "POST".equals(r.getMethod()) && r.getServletPath().matches("/api/v1/notifications/[^/]+/read");
    }
    @Bean NotificationCursor notificationCursor(@Value("${scenecrew.auth.signing-key-base64}") String key) {
        return new NotificationCursor(Base64.getDecoder().decode(key));
    }
    @Bean @Order(10)
    SecurityFilterChain notificationFilterChain(HttpSecurity http, AuthTransport transport, SessionAuthenticator auth,
            SecurityErrorHandler errors, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
                    throws ServletException, IOException {
                Credentials credentials = null;
                try {
                    credentials = transport.inspect(r, false);
                    var principal = auth.authenticate(credentials.access());
                    if (!principal.channel().equals(credentials.channel())) throw new BusinessException(ErrorCode.AUTH_CHANNEL_MISMATCH);
                    if ("POST".equals(r.getMethod()) && "WEB".equals(credentials.channel())) {
                        // #239-only approved bodyless write boundary. CORS is not authorization.
                        var origins = Collections.list(r.getHeaders("Origin"));
                        if (origins.size() != 1 || "null".equals(origins.getFirst()) || !transport.webOrigin(origins.getFirst()))
                            throw new BusinessException(ErrorCode.FORBIDDEN);
                    }
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
                    SecurityContextHolder.setContext(context);
                } catch (BusinessException failure) {
                    if (credentials != null && "ANDROID".equals(credentials.channel()) && credentials.access() != null
                            && failure.getErrorCode() == ErrorCode.UNAUTHENTICATED)
                        s.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                    resolver.resolveException(r, s, null, failure); return;
                }
                try { chain.doFilter(r, s); }
                finally { SecurityContextHolder.clearContext(); }
            }
        };
        return http.securityMatcher(NotificationSecurity::matches).cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(filter, CsrfFilter.class)
                // Exact WEB Origin / existing Android transport check runs above first.
                .csrf(c -> c.ignoringRequestMatchers(NotificationSecurity::matches))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors)).build();
    }
}

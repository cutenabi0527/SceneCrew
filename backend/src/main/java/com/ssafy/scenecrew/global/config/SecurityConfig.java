package com.ssafy.scenecrew.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.util.ServletRequestPathUtils;
import com.ssafy.scenecrew.account.filter.PublicAuthFilter;

import com.ssafy.scenecrew.global.error.SecurityErrorHandler;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;

@Configuration
public class SecurityConfig {
    private static final PathPatternRequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorHandler errorHandler,
            ObjectProvider<PublicAuthFilter> candidateAuth,
            ObjectProvider<com.ssafy.scenecrew.account.oauth.OAuthWebController> candidateOAuth,
            ObjectProvider<RequestMappingHandlerMapping> controllers,
            ObjectProvider<AbstractUrlHandlerMapping> urlHandlers,
            @Value("${scenecrew.equipment.collector.enabled:false}") boolean collectorIngest,
            @Value("${scenecrew.safety.camera.enabled:false}") boolean cameraIngest) throws Exception {
        // 기본은 헬스체크 외 차단. 후보 설정이 있을 때만 인증 후보 경로를 연결한다.
        // 오류 처리(/error)로 넘어온 요청은 막지 않아야 공통 오류 본문이 나간다.
        var auth = candidateAuth.getIfAvailable();
        boolean oauth = candidateOAuth.getIfAvailable()!=null;
        if (auth != null) {
            http.addFilterBefore(auth, CsrfFilter.class)
                    .addFilterAfter(auth.missingApiFallback(request -> isMissingApi(request, controllers, urlHandlers)),
                            CsrfFilter.class)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    // H01 is enforced before this exclusion. Business writes (#282) are checked by PublicAuthFilter
                    // (allowed WEB Origin + JSON, V9-D34). Chief transfer checks the channel and allowed Origin.
                    // All other unsafe methods keep Spring CSRF.
                    .csrf(csrf -> csrf.ignoringRequestMatchers(r -> PublicAuthFilter.authenticationPost(r) || PublicAuthFilter.profilePatch(r)
                            || PublicAuthFilter.accountDeactivate(r) || PublicAuthFilter.consentPatch(r)
                            || PublicAuthFilter.inviteJoin(r) || PublicAuthFilter.chiefTransfer(r) || PublicAuthFilter.businessWrite(r)
                            || PublicAuthFilter.businessUpload(r) || PublicAuthFilter.businessDelete(r) || PublicAuthFilter.deviceWrite(r)
                            || PublicAuthFilter.cameraFrame(r)
                            || oauth && PublicAuthFilter.identityWrite(r)
                            || oauth && "POST".equals(r.getMethod()) && "/api/v1/auth/oauth/exchange".equals(r.getServletPath())));
        }
        // 장비 수집기 경로(#330)는 사용자 세션·Cookie를 쓰지 않고 수집기 토큰으로 서비스가 직접 확인한다.
        // 연결 코드 교환(pair)·찾은 수신기 보고(receivers, #394)도 같다. 코드·토큰은 CollectorPairingService가 확인한다.
        String collectorPath = "/internal/equipment-collectors/*/observations";
        if (collectorIngest) {
            http.csrf(csrf -> csrf.ignoringRequestMatchers(
                    request -> "POST".equals(request.getMethod()) && request.getServletPath()
                            .matches("/internal/equipment-collectors/[^/]+/observations|/internal/equipment-collectors/pair")
                            || "PUT".equals(request.getMethod()) && request.getServletPath()
                            .matches("/internal/equipment-collectors/[^/]+/receivers")));
        }
        // 카메라 수집기 경로(#349)도 같다. 수집기 토큰은 CameraFrameService가 확인한다.
        String cameraPath = "/internal/camera-collectors/*/frames";
        if (cameraIngest) {
            http.csrf(csrf -> csrf.ignoringRequestMatchers(
                    request -> "POST".equals(request.getMethod()) && request.getServletPath()
                            .matches("/internal/camera-collectors/[^/]+/frames")));
        }
        // #263 CorsConfig의 허용 목록으로 preflight를 인증·denyAll보다 먼저 처리한다.
        return http.cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> {
                    authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers("/actuator/health").permitAll()
                            // AI 서버(STT)의 음성 다운로드. 사용자 세션이 아니라 서비스 토큰을 컨트롤러가 직접 확인한다(#266).
                            .requestMatchers(HttpMethod.GET, "/internal/audio-assets/*").permitAll()
                            // AI 서버(문서 추출)의 문서 다운로드. 음성과 같이 서비스 토큰을 컨트롤러가 직접 확인한다(#236).
                            .requestMatchers(HttpMethod.GET, "/internal/intake-documents/*").permitAll();
                    if (collectorIngest) {
                        authorize.requestMatchers(HttpMethod.POST, collectorPath, "/internal/equipment-collectors/pair").permitAll()
                                .requestMatchers(HttpMethod.PUT, "/internal/equipment-collectors/*/receivers").permitAll();
                    }
                    if (cameraIngest) {
                        authorize.requestMatchers(HttpMethod.POST, cameraPath).permitAll();
                    }
                    if (auth != null) {
                        authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/auth/refresh", "/api/v1/auth/register",
                                        "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification",
                                        "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm").permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/v1/me", "/api/v1/events", "/api/v1/events/*",
                                        "/api/v1/events/*/work-groups", "/api/v1/events/*/members", "/api/v1/events/*/issues",
                                        "/api/v1/issues/*", "/api/v1/events/*/change-sets", "/api/v1/events/*/my-changes",
                                        "/api/v1/change-sets/*", "/api/v1/change-sets/*/evidence/*", "/api/v1/ai-jobs/*",
                                        "/api/v1/events/*/channels", "/api/v1/events/*/messages", "/api/v1/tasks/*", "/api/v1/events/*/tasks",
                                        "/api/v1/events/*/plan").authenticated()
                                // 장비 현황 조회 EXT01·03·05. 조회 자격(V9-D13)은 서비스가 Member로 본다.
                                .requestMatchers(HttpMethod.GET, "/api/v1/events/*/equipment", "/api/v1/equipment/*",
                                        "/api/v1/events/*/equipment-reservations", "/api/v1/recovery-simulations/*",
                                        "/api/v1/issues/*/recovery-simulations/latest").authenticated()
                                // 장비 등록 EXT02·상태 기록 EXT04·업무 장비 점검 EXT09. 쓰기 검사는 PublicAuthFilter(businessWrite)가 한다.
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/equipment", "/api/v1/equipment/*/observations",
                                        "/api/v1/tasks/*/equipment-checks", "/api/v1/events/*/recovery-simulations").authenticated()
                                // 장비 관계 R1 조회·추가·교체·해제
                                .requestMatchers(HttpMethod.GET, "/api/v1/events/*/equipment-context").authenticated()
                                // 안전 지도·관측·카메라 EXT10·11·13·14·21~24, 카메라 현재 프레임 Q2(CHIEF 전용은 서비스가 본다).
                                .requestMatchers(HttpMethod.GET, "/api/v1/events/*/safety-map", "/api/v1/events/*/safety-observations",
                                        "/api/v1/events/*/safety-support-candidates",
                                        "/api/v1/safety-observations/*", "/api/v1/events/*/safety-cameras",
                                        "/api/v1/safety-cameras/*", "/api/v1/safety-cameras/*/snapshot",
                                        "/api/v1/events/*/safety-map/background").authenticated()
                                .requestMatchers(HttpMethod.PATCH, "/api/v1/events/*/safety-map", "/api/v1/safety-cameras/*").authenticated()
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/safety-cameras").authenticated()
                                // 카메라 웹 연결·해제·프레임, 내 연결 목록 L01(#393). 자격은 서비스가 Member·담당 업무로 본다.
                                .requestMatchers(HttpMethod.POST, "/api/v1/safety-cameras/*/browser-link", "/api/v1/safety-cameras/*/frames").authenticated()
                                .requestMatchers(HttpMethod.DELETE, "/api/v1/safety-cameras/*/browser-link").authenticated()
                                .requestMatchers(HttpMethod.GET, "/api/v1/events/*/my-links").authenticated()
                                // 장비 연결 코드·수신기 연결·해제(#394). 자격은 서비스가 Member·담당 업무로 본다.
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/equipment-links/codes").authenticated()
                                .requestMatchers(HttpMethod.PUT, "/api/v1/equipment/*/link").authenticated()
                                .requestMatchers(HttpMethod.DELETE, "/api/v1/equipment/*/link").authenticated()
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/equipment-connections",
                                        "/api/v1/events/*/equipment-requirements", "/api/v1/events/*/equipment-dependencies").authenticated()
                                .requestMatchers(HttpMethod.PATCH, "/api/v1/events/*/equipment-connections/*",
                                        "/api/v1/events/*/equipment-requirements/*", "/api/v1/events/*/equipment-dependencies/*").authenticated()
                                .requestMatchers(HttpMethod.DELETE, "/api/v1/events/*/equipment-connections/*",
                                        "/api/v1/events/*/equipment-requirements/*", "/api/v1/events/*/equipment-dependencies/*").authenticated()
                                // 계획 초안 저장(028). 쓰기 검사는 PublicAuthFilter(businessWrite)가 한다.
                                .requestMatchers(HttpMethod.PUT, "/api/v1/events/*/draft-plan").authenticated()
                                // 미확정 장비·장소 초안 Q3(#355). 총괄 확인은 서비스가 Member로 본다.
                                .requestMatchers(HttpMethod.GET, "/api/v1/events/*/unconfirmed-draft").authenticated()
                                .requestMatchers(HttpMethod.PUT, "/api/v1/events/*/unconfirmed-draft").authenticated()
                                // 채팅 WebSocket 연결(#238): 인증·Origin·참여 확인은 ChatHandshakeInterceptor·첫 AUTH 프레임이 한다.
                                .requestMatchers(HttpMethod.GET, "/api/v1/events/*/ws").permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/initial-assignments", "/api/v1/events/*/issues", "/api/v1/issues/*/reports",
                                        "/api/v1/reports/*/analyze", "/api/v1/events/*/change-sets", "/api/v1/change-sets/*/preview",
                                        "/api/v1/change-sets/*/apply", "/api/v1/change-sets/*/discard",
                                        "/api/v1/change-acknowledgments/*/ack", "/api/v1/tasks/*/start", "/api/v1/tasks/*/complete").authenticated()
                                .requestMatchers(HttpMethod.PATCH, "/api/v1/issues/*", "/api/v1/issues/*/situation",
                                        "/api/v1/change-sets/*",
                                        "/api/v1/members/*/availability", "/api/v1/tasks/*/checklist").authenticated()
                                // 팀원 그룹 이동·예비 지정(038), 행사 이탈(040)
                                .requestMatchers(HttpMethod.PATCH, "/api/v1/members/*").authenticated()
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/leave").authenticated()
                                // 음성 전사 접수·원본 정리(#340 060·061). 쓰기 검사는 PublicAuthFilter가 한다.
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/transcriptions").authenticated()
                                // 입력 문서 등록·정리·다운로드(046·047·048). 쓰기 검사는 PublicAuthFilter가 한다.
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/documents").authenticated()
                                .requestMatchers(HttpMethod.DELETE, "/api/v1/events/*/documents").authenticated()
                                .requestMatchers(HttpMethod.GET, "/api/v1/intake-documents/*/download", "/api/v1/intake-documents/*/pages/*/image").authenticated()
                                .requestMatchers(HttpMethod.DELETE, "/api/v1/audio-assets/*").authenticated()
                                // 행사 생성·수정·공개·시작·종료(#278)
                                .requestMatchers(HttpMethod.POST, "/api/v1/events", "/api/v1/events/*/publish",
                                        "/api/v1/events/*/start", "/api/v1/events/*/end").authenticated()
                                // 문서 추출 접수(#236 049). 쓰기 검사는 PublicAuthFilter(businessWrite)가 한다.
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/extractions").authenticated()
                                // 최초 계획 생성 접수(#280 043). 쓰기 검사는 PublicAuthFilter(businessWrite)가 한다.
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/plan-generations").authenticated()
                                // AI 대응안 생성 접수(#343 064). 쓰기 검사는 PublicAuthFilter(businessWrite)가 한다.
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/change-generations").authenticated()
                                // 행사 종료 요약(#269 080). 쓰기 검사는 PublicAuthFilter(businessWrite)가 한다.
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/summary").authenticated()
                                .requestMatchers(HttpMethod.PATCH, "/api/v1/events/*").authenticated()
                                // 확인한 행사 입력 반영(026). 쓰기 검사는 PublicAuthFilter(businessWrite)가 한다.
                                .requestMatchers(HttpMethod.PATCH, "/api/v1/events/*/input").authenticated()
                                .requestMatchers(HttpMethod.PATCH, "/api/v1/me").authenticated()
                                .requestMatchers(HttpMethod.POST, "/api/v1/me/deactivate").authenticated()
                                .requestMatchers(HttpMethod.GET, "/api/v1/me/consents").authenticated()
                                .requestMatchers(HttpMethod.PATCH, "/api/v1/me/consents").authenticated()
                                .requestMatchers(PublicAuthFilter::deviceWrite).authenticated()
                                // 탈퇴 처리 상태(V9-D40): 탈퇴한 사람은 로그인할 수 없으므로 접수 번호만으로 본다.
                                .requestMatchers(HttpMethod.GET, "/api/v1/deletion-requests/*").permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/v1/invites/*/options", "/api/v1/events/*/invite").authenticated()
                                .requestMatchers(HttpMethod.POST, "/api/v1/events/*/invite/rotate").authenticated()
                                .requestMatchers(HttpMethod.POST, "/api/v1/invites/*/join").authenticated()
                                .requestMatchers(PublicAuthFilter::chiefTransfer).authenticated();
                    }
                    if (oauth) {
                        authorize.requestMatchers(HttpMethod.GET,"/api/v1/auth/oauth/*/authorize","/api/v1/auth/oauth/*/callback").permitAll()
                                .requestMatchers(HttpMethod.POST,"/api/v1/auth/oauth/exchange").permitAll()
                                // #227 WEB 연결: 현재 세션 필수(PublicAuthFilter가 인증·H01 검사)
                                .requestMatchers(HttpMethod.POST,"/api/v1/me/reauthenticate","/api/v1/me/identities/*").authenticated()
                                .requestMatchers(HttpMethod.DELETE,"/api/v1/me/identities/*").authenticated();
                    }
                    authorize.anyRequest().denyAll();
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .build();
    }

    private static boolean isMissingApi(HttpServletRequest request,
            ObjectProvider<RequestMappingHandlerMapping> controllers,
            ObjectProvider<AbstractUrlHandlerMapping> urlHandlers) {
        if (!API.matches(request)) {
            return false;
        }
        var path = ServletRequestPathUtils.parse(request).pathWithinApplication();
        // Match the path independently of method and content type so registered routes keep their existing behavior.
        boolean controllerPath = controllers.stream().flatMap(mapping -> mapping.getHandlerMethods().keySet().stream())
                .flatMap(info -> info.getPathPatternsCondition().getPatterns().stream())
                .anyMatch(pattern -> pattern.matches(path));
        // Include URL-mapped handlers such as WebSocket. The generic static resource fallback is not an API.
        return !controllerPath && urlHandlers.stream()
                .flatMap(mapping -> mapping.getPathPatternHandlerMap().entrySet().stream())
                .noneMatch(entry -> entry.getKey().matches(path)
                        && !(entry.getKey().getPatternString().equals("/**")
                                && entry.getValue() instanceof ResourceHttpRequestHandler));
    }
}

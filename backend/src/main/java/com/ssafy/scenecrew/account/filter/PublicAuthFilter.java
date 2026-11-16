package com.ssafy.scenecrew.account.filter;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import com.ssafy.scenecrew.account.dto.Credentials;
import com.ssafy.scenecrew.account.controller.AuthTransport;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.*;

public final class PublicAuthFilter extends OncePerRequestFilter {
    public static final String CREDENTIALS = PublicAuthFilter.class.getName();
    private static final PathPatternRequestMatcher CHIEF_TRANSFER = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, "/api/v1/events/{e}/chief-transfer");

    public static boolean chiefTransfer(HttpServletRequest request) {
        return CHIEF_TRANSFER.matches(request);
    }

    private final AuthTransport transport;
    private final SessionAuthenticator authenticator;
    private final HandlerExceptionResolver errors;
    public PublicAuthFilter(AuthTransport transport, SessionAuthenticator authenticator, HandlerExceptionResolver errors) {
        this.transport = transport; this.authenticator = authenticator; this.errors = errors;
    }
    public static boolean login(HttpServletRequest r) {
        return "POST".equals(r.getMethod()) && "/api/v1/auth/login".equals(r.getServletPath());
    }
    public static boolean logout(HttpServletRequest r) {
        return "POST".equals(r.getMethod()) && "/api/v1/auth/logout".equals(r.getServletPath());
    }
    public static boolean refresh(HttpServletRequest r) {
        return "POST".equals(r.getMethod()) && "/api/v1/auth/refresh".equals(r.getServletPath());
    }
    public static boolean registration(HttpServletRequest r) {
        return "POST".equals(r.getMethod()) && "/api/v1/auth/register".equals(r.getServletPath());
    }
    public static boolean verification(HttpServletRequest r) {
        return "POST".equals(r.getMethod()) && "/api/v1/auth/verify-email".equals(r.getServletPath());
    }
    public static boolean resend(HttpServletRequest r) {
        return "POST".equals(r.getMethod()) && "/api/v1/auth/resend-verification".equals(r.getServletPath());
    }
    public static boolean passwordReset(HttpServletRequest r) {
        return "POST".equals(r.getMethod()) && java.util.Set.of(
                "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm").contains(r.getServletPath());
    }
    public static boolean authenticationPost(HttpServletRequest r) {
        return login(r) || logout(r) || refresh(r) || registration(r) || verification(r) || resend(r) || passwordReset(r);
    }
    public static boolean profilePatch(HttpServletRequest r) {
        return "PATCH".equals(r.getMethod()) && "/api/v1/me".equals(r.getServletPath());
    }
    /** #334 018 탈퇴. 프로필 수정과 같은 H01 쓰기 검사(WEB 허용 Origin·CSRF·JSON)를 한다. */
    public static boolean accountDeactivate(HttpServletRequest r) {
        return "POST".equals(r.getMethod()) && "/api/v1/me/deactivate".equals(r.getServletPath());
    }
    /** #229·#334 017 동의 변경. 프로필 수정과 같은 H01 쓰기 검사를 한다. */
    public static boolean consentPatch(HttpServletRequest r) {
        return "PATCH".equals(r.getMethod()) && "/api/v1/me/consents".equals(r.getServletPath());
    }
    /** 동의 조회. 현재 세션으로 인증만 한다. */
    public static boolean consentRead(HttpServletRequest r) {
        return "GET".equals(r.getMethod()) && "/api/v1/me/consents".equals(r.getServletPath());
    }
    public static boolean deviceWrite(HttpServletRequest r) {
        return ("PUT".equals(r.getMethod()) || "DELETE".equals(r.getMethod()))
                && r.getServletPath().matches("/api/v1/me/devices/[^/]+");
    }
    /** #231: pre-participation options and chief-only link reads still require current authentication. */
    public static boolean inviteRead(HttpServletRequest r) {
        return "GET".equals(r.getMethod()) && (r.getServletPath().matches("/api/v1/invites/[^/]+/options")
                || r.getServletPath().matches("/api/v1/events/[^/]+/invite"));
    }
    /** #232: Join is an access-authenticated write, not a public authentication command. */
    public static boolean inviteJoin(HttpServletRequest r) {
        return "POST".equals(r.getMethod()) && r.getServletPath().matches("/api/v1/invites/[^/]+/join");
    }
    /** #227 014 재확인·015 연결 시작·016 해제. 현재 세션으로 인증하고 프로필 수정과 같은 H01 쓰기 검사를 한다. */
    public static boolean identityWrite(HttpServletRequest r) {
        return "POST".equals(r.getMethod()) && "/api/v1/me/reauthenticate".equals(r.getServletPath())
                || ("POST".equals(r.getMethod()) || "DELETE".equals(r.getMethod()))
                && r.getServletPath().matches("/api/v1/me/identities/[A-Z]+");
    }
    /** SC-API-021 참여 행사 목록. 현재 세션으로 인증만 하고, 행사별 권한은 서비스가 Member로 판단한다. */
    public static boolean eventList(HttpServletRequest r) {
        return "GET".equals(r.getMethod()) && "/api/v1/events".equals(r.getServletPath());
    }
    /** 행사 하위 조회(023·027·036·037·055·057·065·067·076·072·073). 현재 세션으로 인증만 하고, 행사 권한은 서비스가 Member로 판단한다. */
    public static boolean eventRead(HttpServletRequest r) {
        return "GET".equals(r.getMethod())
                && (r.getServletPath().matches(
                        "/api/v1/events/[^/]+(/work-groups|/members|/issues|/change-sets|/my-changes|/channels|/messages|/tasks|/plan|/equipment|/equipment-reservations|/equipment-context|/safety-map|/safety-observations|/safety-cameras|/safety-support-candidates|/unconfirmed-draft|/my-links)?")
                || r.getServletPath().matches("/api/v1/issues/[^/]+")
                || r.getServletPath().matches("/api/v1/tasks/[^/]+")
                || r.getServletPath().matches("/api/v1/change-sets/[^/]+(/evidence/[^/]+)?")
                || r.getServletPath().matches("/api/v1/ai-jobs/[^/]+")
                // 048 입력 문서 다운로드, V9-D62 입력 PDF 쪽 그림(평면도 배경)
                || r.getServletPath().matches("/api/v1/intake-documents/[^/]+/download")
                || r.getServletPath().matches("/api/v1/intake-documents/[^/]+/pages/[^/]+/image")
                // V9-D62 안전 지도 평면도 배경
                || r.getServletPath().matches("/api/v1/events/[^/]+/safety-map/background")
                // 장비 상세 EXT03, 안전 관측 EXT14·카메라 EXT23
                || r.getServletPath().matches("/api/v1/equipment/[^/]+")
                || r.getServletPath().matches("/api/v1/(safety-observations|safety-cameras)/[^/]+")
                // 카메라 현재 프레임 Q2(#349)
                || r.getServletPath().matches("/api/v1/safety-cameras/[^/]+/snapshot")
                // 복구 계산 결과 EXT07·최근 결과 Q1
                || r.getServletPath().matches("/api/v1/recovery-simulations/[^/]+")
                || r.getServletPath().matches("/api/v1/issues/[^/]+/recovery-simulations/latest"));
    }
    /** 보고·이슈(#282 056·058·059·062)와 변경안·변경 확인(#283 066~071·077) 쓰기. 현재 세션으로 인증하고 업무 쓰기 검사(WEB 허용 Origin·JSON)를 한다. */
    public static boolean businessWrite(HttpServletRequest r) {
        String path = r.getServletPath();
        return "POST".equals(r.getMethod()) && ("/api/v1/events".equals(path)
                        || path.matches("/api/v1/events/[^/]+/(issues|change-sets|initial-assignments|publish|start|end|invite/rotate|extractions|plan-generations|change-generations|summary)")
                        || path.matches("/api/v1/issues/[^/]+/reports") || path.matches("/api/v1/reports/[^/]+/analyze")
                        || path.matches("/api/v1/tasks/[^/]+/(start|complete)")
                        || path.matches("/api/v1/change-sets/[^/]+/(preview|apply|discard)")
                        || path.matches("/api/v1/change-acknowledgments/[^/]+/ack")
                        // 장비 등록 EXT02·상태 기록 EXT04·업무 장비 점검 EXT09
                        || path.matches("/api/v1/events/[^/]+/equipment") || path.matches("/api/v1/equipment/[^/]+/observations")
                        || path.matches("/api/v1/tasks/[^/]+/equipment-checks")
                        // 장비 관계 R1 추가
                        || path.matches("/api/v1/events/[^/]+/equipment-(connections|requirements|dependencies)")
                        // 안전 카메라 등록 EXT22, 복구 계산 접수 EXT06
                        || path.matches("/api/v1/events/[^/]+/(safety-cameras|recovery-simulations)")
                        // 카메라 웹 연결 시작(#393), 장비 연결 코드 발급(#394)
                        || path.matches("/api/v1/safety-cameras/[^/]+/browser-link")
                        || path.matches("/api/v1/events/[^/]+/equipment-links/codes"))
                || "PATCH".equals(r.getMethod()) && (path.matches("/api/v1/issues/[^/]+(/situation)?") || path.matches("/api/v1/change-sets/[^/]+")
                        || path.matches("/api/v1/events/[^/]+(/input)?") || path.matches("/api/v1/members/[^/]+(/availability)?")
                        || path.matches("/api/v1/tasks/[^/]+/checklist")
                        // 장비 관계 R1 교체
                        || path.matches("/api/v1/events/[^/]+/equipment-(connections|requirements|dependencies)/[^/]+")
                        // 안전 지도 저장 EXT11·카메라 수정 EXT24
                        || path.matches("/api/v1/events/[^/]+/safety-map") || path.matches("/api/v1/safety-cameras/[^/]+"))
                // 028 계획 초안 저장
                || "PUT".equals(r.getMethod()) && path.matches("/api/v1/events/[^/]+/(draft-plan|unconfirmed-draft)")
                // 수신기 ↔ 장비 칸 연결(#394)
                || "PUT".equals(r.getMethod()) && path.matches("/api/v1/equipment/[^/]+/link")
                // 047 입력 문서 정리(본문 JSON이 있어 업무 쓰기 검사를 한다)
                || "DELETE".equals(r.getMethod()) && path.matches("/api/v1/events/[^/]+/documents");
    }
    /** 파일 업로드 쓰기(#340 060 음성 전사 접수). 현재 세션으로 인증하고 업로드 쓰기 검사(WEB 허용 Origin·multipart)를 한다. */
    public static boolean businessUpload(HttpServletRequest r) {
        // 060 음성 전사 접수, 046 입력 문서 등록
        return "POST".equals(r.getMethod()) && r.getServletPath().matches("/api/v1/events/[^/]+/(transcriptions|documents)");
    }
    /** 본문 없는 업무 삭제(#340 061 음성 원본 정리). 현재 세션으로 인증하고 WEB 허용 Origin을 검사한다. */
    public static boolean businessDelete(HttpServletRequest r) {
        // 061 음성 원본 정리, 장비 관계 R1 해제(기대 revision은 query), 040 행사 이탈(본문 없는 POST)
        return "DELETE".equals(r.getMethod()) && (r.getServletPath().matches("/api/v1/audio-assets/[^/]+")
                // 카메라 웹 연결 해제(#393), 장비 연결 해제(#394)
                || r.getServletPath().matches("/api/v1/safety-cameras/[^/]+/browser-link")
                || r.getServletPath().matches("/api/v1/equipment/[^/]+/link")
                || r.getServletPath().matches("/api/v1/events/[^/]+/equipment-(connections|requirements|dependencies)/[^/]+"))
                || "POST".equals(r.getMethod()) && r.getServletPath().matches("/api/v1/events/[^/]+/leave");
    }
    /** 카메라 웹 연결 프레임(#393). 현재 세션으로 인증하고 WEB 허용 Origin과 본문 image/jpeg를 검사한다. */
    public static boolean cameraFrame(HttpServletRequest r) {
        return "POST".equals(r.getMethod()) && r.getServletPath().matches("/api/v1/safety-cameras/[^/]+/frames");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest r) {
        return !authenticationPost(r) && !chiefTransfer(r) && !profilePatch(r) && !accountDeactivate(r) && !consentPatch(r) && !consentRead(r) && !identityWrite(r) && !inviteJoin(r) && !inviteRead(r)
                && !eventList(r) && !eventRead(r) && !businessWrite(r) && !businessUpload(r) && !businessDelete(r) && !cameraFrame(r)
                && !deviceWrite(r)
                && !("GET".equals(r.getMethod()) && "/api/v1/me".equals(r.getServletPath()));
    }
    @Override protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
            throws ServletException, IOException {
        filterRequest(r, s, chain, authenticationPost(r));
    }

    public OncePerRequestFilter missingApiFallback(RequestMatcher missingApi) {
        return new OncePerRequestFilter() {
            @Override protected boolean shouldNotFilter(HttpServletRequest request) {
                return request.getDispatcherType() != DispatcherType.REQUEST
                        || SecurityContextHolder.getContext().getAuthentication() != null
                        || !missingApi.matches(request);
            }

            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                filterRequest(request, response, (ignoredRequest, ignoredResponse) ->
                        errors.resolveException(request, response, null, new BusinessException(ErrorCode.NOT_FOUND)),
                        false);
            }
        };
    }

    private void filterRequest(HttpServletRequest r, HttpServletResponse s, FilterChain chain, boolean authenticationPost)
            throws ServletException, IOException {
        Credentials credentials = null;
        try {
            // Reference/recovery endpoints use their own proof and have no required session or channel.
            if (authenticationPost && (resend(r) || passwordReset(r))) {
                transport.inspectReference(r);
            } else {
                credentials = transport.inspect(r, authenticationPost);
                r.setAttribute(CREDENTIALS, credentials);
                if (!authenticationPost) {
                    var principal = authenticator.authenticate(credentials.access());
                    if (!principal.channel().equals(credentials.channel()))
                        throw new BusinessException(ErrorCode.AUTH_CHANNEL_MISMATCH);
                    if (profilePatch(r) || accountDeactivate(r) || consentPatch(r) || identityWrite(r) || inviteJoin(r)) transport.checkProfileWrite(r, credentials);
                    if (businessWrite(r)) transport.checkBusinessWrite(r, credentials);
                    if (deviceWrite(r) && "PUT".equals(r.getMethod())) transport.checkBusinessWrite(r, credentials);
                    if (businessUpload(r)) transport.checkBusinessUpload(r, credentials);
                    if (businessDelete(r)) transport.checkBusinessDelete(r, credentials);
                    if (cameraFrame(r)) transport.checkCameraFrame(r, credentials);
                    if (chiefTransfer(r)) transport.verifyProtectedWriteOrigin(r, credentials);
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
                    SecurityContextHolder.setContext(context);
                }
            }
        } catch (BusinessException e) {
            if (!authenticationPost && credentials != null && "ANDROID".equals(credentials.channel())
                    && credentials.access() != null && e.getErrorCode() == ErrorCode.UNAUTHENTICATED) {
                s.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
            }
            errors.resolveException(r, s, null, e); return;
        }
        try { chain.doFilter(r, s); }
        finally { SecurityContextHolder.clearContext(); }
    }
}

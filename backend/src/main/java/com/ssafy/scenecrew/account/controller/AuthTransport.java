package com.ssafy.scenecrew.account.controller;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import com.ssafy.scenecrew.account.dto.Credentials;
import com.ssafy.scenecrew.global.error.*;

/** Reads raw headers before Servlet Cookie parsing can lose malformed/duplicate values. */
public final class AuthTransport {
    public static final String ACCESS = "__Secure-sc-access";
    public static final String REFRESH = "__Secure-sc-refresh";
    private final Set<String> webOrigins;
    private final Set<String> androidOrigins;
    public AuthTransport(Set<String> webOrigins, Set<String> androidOrigins) {
        this.webOrigins = Set.copyOf(webOrigins); this.androidOrigins = Set.copyOf(androidOrigins);
    }
    public Credentials inspect(HttpServletRequest request, boolean authenticationPost) {
        String channel = authenticationPost ? single(request, "X-SceneCrew-Client", ErrorCode.INVALID_AUTH_CHANNEL) : null;
        if (authenticationPost && !("WEB".equals(channel) || "ANDROID".equals(channel))) fail(ErrorCode.INVALID_AUTH_CHANNEL);
        if (authenticationPost && "WEB".equals(channel)) {
            requireWebWrite(request);
        }
        Map<String, String> cookies = cookies(request);
        String authorization = single(request, "Authorization", ErrorCode.MIXED_AUTH_CREDENTIALS);
        boolean hasCookie = cookies.containsKey(ACCESS) || cookies.containsKey(REFRESH);
        if (hasCookie && authorization != null) fail(ErrorCode.MIXED_AUTH_CREDENTIALS);
        String bearer = null;
        if (authorization != null) {
            if (!authorization.matches("(?i:Bearer) [A-Za-z0-9_\\-.]+")) fail(ErrorCode.INVALID_REQUEST);
            bearer = authorization.substring(7);
        }
        if (!authenticationPost) channel = authorization == null ? "WEB" : "ANDROID";
        if ("ANDROID".equals(channel)) {
            if (hasCookie) fail(ErrorCode.AUTH_CHANNEL_MISMATCH);
            if (!androidOrigins.contains(Objects.toString(single(request, "Origin", ErrorCode.AUTH_CHANNEL_MISMATCH), "")))
                fail(ErrorCode.AUTH_CHANNEL_MISMATCH);
        } else if (authorization != null) fail(ErrorCode.AUTH_CHANNEL_MISMATCH);
        return new Credentials(channel, "WEB".equals(channel) ? cookies.get(ACCESS) : bearer, cookies.get(REFRESH));
    }
    /** 채팅 WebSocket 연결(#238): 요청 Origin이 WEB 허용 목록인지 */
    public boolean webOrigin(String origin) { return origin != null && webOrigins.contains(origin); }
    /** 채팅 WebSocket 연결(#238): 요청 Origin이 Android 허용 목록인지 */
    public boolean androidOrigin(String origin) { return origin != null && androidOrigins.contains(origin); }
    /** Called only after actual access authentication and stored-channel matching. */
    public void checkProfileWrite(HttpServletRequest request, Credentials credentials) {
        if ("WEB".equals(credentials.channel())) requireWebWrite(request);
        else requireJson(request);
    }
    /**
     * 업무 쓰기(보고·이슈 등). WEB은 허용 Origin과 JSON만 본다. SameSite=Lax 인증 Cookie와 함께 다른 사이트 요청을 막는다.
     * X-SceneCrew-CSRF(H01)는 요구하지 않는다(2026-11-05 오세진 결정, be/08 "H01 확대 금지" 유지). Android는 JSON만.
     */
    public void checkBusinessWrite(HttpServletRequest request, Credentials credentials) {
        requireAllowedWebOrigin(request, credentials);
        requireJson(request);
    }
    /** 파일 업로드 쓰기(#340 060). businessWrite와 같은 WEB 허용 Origin 검사를 하고, 본문은 multipart/form-data만 받는다. */
    public void checkBusinessUpload(HttpServletRequest request, Credentials credentials) {
        requireAllowedWebOrigin(request, credentials);
        requireMultipart(request);
    }
    /** 본문 없는 삭제(#340 061). businessWrite와 같은 WEB 허용 Origin 검사만 한다. */
    public void checkBusinessDelete(HttpServletRequest request, Credentials credentials) {
        requireAllowedWebOrigin(request, credentials);
    }
    /** 카메라 웹 연결 프레임(#393). businessWrite와 같은 WEB 허용 Origin 검사를 하고, 본문은 image/jpeg만 받는다. */
    public void checkCameraFrame(HttpServletRequest request, Credentials credentials) {
        requireAllowedWebOrigin(request, credentials);
        try {
            var media = MediaType.parseMediaType(request.getContentType());
            if (!MediaType.IMAGE_JPEG.equalsTypeAndSubtype(media)) fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        } catch (IllegalArgumentException e) { fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE); }
    }
    /** 업무 쓰기 공통(V9-D34): WEB Cookie 요청은 허용한 Origin에서만 받는다. Android Bearer는 inspect에서 이미 Origin을 확인했다. */
    private void requireAllowedWebOrigin(HttpServletRequest request, Credentials credentials) {
        if ("WEB".equals(credentials.channel())
                && !webOrigins.contains(Objects.toString(single(request, "Origin", ErrorCode.CSRF_REJECTED), "")))
            fail(ErrorCode.CSRF_REJECTED);
    }
    private void requireMultipart(HttpServletRequest request) {
        try {
            var media = MediaType.parseMediaType(request.getContentType());
            if (!MediaType.MULTIPART_FORM_DATA.equalsTypeAndSubtype(media)) fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        } catch (IllegalArgumentException e) { fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE); }
    }
    private void requireWebWrite(HttpServletRequest request) {
        if (!webOrigins.contains(Objects.toString(single(request, "Origin", ErrorCode.CSRF_REJECTED), ""))
                || !"1".equals(single(request, "X-SceneCrew-CSRF", ErrorCode.CSRF_REJECTED)))
            fail(ErrorCode.CSRF_REJECTED);
        requireJson(request);
    }
    private void requireJson(HttpServletRequest request) {
        try {
            var media = MediaType.parseMediaType(request.getContentType());
            if (!"application".equalsIgnoreCase(media.getType()) || !"json".equalsIgnoreCase(media.getSubtype()))
                fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        } catch (IllegalArgumentException e) { fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE); }
    }

    /** SC-API-003 has no channel/credential prerequisite; still reject malformed/mixed supplied credentials. */
    public void inspectReference(HttpServletRequest request) {
        var supplied=cookies(request);
        String authorization=single(request,"Authorization",ErrorCode.INVALID_REQUEST);
        String channel=single(request,"X-SceneCrew-Client",ErrorCode.INVALID_AUTH_CHANNEL);
        if (channel!=null && !Set.of("WEB","ANDROID").contains(channel)) fail(ErrorCode.INVALID_AUTH_CHANNEL);
        if (!supplied.isEmpty() && authorization!=null) fail(ErrorCode.MIXED_AUTH_CREDENTIALS);
        if (authorization!=null && !authorization.matches("(?i:Bearer) [A-Za-z0-9_\\-.]+")) fail(ErrorCode.INVALID_REQUEST);
        if (("ANDROID".equals(channel) && !supplied.isEmpty()) || ("WEB".equals(channel) && authorization!=null))
            fail(ErrorCode.AUTH_CHANNEL_MISMATCH);
    }

    /** #226 private WEB candidate. Cross-origin starts require the same approved Origin and CSRF marker. */
    public void inspectOAuthStart(HttpServletRequest request) {
        var credentials=inspect(request,false);
        if (!"WEB".equals(single(request,"X-SceneCrew-Client",ErrorCode.INVALID_AUTH_CHANNEL)))
            fail(ErrorCode.INVALID_AUTH_CHANNEL);
        if (!"WEB".equals(credentials.channel())) fail(ErrorCode.AUTH_CHANNEL_MISMATCH);
        String origin=single(request,"Origin",ErrorCode.CSRF_REJECTED);
        if (origin==null) {
            // Same-origin browser GETs can omit Origin. Do not extend this fallback to authentication POSTs.
            if (!"same-origin".equals(single(request,"Sec-Fetch-Site",ErrorCode.CSRF_REJECTED))) fail(ErrorCode.CSRF_REJECTED);
            String referer=single(request,"Referer",ErrorCode.CSRF_REJECTED);
            try {
                var uri=java.net.URI.create(Objects.toString(referer,""));
                if (uri.getUserInfo()!=null || uri.getHost()==null) fail(ErrorCode.CSRF_REJECTED);
                origin=new java.net.URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),null,null,null).toString();
            } catch (IllegalArgumentException | java.net.URISyntaxException invalid) { fail(ErrorCode.CSRF_REJECTED); }
        }
        if (!webOrigins.contains(origin) || !"1".equals(single(request,"X-SceneCrew-CSRF",ErrorCode.CSRF_REJECTED)))
            fail(ErrorCode.CSRF_REJECTED);
    }
    // Cookie-authenticated writes need an allowed browser Origin. Bearer requests
    // are already checked by inspect and cannot use authentication cookies.
    public void verifyProtectedWriteOrigin(HttpServletRequest request, Credentials credentials) {
        if ("WEB".equals(credentials.channel())
                && !webOrigins.contains(Objects.toString(single(request, "Origin", ErrorCode.FORBIDDEN), ""))) {
            fail(ErrorCode.FORBIDDEN);
        }
    }

    private Map<String, String> cookies(HttpServletRequest request) {
        Map<String, String> result = new HashMap<>();
        for (String header : Collections.list(request.getHeaders("Cookie"))) {
            for (String part : header.split(";", -1)) {
                String pair = part.trim(); int at = pair.indexOf('=');
                if (at <= 0) fail(ErrorCode.MALFORMED_COOKIE_HEADER);
                String name = pair.substring(0, at); String value = pair.substring(at + 1);
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) fail(ErrorCode.MALFORMED_COOKIE_HEADER);
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                    value = value.substring(1, value.length() - 1);
                for (char c : value.toCharArray())
                    if (!(c == 0x21 || c >= 0x23 && c <= 0x2b || c >= 0x2d && c <= 0x3a
                            || c >= 0x3c && c <= 0x5b || c >= 0x5d && c <= 0x7e))
                        fail(ErrorCode.MALFORMED_COOKIE_HEADER);
                if (name.equals(ACCESS) || name.equals(REFRESH)) {
                    if (result.containsKey(name)) fail(ErrorCode.DUPLICATE_AUTH_COOKIE);
                    result.put(name, value);
                }
            }
        }
        return result;
    }
    static String single(HttpServletRequest r, String name, ErrorCode code) {
        List<String> values = Collections.list(r.getHeaders(name));
        if (values.size() > 1) fail(code);
        return values.isEmpty() ? null : values.getFirst();
    }
    private static void fail(ErrorCode code) { throw new BusinessException(code); }
}

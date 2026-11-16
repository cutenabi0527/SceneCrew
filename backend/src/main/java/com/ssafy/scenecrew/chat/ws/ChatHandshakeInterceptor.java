package com.ssafy.scenecrew.chat.ws;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.account.controller.AuthTransport;
import com.ssafy.scenecrew.global.error.BusinessException;

/**
 * 채팅 WebSocket 연결 요청 검사(#238). 자격은 URL에 싣지 않는다.
 *
 * <ul>
 *   <li>WEB: Origin이 WEB 허용 목록이고 Access Cookie로 현재 세션이 인증되어야 한다. 연결되면 바로 READY를 보낸다.</li>
 *   <li>Android: Origin이 Android 허용 목록이고 Cookie·Authorization이 없어야 한다. 연결 뒤 5초 안에 AUTH 프레임으로 인증한다.</li>
 *   <li>그 밖의 Origin은 403, 인증 실패는 401로 연결하지 않는다.</li>
 * </ul>
 */
public class ChatHandshakeInterceptor implements HandshakeInterceptor {
    static final String EVENT = "chat.eventId";
    static final String CHANNEL = "chat.channel";
    static final String ACCOUNT = "chat.accountId";
    static final String TOKEN = "chat.accessToken";
    private static final Pattern PATH = Pattern.compile(
            "/api/v1/events/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/ws");

    private final AuthTransport transport;
    private final SessionAuthenticator authenticator;

    public ChatHandshakeInterceptor(AuthTransport transport, SessionAuthenticator authenticator) {
        this.transport = transport;
        this.authenticator = authenticator;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servlet)) return reject(response, HttpStatus.BAD_REQUEST);
        HttpServletRequest http = servlet.getServletRequest();
        Matcher m = PATH.matcher(http.getRequestURI());
        if (!m.matches() || http.getQueryString() != null) return reject(response, HttpStatus.BAD_REQUEST);
        attributes.put(EVENT, UUID.fromString(m.group(1)));
        String origin = http.getHeader("Origin");
        try {
            if (transport.webOrigin(origin)) {
                var credentials = transport.inspect(http, false);
                if (!"WEB".equals(credentials.channel()) || credentials.access() == null)
                    return reject(response, HttpStatus.UNAUTHORIZED);
                var principal = authenticator.authenticate(credentials.access());
                if (!"WEB".equals(principal.channel())) return reject(response, HttpStatus.UNAUTHORIZED);
                attributes.put(CHANNEL, "WEB");
                attributes.put(ACCOUNT, principal.accountId());
                attributes.put(TOKEN, credentials.access());
                return true;
            }
            if (transport.androidOrigin(origin)) {
                if (http.getHeader("Cookie") != null || http.getHeader("Authorization") != null)
                    return reject(response, HttpStatus.BAD_REQUEST);
                attributes.put(CHANNEL, "ANDROID");
                return true;
            }
        } catch (BusinessException e) {
            return reject(response, e.getErrorCode().getStatus());
        }
        return reject(response, HttpStatus.FORBIDDEN);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Exception exception) {
    }

    private static boolean reject(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        return false;
    }
}

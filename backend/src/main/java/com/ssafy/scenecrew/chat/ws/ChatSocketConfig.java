package com.ssafy.scenecrew.chat.ws;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.account.controller.AuthTransport;
import com.ssafy.scenecrew.chat.service.ChatService;

/** 채팅 WebSocket `/api/v1/events/{e}/ws`(#238). Origin·자격 검사는 ChatHandshakeInterceptor가 한다. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class ChatSocketConfig implements WebSocketConfigurer, DisposableBean {
    private final ChatSocketHandler handler;
    private final ChatHandshakeInterceptor interceptor;
    /** Android AUTH 5초 제한을 재는 타이머 */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chat-auth-timeout");
        t.setDaemon(true);
        return t;
    });

    public ChatSocketConfig(ChatService chat, SessionAuthenticator authenticator, AuthTransport transport) {
        this.handler = new ChatSocketHandler(chat, authenticator, timer);
        this.interceptor = new ChatHandshakeInterceptor(transport, authenticator);
    }

    @Override
    public void destroy() {
        timer.shutdownNow();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Origin 허용 여부는 interceptor가 WEB·Android 허용 목록으로 직접 판단한다.
        registry.addHandler(handler, "/api/v1/events/{e}/ws").addInterceptors(interceptor).setAllowedOriginPatterns("*");
    }
}

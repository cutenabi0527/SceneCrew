package com.ssafy.scenecrew.chat.ws;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.chat.service.ChatService;
import com.ssafy.scenecrew.chat.service.ChatService.Message;
import com.ssafy.scenecrew.chat.service.ChatService.Participant;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;
import com.ssafy.scenecrew.global.text.UnicodeText;

/**
 * 채팅 WebSocket(#238, v9 x-websocket-contract). 클라이언트 프레임 AUTH·SUBSCRIBE·SEND, 서버 프레임 READY·STORED·MESSAGE·ERROR.
 *
 * <ul>
 *   <li>READY 전에는 SUBSCRIBE·SEND를 받지 않는다. Android는 연결 뒤 5초 안에 AUTH가 와야 한다.</li>
 *   <li>SUBSCRIBE는 현재 채널 권한을 확인한 뒤 READY.subscribedChannels로 확정한다. SEND는 확정된 채널에서만.</li>
 *   <li>보낼 때와 받을 때마다 세션(로그아웃·만료)·참여 회차·채널 권한을 다시 확인한다. 세션이 끝났으면 ERROR 없이 닫아
 *       앱이 갱신된 자격으로 다시 연결하게 하고, 이탈·이동처럼 권한이 사라졌으면 ERROR FORBIDDEN·NOT_FOUND를 보내고 닫는다.</li>
 *   <li>DB 커밋 뒤에만 보낸 사람에게 STORED, 같은 채널을 구독한 다른 연결에 MESSAGE를 보낸다.</li>
 *   <li>연결 목록은 이 서버 프로세스 안에만 있다(현재 운영은 BE 한 대). 여러 대로 늘리면 Redis 등으로 전달을 나눠야 한다.</li>
 * </ul>
 */
public class ChatSocketHandler extends TextWebSocketHandler {
    static final long AUTH_TIMEOUT_MS = 5000;
    /** 세션이 끝나 다시 연결해야 함(앱은 1초~30초 간격으로 다시 연결한다) */
    static final CloseStatus SESSION_ENDED = new CloseStatus(4401, "session");
    private static final Logger log = LoggerFactory.getLogger(ChatSocketHandler.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String UUID_FORMAT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final ChatService chat;
    private final SessionAuthenticator authenticator;
    private final ScheduledExecutorService timer;
    private final Map<UUID, Set<Connection>> byEvent = new ConcurrentHashMap<>();
    private final Map<String, Connection> bySession = new ConcurrentHashMap<>();

    public ChatSocketHandler(ChatService chat, SessionAuthenticator authenticator, ScheduledExecutorService timer) {
        this.chat = chat;
        this.authenticator = authenticator;
        this.timer = timer;
    }

    /** 연결 하나의 상태 */
    static final class Connection {
        final WebSocketSession socket;
        final UUID eventId;
        final String channel;
        volatile UUID accountId;
        volatile String token;
        volatile Participant participant;
        final Set<String> subscribed = ConcurrentHashMap.newKeySet();

        Connection(WebSocketSession socket, UUID eventId, String channel) {
            this.socket = socket;
            this.eventId = eventId;
            this.channel = channel;
        }
        boolean ready() { return participant != null; }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws IOException {
        var socket = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 512 * 1024);
        Map<String, Object> attrs = raw.getAttributes();
        var connection = new Connection(socket, (UUID) attrs.get(ChatHandshakeInterceptor.EVENT),
                (String) attrs.get(ChatHandshakeInterceptor.CHANNEL));
        bySession.put(raw.getId(), connection);
        byEvent.computeIfAbsent(connection.eventId, k -> ConcurrentHashMap.newKeySet()).add(connection);
        if ("WEB".equals(connection.channel)) {
            connection.accountId = (UUID) attrs.get(ChatHandshakeInterceptor.ACCOUNT);
            connection.token = (String) attrs.get(ChatHandshakeInterceptor.TOKEN);
            establish(connection);
        } else {
            timer.schedule(() -> {
                if (!connection.ready() && connection.socket.isOpen()) close(connection, CloseStatus.POLICY_VIOLATION);
            }, AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Connection connection = bySession.remove(raw.getId());
        if (connection == null) return;
        var set = byEvent.get(connection.eventId);
        if (set != null) {
            set.remove(connection);
            if (set.isEmpty()) byEvent.remove(connection.eventId, set);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage text) {
        Connection connection = bySession.get(raw.getId());
        if (connection == null) return;
        JsonNode frame;
        try {
            frame = MAPPER.readTree(text.getPayload());
        } catch (RuntimeException e) {
            fail(connection, "INVALID_REQUEST", "프레임 형식이 올바르지 않습니다.", null, true);
            return;
        }
        String type = frame != null && frame.isObject() ? textOf(frame.get("type")) : null;
        if ("AUTH".equals(type)) auth(connection, frame);
        else if (!connection.ready()) fail(connection, "INVALID_STATE", "인증 전에는 보낼 수 없습니다.", null, true);
        else if ("SUBSCRIBE".equals(type)) subscribe(connection, frame);
        else if ("SEND".equals(type)) send(connection, frame);
        else fail(connection, "INVALID_REQUEST", "알 수 없는 프레임입니다.", null, true);
    }

    // ---- 프레임 ------------------------------------------------------------------------------------------------

    private void auth(Connection connection, JsonNode frame) {
        String token = textOf(frame.get("accessToken"));
        if (!"ANDROID".equals(connection.channel) || connection.ready() || connection.token != null || frame.size() != 2
                || token == null || token.isEmpty()) {
            fail(connection, "INVALID_STATE", "이 연결에서는 AUTH를 보낼 수 없습니다.", null, true);
            return;
        }
        try {
            var principal = authenticator.authenticate(token);
            if (!"ANDROID".equals(principal.channel())) throw new BusinessException(ErrorCode.AUTH_CHANNEL_MISMATCH);
            connection.accountId = principal.accountId();
            connection.token = token;
        } catch (BusinessException e) {
            fail(connection, "UNAUTHENTICATED", "로그인이 필요합니다.", null, true);
            return;
        }
        establish(connection);
    }

    private void subscribe(Connection connection, JsonNode frame) {
        JsonNode group = frame.get("workGroupId");
        if (frame.size() != 2 || group == null || !(group.isNull() || isUuid(group))) {
            fail(connection, "INVALID_REQUEST", "SUBSCRIBE 형식이 올바르지 않습니다.", null, true);
            return;
        }
        UUID workGroupId = group.isNull() ? null : UUID.fromString(group.asString());
        Participant me = current(connection);
        if (me == null) return;
        if (!chat.canAccess(connection.eventId, me, workGroupId)) {
            fail(connection, "FORBIDDEN", "이 채널을 볼 수 없습니다.", null, true);
            return;
        }
        connection.subscribed.add(key(workGroupId));
        ready(connection);
    }

    private void send(Connection connection, JsonNode frame) {
        JsonNode group = frame.get("workGroupId");
        String clientMessageId = textOf(frame.get("clientMessageId"));
        String body = textOf(frame.get("text"));
        if (frame.size() != 4 || group == null || !(group.isNull() || isUuid(group)) || clientMessageId == null
                || !clientMessageId.matches(UUID_FORMAT)) {
            fail(connection, "INVALID_REQUEST", "SEND 형식이 올바르지 않습니다.", null, true);
            return;
        }
        if (body == null || UnicodeText.isBlank(body)
                || body.codePointCount(0, body.length()) > ChatService.MAX_TEXT) {
            fail(connection, "INVALID_REQUEST", "메시지는 1~1000자입니다.", clientMessageId, false);
            return;
        }
        UUID workGroupId = group.isNull() ? null : UUID.fromString(group.asString());
        if (!connection.subscribed.contains(key(workGroupId))) {
            fail(connection, "INVALID_STATE", "구독한 채널에서만 보낼 수 있습니다.", clientMessageId, false);
            return;
        }
        Participant me = current(connection);
        if (me == null) return;
        ChatService.Sent sent;
        try {
            sent = chat.send(connection.eventId, connection.accountId, me.participationRevision(), workGroupId,
                    clientMessageId, body);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.FORBIDDEN || e.getErrorCode() == ErrorCode.NOT_FOUND)
                fail(connection, e.getErrorCode().name(), "채팅 권한이 바뀌었습니다.", null, true);
            else fail(connection, e.getErrorCode().name(), e.getMessage(), clientMessageId, false);
            return;
        } catch (RuntimeException e) {
            log.warn("채팅 메시지 저장 실패", e);
            fail(connection, "SERVICE_UNAVAILABLE", "메시지를 저장하지 못했습니다.", clientMessageId, false);
            return;
        }
        write(connection, frame("STORED", sent.message()));
        if (sent.stored()) broadcast(connection, sent.message());
    }

    // ---- 내부 ------------------------------------------------------------------------------------------------

    /** 인증 뒤 이 행사의 활성 Member인지 확인하고 READY(빈 구독 목록)를 보낸다. */
    private void establish(Connection connection) {
        try {
            connection.participant = chat.participant(connection.eventId, connection.accountId);
        } catch (BusinessException e) {
            fail(connection, "NOT_FOUND", "참여 중인 공연이 아닙니다.", null, true);
            return;
        }
        ready(connection);
    }

    private void ready(Connection connection) {
        List<String> channels = new ArrayList<>();
        for (String k : connection.subscribed) channels.add("ALL".equals(k) ? null : k);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "READY");
        frame.put("memberId", connection.participant.memberId().toString());
        frame.put("participationRevision", Long.toString(connection.participant.participationRevision()));
        frame.put("subscribedChannels", channels);
        write(connection, frame);
    }

    /**
     * 세션과 참여 상태를 다시 확인한다. 세션이 끝났으면 ERROR 없이 닫고, 참여 회차가 바뀌었거나 이탈했으면 ERROR와 함께 닫는다.
     * 이상이 없으면 지금 참여 정보를 돌려준다.
     */
    private Participant current(Connection connection) {
        try {
            authenticator.authenticate(connection.token);
        } catch (RuntimeException e) {
            close(connection, SESSION_ENDED);
            return null;
        }
        Participant now;
        try {
            now = chat.participant(connection.eventId, connection.accountId);
        } catch (BusinessException e) {
            fail(connection, "NOT_FOUND", "참여 중인 공연이 아닙니다.", null, true);
            return null;
        }
        if (!now.memberId().equals(connection.participant.memberId())
                || now.participationRevision() != connection.participant.participationRevision()) {
            fail(connection, "FORBIDDEN", "참여 정보가 바뀌었습니다.", null, true);
            return null;
        }
        // 그룹 이동으로 볼 수 없게 된 구독 채널은 지운다.
        connection.subscribed.removeIf(k -> !"ALL".equals(k) && !chat.canAccess(connection.eventId, now, UUID.fromString(k)));
        return now;
    }

    /** 같은 행사에서 이 채널을 구독한 다른 연결에 MESSAGE를 보낸다. 받는 쪽도 지금 권한을 다시 확인한다. */
    private void broadcast(Connection sender, Message message) {
        String channel = key(message.workGroupId());
        Map<String, Object> frame = frame("MESSAGE", message);
        for (Connection c : new LinkedHashSet<>(byEvent.getOrDefault(sender.eventId, Set.of()))) {
            if (c == sender || !c.ready() || !c.subscribed.contains(channel)) continue;
            Participant now = current(c);
            if (now != null && c.subscribed.contains(channel)) write(c, frame);
        }
    }

    private static Map<String, Object> frame(String type, Message message) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("message", message);
        return frame;
    }

    private void fail(Connection connection, String code, String message, String clientMessageId, boolean close) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "ERROR");
        frame.put("code", code);
        frame.put("message", message);
        frame.put("clientMessageId", clientMessageId);
        write(connection, frame);
        if (close) close(connection, CloseStatus.POLICY_VIOLATION);
    }

    private void write(Connection connection, Object frame) {
        try {
            if (connection.socket.isOpen()) connection.socket.sendMessage(new TextMessage(MAPPER.writeValueAsString(frame)));
        } catch (IOException | RuntimeException e) {
            log.debug("채팅 프레임을 보내지 못했습니다", e);
            close(connection, CloseStatus.SERVER_ERROR);
        }
    }

    private static void close(Connection connection, CloseStatus status) {
        try {
            if (connection.socket.isOpen()) connection.socket.close(status);
        } catch (IOException ignored) {
            // 이미 끊긴 연결
        }
    }

    private static String key(UUID workGroupId) { return workGroupId == null ? "ALL" : workGroupId.toString(); }

    private static boolean isUuid(JsonNode node) {
        return node.isString() && node.asString().matches(UUID_FORMAT);
    }

    private static String textOf(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }
}

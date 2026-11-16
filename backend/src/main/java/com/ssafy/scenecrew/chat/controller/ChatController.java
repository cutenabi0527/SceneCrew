package com.ssafy.scenecrew.chat.controller;

import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.chat.service.ChatService;
import com.ssafy.scenecrew.chat.service.ChatService.ChannelPage;
import com.ssafy.scenecrew.chat.service.ChatService.MessagePage;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/** 채팅 채널(072)·메시지 복구(073). 실시간 보내기·받기는 WebSocket(ChatSocketHandler). */
@RestController
@ConditionalOnProperty(name = "scenecrew.auth.public-enabled", havingValue = "true")
public class ChatController {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private final ChatService chat;

    public ChatController(ChatService chat) { this.chat = chat; }

    @GetMapping("/api/v1/events/{e}/channels")
    public ResponseEntity<ChannelPage> channels(@PathVariable("e") String e, HttpServletRequest request, Authentication auth) {
        onlyParams(request, Set.of());
        return noStore(chat.channels(uuid(e), account(auth)));
    }

    /** afterSequence와 cursor는 함께 보낼 수 없다(계약 400). workGroupId가 없으면 볼 수 있는 모든 채널 */
    @GetMapping("/api/v1/events/{e}/messages")
    public ResponseEntity<MessagePage> messages(@PathVariable("e") String e, HttpServletRequest request, Authentication auth) {
        onlyParams(request, Set.of("cursor", "limit", "afterSequence", "workGroupId"));
        UUID eventId = uuid(e);
        String cursor = request.getParameter("cursor");
        String after = request.getParameter("afterSequence");
        String group = request.getParameter("workGroupId");
        String limit = request.getParameter("limit");
        if (cursor != null && (after != null || group != null)) throw invalid();
        if (after != null && !after.matches("0|[1-9][0-9]{0,17}")) throw invalid();
        if (limit != null && !limit.matches("[1-9][0-9]{0,2}")) throw invalid();
        return noStore(chat.messages(eventId, account(auth), group == null ? null : uuid(group), group != null,
                after == null ? null : Long.parseLong(after), cursor, limit == null ? null : Integer.parseInt(limit)));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    private static void onlyParams(HttpServletRequest request, Set<String> allowed) {
        for (var e : request.getParameterMap().entrySet())
            if (!allowed.contains(e.getKey()) || e.getValue().length != 1) throw invalid();
    }

    private static UUID uuid(String value) {
        if (!value.matches(UUID_FORMAT)) throw invalid();
        return UUID.fromString(value);
    }

    private static UUID account(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return principal.accountId();
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

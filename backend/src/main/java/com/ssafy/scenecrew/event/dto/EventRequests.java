package com.ssafy.scenecrew.event.dto;

import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import com.ssafy.scenecrew.account.service.SessionAuthenticator;
import com.ssafy.scenecrew.global.error.BusinessException;
import com.ssafy.scenecrew.global.error.ErrorCode;

/** 행사 하위 조회 API가 함께 쓰는 입력 검사. 형식 오류는 행사 존재와 무관한 400이다. */
public final class EventRequests {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private EventRequests() { }

    public static UUID eventId(String value) {
        if (!value.matches(UUID_FORMAT)) throw invalid();
        return UUID.fromString(value);
    }

    /** 허용한 쿼리 이름만, 각각 한 번만. */
    public static void onlyParams(HttpServletRequest request, Set<String> allowed) {
        for (var e : request.getParameterMap().entrySet())
            if (!allowed.contains(e.getKey()) || e.getValue().length != 1) throw invalid();
    }

    /** limit 쿼리. 없으면 null, 앞자리 0·숫자 아님은 400. 범위 검사는 서비스가 한다. */
    public static Integer limit(HttpServletRequest request) {
        String limit = request.getParameter("limit");
        if (limit == null) return null;
        if (!limit.matches("[1-9][0-9]{0,2}")) throw invalid();
        return Integer.parseInt(limit);
    }

    public static UUID accountId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionAuthenticator.Principal principal))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return principal.accountId();
    }

    public static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_REQUEST); }
}

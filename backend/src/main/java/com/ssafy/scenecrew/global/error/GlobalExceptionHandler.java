package com.ssafy.scenecrew.global.error;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.ssafy.scenecrew.global.filter.RequestIdFilter;

import lombok.extern.slf4j.Slf4j;

/**
 * 모든 예외를 {code, message, requestId, details?} 형식으로 바꾼다.
 * Spring MVC 기본 예외(404, 405, 415, 본문 해석 실패 등)는 상위 클래스가 상태 코드를 정하고 본문만 여기서 바꾼다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> handleBusiness(BusinessException e, WebRequest request) {
        ErrorCode code = e.getErrorCode();
        HttpHeaders headers = new HttpHeaders();
        // 진행 중 AI 작업 한도 등: 언제 다시 보내면 되는지 초 단위로 알린다(계약 429 Retry-After).
        if (e instanceof RateLimitedException limited) headers.set(HttpHeaders.RETRY_AFTER, Long.toString(limited.getRetryAfterSeconds()));
        return build(code, e.getMessage(), e.getDetails(), headers, code.getStatus(), request);
    }

    // Security 필터에서 난 인증·권한 오류도 SecurityErrorHandler가 이곳으로 넘긴다.
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException e, WebRequest request) {
        return build(ErrorCode.UNAUTHENTICATED, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException e, WebRequest request) {
        return build(ErrorCode.FORBIDDEN, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception e, WebRequest request) {
        if (inviteRequest(request)) {
            log.error("Invite request failed: {}", e.getClass().getSimpleName());
            if (e instanceof org.springframework.dao.DataAccessException || e instanceof org.springframework.transaction.TransactionException)
                return build(ErrorCode.SERVICE_UNAVAILABLE, request);
        } else if (deviceRequest(request)) {
            log.error("기기 요청 처리 실패: {}", e.getClass().getSimpleName());
        } else log.error("처리하지 못한 예외", e);
        return build(ErrorCode.INTERNAL_ERROR, request);
    }

    // @Valid 실패: 어떤 필드가 어떤 규칙(NotBlank, Size 등)에 걸렸는지 details.fields로 알려 준다.
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "reason", String.valueOf(error.getCode())))
                .toList();
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return build(code, code.getMessage(), Map.of("fields", fields), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (request instanceof ServletWebRequest servlet && servlet.getResponse() != null
                && servlet.getResponse().isCommitted()) {
            return null;
        }
        if (inviteRequest(request)) {
            log.debug("Invite request rejected: {}", statusCode.value());
        } else if (deviceRequest(request)) {
            log.debug("기기 요청 거절: status={}", statusCode.value());
        } else if (statusCode.is5xxServerError()) {
            log.error("요청 처리 중 서버 오류", ex);
        } else {
            log.debug("요청 오류: {}", ex.getMessage());
        }
        ErrorCode code = ErrorCode.fromStatus(statusCode);
        return build(code, code.getMessage(), null, headers, statusCode, request);
    }

    private boolean inviteRequest(WebRequest request) {
        if (!(request instanceof ServletWebRequest servlet)) return false;
        String path = servlet.getRequest().getRequestURI();
        return path.startsWith("/api/v1/invites/") || path.matches("/api/v1/events/[^/]+/invite(/rotate)?");
    }

    private boolean deviceRequest(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                && servlet.getRequest().getRequestURI().matches("/api/v1/me/devices/[^/]+");
    }

    private ResponseEntity<Object> build(ErrorCode code, WebRequest request) {
        return build(code, code.getMessage(), null, new HttpHeaders(), code.getStatus(), request);
    }

    private ResponseEntity<Object> build(ErrorCode code, String message, Map<String, Object> details,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Framework exceptions may supply immutable HttpHeaders.EMPTY. Preserve their headers without mutating them.
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.addAll(headers);
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (inviteRequest(request)) {
            // An invalid Accept header must not break negotiation a second time while writing the error DTO.
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
            responseHeaders.set("Cache-Control", "no-store");
            responseHeaders.set("Referrer-Policy", "no-referrer");
        }
        ErrorResponse body = new ErrorResponse(code.name(), message, requestId, details);
        return ResponseEntity.status(status).headers(responseHeaders).body(body);
    }
}

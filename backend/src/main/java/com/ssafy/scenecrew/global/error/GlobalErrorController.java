package com.ssafy.scenecrew.global.error;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.scenecrew.global.filter.RequestIdFilter;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 컨트롤러 밖(필터 등)에서 난 오류가 /error로 넘어올 때 Spring Boot 기본 본문
 * ({timestamp, status, error, path}) 대신 공통 오류 형식을 쓴다.
 */
@RestController
public class GlobalErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        HttpStatus status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer value
                ? HttpStatus.resolve(value)
                : null;
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ErrorCode code = ErrorCode.fromStatus(status);
        return ResponseEntity.status(status).body(ErrorResponse.of(code, RequestIdFilter.current(request)));
    }
}

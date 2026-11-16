package com.ssafy.scenecrew.global.error;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import static org.assertj.core.api.Assertions.*;

class GlobalExceptionHeadersTest {
    @Test void immutableFrameworkHeadersArePreservedAndNotChanged() {
        var supplied=new HttpHeaders();
        supplied.setAllow(Set.of(HttpMethod.GET));
        supplied.set("X-Framework-Header","preserved");
        var request=new ServletWebRequest(new MockHttpServletRequest("GET","/api/v1/events/fixture/invite"));
        var result=new GlobalExceptionHandler().handleExceptionInternal(new IllegalArgumentException(),null,
                HttpHeaders.readOnlyHttpHeaders(supplied),HttpStatus.METHOD_NOT_ALLOWED,request);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(result.getHeaders().getAllow()).containsExactly(HttpMethod.GET);
        assertThat(result.getHeaders().getFirst("X-Framework-Header")).isEqualTo("preserved");
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(supplied.getCacheControl()).isNull();
        assertThat(supplied.getFirst("Referrer-Policy")).isNull();
        assertThat((ErrorResponse)result.getBody()).extracting(ErrorResponse::code).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test void nonInviteResponsesKeepFrameworkHeadersAndStatus() {
        var supplied=new HttpHeaders();
        supplied.setAllow(Set.of(HttpMethod.GET));
        supplied.set("X-Framework-Header","preserved");
        var request=new ServletWebRequest(new MockHttpServletRequest("GET","/api/v1/me"));
        var result=new GlobalExceptionHandler().handleExceptionInternal(new IllegalArgumentException(),null,
                HttpHeaders.readOnlyHttpHeaders(supplied),HttpStatus.METHOD_NOT_ALLOWED,request);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(result.getHeaders().getAllow()).containsExactly(HttpMethod.GET);
        assertThat(result.getHeaders().getFirst("X-Framework-Header")).isEqualTo("preserved");
        assertThat(result.getHeaders().getCacheControl()).isNull();
        assertThat(result.getHeaders().getContentType()).isNull();
        assertThat(result.getHeaders().getFirst("Referrer-Policy")).isNull();
        assertThat((ErrorResponse)result.getBody()).extracting(ErrorResponse::code).isEqualTo("METHOD_NOT_ALLOWED");
    }
}

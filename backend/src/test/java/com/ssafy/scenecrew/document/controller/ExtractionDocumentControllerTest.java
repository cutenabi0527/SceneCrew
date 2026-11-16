package com.ssafy.scenecrew.document.controller;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ssafy.scenecrew.aijob.client.AiServerProperties;
import com.ssafy.scenecrew.global.error.GlobalExceptionHandler;
import com.ssafy.scenecrew.global.storage.FileStorage;
import com.ssafy.scenecrew.global.storage.OriginalFiles;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AI용 문서 내려받기의 DB 일시 장애 응답(#180). 실제 DB를 끊을 수 없어 DB 접근만 실패하는 가짜로 바꾸고,
 * 오류 응답 처리는 실제 GlobalExceptionHandler를 거친다. 정상·권한·수명 조건은 ExtractionHttpTest가 실제 DB로 본다.
 */
class ExtractionDocumentControllerTest {
    static final String TOKEN = "test-service-token";
    JdbcTemplate jdbc;
    FileStorage storage = mock(FileStorage.class);
    MockMvc mvc;

    /** 모든 DB 호출이 주어진 예외로 실패하는 상태로 컨트롤러를 만든다. */
    void databaseFailsWith(RuntimeException failure) {
        jdbc = mock(JdbcTemplate.class, invocation -> { throw failure; });
        mvc = MockMvcBuilders.standaloneSetup(new ExtractionDocumentController(
                        new AiServerProperties("", TOKEN, Duration.ofSeconds(5)), jdbc, new OriginalFiles(storage)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void databaseConnectionFailureIs503() throws Exception {
        databaseFailsWith(new CannotGetJdbcConnectionException("down"));
        download(TOKEN).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        verifyNoInteractions(storage);
    }

    @Test void databaseTimeoutIs503() throws Exception {
        databaseFailsWith(new QueryTimeoutException("slow"));
        download(TOKEN).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        verifyNoInteractions(storage);
    }

    @Test void wrongTokenIs401BeforeTouchingDatabase() throws Exception {
        databaseFailsWith(new CannotGetJdbcConnectionException("down"));
        download("wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(jdbc, storage);
    }

    org.springframework.test.web.servlet.ResultActions download(String token) throws Exception {
        return mvc.perform(get("/internal/intake-documents/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + token)
                .header("X-SceneCrew-Job-Id", UUID.randomUUID().toString())
                .header("X-SceneCrew-Job-Attempt", "1"));
    }
}

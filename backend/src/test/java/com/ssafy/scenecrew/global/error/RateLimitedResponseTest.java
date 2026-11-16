package com.ssafy.scenecrew.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 429 RATE_LIMITED에 Retry-After(초)가 붙고 본문은 공통 오류 모양 그대로인지(V9-D43 AI 진행 중 1개 규칙). */
class RateLimitedResponseTest {
    @RestController
    static class Limited {
        @PostMapping("/limited")
        void limited() {
            throw new RateLimitedException("같은 AI 작업이 진행 중입니다. 끝난 뒤 다시 요청해 주세요.",
                    Map.of("reason", "AI_JOB_ACTIVE", "kind", "GENERATE_PLAN"), 42);
        }

        @PostMapping("/plain")
        void plain() {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Limited())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void retryAfterAndBody() throws Exception {
        mvc.perform(post("/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("같은 AI 작업이 진행 중입니다. 끝난 뒤 다시 요청해 주세요."))
                .andExpect(jsonPath("$.details.reason").value("AI_JOB_ACTIVE"));
    }

    /** 기존 로그인 한도 응답은 그대로(헤더 없음) */
    @Test
    void plainRateLimitHasNoHeader() throws Exception {
        mvc.perform(post("/plain"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().doesNotExist("Retry-After"));
    }
}
